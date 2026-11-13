package com.lastwave.app.playback.melody

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Fundamental frequency tracking for "sing to find".
 *
 * The core is a YIN difference function over a sliding frame, with two
 * additions that matter once the input is not a clean studio vocal:
 *
 *  - every local minimum of the normalised difference curve is scored, not
 *    just the first one under the threshold, and the score favours the vocal
 *    band. That is what keeps a melody being read as a melody in a mix that
 *    also carries a bass line an octave or a fourth below it;
 *  - a harmonic reinforcement term, since a real pitched note keeps
 *    correlating at two and three times its period while a noise-driven dip
 *    does not.
 *
 * Everything here is plain arithmetic on mono PCM, so it runs on the JVM and
 * is unit tested against synthesised signals.
 */
internal object PitchTracker {

    /** Frame size, slide and the band to trust. */
    data class Config(
        val sampleRateHz: Int = DEFAULT_SAMPLE_RATE,
        val windowSamples: Int = 512,
        val hopSamples: Int = 512,
        val minHz: Float = 80f,
        val maxHz: Float = 1_100f,
        /** Frames quieter than this linear RMS are not voiced at all. */
        val silenceRms: Float = 0.0035f,
        /** Below this clarity a frame is treated as unvoiced. */
        val minClarity: Float = 0.28f,
        /**
         * How much the 300 Hz band preference counts. A mix needs it, because
         * a bass line sits far outside it; a lone voice does not, and giving
         * it weight there only bends the answer toward the middle of the range.
         */
        val priorWeight: Float = 0.36f,
    ) {
        val minLag: Int = max(2, (sampleRateHz / maxHz).toInt())
        val maxLag: Int = max(minLag + 6, (sampleRateHz / minHz).toInt())

        /** A frame needs samples on the right so the widest lag can still be read. */
        val frameSamples: Int get() = windowSamples + 3 * maxLag + 2
    }

    /**
     * One estimate per frame. [midi] is a MIDI note number (69 = A440) and is
     * `NaN` where the frame carries no trustworthy pitch.
     */
    class Trace(
        val config: Config,
        val midi: FloatArray,
        val clarity: FloatArray,
        val rms: FloatArray,
    ) {
        val frameCount: Int get() = midi.size

        fun voicedFrames(): Int = midi.count { !it.isNaN() }
    }

    /** Mono 16-bit PCM in, one pitch estimate per hop out. */
    fun trace(samples: ShortArray, config: Config, from: Int = 0, to: Int = samples.size): Trace {
        val win = config.windowSamples
        val hop = max(1, config.hopSamples)
        val lo = config.minLag
        val hi = config.maxLag
        val frameLen = config.frameSamples
        val start = max(0, from)
        val end = min(to, samples.size)

        val usable = end - start
        val count = if (usable < win + lo + 2) 0 else (usable - frameLen) / hop + 1
        val midi = FloatArray(max(count, 0)) { Float.NaN }
        val clarityOut = FloatArray(midi.size)
        val rmsOut = FloatArray(midi.size)
        if (midi.isEmpty()) return Trace(config, midi, clarityOut, rmsOut)

        val buf = FloatArray(frameLen)
        val df = FloatArray(hi + 2)
        val norm = FloatArray(hi + 2)
        for (f in 0 until count) {
            val base = start + f * hop
            var sum = 0f
            for (i in 0 until frameLen) {
                val v = if (base + i < end) samples[base + i] * (1f / 32768f) else 0f
                buf[i] = v
                sum += v
            }
            val mean = sum / frameLen
            var energy = 0f
            for (i in 0 until frameLen) {
                val v = buf[i] - mean
                buf[i] = v
                if (i < win) energy += v * v
            }
            val rms = sqrt(energy / win)
            rmsOut[f] = rms
            if (rms < config.silenceRms) continue

            for (tau in lo..hi) {
                var acc = 0f
                for (n in 0 until win) {
                    val d = buf[n] - buf[n + tau]
                    acc += d * d
                }
                df[tau] = acc
            }

            // Cumulative-mean-normalised difference function.
            val windowEnergy = df[lo].takeIf { it > 0f } ?: continue
            var running = 0f
            var seen = 0
            for (tau in lo..hi) {
                running += df[tau]
                seen++
                norm[tau] = if (running > 0f) df[tau] * seen / running else Float.MAX_VALUE
            }
            if (norm[hi] <= 0f) continue

            // Score every local minimum: periodicity, band preference and
            // harmonic support. The first dip under the threshold is the usual
            // answer, but in a mix it is often the bass, so it is only a
            // candidate here.
            var bestScore = -1f
            var bestTau = -1
            for (tau in (lo + 1)..(hi - 1)) {
                val v = norm[tau]
                if (v > OCTAVE_LIMIT) continue
                if (v > norm[tau - 1] || v > norm[tau + 1]) continue
                val periodicity = 1f - (v / OCTAVE_LIMIT).coerceIn(0f, 1f)
                val prior = bandPrior(config.sampleRateHz.toFloat() / tau)
                val harmonic = harmonicScore(buf, win, tau)
                val score = 0.42f * periodicity + config.priorWeight * prior + 0.22f * harmonic
                if (score > bestScore) {
                    bestScore = score
                    bestTau = tau
                }
            }
            if (bestTau < 0) {
                // No clean dip: fall back to the deepest one if the frame is at
                // least roughly periodic, otherwise leave it unvoiced.
                var deepest = Float.MAX_VALUE
                for (tau in lo..hi) if (norm[tau] < deepest) { deepest = norm[tau]; bestTau = tau }
                if (deepest > LOOSE_LIMIT || windowEnergy <= 0f) {
                    bestTau = -1
                    continue
                }
            }
            // YIN's first-dip rule, kept as a last step: if half the period is
            // nearly as periodic, the singer was not really an octave down and
            // the deeper-looking dip is a multiple of the true period.
            while (bestTau / 2 >= lo && norm[bestTau / 2] < norm[bestTau] * FOLD_UP_GAIN) bestTau /= 2

            val clarity = (0.55f * (1f - (norm[bestTau] / OCTAVE_LIMIT).coerceIn(0f, 1f)) +
                0.45f * harmonicScore(buf, win, bestTau)).coerceIn(0f, 1f)
            if (clarity < config.minClarity) continue

            midi[f] = hzToMidi(config.sampleRateHz / parabolic(norm, bestTau, lo, hi))
            clarityOut[f] = clarity
        }
        return Trace(config, midi, clarityOut, rmsOut)
    }

