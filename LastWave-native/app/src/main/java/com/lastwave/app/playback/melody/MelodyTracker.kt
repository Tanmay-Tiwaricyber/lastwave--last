package com.lastwave.app.playback.melody

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turning audio into a tune.
 *
 * Two estimators, because the two inputs are not the same kind of signal. A
 * sung line into a microphone is close to monophonic, so an autocorrelation
 * tracker ([PitchTracker]) is both more accurate and cheaper there. The
 * recording being indexed is a full mix, where autocorrelation is perfectly
 * happy to report the bass, so there the tune is found by summing spectral
 * peaks along harmonic stacks after subtracting the local noise floor, and the
 * result is then forced to move like a melody.
 *
 * Both end up as a semitone contour, which is median filtered, walked back onto
 * the set of pitches a melody can actually visit, and cut into notes. Notes,
 * not frames, are what gets compared: a singer's slides and the odd misread
 * frame disappear inside a note, and the sequence of steps between notes is the
 * one thing that survives being sung in somebody else's key.
 */
internal object MelodyTracker {

    /** Parameters of one analysis pass. */
    data class Config(
        val sampleRateHz: Int = 16_000,
        val fftSize: Int = 2_048,
        val hopSamples: Int = 1_024,
        val minHz: Float = 110f,
        val maxHz: Float = 1_300f,
        /** Log-normal preference for the range a sung melody occupies. */
        val priorCenterHz: Float = 320f,
        val priorWidth: Float = 1.35f,
        /** Subtract a local spectral floor first: stops pads competing. */
        val whiten: Boolean = true,
        /**
         * Frames on either side used for the temporal floor: what a bin holds
         * through that many frames is furniture, not tune. At 10 frames this is
         * about 1.3 s, longer than a note usually is and shorter than a chord
         * usually holds.
         */
        val temporalFloorFrames: Int = 10,
        /** Bins on that stride get their own floor; the rest are interpolated. */
        val binStride: Int = 4,
        /** An octave below only wins if it is this much stronger. */
        val subharmonicGain: Float = 1.55f,
        val minFrameRms: Float = 0.0035f,
        val minClarity: Float = 0.30f,
        /** Candidate grid step, in cents. */
        val gridCents: Float = 2f,
    )

