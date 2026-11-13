package com.lastwave.app.playback.melody

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What is kept about a song: the tune, and nothing else. Two byte arrays, a
 * handful of kilobytes at most per track, from which everything the matcher
 * needs is derived again on load.
 */
internal class MelodySignature(
    val outline: MelodyOutline,
    /** Twelve octave-folded pitch classes, normalised to sum 1, for pruning. */
    val pitchClass: FloatArray,
    val durationSeconds: Float,
) {
    val noteCount: Int get() = outline.noteCount

    companion object {

        fun of(outline: MelodyOutline, durationSeconds: Float): MelodySignature {
            val pitches = ArrayList<Float>(outline.noteCount)
            var current = Float.NaN
            for (i in outline.intervals.indices) {
                current = if (i == 0) outline.intervals[0].toFloat() else current + outline.intervals[i]
                pitches.add(current)
            }
            val hist = FloatArray(12)
            for (p in pitches) hist[floorMod(p.roundToInt(), 12)] += 1f
            val total = hist.sum()
            if (total > 0f) for (i in 0 until 12) hist[i] /= total
            return MelodySignature(outline, hist, durationSeconds)
        }

        /**
         * Bytes of a song's tune. Steps between notes fit in a byte with room
         * to spare; a longer note is clamped, since its exact length only
         * matters for jumping to the right moment in the player.
         */
        fun encodeIntervals(intervals: IntArray): ByteArray = ByteArray(intervals.size) { i ->
            intervals[i].coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt()).toByte()
        }

        fun encodeLengths(lengths: List<Int>): ByteArray = ByteArray(lengths.size) { i ->
            lengths[i].coerceIn(1, 250).toByte()
        }

        fun decodeIntervals(bytes: ByteArray): IntArray = IntArray(bytes.size) { bytes[it].toInt() }

        fun decodeLengths(bytes: ByteArray): List<Int> = List(bytes.size) { bytes[it].toInt() and 0xFF }

        private fun floorMod(a: Int, b: Int): Int = ((a % b) + b) % b
    }
}

/** A candidate, with the moment in the song that was matched. */
internal data class MelodyMatch(
    val key: String,
    /** 0..1: how much the sung tune looked like this song's. */
    val score: Float,
    /** Seconds into the track the matched phrase starts. */
    val startSeconds: Float,
    /** Notes of the sung phrase that found a partner. */
    val matchedNotes: Int,
    val totalNotes: Int,
)

/**
 * Sung phrase in, ranked songs out.
 *
 * The sung notes and each song's notes are compared as *interval sequences*
 * with a semi-global alignment: the phrase may start anywhere in the song and
 * end anywhere, missing notes cost a fixed amount, and a step that is off by a
 * semitone is nearly free while a step that is off by five is not. That is the
 * shape of the problem: a person hums eight notes out of a three minute
 * recording, in their own key, at their own tempo.
 *
 * A library is much bigger than a phrase, so songs are first pruned by how
 * their pitch classes are distributed — cheap, and it removes everything in a
 * distant key before any alignment runs.
 */
internal object MelodyMatcher {

    /** Penalty for a sung note with no partner. */
    private const val GAP = 2.2f

    /** Penalty per sung note that nothing matched, relative to [GAP]. */
    private const val SKIP_SONG_NOTE = 0.35f

    /** A step this far wrong is as bad as any other step that wrong. */
    private const val STEP_CAP = 5f

    /** Softness of the exponential that turns a cost into a score. */
    private const val COST_SCALE = 2.4f

    /** How many candidates survive the cheap pass. */
    const val SHORTLIST = 120

    /**
     * Only a strong alignment is worth playing without asking. Calibrated on
     * the library tests: a phrase that comes out note for note scores 1.00, a
     * tune the library does not have tops out near 0.89 while leaving notes
     * unmatched, and a distant one lands under 0.5. A lower score still shows
     * the candidate list, it just does not start playback on its own.
     */
    const val CONFIDENT_AT = 0.86f

    /** And the winner must beat the runner-up by this much. */
    const val MARGIN_AT = 0.06f

    fun pitchClassScore(hum: MelodySignature, song: MelodySignature): Float {
        var dot = 0f
        var a = 0f
        var b = 0f
        for (i in 0 until 12) {
            dot += hum.pitchClass[i] * song.pitchClass[i]
            a += hum.pitchClass[i] * hum.pitchClass[i]
            b += song.pitchClass[i] * song.pitchClass[i]
        }
        if (a <= 0f || b <= 0f) return 0f
        return (dot / (sqrt(a) * sqrt(b))).coerceIn(0f, 1f)
    }