    /** Correlation at two and three times the period, relative to the period itself. */
    private fun harmonicScore(x: FloatArray, win: Int, tau: Int): Float {
        var r1 = 0f
        for (n in 0 until win) r1 += x[n] * x[n + tau]
        if (r1 <= 0f) return 0f
        var acc = 0f
        var weight = 1f
        for (k in 2..3) {
            val lag = tau * k
            if (lag + win > x.size) break
            var r = 0f
            for (n in 0 until win) r += x[n] * x[n + lag]
            acc += (r / r1).coerceIn(0f, 1f) / k
            weight += 1f / k
        }
        return (acc / weight).coerceIn(0f, 1f)
    }

    /**
     * Log-normal preference for the range a sung melody actually lives in.
     * Peaks at 300 Hz and is still near 1 from about 90 to 1000 Hz, so a real
     * soprano or a low croon is not punished; a bass guitar is.
     */
    private fun bandPrior(hz: Float): Float {
        val ratio = ln((hz / 300f).coerceAtLeast(0.05f))
        return exp(-(ratio * ratio) / (2f * 1.25f * 1.25f)).coerceIn(0.25f, 1f)
    }

    /** Sub-sample peak refinement on the normalised difference curve. */
    private fun parabolic(curve: FloatArray, tau: Int, lo: Int, hi: Int): Float {
        if (tau <= lo || tau >= hi) return tau.toFloat()
        val a = curve[tau - 1]
        val b = curve[tau]
        val c = curve[tau + 1]
        val denom = a - 2f * b + c
        if (denom == 0f || denom.isNaN()) return tau.toFloat()
        return tau + (0.5f * (a - c) / denom).coerceIn(-0.8f, 0.8f)
    }

    /** A dip this deep or deeper counts as a candidate pitch. */
    private const val OCTAVE_LIMIT = 0.55f

    /** How much worse the half period may look before the octave is taken anyway. */
    private const val FOLD_UP_GAIN = 1.32f

    /** Without any dip at all, only accept a curve this periodic. */
    private const val LOOSE_LIMIT = 0.72f

    const val DEFAULT_SAMPLE_RATE = 16_000

    /** The band a single voice in a quiet room is expected to cover. */
    val VOICE = Config(
        windowSamples = 1_536,
        hopSamples = 256,
        minHz = 80f,
        maxHz = 1_200f,
        priorWeight = 0.05f,
    )

    fun hzToMidi(hz: Float): Float = 69f + 12f * ln(hz / 440f) / LN2

    fun midiToHz(midi: Float): Float = 440f * exp((midi - 69f) * LN2 / 12f)