    /**
     * Per-frame pitch of the dominant harmonic stack in MIDI numbers, `NaN`
     * where nothing pitched was found.
     *
     * The candidate is a harmonic stack, not a bin: a melody's fundamental is
     * often weaker than its second partial, and in a mix the two sit under
     * instruments that share neither. Summing the stack after subtracting a
     * local spectral floor is what makes a tune visible. The floor is a running
     * median across bins, which is the part that costs real time, so it is
     * sampled every fourth bin.
     */
    fun contour(pcm: ShortArray, config: Config, from: Int = 0, to: Int = pcm.size): FloatArray {
        val end = min(to, pcm.size)
        val frames = max(0, (end - from - config.fftSize) / config.hopSamples + 1)
        if (frames == 0) return FloatArray(0)
        val out = FloatArray(frames) { Float.NaN }

        val bins = config.fftSize / 2 + 1
        val binHz = config.sampleRateHz.toFloat() / config.fftSize
        val loBin = max(2, (config.minHz / binHz).roundToInt())
        // Candidates every `gridCents` from the bottom of the range up: with a
        // uniform log grid, halving a frequency is a fixed number of steps, so
        // the octave check below costs nothing.
        val firstCent = floor(ln(config.minHz / 20f) / LN2 * 1200f)
        val lastCent = ln(config.maxHz / 20f) / LN2 * 1200f
        val states = ((lastCent - firstCent) / config.gridCents).toInt()
        if (states < 8) return out
        val octaveSteps = (1200f / config.gridCents).roundToInt()

        val f0 = FloatArray(states) { i -> 20f * 2f.pow((firstCent + i * config.gridCents) / 1200f) }
        val prior = FloatArray(states) { i ->
            val r = ln(f0[i] / config.priorCenterHz)
            exp(-(r * r) / (2f * config.priorWidth * config.priorWidth)).coerceIn(0.30f, 1f)
        }
        // An octave-below candidate collects more harmonics inside the band and
        // would win on count alone, so each is divided by how many it can use.
        val harmonicCount = FloatArray(states) { i ->
            var k = 1
            var n = 0
            while (k <= HARMONICS && k * f0[i] < TOP_HARMONIC_HZ) {
                n++
                k++
            }
            max(1, n).toFloat()
        }
        val weight = FloatArray(HARMONICS) { k -> 1f / (k + 1).toFloat().pow(0.85f) }
        val binOf = Array(HARMONICS) { k ->
            IntArray(states) { i -> (f0[i] * (k + 1) / binHz).toInt().coerceIn(0, bins - 2) }
        }

        val fft = Fft(config.fftSize)
        val window = HannTable.of(config.fftSize)
        val time = FloatArray(config.fftSize)
        val salience = FloatArray(states)
        val typical = FloatArray((states + MEDIAN_STRIDE - 1) / MEDIAN_STRIDE)

        // Transforms are done in blocks, with a halo of frames on either side,
        // because the temporal floor has to see across a note boundary.
        val halo = max(0, config.temporalFloorFrames)
        var first = 0
        while (first < frames) {
            val last = min(first + CHUNK_FRAMES, frames)
            val low = max(0, first - halo)
            val high = min(frames, last + halo)
            val count = high - low
            val mags = FloatArray(count * bins)
            for (f in 0 until count) {
                val base = from + (low + f) * config.hopSamples
                var sum = 0f
                var energy = 0f
                for (i in 0 until config.fftSize) {
                    val v = if (base + i < end) pcm[base + i] * (1f / 32768f) else 0f
                    time[i] = v
                    sum += v
                    energy += v * v
                }
                if (sqrt(energy / config.fftSize) < config.minFrameRms) continue
                val mean = sum / config.fftSize
                for (i in 0 until config.fftSize) time[i] = (time[i] - mean) * window.at(i)
                fft.forward(time, mags, f * bins, bins)
            }
            if (halo > 0) subtractTemporalFloor(mags, count, bins, halo, config.binStride)
            for (f in first until last) {
                val at = (f - low) * bins
                if (config.whiten) localFloor(mags, at, bins, config.binStride)
                var bandEnergy = 0f
                for (i in MIN_NORM_BIN until bins) bandEnergy += mags[at + i] * mags[at + i]
                val norm = sqrt(bandEnergy) + 1e-6f
                var bestScore = -1f
                var bestState = -1
                for (g in 0 until states) {
                    var acc = 0f
                    for (k in 0 until HARMONICS) {
                        val bin = binOf[k][g]
                        if (bin < loBin) continue
                        acc += weight[k] * max(mags[at + bin], mags[at + bin + 1])
                    }
                    val value = acc / (harmonicCount[g] * norm)
                    salience[g] = value
                    val score = value * prior[g]
                    if (score > bestScore) {
                        bestScore = score
                        bestState = g
                    }
                }
                if (bestState <= 0) continue

                // YIN's octave rule, inverted: only give up the octave when the
                // candidate an octave down is decisively stronger. A listener
                // hums the higher line, and an octave slip costs the matcher
                // nothing, so the tie goes to the voice.
                var chosen = bestState
                while (chosen > octaveSteps) {
                    val lower = chosen - octaveSteps
                    if (salience[lower] > salience[chosen] * config.subharmonicGain) chosen = lower else break
                }

                var n = 0
                for (g in 0 until states step MEDIAN_STRIDE) if (salience[g] > 0f) typical[n++] = salience[g]
                val clarity = if (n == 0) 0f else min(1f, salience[chosen] / (medianOf(typical, n) * 2.6f + 1e-9f))
                if (clarity < config.minClarity) continue
                out[f] = 69f + 12f * log2(f0[chosen] / 440f)
            }
            first = last
        }
        return out
    }

    /**
     * Per bin, subtract the median of that bin across neighbouring frames. A
     * bass note and a held pad sit in the same bin for a whole bar, so this
     * removes them; a melody moves note to note, so it survives. This is the
     * difference between tracking the tune of a mix and tracking its lowest
     * instrument. Done on every [stride]-th bin and stretched over the bins
     * between, which costs the same as the transform and is close enough.
     */
    private fun subtractTemporalFloor(mags: FloatArray, frames: Int, bins: Int, span: Int, stride: Int) {
        if (frames < 3) return
        val window = FloatArray(span * 2 + 1)
        val step = max(1, (span * 2) / window.size + 1)
        var bin = 0
        while (bin < bins) {
            val next = min(bin + stride, bins)
            for (f in 0 until frames) {
                val from = max(0, f - span)
                val upto = min(frames - 1, f + span)
                var m = 0
                var i = from
                while (i <= upto && m < window.size) {
                    var high = mags[i * bins + bin]
                    for (b in bin + 1 until next) if (mags[i * bins + b] > high) high = mags[i * bins + b]
                    window[m++] = high
                    i += step
                }
                if (m < 2) continue
                val floor = medianOf(window, m)
                val at = f * bins
                for (b in bin until next) {
                    val v = mags[at + b] - floor
                    mags[at + b] = if (v > 0f) v else 0f
                }
            }
            bin = next
        }
    }

