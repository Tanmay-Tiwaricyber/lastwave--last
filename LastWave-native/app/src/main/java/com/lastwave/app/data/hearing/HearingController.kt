package com.lastwave.app.data.hearing

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lastwave.app.data.autoeq.PeqFitter
import com.lastwave.app.data.autoeq.PeqMath
import com.lastwave.app.data.autoeq.PeqSettings
import kotlin.math.abs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The stored hearing profile, and the filters it turns into.
 *
 * Two rules keep this honest:
 *
 *  - *The curve is not saved, it is derived.* What is stored is the raw
 *    threshold per band per ear; the filters are re-fitted on every start and
 *    whenever the strength slider moves. A saved fit would silently rot the
 *    moment the fitter or the caps changed, and re-fitting costs milliseconds.
 *  - *The boost is capped before the fit, not after.* Fitting a target that
 *    asks for +14 dB and then trimming the result produces a curve that no
 *    longer matches the ear; capping first gives a fit that matches the
 *    compromise that was actually intended.
 *
 * Nothing here touches the platform EQ: [AutoEqController][com.lastwave.app.data.autoeq.AutoEqController]
 * appends these filters to its own when it publishes to the native chain, so
 * headphone correction and hearing correction stack as two biquad stages, the
 * way they would on a mixing desk.
 */
@Serializable
data class HearingState(
    val profile: HearingProfile? = null,
    val enabled: Boolean = false,
    /** 0 = no correction, 1 = full half-gain. Below 1 for a gentler tilt. */
    val strength: Double = 1.0,
    /** One profile per output device: a seal you did not test through needs its own curve. */
    val devices: Map<String, HearingProfile> = emptyMap(),
    /** Which entry of [devices] [profile] came from; [HearingArchive.ANY_DEVICE] when unkeyed. */
    val activeKey: String = HearingArchive.ANY_DEVICE,
)

private val Context.hearingDataStore: DataStore<Preferences> by preferencesDataStore(name = "hearing_prefs")

@Singleton
class HearingController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val store get() = context.applicationContext.hearingDataStore

    private val data = store.data.catch { emit(emptyPreferences()) }

    val state: StateFlow<HearingState> = data.map { prefs ->
        prefs[KEY_STATE]?.let { raw ->
            runCatching { json.decodeFromString(HearingState.serializer(), raw) }.getOrNull()
        } ?: HearingState()
    }.stateIn(scope, SharingStarted.Eagerly, HearingState())

    val enabled: StateFlow<Boolean> = state.map { it.enabled && it.profile?.isUsable == true }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Loss curve on the fitter's grid, for the chart. */
    val curve: StateFlow<DoubleArray> = state.map { s ->
        val profile = s.profile
        if (profile == null || !profile.isUsable || !s.enabled) {
            DoubleArray(PeqMath.LOG_GRID.size)
        } else {
            profile.curve(strength = s.strength)
        }
    }.distinctUntilChanged { a, b ->
        a.size == b.size && a.indices.all { i -> abs(a[i] - b[i]) < 1e-6 }
    }.stateIn(scope, SharingStarted.Eagerly, DoubleArray(PeqMath.LOG_GRID.size))

    /**
     * The curve the correction chain runs: the hearing shape alone when
     * AutoEq is off or hand-edited, and the reference for the chart and the
     * "what this costs in headroom" line in the dialog.
     *
     * When AutoEq is fitting its own profile, the blend happens there instead —
     * [AutoEqController][com.lastwave.app.data.autoeq.AutoEqController] folds
     * [curve] into its least-squares pass so one fit covers both corrections
     * and one preamp covers the total boost. Fitted here rather than stored, so
     * a change of profile, of strength or of the caps in [HearingProfile] shows
     * up immediately.
     */
    val filters: StateFlow<PeqSettings> = state.map { s ->
        val profile = s.profile
        if (profile == null || !s.enabled || !profile.isUsable) {
            PeqSettings()
        } else {
            runCatching { PeqFitter.fit(profile.curve(strength = s.strength)) }.getOrNull() ?: PeqSettings()
        }
    }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, PeqSettings())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val summary: StateFlow<String> = state.map { s ->
        val profile = s.profile
        when {
            profile == null -> "No test taken yet"
            !profile.isUsable -> profile.summary()
            !s.enabled -> "Tested \u00B7 ${profile.summary()}"
            else -> profile.summary()
        }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, "No test taken yet")

    /**
     * Save a finished (or partly re-run) test against the device it was taken
     * on. Bands already measured on that device are kept, so re-running one
     * doubtful band costs the other seven nothing.
     */
    suspend fun save(profile: HearingProfile, enable: Boolean = true) = runBusy {
        val next = HearingArchive.save(state.value, profile, enable, System.currentTimeMillis())
        write(next)
        Log.i(TAG, "Hearing profile saved for '${next.activeKey}': ${next.profile?.bands?.size ?: 0} bands, ${next.profile?.summary()}")
    }

    /** Switch the correction to another saved device's curve. */
    suspend fun useDevice(key: String) = runBusy { write(HearingArchive.use(state.value, key)) }

    /** Drop one device's curve; the newest remaining one becomes active. */
    suspend fun forgetDevice(key: String) = runBusy { write(HearingArchive.forget(state.value, key)) }

    val savedDevices: StateFlow<List<HearingArchive.Entry>> = state.map { HearingArchive.entries(it) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private suspend fun write(next: HearingState) {
        store.edit { it[KEY_STATE] = json.encodeToString(HearingState.serializer(), next) }
    }

    suspend fun setEnabled(on: Boolean) = runBusy {
        write(state.value.copy(enabled = on))
    }

    /** 0..1, how much of the half-gain correction to apply. */
    suspend fun setStrength(value: Double) = runBusy {
        write(state.value.copy(strength = value.coerceIn(0.0, 1.0)))
    }

    /** Every device's curve, gone. */
    suspend fun clear() = runBusy { write(HearingState()) }

    /** Drop a single band's answer and keep the rest, for "that one was wrong". */
    suspend fun removeBand(frequencyHz: Double) = runBusy {
        val current = state.value
        val profile = current.profile ?: return@runBusy
        val kept = profile.bands.filter { abs(it.frequencyHz - frequencyHz) > 1.0 }
        val next = profile.copy(bands = kept)
        write(
            current.copy(
                profile = next,
                devices = current.devices + (current.activeKey to next),
                enabled = next.isUsable && current.enabled,
            ),
        )
    }

    /** The dB of boost the profile asks for right now, for the safety line in the UI. */
    suspend fun appliedBoostDb(): Double = withContext(Dispatchers.Default) {
        val s = state.value
        val profile = s.profile ?: return@withContext 0.0
        if (!s.enabled) return@withContext 0.0
        profile.appliedBoostDb(strength = s.strength)
    }

    private suspend fun runBusy(block: suspend () -> Unit) {
        _busy.value = true
        try {
            block()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Hearing profile update failed", error)
        } finally {
            _busy.value = false
        }
    }

    private companion object {
        val KEY_STATE = stringPreferencesKey("hearing_state")
        const val TAG = "HearingController"
    }
}