    private const val LN2 = 0.6931472f

    /** Note name of a MIDI number, for the live read-out while you sing. */
    fun noteName(midi: Float): String {
        if (midi.isNaN()) return "\u2013"
        val rounded = midi.roundToInt()
        val names = arrayOf("C", "C\u266F", "D", "D\u266F", "E", "F", "F\u266F", "G", "G\u266F", "A", "A\u266F", "B")
        return names[floorMod(rounded, 12)] + (rounded / 12 - 1).toString()
    }

    private fun floorMod(a: Int, b: Int): Int = ((a % b) + b) % b

    /**
     * Resample a contour to [target] points by interpolating across the voiced
     * span, so two performances of different length can be compared point by
     * point. Unvoiced edges are trimmed and short interior gaps are bridged.
     * Returns null when there is not enough pitched material to describe.
     */
    fun normalise(midi: FloatArray, target: Int): FloatArray? {
        if (midi.isEmpty() || target < 2) return null
        var first = -1
        var last = -1
        for (i in midi.indices) if (!midi[i].isNaN()) {
            if (first < 0) first = i
            last = i
        }
        if (first < 0 || last - first < 2) return null
        val filled = midi.copyOf()
        var gapStart = -1
        for (i in first..last) {
            if (filled[i].isNaN()) {
                if (gapStart < 0) gapStart = i
            } else if (gapStart >= 0) {
                if (i - gapStart <= MAX_GAP) {
                    val a = filled[gapStart - 1]
                    val span = (i - gapStart + 1).toFloat()
                    for (g in gapStart until i) filled[g] = a + (filled[i] - a) * (g - (gapStart - 1)) / span
                }
                gapStart = -1
            }
        }
        if (gapStart >= 0) {
            var hold = Float.NaN
            for (i in gapStart - 1 downTo first) if (!filled[i].isNaN()) { hold = filled[i]; break }
            if (!hold.isNaN()) for (g in gapStart..last) filled[g] = hold
        }
        val out = FloatArray(target)
        val span = (last - first).toFloat()
        for (k in 0 until target) {
            val pos = first + span * k / (target - 1)
            val i0 = pos.toInt().coerceIn(first, last - 1)
            val frac = pos - i0
            var a = filled[i0]
            var b = filled[i0 + 1]
            if (a.isNaN() && b.isNaN()) {
                out[k] = Float.NaN
                continue
            }
            if (a.isNaN()) a = b
            if (b.isNaN()) b = a
            out[k] = a + (b - a) * frac
        }
        return if (out.count { !it.isNaN() } < target / 3) null else out
    }

    private const val MAX_GAP = 5

    /**
     * Median filter, which is how vibrato, a portamento slide and the odd
     * mis-tracked frame are kept from dominating a comparison.
     */
    fun smooth(contour: FloatArray, radius: Int = 2): FloatArray {
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

    private fun medianOf(buf: FloatArray, size: Int): Float {
        for (i in 1 until size) {
            val v = buf[i]
            var j = i - 1
            while (j >= 0 && buf[j] > v) {
                buf[j + 1] = buf[j]
                j--
            }
            buf[j + 1] = v
        }
        return buf[size / 2]
    }

    /** Semitone distance that does not care about octaves: 12 folds to 0. */
    fun octaveFoldedDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 12f
        return min(d, 12f - d)
    }

    /**
     * Downmix and, if needed, decimate to the rate the tracker expects. Uses a
     * box average rather than dropping samples so aliasing does not invent
     * pitch where there is none.
     */
    fun toMono16(
        input: ShortArray,
        inputRateHz: Int,
        channels: Int,
        targetRateHz: Int = DEFAULT_SAMPLE_RATE,
    ): ShortArray {
        val frames = input.size / max(1, channels)
        val ratio = inputRateHz.toFloat() / targetRateHz
        if (ratio <= 1.0001f && channels <= 1) return input.copyOf()
        val out = ShortArray(max(1, (frames / ratio).toInt()))
        var inPos = 0f
        for (o in out.indices) {
            val from = inPos.toInt()
            val toExclusive = max(from + 1, min(((o + 1) * ratio).toInt(), frames))
            var acc = 0f
            var n = 0
            for (i in from until toExclusive) {
                for (c in 0 until channels) {
                    acc += input[min(i * channels + c, input.size - 1)]
                }
                n += channels
            }
            val v = if (n > 0) acc / n else 0f
            out[o] = v.toInt().coerceIn(-32768, 32767).toShort()
            inPos = (toExclusive).toFloat()
        }
        return out
    }
}
