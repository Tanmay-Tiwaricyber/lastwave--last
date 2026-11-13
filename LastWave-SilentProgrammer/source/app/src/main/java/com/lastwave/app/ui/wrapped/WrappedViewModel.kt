package com.lastwave.app.ui.wrapped

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.lastwave.app.R
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.repository.WrappedPeriod
import com.lastwave.app.data.repository.WrappedReport
import com.lastwave.app.data.repository.WrappedRepository
import com.lastwave.app.data.repository.WrappedWindow
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Calendar
import javax.inject.Inject

/**
 * State holder for LastWave Wrapped (v5.4.0; story, months, poster styles and
 * "play my top tracks" added in v5.5.0).
 *
 * The recap is computed on demand from the append-only listen log, so there is
 * nothing to keep in sync: switching period simply re-runs the aggregation.
 */
@HiltViewModel
class WrappedViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: WrappedRepository,
    private val musicPlayer: MusicPlayer,
    private val settingsPreferences: SettingsPreferences,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val periods: List<WrappedPeriod> = emptyList(),
        val selected: WrappedPeriod = WrappedPeriod.AllTime,
        val report: WrappedReport? = null,
        /** True when [selected] had no data and the report shows All time instead. */
        val fellBackToAllTime: Boolean = false,
        val sharing: Boolean = false,
        /** The in-app "reduce motion" preference; the screen also checks the system setting. */
        val reduceMotion: Boolean = false,
    )

    sealed interface Event {
        data object ShareFailed : Event
        data object NothingToPlay : Event
        data class Playing(val count: Int) : Event
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 1)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val thisYear = Calendar.getInstance().get(Calendar.YEAR)
            val years = WrappedWindow.yearsWithData(repository.firstListenAt(), now)
                .ifEmpty { listOf(thisYear) }
            val periods = buildList<WrappedPeriod> {
                years.forEach { add(WrappedPeriod.Year(it)) }
                add(WrappedPeriod.Last12Months)
                add(WrappedPeriod.AllTime)
                addAll(monthsWithData(repository.firstListenAt(), now))
            }
            _state.update { it.copy(periods = periods, selected = periods.first()) }
            load(periods.first())
            // Housekeeping: cheap, and only ever runs when the screen is opened.
            runCatching { repository.prune() }
        }
    }

    init {
        viewModelScope.launch {
            settingsPreferences.settings
                .map { it.reduceMotion }
                .catch { emit(false) }
                .collect { reduce -> _state.update { it.copy(reduceMotion = reduce) } }
        }
    }

    /**
     * Months that can have data, newest first, capped at twelve. Before the
     * first listen there is nothing to show, so only the current month is
     * offered.
     */
    private fun monthsWithData(firstListenAtMillis: Long?, nowMillis: Long): List<WrappedPeriod.Month> {
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val nowTotal = now.get(Calendar.YEAR) * 12 + now.get(Calendar.MONTH)
        val firstTotal = firstListenAtMillis?.takeIf { it > 0L }?.let {
            val c = Calendar.getInstance().apply { timeInMillis = it }
            c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH)
        } ?: nowTotal
        val oldest = maxOf(minOf(firstTotal, nowTotal), nowTotal - 11)
        return (nowTotal downTo oldest).map { WrappedPeriod.Month(it / 12, it % 12) }
    }

    /**
     * Queues the period's top tracks, most-played first. Only tracks the log
     * recorded a stream id for can be queued; anything else is left out.
     */
    fun playTop() {
        val report = _state.value.report ?: return
        if (report.isEmpty) return
        viewModelScope.launch {
            val tracks = runCatching { repository.topTracksForPlayback(report.window) }
                .getOrDefault(emptyList())
                .filter { !it.videoId.isNullOrBlank() }
                .map {
                    PlayableTrack(
                        title = it.title,
                        artist = it.artist,
                        artworkUrl = it.artworkUrl,
                        videoId = it.videoId,
                    )
                }
            if (tracks.isEmpty()) {
                _events.tryEmit(Event.NothingToPlay)
            } else {
                musicPlayer.playQueue(tracks, startIndex = 0, sourceLabel = "Wrapped \u00B7 ${report.window.period.label}")
                _events.tryEmit(Event.Playing(tracks.size))
            }
        }
    }

    fun select(period: WrappedPeriod) {
        if (period == _state.value.selected && _state.value.report != null) return
        load(period)
    }

    private fun load(period: WrappedPeriod) {
        loadJob?.cancel()
        _state.update { it.copy(selected = period, loading = true) }
        loadJob = viewModelScope.launch {
            val report = runCatching { repository.buildReport(period) }.getOrNull()
            _state.update {
                it.copy(
                    loading = false,
                    report = report,
                    fellBackToAllTime = report != null &&
                        !report.isEmpty &&
                        report.window.period != period,
                )
            }
        }
    }

    /** Renders the poster off the main thread and opens the share sheet. */
    fun share(activityContext: Context, style: WrappedPoster.Style = WrappedPoster.Style.SUMMARY) {
        val report = _state.value.report ?: return
        if (report.isEmpty || _state.value.sharing) return
        _state.update { it.copy(sharing = true) }
        viewModelScope.launch {
            val ok = runCatching {
                val uri = withContext(Dispatchers.IO) {
                    val wanted = if (WrappedPoster.supports(style, report)) style else WrappedPoster.Style.SUMMARY
                    val bitmap = WrappedPoster.render(report, loadTypefaces(), loadCovers(report), wanted)
                    val dir = File(appContext.cacheDir, "shared/wrapped").apply { mkdirs() }
                    // One file per period is plenty; older exports are replaced.
                    dir.listFiles()?.forEach { it.delete() }
                    val slug = report.window.period.label.lowercase().replace(Regex("[^a-z0-9]+"), "-")
                    val file = File(dir, "lastwave-wrapped-$slug-${wanted.name.lowercase()}.png")
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    FileProvider.getUriForFile(appContext, "${appContext.packageName}.provider", file)
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("LastWave Wrapped", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, appContext.getString(R.string.wrapped_share)).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activityContext.startActivity(chooser)
            }.isSuccess
            _state.update { it.copy(sharing = false) }
            if (!ok) _events.tryEmit(Event.ShareFailed)
        }
    }

    private fun loadTypefaces(): WrappedPoster.Typefaces {
        val fallback = WrappedPoster.Typefaces.SYSTEM
        return WrappedPoster.Typefaces(
            sans = runCatching { ResourcesCompat.getFont(appContext, R.font.plex_sans_regular) }.getOrNull()
                ?: fallback.sans,
            sansBold = runCatching { ResourcesCompat.getFont(appContext, R.font.plex_sans_semibold) }.getOrNull()
                ?: fallback.sansBold,
            mono = runCatching { ResourcesCompat.getFont(appContext, R.font.plex_mono_medium) }.getOrNull()
                ?: fallback.mono,
            monoRegular = runCatching { ResourcesCompat.getFont(appContext, R.font.plex_mono_regular) }.getOrNull()
                ?: fallback.monoRegular,
        )
    }

    /**
     * Best-effort artwork for the three poster rows. Anything that is not in
     * Coil's cache within a couple of seconds is skipped — the poster keeps a
     * placeholder tile rather than making the share sheet wait on the network.
     */
    private suspend fun loadCovers(report: WrappedReport): Map<String, Bitmap> {
        val out = HashMap<String, Bitmap>()
        for (entry in report.topTracks.take(3)) {
            val url = entry.artworkUrl?.takeIf { it.isNotBlank() } ?: continue
            val bitmap = withTimeoutOrNull(2_500L) {
                runCatching {
                    val request = ImageRequest.Builder(appContext)
                        .data(url)
                        .size(160)
                        .allowHardware(false)
                        .build()
                    (appContext.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap(160, 160)
                }.getOrNull()
            } ?: continue
            out[entry.videoId ?: entry.title] = bitmap
        }
        return out
    }
}
