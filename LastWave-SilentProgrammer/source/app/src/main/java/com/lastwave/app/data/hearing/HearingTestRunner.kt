package com.lastwave.app.data.hearing

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lastwave.app.playback.MusicPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Plays the test tones and drives a [HearingTestSession].
 *
 * Three properties matter more here than the code being tidy:
 *
 *  - *The tone must not hear the app's own EQ.* The parametric EQ and the
 *    DynamicsProcessing bands are attached to the media player's audio session
 *    (AudioEffectsEngine hands `audioSessionId` to the effect constructor), so a
 *    fresh [AudioTrack] with its own session is untouched by them. That is the
 *    whole point: calibrating a curve through the curve would be circular.
 *  - *Quiet, and paused.* A -60 dBFS tone is quieter than the room, so playback
 *    is paused before the first burst and this class never resumes it. If no tap
 *    arrives within [LISTEN_TIMEOUT_MS] the step is recorded as "not heard" —
 *    a listener who nodded off must not walk away with a curve.
 *  - *One ear at a time.* Isolation happens in the samples, not through a volume
 *    call, because [AudioTrack] has no per-channel gain on modern Android: the
 *    opposite channel holds exact zeros. Whether a particular headset keeps them
 *    apart is a hardware question, so the UI says "if you hear it clearly in both
 *    ears, use the mono option".
 */
class HearingTestRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val player: MusicPlayer,
) {

    enum class Phase { IDLE, TONE, LISTENING, FINISHED, STOPPED, FAILED }

    data class Ui(
        val phase: Phase = Phase.IDLE,
        val step: HearingTestSession.Step? = null,
        val message: String = "",
        val routedTo: String = "",
        /** Device volume as a percentage; above [SAFE_VOLUME_PERCENT] the test refuses to start. */
        val volumePercent: Int = 0,
        val hasHeadphones: Boolean = true,
        val canAnswer: Boolean = false,
        val canReplay: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    @Volatile
    private var session: HearingTestSession? = null

    @Volatile
    private var toneWanted = false

    private var track: AudioTrack? = null
    private var routedDevice: AudioDeviceInfo? = null

    /** True when the tone is currently going to one channel only. */
    @Volatile
    var monoIsolation = true

    /**
     * Start the test. Returns false, with a message explaining why, when the
     * volume is too high to be linear or no headphones are attached.
     */
    fun start(): Boolean {
        if (session != null) return false
        val volume = volumePercent()
        if (volume > SAFE_VOLUME_PERCENT) {
            fail(
                "Turn the device volume down to $SAFE_VOLUME_PERCENT% or less first. Past that " +
                    "the output limiter compresses the difference between steps, and the test " +
                    "would be measuring the limiter instead of the ear.",
            )
            return false
        }
        val device = pickOutput()
        if (device == null) {
            _ui.value = Ui(
                phase = Phase.FAILED,
                hasHeadphones = false,
                message = "Connect headphones first. A hearing test on a phone speaker in a room " +
                    "measures the room, not the ear.",
            )
            return false
        }
        val opened = openTrack(device, HearingTones.SAMPLE_RATE)
        if (opened == null) {
            fail("The audio output refused the test stream. Try the other device, or unplug and retry.")
            return false
        }
        runCatching { player.pause() }
        track = opened
        routedDevice = device
        val current = HearingTestSession()
        session = current
        _ui.value = Ui(
            phase = Phase.TONE,
            step = current.current(),
            routedTo = label(device),
            volumePercent = volume,
            message = "First band: ${earName(current.current()?.ear)} ear. Answer after each tone " +
                "stops \u2014 \u201CHeard it\u201D only if you made it out at all.",
        )
        scope.launch { runTest(current) }
        return true
    }

    /** The listener's answer. The latch in [Ui.canAnswer] keeps a double tap out of the staircase. */
    fun answer(heard: Boolean) {
        val current = session ?: return
        val snapshot = _ui.value
        if (snapshot.phase != Phase.LISTENING || !snapshot.canAnswer) return
        _ui.value = snapshot.copy(canAnswer = false, canReplay = false, message = "")
        current.answer(heard)
    }

    /** Play the current band again at the same level. Not an answer: the staircase does not move. */
    fun replay() {
        val current = session ?: return
        val snapshot = _ui.value
        if (snapshot.phase != Phase.LISTENING || !snapshot.canReplay) return
        _ui.value = _ui.value.copy(canReplay = false, canAnswer = false, phase = Phase.TONE)
        scope.launch { present(current, replayOnly = true) }
    }

    /** "That answer was wrong": this band starts over from its start level. */
    fun retryBand() {
        val current = session ?: return
        cutTone()
        current.retryCurrent()
        _ui.value = _ui.value.copy(canAnswer = false, canReplay = false, phase = Phase.TONE, step = current.current())
    }

    /** Abandon this band; it is reported as untested instead of guessed at. */
    fun skipBand() {
        val current = session ?: return
        cutTone()
        current.skip()
        _ui.value = _ui.value.copy(canAnswer = false, canReplay = false, phase = Phase.TONE, step = current.current())
        if (current.isComplete) finish(current)
    }

    /**
     * End the run. Stopping keeps whatever bands were already measured: the
     * button says so, and a listener who gives up after three bands is holding
     * three bands of information rather than nothing.
     */
    fun stop() {
        toneWanted = false
        val current = session
        releaseTrack()
        if (current != null && current.profile().bands.isNotEmpty()) {
            finish(current)
        } else {
            session = null
            _ui.value = _ui.value.copy(
                phase = Phase.STOPPED,
                canAnswer = false,
                canReplay = false,
                message = "Test stopped; nothing was kept.",
            )
        }
    }

    /** Back to a clean dialog; keeps nothing. */
    fun reset() {
        toneWanted = false
        releaseTrack()
        session = null
        _ui.value = Ui()
    }

    /** What the run found so far, which is why leaving early is allowed. */
    fun profile(): HearingProfile {
        val current = session
        val device = routedDevice
        return current?.profile(deviceLabel = label(device), formFactor = formFactorOf(device))
            ?: HearingProfile(bands = emptyList())
    }

    fun readings(): List<HearingTestSession.Reading> = session?.readings().orEmpty()

    /** Refresh the volume/device lines while the dialog is open. */
    fun pollEnvironment() {
        val snapshot = _ui.value
        if (snapshot.phase != Phase.IDLE && snapshot.phase != Phase.FAILED) {
            _ui.value = snapshot.copy(volumePercent = volumePercent())
            return
        }
        val device = pickOutput()
        _ui.value = snapshot.copy(
            volumePercent = volumePercent(),
            hasHeadphones = device != null,
            // So the "measured on X, listening on Y" line is true now, not when
            // the dialog happened to be opened.
            routedTo = label(device),
        )
    }

    // ---------------------------------------------------------------- test loop

    private suspend fun runTest(current: HearingTestSession) {
        while (session === current && current.beginPresentation() != null) {
            present(current)
            var waited = 0L
            while (_ui.value.phase == Phase.LISTENING && _ui.value.canAnswer && waited < LISTEN_TIMEOUT_MS) {
                delay(TICK_MS)
                waited += TICK_MS
            }
            val phase = _ui.value.phase
            if (phase == Phase.STOPPED || phase == Phase.FAILED || phase == Phase.FINISHED) return
            if (_ui.value.canAnswer) {
                answer(heard = false)
                if (_ui.value.phase == Phase.LISTENING) {
                    _ui.value = _ui.value.copy(canAnswer = false, message = "No answer, counted as not heard.")
                }
            }
            if (current.isComplete) break
            delay(SETTLE_MS)
        }
        finish(current)
    }

    private suspend fun present(current: HearingTestSession, replayOnly: Boolean = false) {
        val step = current.current() ?: return
        val samples = runCatching {
            HearingTones.burst(
                frequencyHz = step.frequencyHz,
                levelDbFs = step.levelDb,
                durationSec = TONE_SECONDS,
                ear = if (monoIsolation) step.ear else null,
            )
        }.getOrNull()
        if (samples == null) {
            fail("Could not build the test tone.")
            return
        }
        toneWanted = true
        if (!writeBlocking(samples)) {
            if (_ui.value.phase == Phase.TONE) fail("The output device stopped accepting audio.")
            return
        }
        if (!toneWanted) return // cut over or stopped while the tone was queued
        delay(TAIL_MS)
        _ui.value = _ui.value.copy(
            phase = Phase.LISTENING,
            step = if (replayOnly) _ui.value.step else current.current(),
            canAnswer = true,
            canReplay = true,
            message = if (replayOnly) "Again. Heard it that time?" else "Did you hear that?",
        )
    }

    private fun finish(current: HearingTestSession) {
        toneWanted = false
        releaseTrack()
        val kept = current.profile().bands.size
        _ui.value = _ui.value.copy(
            phase = Phase.FINISHED,
            canAnswer = false,
            canReplay = false,
            step = null,
            message = if (kept < HearingProfile.MIN_BANDS) {
                "Only $kept band${if (kept == 1) "" else "s"} came out usable, and a correction needs " +
                    "${HearingProfile.MIN_BANDS}. A quieter room usually fixes this."
            } else {
                "$kept of ${current.total} bands measured. Look the numbers over before saving."
            },
        )
    }

    private fun fail(message: String) {
        toneWanted = false
        releaseTrack()
        _ui.value = _ui.value.copy(phase = Phase.FAILED, canAnswer = false, canReplay = false, message = message)
    }

    /** Stop whatever is queued so a retry or skip is not heard on top of the next band. */
    private fun cutTone() {
        toneWanted = false
        val target = track ?: return
        mainHandler.post {
            runCatching {
                if (target.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    target.pause()
                    target.flush()
                    target.play()
                }
            }
        }
    }

    private fun writeBlocking(samples: ShortArray): Boolean {
        val target = track ?: return false
        return try {
            val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
            samples.forEach { bytes.putShort(it) }
            val payload = bytes.array()
            if (target.playState != AudioTrack.PLAYSTATE_PLAYING) target.play()
            val startHead = runCatching { target.playbackHeadPosition }.getOrDefault(0)
            var offset = 0
            while (offset < payload.size && toneWanted) {
                val written = target.write(payload, offset, payload.size - offset, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) {
                    Log.w(TAG, "AudioTrack.write returned $written")
                    return false
                }
                offset += written
            }
            if (!toneWanted) return true
            // Wait for the hardware to reach the end of the burst, so the
            // question is never asked while the tone is still sounding. A
            // head-position reset can only cost a bounded extra wait.
            val frames = samples.size / CHANNELS
            val deadline = System.currentTimeMillis() + (frames * 1000L / HearingTones.SAMPLE_RATE) + 1200L
            while (toneWanted && System.currentTimeMillis() < deadline) {
                val head = runCatching { target.playbackHeadPosition }.getOrDefault(Int.MAX_VALUE)
                if (head - startHead >= frames) break
                Thread.sleep(12)
            }
            true
        } catch (error: Exception) {
            Log.w(TAG, "test tone write failed", error)
            false
        }
    }

    private fun releaseTrack() {
        val target = track
        track = null
        runCatching {
            target?.pause()
            target?.flush()
            target?.stop()
        }
        runCatching { target?.release() }
    }

    // ------------------------------------------------------------------ routing

    private fun pickOutput(): AudioDeviceInfo? {
        val devices = runCatching { audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull()
            ?: return null
        val preferred = intArrayOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
        )
        for (type in preferred) {
            devices.firstOrNull { it.type == type && it.isSink }?.let { return it }
        }
        return devices.firstOrNull { it.isSink && it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    private fun label(device: AudioDeviceInfo?): String =
        device?.let { runCatching { it.productName.toString() }.getOrNull() }.orEmpty().ifBlank { "the connected device" }

    /**
     * What kind of seal this is, remembered with the profile. The same ear
     * loses a few dB of bass to a bad ear-tip fit, which is why the curve is
     * kept per device instead of once per person.
     */
    private fun formFactorOf(device: AudioDeviceInfo?): String = when (device?.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> "in-ear"

        null -> ""
        else -> "headphones"
    }

    private fun earName(ear: Ear?): String = when (ear) {
        Ear.LEFT -> "left"
        Ear.RIGHT -> "right"
        null -> "both"
    }

    private fun volumePercent(): Int {
        val manager = audioManager ?: return 0
        val max = runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        val current = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        return if (max <= 0) 100 else (current * 100 / max)
    }

    companion object {
        /**
         * Above this the test refuses to start. Not a hearing-protection number:
         * the reason is measurement. Near maximum output Android's limiter
         * compresses the difference between steps, so the staircase would be
         * finding the limiter's knee instead of the ear's threshold.
         */
        const val SAFE_VOLUME_PERCENT = 60

        private const val TAG = "HearingTestRunner"
        const val CHANNELS = 2
        const val TONE_SECONDS = 0.75
        const val TAIL_MS = 60L
        const val SETTLE_MS = 220L
        const val TICK_MS = 60L
        const val LISTEN_TIMEOUT_MS = 6_000L
    }
}

/**
 * A stereo 16-bit stream track pinned to [device].
 *
 * 4x the minimum buffer, the same margin DirectTrackStream uses for real
 * playback: a tight buffer underruns on a Bluetooth link, and an underrun in
 * the middle of a burst reads to the listener as "it got louder", which is
 * exactly the judgement the test must not depend on.
 */
internal fun openTrack(device: AudioDeviceInfo?, sampleRateHz: Int): AudioTrack? = runCatching {
    val mask = AudioFormat.CHANNEL_OUT_STEREO
    val minBytes = AudioTrack.getMinBufferSize(sampleRateHz, mask, AudioFormat.ENCODING_PCM_16BIT)
    if (minBytes <= 0) return@runCatching null
    val created = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRateHz)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(mask)
                .build(),
        )
        .setBufferSizeInBytes(minBytes * 4)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
        .build()
    if (created.state != AudioTrack.STATE_INITIALIZED) {
        created.release()
        return@runCatching null
    }
    if (device != null) runCatching { created.setPreferredDevice(device) }
    created
}.getOrNull()
