package com.lastwave.app.data.hearing

import com.lastwave.app.data.autoeq.PeqFitter
import com.lastwave.app.data.autoeq.PeqMath
import com.lastwave.app.data.autoeq.PeqSettings

/**
 * Where the hearing curve meets the headphone correction.
 *
 * Kept out of [com.lastwave.app.data.autoeq.AutoEqController] so the arithmetic
 * can be tested on a JVM: the controller around it is Context, DataStore and a
 * native handle, none of which a test can stand up here.
 *
 * Two rules, both load-bearing:
 *
 *  - The curves are summed *before* the fit, and the app's existing
 *    −15…+12 dB window is applied to the sum. Clipping the result afterwards
 *    would leave a fitted curve that no longer matches either input. The cap is
 *    what keeps a +6 dB ear correction on top of a +9 dB bass lift from turning
 *    into a request the limiter has to eat.
 *  - A hand-edited profile is not refitted. There the hearing peaks are
 *    appended and, if the 20-slot native budget is full, the *weakest* of them
 *    are dropped: losing 1 dB of treble correction is a worse outcome than
 *    silently changing numbers the user typed themselves.
 */
object HearingBlender {

    /** The window AutoEq has always coerced its target to. */
    const val MIN_DB = -15.0
    const val MAX_DB = 12.0

    /**
     * Every contribution sampled on [grid], coerced into [MIN_DB]…[MAX_DB].
     *
     * @param base the profile already in the chain, fitted or hand-written; null
     *   when there is none, which is the "hearing correction only" case.
     * @param delta per-grid-point target-shape adjustment, or null.
     * @param hearingCurve the ear correction from [HearingProfile.curve], or null.
     */
    fun desiredCurve(
        base: PeqSettings?,
        delta: List<Double>? = null,
        bassDb: Double = 0.0,
        tiltDbPerOctave: Double = 0.0,
        hearingCurve: DoubleArray? = null,
        grid: DoubleArray = PeqMath.LOG_GRID,
    ): DoubleArray = DoubleArray(grid.size) { i ->
        val frequency = grid[i]
        val value = (base?.let { PeqMath.filtersDb(it.filters, frequency) } ?: 0.0) +
            (delta?.getOrNull(i) ?: 0.0) +
            PeqMath.bassShelfDb(bassDb, frequency) +
            PeqMath.tiltDb(tiltDbPerOctave, frequency) +
            (hearingCurve?.getOrNull(i) ?: 0.0)
        value.coerceIn(MIN_DB, MAX_DB)
    }

    /** One least-squares pass over the summed curve, preamp included. */
    fun fit(desired: DoubleArray, grid: DoubleArray = PeqMath.LOG_GRID): PeqSettings = PeqFitter.fit(desired, grid)

    /**
     * Manual filters plus the hearing peaks, inside the native filter budget.
     *
     * The preamp is recomputed for the whole list, because a second stack of
     * boosts can push the combined peak over 0 dBFS even when each half looked
     * safe on its own.
     */
    fun appendHearing(auto: PeqSettings, hearing: PeqSettings, limit: Int = PeqSettings.MAX_FILTERS): PeqSettings {
        val room = limit - auto.filters.count { it.enabled }
        if (room <= 0) return auto
        val extra = hearing.filters.filter { it.enabled }
            .sortedByDescending { kotlin.math.abs(it.gainDb) }
            .take(room)
        if (extra.isEmpty()) return auto
        val filters = (auto.filters + extra).take(limit)
        return PeqSettings(preampDb = PeqMath.autoPreampDb(filters, PeqMath.LOG_GRID), filters = filters)
    }
}
