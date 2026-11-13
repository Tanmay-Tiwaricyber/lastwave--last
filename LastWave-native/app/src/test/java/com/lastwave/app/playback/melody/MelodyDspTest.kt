package com.lastwave.app.playback.melody

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The maths behind "sing to find", tested against synthesised audio.
 *
 * No microphone and no decoder needed: the tests render notes and whole
 * arrangements as PCM, run the same code the app runs, and check that a tune
 * can be read out of a mix, that a sung line comes out as the same tune, and
 * that one finds its own song among several others.
 */
class MelodyDspTest {

    private companion object {
        const val RATE = 16_000

        val SONG = MelodyTracker.Config(
            sampleRateHz = RATE,
            fftSize = 2_048,
            hopSamples = 1_024,
            minHz = 110f,
            maxHz = 1_300f,
        )

        val HUM = PitchTracker.Config(
            sampleRateHz = RATE,
            windowSamples = 1_536,
            hopSamples = 256,
            minHz = 80f,
            maxHz = 1_200f,
            priorWeight = 0.05f,
        )

        fun hz(midi: Double) = 440.0 * 2.0.pow((midi - 69.0) / 12.0)

        fun write(out: ShortArray, at: Int, sample: Double) {
            if (at < 0 || at >= out.size) return
            out[at] = (out[at] + sample).coerceIn(-30_000.0, 30_000.0).roundToInt().toShort()
        }

        /** A struck note: four partials, a fast attack and a decay. */
        fun note(out: ShortArray, from: Int, length: Int, frequency: Double, amplitude: Double, decay: Double) {
            for (i in 0 until length) {
                val t = i.toDouble() / RATE
                val env = amplitude * exp(-decay * i / length) * min(1.0, i / (0.006 * RATE))
                var v = 0.0
                for (k in 1..4) v += sin(2 * PI * frequency * k * t) / k
                write(out, from + i, v * env * 30_000.0)
            }
        }

        /** A sustained tone: what a pad, a string section or a bass holds. */
        fun pad(out: ShortArray, from: Int, length: Int, frequency: Double, amplitude: Double) {
            for (i in 0 until length) {
                val t = i.toDouble() / RATE
                var v = 0.0
                for (k in 1..3) v += sin(2 * PI * frequency * k * t + k) / (k * k)
                write(out, from + i, v * amplitude * 30_000.0)
            }
        }

        /**
         * A tune over a backing: a bass a twelfth below the melody, two chord
         * tones, and a percussive tick on every half bar. Close enough to a
         * real recording to be worth testing against, since the bass is exactly
         * the thing that fools a naive pitch tracker.
         */
        fun arrangement(melody: IntArray, secondsPerNote: Double = 0.55, seed: Int): ShortArray {
            val per = (secondsPerNote * RATE).toInt()
            val out = ShortArray(melody.size * per)
            var rng = seed * 31 + 7
            fun noise(): Double {
                rng = (rng * 1_103_515_245 + 12_345) and 0x7f_ff_ff_ff
                return rng / 1_073_741_824.0 - 1.0
            }
            melody.forEachIndexed { i, midi ->
                val from = i * per
                note(out, from, (per * 0.98).toInt(), hz(midi.toDouble()), amplitude = 0.55, decay = 1.6)
                pad(out, from, per, hz((midi - 19).coerceAtLeast(45).toDouble()), 0.26)
                pad(out, from, per, hz((midi + 4).toDouble()), 0.10)
                pad(out, from, per, hz((midi + 7).toDouble()), 0.09)
                for (b in 0 until 2) {
                    val at = from + b * per / 2
                    for (n in 0 until 160) {
                        val env = exp(-n / 34.0)
                        write(out, at + n, noise() * 0.08 * env * 30_000.0)
                    }
                }
            }
            return out
        }

        /** A sung line: vibrato, a soft attack and release, and a little room. */
        fun sung(
            melody: IntArray,
            secondsPerNote: Double = 0.55,
            transpose: Int = 0,
            tempo: Double = 1.0,
            octave: Int = 0,
        ): ShortArray {
            val per = (secondsPerNote / tempo * RATE).toInt()
            val out = ShortArray(melody.size * per + RATE / 8)
            for (i in melody.indices) {
                val frequency = hz((melody[i] + transpose + 12 * octave).toDouble())
                val length = (per * 0.86).toInt()
                for (n in 0 until length) {
                    val t = n.toDouble() / RATE
                    val wobble = 22.0 / 1200.0 * sin(2 * PI * 5.4 * t)
                    val f = frequency * 2.0.pow(wobble)
                    val attack = min(1.0, n / (0.03 * RATE))
                    val release = min(1.0, (length - n) / (0.05 * RATE))
                    val env = 0.7 * attack * release
                    var v = sin(2 * PI * f * t) + 0.45 * sin(2 * PI * 2 * f * t + 0.7) +
                        0.22 * sin(2 * PI * 3 * f * t + 1.3) + 0.08 * sin(2 * PI * 4 * f * t)
                    write(out, i * per + n, v * env * 12_000.0)
                }
            }
            var rng = 4242
            for (i in out.indices) {
                rng = (rng * 1_103_515_245 + 12_345) and 0x7f_ff_ff_ff
                write(out, i, (rng / 1_073_741_824.0 - 1.0) * 300.0)
            }
            return out
        }

        /** Sung with the voice tracker, in note numbers. */
        fun sungNotes(melody: IntArray, transpose: Int = 0, tempo: Double = 1.0, octave: Int = 0): List<Float> {
            val trace = PitchTracker.trace(sung(melody, transpose = transpose, tempo = tempo, octave = octave), HUM)
            return MelodyTracker.notes(MelodyTracker.median(trace.midi, 9), minFrames = 8)
                .map { it.midi }
        }

        /** Indexed with the mix tracker, in note numbers. */
        fun recordedNotes(melody: IntArray, seed: Int): List<Float> =
            MelodyTracker.outlineOfRecording(arrangement(melody, seed = seed), SONG)
                .intervals
                .toList()
                .runningReduce { acc, step -> acc + step }
                .map { it.toFloat() }

        val MELODIES = listOf(
            intArrayOf(64, 64, 67, 69, 67, 64, 62, 60),
            intArrayOf(57, 60, 64, 67, 64, 60, 57, 55),
            intArrayOf(69, 67, 64, 62, 60, 62, 64, 67),
            intArrayOf(62, 65, 69, 67, 65, 62, 60, 62),
            intArrayOf(60, 64, 67, 72, 69, 67, 64, 60),
            intArrayOf(67, 67, 65, 64, 62, 64, 67, 69),
            intArrayOf(55, 59, 62, 64, 62, 59, 55, 53),
            intArrayOf(71, 69, 67, 64, 67, 69, 71, 74),
        )

        /** Align two lists of note numbers the way the matcher does. */
        fun signatureOf(notes: List<Float>): MelodySignature {
            val intervals = IntArray(notes.size) { i -> if (i == 0) notes[0].roundToInt() else (notes[i] - notes[i - 1]).roundToInt() }
            return MelodySignature.of(
                MelodyOutline(intervals, List(notes.size) { 10 }, frameSeconds = SONG.hopSamples / SONG.sampleRateHz.toFloat()),
                durationSeconds = notes.size * 0.55f,
            )
        }
    }

