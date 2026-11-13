package com.lastwave.app.data.hearing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/**
 * What a self-test measures, and what it turns into.
 *
 * A phone has no calibrated output level, so nothing here is in dB SPL and
 * nothing pretends to be an audiogram. What the device *can* tell you is how
 * much louder one band has to be than another for the two to sound equally
 * loud, which is exactly the information an EQ needs. Every number here is
 * therefore relative: a threshold per band, measured against the quietest band
 * that same ear found easy today.
 *
 * The consequence is worth stating plainly because it is also the limit: a flat
 * curve means "no difference between bands", not "hearing is normal".
 */

/** One band of a self-test: thresholds per ear, in dB relative to the test's own start level. */
@Serializable
data class HearingBand(
    val frequencyHz: Double,
    /** Bigger = needed more level = less sensitive at that frequency. */
    val leftThresholdDb: Double,
    val rightThresholdDb: Double,
) {
    val meanThresholdDb: Double get() = (leftThresholdDb + rightThresholdDb) / 2.0
}

/** The result of one test: both ears, several bands. */
@Serializable
data class HearingProfile(
    val bands: List<HearingBand>,
    val takenAtMillis: Long = 0L,
    /** Which output the test ran on: the same ear needs a different curve per seal. */
    val deviceLabel: String = "",
    /** "in-ear", "over-ear" and friends. */
    val formFactor: String = "",
) {

    /** Loss at each band against the best (quietest) band this ear managed. */
    fun losses(): List<Loss> {
        if (bands.isEmpty()) return emptyList()
        val reference = bands.minOf { it.meanThresholdDb }
        return bands.map { band ->
            Loss(
                frequencyHz = band.frequencyHz,
                leftDb = max(0.0, band.leftThresholdDb - reference),
                rightDb = max(0.0, band.rightThresholdDb - reference),
            )
        }
    }

    /**
     * The correction to hand to the parametric fitter, in dB on [grid].
     * Half-gain, capped, tapered at the top, smoothed, and never cutting: this
     * curve only adds what the ear is missing, because cutting the bands that
     * are fine is what makes the result sound like a telephone.
     */
    fun curve(
        grid: DoubleArray = DEFAULT_GRID,
        strength: Double = 1.0,
        maxBoostDb: Double = MAX_BOOST_DB,
    ): DoubleArray {
        val losses = losses()
        if (losses.isEmpty()) return DoubleArray(grid.size)
        val frequencies = DoubleArray(losses.size) { losses[it].frequencyHz }
        val boosts = DoubleArray(losses.size) { i ->
            val loss = losses[i].meanLossDb
            if (loss < MIN_LOSS_DB) {
                0.0
            } else {
                val halfGain = loss * HALF_GAIN_RULE * strength.coerceIn(0.0, 1.0)
                val taper = if (losses[i].frequencyHz > TAPER_FROM_HZ) HIGH_FREQUENCY_TAPER else 1.0
                max(0.0, min(halfGain * taper, maxBoostDb))
            }
        }
        val raw = DoubleArray(grid.size) { i -> interpolateLog(grid[i], frequencies, boosts) }
        return smooth(raw, maxBoostDb)
    }

    /**
     * What a balance control *would* want: both values zero or negative, so the
     * more sensitive ear is turned down to meet the other rather than the weak
     * ear being pushed up. LastWave's EQ chain is mono-equal for both channels
     * (ParametricEq has one filter list), so today this number is only shown in
     * the summary — it is deliberately not applied, and a hearing test is not a
     * substitute for the assessment a real asymmetry deserves.
     */
    fun channelTrimsDb(limitDb: Double = MAX_BALANCE_DB): Pair<Double, Double> {
        val losses = losses()
        if (losses.isEmpty()) return 0.0 to 0.0
        val difference = losses.sumOf { it.rightDb - it.leftDb } / losses.size
        return when {
            difference > 0.0 -> -min(abs(difference), limitDb) to 0.0
            difference < 0.0 -> 0.0 to -min(abs(difference), limitDb)
            else -> 0.0 to 0.0
        }
    }

    val isUsable: Boolean get() = bands.size >= MIN_BANDS

    /** Worst loss across the bands: what the summary is graded on. */
    val peakLossDb: Double get() = losses().maxOfOrNull { it.meanLossDb } ?: 0.0

    /** Loss in the low half and in the treble half — the shape, not just the size. */
    fun bandSplit(): Pair<Double, Double> {
        val losses = losses()
        if (losses.isEmpty()) return 0.0 to 0.0
        val low = losses.filter { it.frequencyHz < SPLIT_HZ }.map { it.meanLossDb }
        val high = losses.filter { it.frequencyHz >= SPLIT_HZ }.map { it.meanLossDb }
        return low.averageOrZero() to high.averageOrZero()
    }

    /**
     * How much the two ears disagree, averaged over the bands. An EQ has one
     * filter list for both channels, so a real asymmetry is a reason to see an
     * audiologist, not a reason to reach for a curve: the number is reported and
     * never corrected here.
     */
    val asymmetryDb: Double
        get() {
            if (bands.isEmpty()) return 0.0
            // Raw thresholds, not [losses]: losses are floored at zero against a
            // shared reference, which would hide an ear that is simply quieter
            // everywhere by the same amount.
            return bands.sumOf { abs(it.leftThresholdDb - it.rightThresholdDb) } / bands.size
        }

    /** Plain words for a settings row and the result card. */
    fun summary(): String {
        if (!isUsable) return "Not enough bands measured"
        val (low, high) = bandSplit()
        val worst = max(low, high)
        val grade = when {
            worst < GRADE_MILD_DB -> "even across the range"
            worst < GRADE_MODERATE_DB -> "mild"
            worst < GRADE_SEVERE_DB -> "moderate"
            else -> "marked"
        }
        val shape = when {
            worst < GRADE_MILD_DB -> ""
            high > low + 4.0 -> " · high-frequency $grade loss"
            low > high + 4.0 -> " · low-frequency $grade loss"
            else -> " · $grade loss across the range"
        }
        val difference = asymmetryDb
        val balance = if (difference < 0.9) {
            ""
        } else {
            " · ears differ by ${"%.1f".format(difference)} dB, which an EQ cannot split"
        }
        val head = if (worst < GRADE_MILD_DB) "Nothing to correct" else "Correction ready"
        return "$head$shape$balance"
    }

    /** The loudest boost the curve asks for, which is what eats headroom. */
    fun appliedBoostDb(grid: DoubleArray = DEFAULT_GRID, strength: Double = 1.0): Double =
        curve(grid = grid, strength = strength).maxOrNull() ?: 0.0

    /** A loss at one band, for the chart and the summary. */
    @Serializable
    data class Loss(val frequencyHz: Double, val leftDb: Double, val rightDb: Double) {
        val meanLossDb: Double get() = (leftDb + rightDb) / 2.0
    }

    companion object {

        /** The bands a phone test can manage in a few minutes, treble-weighted. */
        val FREQUENCIES = doubleArrayOf(250.0, 500.0, 1_000.0, 2_000.0, 3_000.0, 4_000.0, 6_000.0, 8_000.0)

        /** Measured first; the staircase's start level is picked from it. */
        const val REFERENCE_HZ = 1_000.0

        /**
         * The hearing-aid rule: give back half of what is missing, not all of
         * it. Full compensation sounds wrong within a day, and it hides the
         * fact that the curve was measured through one particular ear-tip seal.
         */
        const val HALF_GAIN_RULE = 0.5

        /** Below this, it is measurement scatter rather than hearing. */
        const val MIN_LOSS_DB = 5.0

        const val MAX_BOOST_DB = 6.0
        const val MAX_BALANCE_DB = 4.0

        /** Above this frequency the taper applies: treble boost is where harshness starts. */
        const val TAPER_FROM_HZ = 4_000.0
        const val HIGH_FREQUENCY_TAPER = 0.5

        const val MIN_BANDS = 4
        const val SPLIT_HZ = 1_500.0

        const val GRADE_MILD_DB = 10.0
        const val GRADE_MODERATE_DB = 20.0
        const val GRADE_SEVERE_DB = 30.0

        /** Same third-octave grid the parametric fitter is fitted against. */
        val DEFAULT_GRID: DoubleArray = com.lastwave.app.data.autoeq.PeqMath.LOG_GRID

        /** Re-run a single band without losing the rest of the profile. */
        fun combine(existing: HearingProfile?, fresh: HearingProfile): HearingProfile {
            if (existing == null || !existing.isUsable) return fresh
            val byFrequency = existing.bands.associateBy { it.frequencyHz.roundToInt() }.toMutableMap()
            fresh.bands.forEach { byFrequency[it.frequencyHz.roundToInt()] = it }
            return fresh.copy(
                bands = byFrequency.values.sortedBy { it.frequencyHz },
                takenAtMillis = max(existing.takenAtMillis, fresh.takenAtMillis),
            )
        }

        /**
         * Linear in log-frequency, which is the only sensible axis here: the
         * ear works in octaves and the bands are spaced by octave, so linear in
         * Hz would put most of the correction below 1 kHz by accident.
         */
        private fun interpolateLog(frequency: Double, points: DoubleArray, values: DoubleArray): Double {
            if (points.isEmpty()) return 0.0
            if (points.size == 1) return values[0]
            val logF = ln(frequency)
            if (logF <= ln(points.first())) return values.first()
            if (logF >= ln(points.last())) return values.last()
            var lo = 0
            var hi = points.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (ln(points[mid]) <= logF) lo = mid else hi = mid
            }
            val x0 = ln(points[lo])
            val x1 = ln(points[hi])
            val t = if (x1 == x0) 0.0 else (logF - x0) / (x1 - x0)
            return values[lo] * (1.0 - t) + values[hi] * t
        }

        /**
         * A binomial low-pass, then a clamp. The grid is a twelfth of an octave
         * per point while the ear's own filters are about a third wide, so a
         * single bad band would otherwise become a narrow spike: the fitter would
         * spend two of its eight peaking filters chasing it, one positive and one
         * negative, and the result would sound like a resonator rather than like
         * a hearing aid. Two points either side is roughly a third of an octave.
         */
        private fun smooth(values: DoubleArray, limitDb: Double): DoubleArray {
            if (values.size < 3) return DoubleArray(values.size) { values[it].coerceIn(0.0, limitDb) }
            fun at(index: Int) = values[index.coerceIn(0, values.size - 1)]
            return DoubleArray(values.size) { i ->
                val weighted = at(i - 2) + 4.0 * at(i - 1) + 6.0 * at(i) + 4.0 * at(i + 1) + at(i + 2)
                (weighted / 16.0).coerceIn(0.0, limitDb)
            }
        }

        private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
    }
}

