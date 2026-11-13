package com.lastwave.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.local.db.HourAggregate
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class WrappedCalculatorTest {

    private val ist: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")
    private val istOffset = 19_800_000L // +05:30

    private fun istMillis(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance(ist).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis

    // ── Streaks ─────────────────────────────────────────────────────────────

    @Test
    fun `no listening means no streak`() {
        assertThat(WrappedCalculator.streaks(emptyList(), 100L)).isEqualTo(0 to 0)
    }

    @Test
    fun `longest run is found even when it is not the latest`() {
        val days = listOf(10L, 11L, 12L, 13L, 20L, 21L)
        val (longest, current) = WrappedCalculator.streaks(days, todayDayIndex = 21L)
        assertThat(longest).isEqualTo(4)
        assertThat(current).isEqualTo(2)
    }

    @Test
    fun `streak survives until the end of the following day`() {
        val days = listOf(5L, 6L, 7L)
        // Today (8) has no listen yet — yesterday's run is still alive.
        assertThat(WrappedCalculator.streaks(days, todayDayIndex = 8L).second).isEqualTo(3)
        // Two days on, it is broken.
        assertThat(WrappedCalculator.streaks(days, todayDayIndex = 9L).second).isEqualTo(0)
    }

    @Test
    fun `duplicate and unsorted days are tolerated`() {
        val days = listOf(3L, 1L, 2L, 2L, 3L)
        assertThat(WrappedCalculator.streaks(days, todayDayIndex = 3L)).isEqualTo(3 to 3)
    }

    // ── Local-day bucketing ─────────────────────────────────────────────────

    @Test
    fun `late night listen in IST counts for the local day not the UTC one`() {
        // 02:00 IST on 12 Nov is still 11 Nov in UTC.
        val lateNight = istMillis(2026, Calendar.NOVEMBER, 12, hour = 2)
        val sameEvening = istMillis(2026, Calendar.NOVEMBER, 12, hour = 21)
        val a = WrappedCalculator.localDayIndex(lateNight, istOffset)
        val b = WrappedCalculator.localDayIndex(sameEvening, istOffset)
        assertThat(a).isEqualTo(b)
        // Without the offset the two would land on different days — the bug this guards.
        assertThat(lateNight / 86_400_000L).isNotEqualTo(sameEvening / 86_400_000L)
    }

    @Test
    fun `local midnight starts a new day`() {
        val before = istMillis(2026, Calendar.NOVEMBER, 11, hour = 23, minute = 59)
        val after = istMillis(2026, Calendar.NOVEMBER, 12, hour = 0, minute = 1)
        assertThat(
            WrappedCalculator.localDayIndex(after, istOffset) -
                WrappedCalculator.localDayIndex(before, istOffset),
        ).isEqualTo(1L)
    }

    // ── Windows ─────────────────────────────────────────────────────────────

    @Test
    fun `year window is half open on local year boundaries`() {
        val now = istMillis(2027, Calendar.MARCH, 1)
        val w = WrappedWindow.forPeriod(WrappedPeriod.Year(2026), now, ist)
        assertThat(w.startMillis).isEqualTo(istMillis(2026, Calendar.JANUARY, 1))
        assertThat(w.endMillis).isEqualTo(istMillis(2027, Calendar.JANUARY, 1))
    }

    @Test
    fun `current year window is clamped to now`() {
        val now = istMillis(2026, Calendar.NOVEMBER, 12, hour = 9)
        val w = WrappedWindow.forPeriod(WrappedPeriod.Year(2026), now, ist)
        assertThat(w.endMillis).isEqualTo(now + 1)
        assertThat(w.startMillis).isLessThan(w.endMillis)
    }

    @Test
    fun `years with data run newest first and include the current year`() {
        val first = istMillis(2024, Calendar.JUNE, 3)
        val now = istMillis(2026, Calendar.NOVEMBER, 12)
        assertThat(WrappedWindow.yearsWithData(first, now, ist)).containsExactly(2026, 2025, 2024).inOrder()
        assertThat(WrappedWindow.yearsWithData(null, now, ist)).isEmpty()
    }

    // ── Personality ─────────────────────────────────────────────────────────

    private fun clock(vararg pairs: Pair<Int, Long>): List<Long> {
        val out = LongArray(24)
        pairs.forEach { (h, m) -> out[h] = m }
        return out.toList()
    }

    @Test
    fun `night listening wraps around midnight`() {
        val (name, _) = WrappedCalculator.personality(clock(23 to 120, 1 to 90, 14 to 30))
        assertThat(name).isEqualTo("Night owl")
    }

    @Test
    fun `mornings win when most minutes land before noon`() {
        val (name, _) = WrappedCalculator.personality(clock(7 to 200, 9 to 100, 20 to 40))
        assertThat(name).isEqualTo("Morning person")
    }

    @Test
    fun `evenly spread listening has no dominant window`() {
        val even = List(24) { 10L }
        assertThat(WrappedCalculator.personality(even).first).isEqualTo("All-day listener")
    }

    @Test
    fun `empty clock falls back gracefully`() {
        assertThat(WrappedCalculator.personality(emptyList()).first).isEqualTo("Explorer")
        assertThat(WrappedCalculator.personality(List(24) { 0L }).first).isEqualTo("Explorer")
    }

    // ── Small helpers ───────────────────────────────────────────────────────

    @Test
    fun `top share is bounded and safe on empty totals`() {
        assertThat(WrappedCalculator.topSharePercent(0, 0)).isEqualTo(0)
        assertThat(WrappedCalculator.topSharePercent(25, 100)).isEqualTo(25)
        assertThat(WrappedCalculator.topSharePercent(500, 100)).isEqualTo(100)
    }

    @Test
    fun `hour rows expand to a dense clock and ignore junk hours`() {
        val out = WrappedCalculator.hourMinutes(
            listOf(HourAggregate(0, 120_000), HourAggregate(23, 60_000), HourAggregate(42, 999_999)),
        )
        assertThat(out).hasSize(24)
        assertThat(out[0]).isEqualTo(2)
        assertThat(out[23]).isEqualTo(1)
        assertThat(out.sum()).isEqualTo(3)
    }

    @Test
    fun `durations read naturally`() {
        assertThat(WrappedCalculator.formatDuration(45)).isEqualTo("45 min")
        assertThat(WrappedCalculator.formatDuration(120)).isEqualTo("2 hr")
        assertThat(WrappedCalculator.formatDuration(135)).isEqualTo("2 hr 15 min")
    }
}