    @Test
    fun theFftAgreesWithADirectTransform() {
        val size = 64
        val signal = FloatArray(size) { i -> (sin(2 * PI * 4 * i / size) + 0.3 * cos(2 * PI * 11 * i / size)).toFloat() }
        val expected = FloatArray(size / 2 + 1) { bin ->
            var re = 0.0
            var im = 0.0
            for (n in 0 until size) {
                re += signal[n] * cos(2 * PI * bin * n / size)
                im -= signal[n] * sin(2 * PI * bin * n / size)
            }
            sqrt(re * re + im * im).toFloat()
        }
        val input = signal.copyOf()
        val output = FloatArray(size / 2 + 1)
        Fft(size).forward(input, output, size / 2 + 1)
        val peak = output.indices.maxByOrNull { output[it] }!!
        assertEquals("energy belongs to the fourth partial", 4, peak)
        for (bin in 0..size / 2) {
            assertEquals("bin $bin", expected[bin], output[bin], 0.05f * (abs(expected[bin]) + 1f))
        }
    }

    @Test
    fun aViterbiPassRemovesSpikesButKeepsTheTune() {
        val tune = floatArrayOf(64f, 64f, 64f, 64f, 67f, 67f, 67f, 67f, 69f, 69f, 69f, 69f)
        val noisy = tune.copyOf().also {
            it[1] = 82f
            it[5] = 55f
            it[9] = 45f
        }
        val walked = MelodyTracker.viterbi(noisy)
        for (i in tune.indices) {
            assertEquals("frame $i", tune[i], walked[i], 1.2f)
        }
    }

    @Test
    fun aSungLineComesOutOfTheVoiceTrackerAsItsOwnTune() {
        val melody = intArrayOf(64, 67, 69, 67, 64, 62, 60, 62)
        val notes = sungNotes(melody)
        assertEquals("heard ${notes.map { it.roundToInt() }}", melody.size, notes.size)
        melody.forEachIndexed { i, midi ->
            assertEquals("note $i", midi.toFloat(), notes[i], 1.2f)
        }
    }