/**
 * A down-up staircase: how a threshold is found without knowing the device's
 * output level, and small enough to be sure about on a phone.
 *
 * The steps are deliberately asymmetric — drop 2 dB while the tone is still
 * audible, come back up 1 dB at a time — because the number that matters is the
 * last level that *was* heard, and coarse up-steps would miss it by a full step.
 * The estimate is the mean of the reversal points rather than the last level,
 * which is what keeps one slipped tap from moving the whole band.
 */
class ThresholdStaircase(
    val startDb: Double = -26.0,
    val floorDb: Double = -64.0,
    val ceilingDb: Double = -8.0,
    val stepDownDb: Double = 2.0,
    val stepUpDb: Double = 1.0,
    val reversalsNeeded: Int = 3,
    val maxResponses: Int = 40,
) {

    enum class Direction { DOWN, UP }

    var levelDb: Double = startDb
        private set
    var direction: Direction = Direction.DOWN
        private set
    var responses: Int = 0
        private set
    var finished: Boolean = false
        private set

    /** Every level presented, for the "how did that go" sparkline. */
    val trace: List<Double> get() = recorded

    private val recorded = ArrayList<Double>()
    private val reversals = ArrayList<Double>()
    private var heardOnce = false
    private var lastUnheardDb: Double? = null
    private var upSteps = 0

    /**
     * Feed one presentation's answer.
     *
     * @param heard whether the listener reported hearing the tone that just
     * played at [levelDb].
     */
    fun respond(heard: Boolean) {
        if (finished) return
        responses++
        recorded.add(levelDb)
        if (heard) {
            heardOnce = true
            upSteps = 0
            val unheard = lastUnheardDb
            if (unheard != null) {
                // A reversal: missed on the way down, heard again on the way up.
                lastUnheardDb = null
                reversals.add((unheard + levelDb) / 2.0)
                if (reversals.size >= reversalsNeeded) {
                    finished = true
                    return
                }
                direction = Direction.DOWN
            }
            levelDb = max(floorDb, levelDb - stepDownDb)
            if (levelDb <= floorDb) {
                finished = true
                return
            }
        } else if (!heardOnce) {
            // Nothing heard yet, at any level: climb until the first yes rather
            // than treating "start level" as a threshold.
            levelDb = min(ceilingDb, levelDb + stepUpDb)
            if (levelDb >= ceilingDb || responses >= maxResponses) {
                finished = true
                return
            }
        } else {
            lastUnheardDb = levelDb
            direction = Direction.UP
            upSteps++
            levelDb = min(ceilingDb, levelDb + stepUpDb)
            if (upSteps > MAX_UP_STEPS || levelDb >= ceilingDb) {
                finished = true
                return
            }
        }
        if (responses >= maxResponses) finished = true
    }

    /**
     * Estimated threshold. With no reversal yet this is simply where the test
     * stopped, and [isConfident] is false, so a dead band cannot masquerade as a
     * precise measurement.
     */
    val thresholdDb: Double
        get() {
            if (reversals.isEmpty()) return if (heardOnce) levelDb + stepDownDb else ceilingDb
            val taken = reversals.takeLast(REVERSALS_TO_AVERAGE)
            return taken.sum() / taken.size
        }

    /** How many down/up turns have been averaged so far. */
    val reversalCount: Int get() = reversals.size

    /** Scatter between reversals: the honest "how much do I trust this band". */
    val spreadDb: Double
        get() {
            if (reversals.size < 2) return Double.POSITIVE_INFINITY
            val mean = reversals.sum() / reversals.size
            val variance = reversals.sumOf { (it - mean) * (it - mean) } / reversals.size
            return sqrt(variance)
        }

    val isConfident: Boolean get() = reversals.size >= reversalsNeeded && spreadDb <= CONFIDENT_SPREAD_DB

    /** The ear never heard even the loudest tone: nothing safe to correct. */
    val noResponse: Boolean get() = !heardOnce && finished

    private companion object {
        const val MAX_UP_STEPS = 24
        const val REVERSALS_TO_AVERAGE = 3
        const val CONFIDENT_SPREAD_DB = 4.0
    }
}