    /**
     * Per-bin floor across frequency: the energy a peak does not have in common
     * with its neighbours. Sharp melodic peaks stand out from smeared chords.
     */
    private fun localFloor(mags: FloatArray, at: Int, bins: Int, stride: Int) {
        val span = WHITEN_SPAN
        val window = FloatArray((span / max(1, stride)) * 2 + 8)
        var bin = 0
        while (bin < bins) {
            val next = min(bin + stride, bins)
            val from = max(0, bin - span)
            val upto = min(bins - 1, bin + span)
            var m = 0
            var j = from
            while (j <= upto && m < window.size) {
                if (j == 0 || j == bin || j == upto || j % stride == 0) window[m++] = mags[at + j]
                j += stride
            }
            if (m == 0) window[m++] = mags[at + bin]
            val floor = medianOf(window, m)
            for (b in bin until next) {
                val v = mags[at + b] - floor
                mags[at + b] = if (v > 0f) v else 0f
            }
            bin = next
        }
    }

    private const val HARMONICS = 6
    private const val CHUNK_FRAMES = 512
    private const val TOP_HARMONIC_HZ = 3_800f
    private const val MIN_NORM_BIN = 5
    private const val LN2 = 0.6931472f
    private const val MEDIAN_STRIDE = 4
    private const val WHITEN_SPAN = 26

    private fun log2(v: Float): Float = ln(v) / LN2


    /**
     * Walk a noisy contour back onto the pitches it can actually visit: a
     * Viterbi pass whose transition cost grows with the size of a jump and
     * which rewards staying put. This is what removes the octave flicker and
     * the one-frame wrong notes that make a raw per-frame tracker useless.
     */
    fun viterbi(midi: FloatArray, jumpCost: Float = 1.4f, holdBonus: Float = 0.55f): FloatArray {
        if (midi.isEmpty()) return midi
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (v in midi) {
            if (v.isNaN()) continue
            val n = v.roundToInt()
            if (n < lo) lo = n
            if (n > hi) hi = n
        }
        if (lo > hi) return midi
        lo -= 1
        hi += 1
        var step = 1
        while (hi - lo + 1 > MAX_STATES * step) step++
        val states = min(hi - lo + 1, MAX_STATES)
        val grid = FloatArray(states) { (lo + it * step).toFloat() }

        val cost = FloatArray(states)
        val next = FloatArray(states)
        val back = ShortArray(midi.size * states)
        for (i in midi.indices) {
            val observed = midi[i]
            // Emissions are a snap, not a slope: the frame votes for the pitch
            // it is closest to and gives up on everything else. A soft
            // distance-based emission lets the transition cost flatten the
            // whole line to one note, which is the opposite of the point.
            var nearest = 0
            var closest = Float.MAX_VALUE
            if (!observed.isNaN()) {
                for (s in 0 until states) {
                    val d = abs(grid[s] - observed)
                    if (d < closest) {
                        closest = d
                        nearest = s
                    }
                }
            }
            var bestTotal = Float.MAX_VALUE
            for (s in 0 until states) {
                var bestPrev = Float.MAX_VALUE
                var bestPrevState = 0
                for (p in 0 until states) {
                    val distance = abs(grid[p] - grid[s])
                    val c = cost[p] + jumpCost * distance - if (distance == 0f) holdBonus else 0f
                    if (c < bestPrev) {
                        bestPrev = c
                        bestPrevState = p
                    }
                }
                next[s] = bestPrev + if (observed.isNaN() || s != nearest) UNOBSERVED_COST else 0f
                back[i * states + s] = bestPrevState.toShort()
                if (next[s] < bestTotal) bestTotal = next[s]
            }
            for (s in 0 until states) cost[s] = next[s] - bestTotal
        }

        var state = 0
        for (s in 0 until states) if (cost[s] < cost[state]) state = s
        val out = FloatArray(midi.size)
        for (i in midi.indices.reversed()) {
            // Even an unvoiced frame gets a pitch here, from its neighbours: a
            // held note that flickers for 20 ms should not become two notes.
            out[i] = grid[state]
            state = back[i * states + state].toInt()
        }
        return out
    }

