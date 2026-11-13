package com.lastwave.app.ui.match

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.match.MelodyIndex
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.melody.MelodyMatcher
import com.lastwave.app.playback.melody.MelodyOutline
import com.lastwave.app.playback.melody.MelodyTracker
import com.lastwave.app.playback.melody.PitchTracker
import com.lastwave.app.ui.settings.toPlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State for "sing to find": tap, hum eight notes, the app plays the song it
 * thinks you meant (v5.7.0).
 *
 * Capture is a plain [AudioRecord] loop at 16 kHz — the rate the pitch tracker
 * is built for, and low enough that a whole take is a few hundred kilobytes.
 * The trace is deliberately not run while you sing: a note you have not finished
 * is a note of unknown length, and length is part of what the matcher uses. What
 * the screen shows during a take is level and elapsed time, which is also the
 * useful thing, because it answers "is it hearing me" before "what did I sing".
 */
@HiltViewModel
internal class SingToFindViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicPlayer: MusicPlayer,
    private val melodyIndex: MelodyIndex,
) : ViewModel() {

    data class UiState(
        val permissionMissing: Boolean = false,
        val phase: Phase = Phase.READY,
        /** 0..1, smoothed, for the meter while singing. */
        val level: Float = 0f,
        val elapsedSeconds: Float = 0f,
        val index: MelodyIndex.Progress? = null,
        val results: List<MelodyIndex.Result> = emptyList(),
        val verdict: MelodyMatcher.Verdict = MelodyMatcher.Verdict.NOTHING,
        /** What the app heard, as note names, so a wrong answer is at least explainable. */
        val heardNotes: List<String> = emptyList(),
        val indexedSongs: Int = 0,
        val message: String? = null,
        /** True when the app started playback itself rather than on a tap. */
        val autoPlayed: Boolean = false,
    )

    enum class Phase { READY, INDEXING, LISTENING, THINKING, RESULT }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val recording = AtomicBoolean(false)
    private var capture: Job? = null

    /** The current take, mono 16 kHz, reused so a screen rotation does not allocate. */
    private val taken = ShortArray(MAX_SAMPLES)
    private var takenSize = 0

    init {
        refresh()
    }

    /** Re-check the microphone and how much of the library has a tune read. */
    fun refresh() {
        _state.update { it.copy(permissionMissing = !hasMicPermission()) }
        viewModelScope.launch {
            val candidates = runCatching { melodyIndex.candidates() }.getOrDefault(emptyList())
            val signed = runCatching { melodyIndex.signedKeyCount() }.getOrDefault(0)
            _state.update {
                it.copy(
                    indexedSongs = signed,
                    message = when {
                        candidates.isEmpty() ->
                            "No downloaded songs to search \u00B7 sing-to-find only knows the audio on this phone"
                        signed == 0 -> "No tunes read yet \u00B7 tap Read tunes first"
                        else -> it.message
                    },
                )
            }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(permissionMissing = !granted) }
    }

    /** Compute the tune of everything not yet read. Safe to call twice. */
    fun indexLibrary() {
        if (_state.value.phase == Phase.INDEXING) return
        viewModelScope.launch {
            _state.update { it.copy(phase = Phase.INDEXING, index = MelodyIndex.Progress(0, 0, "")) }
            val usable = runCatching {
                melodyIndex.indexMissing { progress -> _state.update { it.copy(index = progress) } }
            }.getOrDefault(-1)
            _state.update {
                it.copy(
                    phase = Phase.READY,
                    index = null,
                    indexedSongs = usable.coerceAtLeast(0),
                    message = when {
                        usable < 0 -> "Reading tunes failed"
                        usable == 0 -> "No song had a tune worth reading"
                        else -> "Read the tunes of $usable song${if (usable == 1) "" else "s"}"
                    },
                )
            }
        }
    }

    fun startListening() {
        if (recording.get()) return
        if (!hasMicPermission()) {
            _state.update { it.copy(permissionMissing = true) }
            return
        }
        takenSize = 0
        recording.set(true)
        _state.update {
            it.copy(phase = Phase.LISTENING, results = emptyList(), level = 0f, elapsedSeconds = 0f, message = null, autoPlayed = false)
        }
        capture = viewModelScope.launch(Dispatchers.Default) {
            val record = openRecord()
            if (record == null) {
                recording.set(false)
                _state.update { it.copy(phase = Phase.READY, message = "Microphone unavailable on this device") }
                return@launch
            }
            val chunk = ShortArray(READ_SAMPLES)
            var level = 0f
            var failed = false
            try {
                record.startRecording()
                while (recording.get() && takenSize < MAX_SAMPLES) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read < 0) {
                        failed = true
                        break
                    }
                    if (read == 0) continue
                    val room = min(read, MAX_SAMPLES - takenSize)
                    var sum = 0.0
                    for (i in 0 until room) {
                        taken[takenSize + i] = chunk[i]
                        sum += chunk[i].toDouble() * chunk[i]
                    }
                    takenSize += room
                    // Smoothed, so the meter breathes instead of flickering.
                    val rms = (sqrt(sum / room) / 32_768.0).toFloat()
                    level = 0.6f * level + 0.4f * (rms * 6f).coerceIn(0f, 1f)
                    _state.update {
                        it.copy(level = level, elapsedSeconds = takenSize.toFloat() / SAMPLE_RATE)
                    }
                }
            } catch (error: Exception) {
                failed = true
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                recording.set(false)
            }
            if (failed) {
                _state.update { it.copy(phase = Phase.READY, level = 0f, message = "Recording failed") }
                return@launch
            }
            analyse()
        }
    }

    fun stopListening() {
        recording.set(false)
    }

    /** Back to an empty screen, whether or not a take is running. */
    fun reset() {
        recording.set(false)
        capture?.cancel()
        capture = null
        takenSize = 0
        _state.update { UiState(permissionMissing = !hasMicPermission(), indexedSongs = it.indexedSongs) }
    }

    /** The part that costs real time: trace, cut into notes, align against the library. */
    private suspend fun analyse() {
        _state.update { it.copy(phase = Phase.THINKING, level = 0f) }
        val samples = taken.copyOf(takenSize)
        val outcome = runCatching {
            withContext(Dispatchers.Default) { analyseBlocking(samples) }
        }.getOrDefault(Outcome.Failed)

        val matched = outcome as? Outcome.Matched
        val matches = matched?.results?.map { it.match }.orEmpty()
        val verdict = MelodyMatcher.verdict(matches)
        val autoPlay = matched != null && matches.isNotEmpty() && MelodyMatcher.isConfident(matches)
        if (autoPlay) playNow(matched!!.results.first())

        _state.update { current ->
            when {
                matched != null -> current.copy(
                    phase = Phase.RESULT,
                    results = matched.results,
                    verdict = verdict,
                    heardNotes = matched.outline.noteNames(),
                    autoPlayed = autoPlay,
                    message = when (verdict) {
                        MelodyMatcher.Verdict.SURE -> "That sounds like ${matched.results.first().track.title}"
                        MelodyMatcher.Verdict.CLOSE -> "Close \u00B7 tap one to play it"
                        MelodyMatcher.Verdict.UNCERTAIN -> "Not sure \u00B7 these are the nearest tunes in your library"
                        MelodyMatcher.Verdict.NOTHING ->
                            "Nothing matches \u00B7 only downloaded songs are searched, and ${current.indexedSongs} have been read"
                    },
                )
                outcome is Outcome.NoTune -> current.copy(
                    phase = Phase.READY,
                    results = emptyList(),
                    heardNotes = emptyList(),
                    message = "Could not hear a tune \u00B7 sing louder and closer, one line at a time",
                )
                outcome is Outcome.TooShort -> current.copy(
                    phase = Phase.READY,
                    results = emptyList(),
                    heardNotes = emptyList(),
                    message = "Too short \u00B7 hum one clear line, about two seconds or more",
                )
                else -> current.copy(phase = Phase.READY, message = "Could not analyse that \u00B7 try again")
            }
        }
    }

    private suspend fun analyseBlocking(samples: ShortArray): Outcome {
        if (samples.size < MIN_SAMPLES) return Outcome.TooShort
        val trace = PitchTracker.trace(samples, HUM)
        val outline = MelodyTracker.outlineOfVoice(trace, HUM.hopSamples.toFloat() / HUM.sampleRateHz)
        if (outline.isEmpty) return Outcome.NoTune
        val results = melodyIndex.match(outline)
        if (results.isEmpty()) return Outcome.Matched(outline, emptyList())
        return Outcome.Matched(outline, results)
    }

    /** Play a hit from the moment the sung phrase starts inside it. */
    fun play(result: MelodyIndex.Result) {
        viewModelScope.launch { playNow(result) }
    }

    private fun playNow(result: MelodyIndex.Result) {
        val track = result.track.toPlayableTrack()
        val startMs = (result.match.startSeconds * 1_000f).roundToInt().coerceAtLeast(0)
        musicPlayer.playQueue(listOf(track), startIndex = 0, sourceLabel = "Sing to find")
        if (startMs > 0) musicPlayer.seekTo(startMs.toLong())
    }

    private fun openRecord(): AudioRecord? {
        val minBuffer = runCatching {
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        }.getOrDefault(4_096).coerceAtLeast(4_096)
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 4,
            )
        }.getOrNull()
        if (record != null && record.state == AudioRecord.STATE_INITIALIZED) return record
        runCatching { record?.release() }
        return null
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private sealed class Outcome {
        data object TooShort : Outcome()
        data object NoTune : Outcome()
        data object Failed : Outcome()
        data class Matched(val outline: MelodyOutline, val results: List<MelodyIndex.Result>) : Outcome()
    }

    private companion object {
        const val SAMPLE_RATE = PitchTracker.DEFAULT_SAMPLE_RATE

        /** 12 s at 16 kHz: long enough for a chorus, small enough to keep in RAM. */
        const val MAX_SAMPLES = SAMPLE_RATE * 12

        /** Under two seconds there is no tune to identify, whatever the tracker says. */
        const val MIN_SAMPLES = SAMPLE_RATE * 2

        /** 64 ms reads: about four tracker hops, which keeps the meter smooth. */
        const val READ_SAMPLES = 1_024

        /** A lone voice into a microphone. See [PitchTracker.VOICE]. */
        val HUM = PitchTracker.VOICE
    }
}

/** Note names for a tune described as intervals: what the app thinks you sang. */
internal fun MelodyOutline.noteNames(): List<String> {
    if (intervals.isEmpty()) return emptyList()
    val out = ArrayList<String>(intervals.size)
    var pitch = intervals[0].toFloat()
    out += PitchTracker.noteName(pitch)
    for (i in 1 until intervals.size) {
        pitch += intervals[i]
        out += PitchTracker.noteName(pitch)
    }
    return out
}