/** Which ear a stimulus goes to. */
enum class Ear { LEFT, RIGHT }

/**
 * Test-tone synthesis: a warbled pure tone, faded, into one channel.
 *
 * The warble is not decoration. A steady sine through a sealed earbud excites
 * one standing wave in the ear canal, so whether it is heard depends on the fit
 * of the tip; a tone that wanders ±30 Hz at 5 Hz averages the canal's response
 * out and measures the ear instead. Fades matter for the same reason: a click at
 * the start is its own wideband stimulus, and a level of -40 dBFS is quiet
 * enough that a click would be heard when the tone was not.
 */
object HearingTones {

    const val SAMPLE_RATE = 48_000
    const val WARBLE_HZ = 5.0
    const val WARBLE_DEPTH_HZ = 30.0
    const val FADE_MS = 35

    /** @param ear the channel the tone occupies; the other is exact silence. */
    fun burst(
        frequencyHz: Double,
        levelDbFs: Double,
        durationSec: Double = 0.75,
        ear: Ear? = null,
        channels: Int = 2,
    ): ShortArray {
        val frames = max(1, (durationSec * SAMPLE_RATE).roundToInt())
        val amplitude = 10.0.pow(levelDbFs / 20.0) * Short.MAX_VALUE
        val fadeFrames = min(frames / 2, (FADE_MS / 1000.0 * SAMPLE_RATE).roundToInt())
        val out = ShortArray(frames * max(1, channels))
        var phase = 0.0
        val step = 2.0 * PI / SAMPLE_RATE
        for (f in 0 until frames) {
            val time = f / SAMPLE_RATE.toDouble()
            val wobble = WARBLE_DEPTH_HZ * sin(2.0 * PI * time * WARBLE_HZ)
            phase += step * (frequencyHz + wobble)
            var envelope = 1.0
            if (fadeFrames > 0) {
                envelope = when {
                    f < fadeFrames -> 0.5 - 0.5 * cos(PI * f / fadeFrames)
                    f >= frames - fadeFrames -> 0.5 - 0.5 * cos(PI * (frames - 1 - f) / fadeFrames)
                    else -> 1.0
                }
            }
            val sample = (sin(phase) * amplitude * envelope).roundToInt().coerceIn(-32_767, 32_767).toShort()
            for (c in 0 until channels) {
                val wanted = ear == null || channels == 1 ||
                    (ear == Ear.LEFT && c == 0) || (ear == Ear.RIGHT && c == 1)
                out[f * max(1, channels) + c] = if (wanted) sample else 0
            }
        }
        return out
    }