    private const val UNOBSERVED_COST = 4f
    private const val MAX_STATES = 128

    /** Median filter over a sliding window, `NaN` tolerant. */
    fun median(contour: FloatArray, radius: Int): FloatArray {
        val n = contour.size
        if (n == 0) return contour
        val out = FloatArray(n)
        val buf = FloatArray(radius * 2 + 1)
        for (i in 0 until n) {
            var m = 0
            for (d in -radius..radius) {
                val v = contour[(i + d).coerceIn(0, n - 1)]
                if (!v.isNaN()) buf[m++] = v
            }
            out[i] = if (m == 0) Float.NaN else medianOf(buf, m)
        }
        return out
    }

    /** One sung or played note: its pitch and how long it held, in frames. */
    data class Note(val midi: Float, val frames: Int)

    /**
     * Cut a contour into notes. A run ends where the pitch leaves the current
     * note; anything shorter than [minFrames] is a slide or a cough and is
     * dropped; neighbours of the same pitch are re-joined, which is what makes
     * a held note with a wobble in the middle survive as one note.
     */
    fun notes(contour: FloatArray, minFrames: Int, tolerance: Float = 1.2f, mergeWithin: Float = 1.2f): List<Note> {
        if (contour.isEmpty()) return emptyList()
        val runs = ArrayList<FloatArray>()
        var current: ArrayList<Float>? = null
        fun close(list: ArrayList<Float>?) {
            if (list != null && list.isNotEmpty()) runs.add(list.toFloatArray())
        }
        for (v in contour) {
            if (v.isNaN()) {
                close(current)
                current = null
                continue
            }
            val list = current
            if (list == null) {
                current = arrayListOf(v)
            } else {
                val array = list.toFloatArray()
                val centre = medianOf(array, array.size)
                if (abs(v - centre) <= tolerance) {
                    list.add(v)
                } else {
                    runs.add(array)
                    current = arrayListOf(v)
                }
            }
        }
        close(current)

        val notes = ArrayList<Note>()
        for (run in runs) {
            if (run.size < minFrames) continue
            val pitch = medianOf(run, run.size)
            val last = notes.lastOrNull()
            if (last != null && abs(last.midi - pitch) <= mergeWithin) {
                val total = last.frames + run.size
                notes[notes.size - 1] = Note((last.midi * last.frames + pitch * run.size) / total, total)
            } else {
                notes += Note(pitch, run.size)
            }
        }
        return notes
    }

    /**
     * The key-free description of a tune: the starting note, then the signed
     * number of semitones to each note after it.
     */
    fun intervals(notes: List<Note>): IntArray {
        if (notes.isEmpty()) return IntArray(0)
        val out = IntArray(notes.size)
        out[0] = notes[0].midi.roundToInt()
        for (i in 1 until notes.size) out[i] = (notes[i].midi - notes[i - 1].midi).roundToInt()
        return out
    }

    /**
     * The mix path: spectral contour, Viterbi, then notes. [hopSamples] comes
     * from the analysis [Config] so note lengths can be turned back into time.
     */
    fun outlineOfRecording(pcm: ShortArray, config: Config): MelodyOutline {
        val raw = contour(pcm, config)
        val walked = median(viterbi(median(raw, 2)), 3)
        // A run shorter than about a fifth of a second is a slide, a consonant
        // or a bad frame, not a note.
        val shortestFrames = max(2, (0.19f * config.sampleRateHz / config.hopSamples).roundToInt())
        val notes = notes(walked, minFrames = shortestFrames, tolerance = 0.9f, mergeWithin = 0.9f)
        return MelodyOutline(
            intervals(notes),
            notes.map { it.frames },
            frameSeconds = config.hopSamples / config.sampleRateHz.toFloat(),
        )
    }

    /** The voice path: a YIN trace, smoothed, then notes. No Viterbi. */
    fun outlineOfVoice(trace: PitchTracker.Trace, secondsPerFrame: Float): MelodyOutline {
        val smoothed = median(trace.midi, 9)
        val tenthSeconds = max(1, (0.13f / max(secondsPerFrame, 0.001f)).toInt())
        val notes = notes(smoothed, minFrames = tenthSeconds)
        return MelodyOutline(intervals(notes), notes.map { it.frames }, frameSeconds = secondsPerFrame)
    }

