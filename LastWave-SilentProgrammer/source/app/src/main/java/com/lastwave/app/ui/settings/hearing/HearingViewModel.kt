package com.lastwave.app.ui.settings.hearing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.hearing.HearingController
import com.lastwave.app.data.hearing.HearingProfile
import com.lastwave.app.data.hearing.HearingTestRunner
import com.lastwave.app.data.hearing.HearingTestSession
import com.lastwave.app.data.autoeq.PeqSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Glue between the tone runner, the stored profile and the dialog.
 *
 * Nothing here decides what a threshold means: that is
 * [HearingProfile][com.lastwave.app.data.hearing.HearingProfile]'s job, tested
 * on a JVM. This class only moves state, and one rule is worth naming because it
 * is the one a reviewer would otherwise "fix": a finished run is *not* saved
 * automatically. The listener has to see the numbers, and agree, before an EQ
 * starts shaping their music — a test you cannot read is a test you cannot
 * trust.
 */
@HiltViewModel
class HearingViewModel @Inject constructor(
    private val controller: HearingController,
    private val runner: HearingTestRunner,
) : ViewModel() {

    val state: StateFlow<com.lastwave.app.data.hearing.HearingState> = controller.state
    val enabled: StateFlow<Boolean> = controller.enabled
    val curve: StateFlow<DoubleArray> = controller.curve
    val filters: StateFlow<PeqSettings> = controller.filters
    val summary: StateFlow<String> = controller.summary
    val busy: StateFlow<Boolean> = controller.busy

    val ui: StateFlow<HearingTestRunner.Ui> = runner.ui
    val testPhase: StateFlow<HearingTestRunner.Phase> = runner.ui
        .map { it.phase }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HearingTestRunner.Phase.IDLE)

    private val _readings = MutableStateFlow<List<HearingTestSession.Reading>>(emptyList())
    val readings: StateFlow<List<HearingTestSession.Reading>> = _readings.asStateFlow()

    private val _pending = MutableStateFlow<HearingProfile?>(null)

    /** What the last run found, before it is accepted. */
    val pending: StateFlow<HearingProfile?> = _pending.asStateFlow()

    /** Applied now, so a change is heard while the dialog is still open. */
    fun setStrength(value: Double) {
        viewModelScope.launch { controller.setStrength(value) }
    }

    val savedDevices: StateFlow<List<com.lastwave.app.data.hearing.HearingArchive.Entry>> = controller.savedDevices

    /** Listen through another saved device's curve. */
    fun useDevice(key: String) {
        viewModelScope.launch { controller.useDevice(key) }
    }

    fun forgetDevice(key: String) {
        viewModelScope.launch { controller.forgetDevice(key) }
    }

    /** True when the curve in use was measured on a different output than this one. */
    fun isMismatched(currentDeviceLabel: String): Boolean =
        com.lastwave.app.data.hearing.HearingArchive.isMismatched(state.value, currentDeviceLabel)

    fun setEnabled(on: Boolean) {
        viewModelScope.launch { controller.setEnabled(on) }
    }

    /** Re-read volume and routing while the dialog is not playing tones. */
    fun refreshEnvironment() = runner.pollEnvironment()

    fun startTest() {
        _readings.value = emptyList()
        _pending.value = null
        runner.reset()
        runner.start()
    }

    fun answer(heard: Boolean) = runner.answer(heard)
    fun replay() = runner.replay()
    fun retryBand() = runner.retryBand()
    fun skipBand() = runner.skipBand()
    fun stopTest() = runner.stop()

    /** Keep the run's numbers without switching the correction on. */
    fun acceptProfile(enable: Boolean) {
        val profile = _pending.value ?: runner.profile()
        if (profile.bands.isEmpty()) return
        viewModelScope.launch {
            controller.save(profile, enable = enable)
            _pending.value = null
            runner.reset()
            refreshReadings()
        }
    }

    fun discardRun() {
        _pending.value = null
        runner.reset()
    }

    fun clearProfile() {
        viewModelScope.launch {
            controller.clear()
            _pending.value = null
            runner.reset()
        }
    }

    /** Drop one band's answer, e.g. "the cat landed on me during that one". */
    fun removeBand(frequencyHz: Double) {
        viewModelScope.launch { controller.removeBand(frequencyHz) }
    }

    /** Called when the run reaches its end, and when the dialog opens. */
    fun refreshReadings() {
        _readings.value = runner.readings()
        if (runner.ui.value.phase == HearingTestRunner.Phase.FINISHED) {
            _pending.value = runner.profile()
        }
    }

    init {
        viewModelScope.launch {
            runner.ui.map { it.phase to it.step?.responses }
                .distinctUntilChanged()
                .collect { refreshReadings() }
        }
    }
}