    /** RMS in dB below full scale, for the meter and the tests. */
    fun rmsDbFs(samples: ShortArray): Double {
        if (samples.isEmpty()) return Double.NEGATIVE_INFINITY
        var sum = 0.0
        for (s in samples) {
            val v = s.toDouble() / Short.MAX_VALUE
            sum += v * v
        }
        val rms = sqrt(sum / samples.size)
        return if (rms <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(rms)
    }

    /**
     * The frequency in [minHz]..[maxHz] with the most energy, found by
     * correlating against steady sines on a [stepHz] comb.
     *
     * A comb, not a single probe, because the tone is deliberately warbled: a
     * steady 1 kHz sine would correlate poorly with it and "prove" the wrong
     * thing. This is the check that the burst really sits where the test says it
     * does, which is the one property the whole measurement rests on.
     */
    fun dominantFrequencyHz(
        samples: ShortArray,
        minHz: Double,
        maxHz: Double,
        stepHz: Double = 5.0,
        channels: Int = 2,
    ): Double {
        val frames = samples.size / max(1, channels)
        if (frames == 0) return Double.NaN
        var best = Double.NaN
        var bestMagnitude = -1.0
        var f = minHz
        while (f <= maxHz) {
            val step = 2.0 * PI * f / SAMPLE_RATE
            var re = 0.0
            var im = 0.0
            for (n in 0 until frames) {
                val value = samples[n * max(1, channels)].toDouble() / Short.MAX_VALUE
                re += value * cos(step * n)
                im -= value * sin(step * n)
            }
            val magnitude = sqrt(re * re + im * im) / frames
            if (magnitude > bestMagnitude) {
                bestMagnitude = magnitude
                best = f
            }
            f += stepHz
        }
        return best
    }

    /** Strength of the correlation at one frequency, 0..1 of full-scale RMS. */
    fun toneStrengthAt(samples: ShortArray, frequencyHz: Double, channels: Int = 2): Double {
        val frames = samples.size / max(1, channels)
        if (frames == 0) return 0.0
        val step = 2.0 * PI * frequencyHz / SAMPLE_RATE
        var re = 0.0
        var im = 0.0
        for (n in 0 until frames) {
            val value = samples[n * max(1, channels)].toDouble() / Short.MAX_VALUE
            re += value * cos(step * n)
            im -= value * sin(step * n)
        }
        return sqrt(re * re + im * im) / frames
    }

    /** Sum of absolute samples on one channel, to check ear isolation. */
    fun channelEnergy(samples: ShortArray, channel: Int, channels: Int = 2): Double {
        if (samples.isEmpty()) return 0.0
        var acc = 0.0
        var f = 0
        while (f * channels + channel < samples.size) {
            acc += abs(samples[f * channels + channel].toDouble())
            f++
        }
        return acc / max(1, samples.size / channels)
    }
}