    private fun medianOf(values: FloatArray, size: Int): Float {
        for (i in 1 until size) {
            val v = values[i]
            var j = i - 1
            while (j >= 0 && values[j] > v) {
                values[j + 1] = values[j]
                j--
            }
            values[j + 1] = v
        }
        return values[size / 2]
    }
}

/**
 * A tune as the matcher wants it: the note it starts on, then the steps between
 * notes, and how long each one was held.
 */
internal data class MelodyOutline(
    /** First entry is a MIDI number; every entry after it is a semitone step. */
    val intervals: IntArray,
    /** Frames each note held, same order as [intervals]. */
    val frameCounts: List<Int>,
    /** Seconds per analysis frame, so notes can be turned back into time. */
    val frameSeconds: Float,
) {
    val noteCount: Int get() = intervals.size

    /** Seconds from the start of the audio to the beginning of note [index]. */
    fun startSecondsOf(index: Int): Float {
        var frames = 0
        for (i in 0 until min(index, frameCounts.size)) frames += frameCounts[i]
        return frames * frameSeconds
    }

    val isEmpty: Boolean get() = noteCount < MIN_NOTES

    companion object {
        /** Fewer notes than this cannot identify anything, and says so. */
        const val MIN_NOTES = 4
    }

    override fun equals(other: Any?): Boolean =
        other is MelodyOutline && intervals.contentEquals(other.intervals) && frameCounts == other.frameCounts

    override fun hashCode(): Int = 31 * intervals.contentHashCode() + frameCounts.hashCode()
}

/**
 * The smallest FFT that will do: radix-2 in place, magnitudes out. Only a
 * power-of-two size is supported, which the analysis config guarantees.
 */
internal class Fft(val size: Int) {

    private val cosTable = FloatArray(size / 2) { cos(2.0 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { sin(2.0 * PI * it / size).toFloat() }
    private val order = IntArray(size) { i -> permute(i) }
    private val real = FloatArray(size)
    private val imaginary = FloatArray(size)

    private fun permute(i: Int): Int {
        var bits = 0
        var s = size
        while (s > 1) {
            s = s shr 1
            bits++
        }
        var x = i
        var r = 0
        repeat(bits) {
            r = (r shl 1) or (x and 1)
            x = x shr 1
        }
        return r
    }

    /**
     * @param input [size] windowed samples, read-only
     * @param output magnitudes of bins `0 until binCount`
     */
    fun forward(input: FloatArray, output: FloatArray, binCount: Int) = forward(input, output, 0, binCount)

    /** Same, writing into [output] from [outputOffset]: frames of a block are
     * packed into one buffer so the temporal pass can look across them. */
    fun forward(input: FloatArray, output: FloatArray, outputOffset: Int, binCount: Int) {
        require(input.size >= size) { "input shorter than the transform" }
        // Bit-reversal is a permutation, so it needs a copy: writing the
        // reordered value back into place would clobber samples still to read.
        for (i in 0 until size) {
            real[i] = input[i]
            imaginary[i] = 0f
        }
        for (i in 0 until size) {
            val j = order[i]
            if (j > i) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imaginary[i]
                imaginary[i] = imaginary[j]
                imaginary[j] = ti
            }
        }
        var width = 2
        while (width <= size) {
            val half = width / 2
            val step = size / width
            var start = 0
            while (start < size) {
                var k = 0
                for (i in start until start + half) {
                    val wr = cosTable[k * step]
                    val wi = -sinTable[k * step]
                    val ar = real[i]
                    val ai = imaginary[i]
                    val br = real[i + half]
                    val bi = imaginary[i + half]
                    val tr = br * wr - bi * wi
                    val ti = br * wi + bi * wr
                    real[i] = ar + tr
                    imaginary[i] = ai + ti
                    real[i + half] = ar - tr
                    imaginary[i + half] = ai - ti
                    k++
                }
                start += width
            }
            width = width shl 1
        }
        val n = min(binCount, size / 2 + 1)
        for (i in 0 until n) output[outputOffset + i] = sqrt(real[i] * real[i] + imaginary[i] * imaginary[i])
    }
}

/** A precomputed Hann window. */
internal class HannTable private constructor(private val values: FloatArray) {

    fun at(index: Int): Float = values[index]

    companion object {
        private val cache = HashMap<Int, HannTable>()

        fun of(size: Int): HannTable = cache.getOrPut(size) {
            HannTable(FloatArray(size) { i ->
                (0.5f - 0.5f * cos(2.0 * PI * i / (size - 1)).toFloat()).coerceAtLeast(0f)
            })
        }
    }
}