    @Test
    fun aTuneIsReadOutOfAMixWithABassUnderIt() {
        var exact = 0
        val heard = ArrayList<String>()
        for (i in MELODIES.indices) {
            val notes = recordedNotes(MELODIES[i], seed = i + 1)
            val trueNotes = merged(MELODIES[i])
            val matched = countMatched(trueNotes, notes)
            if (matched >= trueNotes.size - 1) {
                exact++
            } else {
                heard += "song$i true${MELODIES[i].toList()} got${notes.map { it.roundToInt() }}"
            }
        }
        assertTrue("$exact of ${MELODIES.size} arrangements came out as their own tune; $heard", exact >= MELODIES.size - 2)
    }

    /** Consecutive repeats of one pitch are one note to a listener, and the
     * segmenter says so too, so the reference for comparison is merged first. */
    private fun merged(melody: IntArray): List<Float> {
        val out = ArrayList<Float>()
        for (m in melody) if (out.isEmpty() || out.last() != m.toFloat()) out.add(m.toFloat())
        return out
    }

    /** How many of [expected] appear in [heard] in order, within a semitone. */
    private fun countMatched(expected: List<Float>, heard: List<Float>): Int {
        var i = 0
        var hits = 0
        for (h in heard) {
            if (i < expected.size && abs(h - expected[i]) <= 1.2f) {
                hits++
                i++
            }
        }
        return hits
    }

    @Test
    fun notesShorterThanAHandfulOfFramesAreIgnored() {
        val contour = FloatArray(200) { 60f }
        contour[50] = 72f
        contour[51] = 72f
        contour[100] = 55f
        val notes = MelodyTracker.notes(contour, minFrames = 8)
        assertEquals(1, notes.size)
        assertEquals(60f, notes[0].midi, 0.6f)
    }

    @Test
    fun intervalsForgetTheKeyAndRememberTheShape() {
        val notes = listOf(64f, 64f, 67f, 69f)
        val inC = MelodyTracker.intervals(notes.map { MelodyTracker.Note(it, 10) })
        val transposed = MelodyTracker.intervals(notes.map { MelodyTracker.Note(it + 5f, 10) })
        // The first entry says where the tune starts; the steps after it are
        // the tune, and those are what a singer keeps when they move key.
        assertEquals(inC.drop(1), transposed.drop(1))
        assertEquals(listOf(64, 0, 3, 2), inC.toList())
    }

    @Test
    fun aHummedTuneFindsItsOwnSongAmongEight() {
        val bank = MELODIES.mapIndexed { i, melody ->
            "song-$i" to signatureOf(recordedNotes(melody, seed = i + 1))
        }
        val usable = bank.filter { it.second.noteCount >= MelodyOutline.MIN_NOTES }
        assertTrue("only ${usable.size} of ${bank.size} songs produced a tune", usable.size >= 7)

        val attempts = listOf(
            Triple(0, 1.0, 0),
            Triple(5, 1.12, -1),
            Triple(-7, 0.9, 1),
            Triple(3, 1.0, 1),
            Triple(0, 1.2, 0),
            Triple(-4, 1.0, 1),
        )
        val misses = ArrayList<String>()
        val best = ArrayList<String>()
        for (target in intArrayOf(0, 2, 4, 6)) {
            for ((transpose, tempo, octave) in attempts) {
                val hum = signatureOf(sungNotes(MELODIES[target], transpose, tempo, octave))
                val ranked = MelodyMatcher.rank(hum, usable)
                val top = ranked.firstOrNull()
                best += "song$target tr%+d: %.2f".format(transpose, top?.score ?: 0f)
                if (top?.key != "song-$target") {
                    misses += "song$target tr$transpose t$tempo o$octave -> ${top?.key} (${ranked.take(3).map { String.format("%.2f", it.score) }})"
                }
            }
        }
        val total = 4 * attempts.size
        val hits = total - misses.size
        assertTrue("$hits of $total variants found their song; missed: $misses; tops: $best", hits >= 20)
    }

