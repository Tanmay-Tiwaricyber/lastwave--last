package com.lastwave.app.data.match

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import com.lastwave.app.data.local.db.MelodySignatureDao
import com.lastwave.app.data.local.db.MelodySignatureEntity
import com.lastwave.app.playback.CapturingAudioSink
import com.lastwave.app.playback.melody.MelodyMatch
import com.lastwave.app.playback.melody.MelodyMatcher
import com.lastwave.app.playback.melody.MelodyOutline
import com.lastwave.app.playback.melody.MelodySignature
import com.lastwave.app.playback.melody.MelodyTracker
import com.lastwave.app.playback.melody.PitchTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reading the tunes of the songs on this phone, so a hummed phrase can be
 * matched against them.
 *
 * A signature is computed by decoding the file to PCM with [CapturingAudioSink]
 * — the same trick the FLAC transcoder uses, which runs as fast as the CPU
 * allows rather than in real time — and then running the mix tracker over it.
 * Decoding is the expensive part, so results are cached in Room keyed by track
 * and by file size: replacing a download recomputes, everything else is free.
 * A song that yields no tune is stored as an empty row rather than forgotten,
 * because "this file has no melody to find" is also worth knowing.
 *
 * Only files the app can open are indexed, which means your downloads and the
 * audio you imported. Singing at a song that is not on the device needs a
 * catalogue-wide fingerprint service; this is not that, and says so in the UI.
 */
