package com.lastwave.app.data.hearing

/**
 * The state machine of one self-test, with no Android in it.
 *
 * Split from playback on purpose: which band is measured next, what the
 * listener's taps do to a staircase, and which finished staircases are worth
 * keeping is all arithmetic that a JVM test can drive thousands of times. The
 * AudioTrack side ([HearingTestRunner]) only moves sound and forwards taps.
 */
class HearingTestSession(

    /** What to measure, in order. */
    val plan: List<Target> = DEFAULT_PLAN,

    /** A band with no reversal by this many responses is given up on. */
    val maxResponsesPerBand: Int = 40,
) {

    /** One staircase run: one ear, one frequency. */
    data class Target(val ear: Ear, val frequencyHz: Double)

    /** Where the test is, for the UI. */
    data class Step(
        val index: Int,
        val total: Int,
        val ear: Ear,
        val frequencyHz: Double,
        val levelDb: Double,
        val responses: Int,
        val reversals: Int,
        val awaitingAnswer: Boolean,
    )

    private val staircases = LinkedHashMap<Target, ThresholdStaircase>()
    private var index = 0
    private var awaiting = false

    val total: Int get() = plan.size

    /** True once every planned band has been run (not that all succeeded). */
    val isComplete: Boolean get() = index >= plan.size

    val answeredCount: Int get() = plan.count { (staircases[it]?.responses ?: 0) > 0 }

    /**
     * The band being presented right now, or the next one to start. A step whose
     * staircase is finished has already been consumed, so [nextLevel] reports
     * the level for the *following* presentation.
     */
    fun current(): Step? {
        val target = plan.getOrNull(index) ?: return null
        val staircase = staircases[target]
        return Step(
            index = index,
            total = total,
            ear = target.ear,
            frequencyHz = target.frequencyHz,
            levelDb = staircase?.levelDb ?: START_DB,
            responses = staircase?.responses ?: 0,
            reversals = staircase?.reversalCount ?: 0,
            awaitingAnswer = awaiting,
        )
    }

    /**
     * Start the tone for the current step. Returns the level to play at, in
     * dB below full scale, or null when the test is over.
     */
    fun beginPresentation(): Double? {
        val target = plan.getOrNull(index) ?: return null
        val staircase = staircases.getOrPut(target) { newStaircase() }
        if (staircase.finished) {
            awaiting = false
            return null
        }
        awaiting = true
        return staircase.levelDb
    }

    /**
     * Record the listener's answer. Returns true when the step is over and the
     * caller should advance (which this method does).
     */
    fun answer(heard: Boolean): Boolean {
        val target = plan.getOrNull(index) ?: return false
        val staircase = staircases.getOrPut(target) { newStaircase() }
        if (!awaiting) return false
        awaiting = false
        staircase.respond(heard)
        if (staircase.finished) index++
        return staircase.finished
    }

    /** "That one was wrong": throw this band away and run it again from the start level. */
    fun retryCurrent() {
        val target = plan.getOrNull(index) ?: return
        staircases.remove(target)
        awaiting = false
    }

    /** Move on without a usable answer: the band is reported as untested. */
    fun skip() {
        if (index < plan.size) {
            awaiting = false
            index++
        }
    }

    /** Per-band readout for the result screen: what was found, and how far to trust it. */
    fun readings(): List<Reading> = plan.map { target ->
        val staircase = staircases[target]
        Reading(
            ear = target.ear,
            frequencyHz = target.frequencyHz,
            thresholdDb = staircase?.thresholdDb,
            spreadDb = staircase?.spreadDb?.takeIf { it.isFinite() },
            confident = staircase?.isConfident == true,
            noResponse = staircase?.noResponse == true,
            abandoned = staircase == null || staircase.responses == 0,
        )
    }

    /**
     * The profile, using only bands worth keeping.
     *
     * A band is kept when at least one ear has a confident threshold. When only
     * one ear was measured the value is copied to the other rather than treated
     * as an asymmetry: [HearingProfile.losses] differences are what drive the
     * curve, so a missing ear must not invent a loss for it.
     */
    fun profile(deviceLabel: String = "", formFactor: String = ""): HearingProfile {
        val byFrequency = LinkedHashMap<Double, Pair<Double, Double>>()
        plan.forEach { target ->
            val staircase = staircases[target] ?: return@forEach
            if (staircase.responses == 0 || staircase.noResponse) return@forEach
            if (!staircase.isConfident && staircase.responses < minResponsesForBestEffort) return@forEach
            val previous = byFrequency[target.frequencyHz]
            val value = staircase.thresholdDb
            byFrequency[target.frequencyHz] = when (target.ear) {
                Ear.LEFT -> (value to (previous?.second ?: value))
                Ear.RIGHT -> ((previous?.first ?: value) to value)
            }
        }
        val bands = byFrequency.entries
            .sortedBy { it.key }
            .map { (frequency, thresholds) ->
                HearingBand(
                    frequencyHz = frequency,
                    leftThresholdDb = thresholds.first,
                    rightThresholdDb = thresholds.second,
                )
            }
        return HearingProfile(bands = bands, deviceLabel = deviceLabel, formFactor = formFactor)
    }

    /** How many answers a band needs before an unconfident estimate is still saved. */
    private val minResponsesForBestEffort: Int get() = 4

    private fun newStaircase() = ThresholdStaircase(
        startDb = START_DB,
        floorDb = FLOOR_DB,
        ceilingDb = CEILING_DB,
        stepDownDb = STEP_DOWN_DB,
        stepUpDb = STEP_UP_DB,
        reversalsNeeded = REVERSALS,
        maxResponses = maxResponsesPerBand,
    )

    /** One band/ear outcome, for the results list. */
    data class Reading(
        val ear: Ear,
        val frequencyHz: Double,
        val thresholdDb: Double?,
        val spreadDb: Double?,
        val confident: Boolean,
        val noResponse: Boolean,
        val abandoned: Boolean,
    ) {
        /** Words for the row: a number, or the reason there isn't one. */
        fun label(): String {
            val earName = if (ear == Ear.LEFT) "L" else "R"
            return when {
                abandoned -> "$band $earName \u00B7 skipped"
                noResponse -> "$band $earName \u00B7 never heard"
                thresholdDb == null -> "$band $earName \u00B7 no answer"
                !confident -> "$band \u00B7 $earName ${fmt(thresholdDb)} dB \u00B7 rough"
                else -> "$band \u00B7 $earName ${fmt(thresholdDb)} dB"
            }
        }

        /** 250 and 8000 are not "0.25k" and "8k" to a listener reading a table. */
        private val band: String
            get() = if (frequencyHz >= 1_000.0) {
                "${(frequencyHz / 1000.0)}".removeSuffix(".0") + " kHz"
            } else {
                "${frequencyHz.toInt()} Hz"
            }

        private fun fmt(value: Double?): String = value?.let { "%.1f".format(it) } ?: "n/a"
    }

    companion object {

        /** The test's level domain is dB below full scale, not dB SPL: see [HearingProfile]. */
        const val START_DB = -26.0
        const val FLOOR_DB = -64.0
        const val CEILING_DB = -8.0
        const val STEP_DOWN_DB = 2.0
        const val STEP_UP_DB = 1.0
        const val REVERSALS = 3

        /**
         * Reference frequency first, then ascending, both ears alternating per
         * band. The 1 kHz band sets the scale everything else is compared to, so
         * it must be measured before the ear's sensitivity drifts across a long
         * run; alternating ears keeps a loud judgement on one side from
         * anchoring the next on the other.
         */
        val DEFAULT_PLAN: List<Target> = run {
            val ordered = listOf(HearingProfile.REFERENCE_HZ) +
                HearingProfile.FREQUENCIES.filter { it != HearingProfile.REFERENCE_HZ }.sorted()
            ordered.flatMap { frequency -> listOf(Target(Ear.LEFT, frequency), Target(Ear.RIGHT, frequency)) }
        }

        /** Half the run, for the progress bar in the dialog. */
        fun progressOf(index: Int, total: Int): Float =
            if (total <= 0) 0f else (index.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }
}