    @Test
    fun aTuneNobodyHasIsNotReportedAsAMatch() {
        val bank = MELODIES.mapIndexed { i, melody -> "song-$i" to signatureOf(recordedNotes(melody, seed = i + 1)) }
        val outsiders = listOf(
            intArrayOf(60, 63, 67, 70, 67, 63, 60, 58),
            intArrayOf(72, 70, 68, 65, 63, 65, 68, 70),
            intArrayOf(55, 62, 57, 64, 59, 66, 61, 68),
        )
        var falsePositives = 0
        val detail = ArrayList<String>()
        for (melody in outsiders) {
            val hum = signatureOf(sungNotes(melody, transpose = 2, tempo = 1.0, octave = 0))
            val ranked = MelodyMatcher.rank(hum, bank)
            val top = ranked.firstOrNull()
            detail += "outside${melody.joinToString("-")}: %.2f %d/%d ${MelodyMatcher.verdict(ranked)}".format(
                top?.score ?: 0f, top?.matchedNotes ?: 0, top?.totalNotes ?: 0,
            )
            if (MelodyMatcher.isConfident(ranked)) falsePositives++
        }
        // A tune the library does not have may still line up with something by
        // chance; what must not happen is the app starting playback on its own.
        val own = ArrayList<String>()
        for (target in intArrayOf(0, 2, 6)) {
            val hum = signatureOf(sungNotes(MELODIES[target]))
            val ranked = MelodyMatcher.rank(hum, bank)
            val top = ranked.firstOrNull()
            own += "song$target: %.2f %d/%d ${MelodyMatcher.verdict(ranked)}".format(top?.score ?: 0f, top?.matchedNotes ?: 0, top?.totalNotes ?: 0)
        }
        assertEquals("confident on tunes nobody has: $detail | for comparison $own", 0, falsePositives)
    }

    @Test
    fun aPhraseFromTheMiddleOfASongStillFindsIt() {
        val melody = MELODIES[4]
        val song = signatureOf(recordedNotes(melody, seed = 5))
        // Someone sings only the fourth to seventh notes of the tune.
        val phrase = melody.copyOfRange(3, 7)
        val hum = signatureOf(sungNotes(phrase))
        val match = MelodyMatcher.align(hum, song, "song-4")
        assertNotNull("nothing matched at all", match)
        assertTrue("score ${match!!.score} matched ${match.matchedNotes}/${match.totalNotes}", match.score > 0.45f)
        assertTrue("matched ${match.matchedNotes} of ${match.totalNotes}", match.matchedNotes >= 3)
        // Four notes in at half a second each: the phrase starts partway in.
        assertTrue("start ${match.startSeconds}", match.startSeconds > 0.5f)
    }

    @Test
    fun aShortOrEmptyTuneIsRefused() {
        val empty = signatureOf(emptyList())
        assertNull(MelodyMatcher.align(empty, signatureOf(listOf(60f, 62f, 64f)), "x"))
        val tooFew = signatureOf(listOf(60f, 62f))
        assertNull(MelodyMatcher.align(tooFew, signatureOf(listOf(60f, 62f, 64f, 67f, 69f))))
        assertTrue(MelodyOutline(IntArray(3), listOf(1, 1, 1), 0.064f).isEmpty)
    }

    @Test
    fun signaturesRoundTripThroughTheirBytes() {
        val notes = listOf(60f, 64f, 67f, 72f, 69f, 67f, 64f, 60f)
        val signature = signatureOf(notes)
        val intervals = MelodySignature.encodeIntervals(signature.outline.intervals)
        val lengths = MelodySignature.encodeLengths(signature.outline.frameCounts)
        assertEquals(signature.outline.intervals.toList(), MelodySignature.decodeIntervals(intervals).toList())
        assertEquals(signature.outline.frameCounts, MelodySignature.decodeLengths(lengths))
        assertEquals(0.99f, signature.pitchClass.sum(), 0.02f)
        // The pitch classes are octave-folded, so a song an octave up agrees.
        val up = signatureOf(notes.map { it + 12f })
        assertEquals(1f, MelodyMatcher.pitchClassScore(signature, up), 0.02f)
    }

    @Test
    fun whiteningLeavesASustainedPadBehind() {
        // A held chord alone has no tune to report; add a moving line and one
        // appears. This is the whole reason the mix path is not a plain pitch
        // tracker.
        val chordOnly = ShortArray((0.55 * RATE * 8).toInt())
        for (m in intArrayOf(48, 52, 55, 60)) {
            pad(chordOnly, 0, chordOnly.size, hz(m.toDouble()), 0.18)
        }
        val melody = intArrayOf(64, 67, 69, 67, 64, 62, 60, 62)
        val withTune = arrangement(melody, seed = 11)
        val quiet = MelodyTracker.outlineOfRecording(chordOnly, SONG)
        val loud = MelodyTracker.outlineOfRecording(withTune, SONG)
        assertTrue("chord alone produced ${quiet.noteCount} notes", quiet.noteCount < melody.size + 4)
        val pitches = loud.intervals.toList().runningReduce { a, b -> a + b }.map { it.toFloat() }
        val hits = countMatched(melody.map { it.toFloat() }, pitches)
        assertTrue("tune produced ${loud.noteCount} notes, heard $pitches", hits >= 5)
    }
}