@Singleton
@OptIn(UnstableApi::class)
internal class MelodyIndex @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadedTrackDao: DownloadedTrackDao,
    private val signatureDao: MelodySignatureDao,
) {

    /** Progress of a library pass. Counts only tracks still to be done. */
    data class Progress(val done: Int, val total: Int, val currentTitle: String)

    private val lock = Mutex()

    /** Everything already computed, as `trackKey to signature`, tuneless songs left out. */
    suspend fun library(): List<Pair<String, MelodySignature>> = withContext(Dispatchers.IO) {
        signatureDao.getAll()
            .asSequence()
            .filter { it.noteCount >= MelodyOutline.MIN_NOTES && it.intervals.isNotEmpty() }
            .map { it.trackKey to it.toSignature() }
            .toList()
    }

    /** Songs on disk that could be indexed. */
    suspend fun candidates(): List<DownloadedTrackEntity> = withContext(Dispatchers.IO) {
        downloadedTrackDao.getAllList().filter { it.fileOrNull() != null }
    }

    suspend fun indexedKeyCount(): Int = withContext(Dispatchers.IO) { signatureDao.count() }

    suspend fun signedKeyCount(): Int = withContext(Dispatchers.IO) { library().size }

    /**
     * Compute whatever is missing. [onProgress] runs after every track, failures
     * included, so a progress bar always moves. Returns how many songs in the
     * library now have a usable tune.
     */
    suspend fun indexMissing(onProgress: (Progress) -> Unit = {}): Int = lock.withLock {
        val tracks = candidates()
        val known = withContext(Dispatchers.IO) { signatureDao.getAll().associate { it.trackKey to it } }
        val todo = tracks.filter { track ->
            val row = known[track.matchKey()]
            row == null || row.stamp != track.sizeOnDisk()
        }
        for (index in todo.indices) {
            val track = todo[index]
            val signature = runCatching { extract(track) }.getOrNull()
            withContext(Dispatchers.IO) { store(track.matchKey(), track.sizeOnDisk(), signature) }
            onProgress(Progress(index + 1, todo.size, track.title))
        }
        library().size
    }

    /** Drop every cached tune: after a tracker change, or from Settings. */
    suspend fun clear() = withContext(Dispatchers.IO) { signatureDao.clearAll() }

    /** Rank the library against a sung outline. Empty when nothing is indexed. */
    suspend fun match(hum: MelodyOutline): List<Result> {
        val songs = library()
        if (songs.isEmpty() || hum.isEmpty) return emptyList()
        val signature = MelodySignature.of(hum, durationSeconds = hum.noteCount * 0.55f)
        val ranked = MelodyMatcher.rank(signature, songs, limit = 6)
        if (ranked.isEmpty()) return emptyList()
        val byKey = withContext(Dispatchers.IO) {
            downloadedTrackDao.getAllList().associateBy { it.matchKey() }
        }
        return ranked.mapNotNull { match ->
            val track = byKey[match.key] ?: return@mapNotNull null
            Result(match = match, track = track)
        }
    }

    /** A ranked song, with the library row needed to play it. */
    data class Result(val match: MelodyMatch, val track: DownloadedTrackEntity) {
        val percent: Int get() = MelodyMatcher.percent(match.score)
    }

    /** One song, start to finish: decode, trace, sign. Null when the file will not decode. */
    private suspend fun extract(track: DownloadedTrackEntity): MelodySignature? = withContext(Dispatchers.Default) {
        val file = track.fileOrNull() ?: return@withContext null
        val pcmFile = File.createTempFile("melody_pcm_", ".pcm", context.cacheDir)
        val sink = CapturingAudioSink(pcmFile)
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = sink
        }
        val item = MediaItem.Builder()
            .setMediaId("melody:${track.id}")
            .setUri(Uri.fromFile(file))
            .build()
        val player = ExoPlayer.Builder(context, renderers).build()
        try {
            val ended = withTimeoutOrNull(MAX_DECODE_MS) { player.awaitEndOf(item) } == true
            if (!ended || !sink.isConfigured() || sink.framesWritten <= 0) return@withContext null
            val pcm = readLeadingPcm(pcmFile, sink.sampleRate, sink.channelCount) ?: return@withContext null
            if (pcm.size < MIN_ANALYSABLE_SAMPLES) return@withContext null
            val outline = MelodyTracker.outlineOfRecording(pcm, TRACKING)
            if (outline.isEmpty) return@withContext null
            MelodySignature.of(
                outline,
                durationSeconds = pcm.size / PitchTracker.DEFAULT_SAMPLE_RATE.toFloat(),
            )
        } finally {
            runCatching { player.release() }
            runCatching { pcmFile.delete() }
        }
    }

    private suspend fun store(key: String, stamp: Long, signature: MelodySignature?) {
        if (signature == null) {
            signatureDao.put(
                MelodySignatureEntity(
                    trackKey = key,
                    intervals = ByteArray(0),
                    noteFrames = ByteArray(0),
                    frameSeconds = TRACKING.hopSamples / TRACKING.sampleRateHz.toFloat(),
                    durationSeconds = 0f,
                    noteCount = 0,
                    stamp = stamp,
                ),
            )
            return
        }
        signatureDao.put(
            MelodySignatureEntity(
                trackKey = key,
                intervals = MelodySignature.encodeIntervals(signature.outline.intervals),
                noteFrames = MelodySignature.encodeLengths(signature.outline.frameCounts),
                frameSeconds = signature.outline.frameSeconds,
                durationSeconds = signature.durationSeconds,
                noteCount = signature.noteCount,
                stamp = stamp,
            ),
        )
    }

    /**
     * Down to mono at the analysis rate, reading at most the first
     * [MAX_INDEX_SECONDS] of the decoded file. A hummed phrase can start
     * anywhere, but the chorus is rarely past three and a half minutes in, and
     * the tail of an album track is not where anybody sings from.
     */
    private fun readLeadingPcm(file: File, sampleRate: Int, channels: Int): ShortArray? {
        if (sampleRate <= 0 || channels <= 0) return null
        val bytesPerFrame = 2 * channels
        val wantedBytes = (MAX_INDEX_SECONDS.toLong() * sampleRate * bytesPerFrame)
            .coerceAtMost(MAX_INDEX_BYTES)
        val total = minOf(file.length(), wantedBytes).let { it - it % bytesPerFrame }
        if (total < bytesPerFrame) return null
        val bytes = total.toInt()
        return RandomAccessFile(file, "r").use { raf ->
            val raw = ByteArray(bytes)
            raf.readFully(raw, 0, bytes)
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val read = bytes
            val frames = read / bytesPerFrame
            val mono = ShortArray(frames)
            for (f in 0 until frames) {
                var acc = 0
                for (c in 0 until channels) acc += buffer.getShort((f * channels + c) * 2)
                mono[f] = (acc / channels).toShort()
            }
            PitchTracker.toMono16(mono, sampleRate, channels = 1)
        }
    }

    /** Prepare, play silently to the end, resume with whether it finished. */
    private suspend fun ExoPlayer.awaitEndOf(item: MediaItem): Boolean = suspendCancellableCoroutine { cont ->
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && cont.isActive) {
                    removeListener(this)
                    cont.resume(true)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (cont.isActive) {
                    removeListener(this)
                    cont.resume(false)
                }
            }
        }
        addListener(listener)
        cont.invokeOnCancellation { runCatching { removeListener(listener) } }
        volume = 0f
        playWhenReady = true
        setMediaItem(item)
        prepare()
    }

    private fun MelodySignatureEntity.toSignature(): MelodySignature = MelodySignature.of(
        MelodyOutline(
            intervals = MelodySignature.decodeIntervals(intervals),
            frameCounts = MelodySignature.decodeLengths(noteFrames),
            frameSeconds = frameSeconds,
        ),
        durationSeconds = durationSeconds,
    )

    private fun DownloadedTrackEntity.fileOrNull(): File? {
        val path = when {
            filePath.startsWith("/") -> filePath
            mediaStoreUri?.startsWith("file://") == true -> mediaStoreUri.removePrefix("file://")
            else -> null
        }
        return path?.let { File(it) }?.takeIf { it.isFile }
    }

    private fun DownloadedTrackEntity.sizeOnDisk(): Long = fileOrNull()?.length() ?: 0L

    /** Stable identity for a cached tune: the download key the library already uses. */
    private fun DownloadedTrackEntity.matchKey(): String =
        trackKey.takeIf { it.isNotBlank() } ?: "download:$id"

    companion object {
        /** Analysis settings for a full mix. See [MelodyTracker]. */
        val TRACKING = MelodyTracker.Config(
            sampleRateHz = PitchTracker.DEFAULT_SAMPLE_RATE,
            fftSize = 2_048,
            hopSamples = 1_024,
            minHz = 110f,
            maxHz = 1_300f,
        )

        /** Shorter than this there is not enough tune to identify anything. */
        private const val MIN_ANALYSABLE_SAMPLES = 16_000 * 8

        /** Never decode past this point of a track. */
        private const val MAX_INDEX_SECONDS = 210

        /** ...and never hold more than this many bytes of raw PCM in RAM (48 kHz stereo, ~8 min). */
        private const val MAX_INDEX_BYTES = 40L * 1024 * 1024

        /** A stuck decoder must not hang the library pass. */
        private const val MAX_DECODE_MS = 4 * 60 * 1000L
    }
}