    /**
     * Align [hum] against [song]. Returns null when there is nothing to
     * compare, which is the normal answer for a song with no detectable tune.
     */
    fun align(hum: MelodySignature, song: MelodySignature, key: String = ""): MelodyMatch? {
        val a = hum.outline.intervals
        val b = song.outline.intervals
        // The starting note itself is free to differ: that is the key.
        val la = a.size
        val lb = b.size
        if (la < MelodyOutline.MIN_NOTES || lb < la / 2) return null

        val rows = la + 1
        val columns = lb + 1
        val cost = FloatArray(columns)
        val previous = FloatArray(columns)
        // Directions: 1 diagonal, 2 skipped a sung note, 3 skipped a song note.
        val from = ByteArray(rows * columns)

        // Row 0: the phrase may start at any note of the song for free.
        for (j in 0 until columns) cost[j] = 0f
        for (i in 1..la) {
            System.arraycopy(cost, 0, previous, 0, columns)
            cost[0] = previous[0] + GAP
            from[i * columns] = 2
            val aStep = a[i - 1]
            for (j in 1 until columns) {
                val distance = min(abs(aStep - b[j - 1]), STEP_CAP.toInt())
                val diagonal = previous[j - 1] + distance
                val up = previous[j] + GAP
                val left = cost[j - 1] + GAP * SKIP_SONG_NOTE
                var best = diagonal
                var dir = 1
                if (up < best) {
                    best = up
                    dir = 2
                }
                if (left < best) {
                    best = left
                    dir = 3
                }
                cost[j] = best
                from[i * columns + j] = dir.toByte()
            }
        }

        var endColumn = 0
        var bestLast = Float.MAX_VALUE
        for (j in 1 until columns) {
            if (cost[j] < bestLast) {
                bestLast = cost[j]
                endColumn = j
            }
        }
        if (bestLast == Float.MAX_VALUE) return null

        // Walk back for the first song note that took part, and for how many of
        // the sung notes found a partner.
        var i = la
        var j = endColumn
        var matched = 0
        var startColumn = endColumn
        var guard = rows * columns
        while (i > 0 && j > 0 && guard-- > 0) {
            when (from[i * columns + j].toInt()) {
                1 -> {
                    matched++
                    startColumn = j
                    i--
                    j--
                }
                2 -> {
                    startColumn = j
                    i--
                }
                else -> j--
            }
        }

        // The score is the alignment cost per sung note, squashed through an
        // exponential. Deliberately nothing else: multiplying by how much of the
        // phrase was covered sounds sensible and destroys the ranking, because
        // a partial cover is exactly what a short hum of a long song looks like.
        val perNote = max(cost[endColumn] / la, 0f)
        val score = exp(-perNote / COST_SCALE).coerceIn(0f, 1f)
        val startSeconds = if (startColumn > 0) {
            song.outline.startSecondsOf(startColumn - 1).coerceAtLeast(0f)
        } else {
            0f
        }
        return MelodyMatch(
            key = key,
            score = score,
            startSeconds = startSeconds,
            matchedNotes = matched,
            totalNotes = la,
        )
    }

    /** Rank a library, best first. */
    fun rank(
        hum: MelodySignature,
        songs: List<Pair<String, MelodySignature>>,
        limit: Int = 6,
    ): List<MelodyMatch> {
        if (songs.isEmpty()) return emptyList()
        val pruned = songs
            .map { it to pitchClassScore(hum, it.second) }
            .sortedByDescending { it.second }
            .take(min(SHORTLIST, songs.size))
            .map { it.first }
        val matches = ArrayList<MelodyMatch>(pruned.size)
        for (song in pruned) {
            align(hum, song.second, song.first)?.let { matches += it }
        }
        return matches.sortedByDescending { it.score }.take(limit)
    }

    /** Whole-library ranking when nothing survived pruning. */
    fun rankAll(hum: MelodySignature, songs: List<Pair<String, MelodySignature>>, limit: Int = 6): List<MelodyMatch> {
        val matches = ArrayList<MelodyMatch>(songs.size)
        for ((key, signature) in songs) align(hum, signature, key)?.let { matches += it }
        return matches.sortedByDescending { it.score }.take(limit)
    }

    fun percent(score: Float): Int = (score * 100f).roundToInt().coerceIn(0, 99)

    /**
     * Sure enough to start playing. Needs all three: a near-exact alignment,
     * every sung note accounted for, and a winner clearly ahead of the runner
     * up. The middle condition is what keeps a tune the library only resembles
     * from being announced as a hit.
     */
    fun isConfident(ranked: List<MelodyMatch>): Boolean = verdict(ranked) == Verdict.SURE

    /** A name for the UI: how sure the app is, in words a person can act on. */
    fun verdict(ranked: List<MelodyMatch>): Verdict {
        if (ranked.isEmpty()) return Verdict.NOTHING
        val best = ranked[0]
        val second = ranked.getOrNull(1)?.score ?: 0f
        val ahead = best.score - second >= MARGIN_AT
        return when {
            best.score >= CONFIDENT_AT && best.matchedNotes == best.totalNotes && ahead -> Verdict.SURE
            best.score >= CONFIDENT_AT && best.matchedNotes == best.totalNotes -> Verdict.CLOSE
            else -> Verdict.UNCERTAIN
        }
    }

    enum class Verdict {
        /** Play it; the phrase lined up note for note and nothing else came close. */
        SURE,

        /** Play it if asked, but say it is one of several. */
        CLOSE,

        /** Show the list, let the person pick. */
        UNCERTAIN,

        /** Nothing was indexed, or the singing was not audible. */
        NOTHING,
    }
}
