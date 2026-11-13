package com.lastwave.app.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class WrappedInsightsTest {

    private val ist: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    private fun istMillis(year: Int, month: Int, day: Int, hour: Int = 0): Long =
        Calendar.getInstance(ist).apply {
            clear()
            set(year, month, day, hour, 0, 0)
        }.timeInMillis

    private fun day(year: Int, month0: Int, d: Int) = WrappedDates.dayIndexOf(year, month0, d)

    // ── Dates ───────────────────────────────────────────────────────────────

    @Test
    fun `day index round trips through year month day`() {
        val index = day(2026, Calendar.NOVEMBER, 12)
        assertThat(index).isEqualTo(20_769L)
        assertThat(WrappedDates.ymd(index)).isEqualTo(Triple(2026, Calendar.NOVEMBER, 12))
        assertThat(WrappedDates.label(index)).isEqualTo("12 Nov 2026")
    }

    @Test
    fun `weekdays are monday first`() {
        // 12 Nov 2026 is a Thursday; 1 Jan 1970 was too.
        assertThat(WrappedDates.weekdayOf(day(2026, Calendar.NOVEMBER, 12))).isEqualTo(3)
        assertThat(WrappedDates.weekdayOf(0L)).isEqualTo(3)
        assertThat(WrappedDates.weekdayOf(day(2026, Calendar.NOVEMBER, 15))).isEqualTo(6) // Sunday
        assertThat(WrappedDates.weekdayOf(day(2026, Calendar.NOVEMBER, 16))).isEqualTo(0) // Monday
    }

    @Test
    fun `month grid knows leap years and where the month starts`() {
        val feb = WrappedDates.monthGrid(2024, Calendar.FEBRUARY)
        assertThat(feb.daysInMonth).isEqualTo(29)
        assertThat(feb.leadingBlanks).isEqualTo(3) // 1 Feb 2024 was a Thursday
        val dec = WrappedDates.monthGrid(2026, Calendar.DECEMBER)
        assertThat(dec.daysInMonth).isEqualTo(31)
        assertThat(dec.lastDayIndex).isEqualTo(day(2026, Calendar.DECEMBER, 31))
    }

    @Test
    fun `calendar months follow the period`() {
        val today = day(2026, Calendar.FEBRUARY, 10)
        val year = WrappedDates.monthsFor(WrappedPeriod.Year(2025), today)
        assertThat(year).hasSize(12)
        assertThat(year.first().year to year.first().month0).isEqualTo(2025 to 0)

        val month = WrappedDates.monthsFor(WrappedPeriod.Month(2026, 1), today)
        assertThat(month).hasSize(1)

        // Rolling recaps end on the current month and reach back across the year boundary.
        val rolling = WrappedDates.monthsFor(WrappedPeriod.AllTime, today)
        assertThat(rolling).hasSize(12)
        assertThat(rolling.first().year to rolling.first().month0).isEqualTo(2025 to 2)
        assertThat(rolling.last().year to rolling.last().month0).isEqualTo(2026 to 1)
    }

    // ── Windows ─────────────────────────────────────────────────────────────

    @Test
    fun `month window covers exactly one local month`() {
        val now = istMillis(2027, Calendar.JANUARY, 20)
        val w = WrappedWindow.forPeriod(WrappedPeriod.Month(2026, Calendar.DECEMBER), now, ist)
        assertThat(w.startMillis).isEqualTo(istMillis(2026, Calendar.DECEMBER, 1))
        assertThat(w.endMillis).isEqualTo(istMillis(2027, Calendar.JANUARY, 1))
    }

    @Test
    fun `previous period steps back a year or a month and nothing else`() {
        val now = istMillis(2026, Calendar.NOVEMBER, 12)
        assertThat(WrappedWindow.previous(WrappedPeriod.Year(2026), now, ist)?.period)
            .isEqualTo(WrappedPeriod.Year(2025))
        assertThat(WrappedWindow.previous(WrappedPeriod.Month(2026, Calendar.JANUARY), now, ist)?.period)
            .isEqualTo(WrappedPeriod.Month(2025, Calendar.DECEMBER))
        assertThat(WrappedWindow.previous(WrappedPeriod.AllTime, now, ist)).isNull()
        assertThat(WrappedWindow.previous(WrappedPeriod.Last12Months, now, ist)).isNull()
    }

    // ── Rhythm ──────────────────────────────────────────────────────────────

    @Test
    fun `weekday and month totals add up`() {
        val days = listOf(
            DayStat(day(2026, Calendar.NOVEMBER, 14), 30, 5), // Saturday
            DayStat(day(2026, Calendar.NOVEMBER, 21), 45, 7), // Saturday
            DayStat(day(2026, Calendar.NOVEMBER, 16), 10, 2), // Monday
            DayStat(day(2026, Calendar.OCTOBER, 31), 20, 3),  // Saturday, previous month
        )
        val weekdays = WrappedInsightsBuilder.weekdayMinutes(days)
        assertThat(weekdays[5]).isEqualTo(95)
        assertThat(weekdays[0]).isEqualTo(10)
        assertThat(weekdays.sum()).isEqualTo(105)
        assertThat(WrappedInsights(minutesByWeekday = weekdays).topWeekday).isEqualTo(5)

        val months = WrappedInsightsBuilder.monthStats(
            listOf(WrappedDates.monthGrid(2026, Calendar.OCTOBER), WrappedDates.monthGrid(2026, Calendar.NOVEMBER)),
            days,
        )
        assertThat(months.map { it.minutes }).containsExactly(20L, 85L).inOrder()
        assertThat(months.map { it.activeDays }).containsExactly(1, 3).inOrder()
    }

    @Test
    fun `no listening has no favourite weekday`() {
        assertThat(WrappedInsights().topWeekday).isNull()
        assertThat(WrappedInsights().maxDayMinutes).isEqualTo(0)
    }

    @Test
    fun `longest streak range matches the streak length`() {
        val days = listOf(10L, 11L, 12L, 13L, 20L, 21L, 30L)
        assertThat(WrappedInsightsBuilder.longestStreakRange(days)).isEqualTo(10L..13L)
        assertThat(WrappedCalculator.streaks(days, 30L).first).isEqualTo(4)
        assertThat(WrappedInsightsBuilder.longestStreakRange(listOf(7L))).isEqualTo(7L..7L)
        assertThat(WrappedInsightsBuilder.longestStreakRange(emptyList())).isNull()
    }

    @Test
    fun `heatmap shade scales with the busiest day`() {
        assertThat(WrappedInsightsBuilder.intensity(0, 100)).isEqualTo(0)
        assertThat(WrappedInsightsBuilder.intensity(1, 100)).isEqualTo(1)
        assertThat(WrappedInsightsBuilder.intensity(30, 100)).isEqualTo(2)
        assertThat(WrappedInsightsBuilder.intensity(60, 100)).isEqualTo(3)
        assertThat(WrappedInsightsBuilder.intensity(100, 100)).isEqualTo(4)
        assertThat(WrappedInsightsBuilder.intensity(5, 0)).isEqualTo(0)
    }

    // ── Archetype ───────────────────────────────────────────────────────────

    private fun clock(vararg pairs: Pair<Int, Long>): List<Long> {
        val out = LongArray(24)
        pairs.forEach { (h, m) -> out[h] = m }
        return out.toList()
    }

    @Test
    fun `late night loyal repeat listener who finishes songs`() {
        val a = WrappedInsightsBuilder.archetype(
            minutesByHour = clock(23 to 200, 1 to 100, 14 to 50),
            topArtistsShare = 0.8f,
            playsPerTrack = 6f,
            completionPercent = 90,
        )
        assertThat(a.code).isEqualTo("NLRF")
        assertThat(a.name).isEqualTo("Midnight Devotee")
        assertThat(a.spacedCode).isEqualTo("N · L · R · F")
        assertThat(a.axes).hasSize(4)
    }

    @Test
    fun `daytime explorer who skips around`() {
        val a = WrappedInsightsBuilder.archetype(
            minutesByHour = clock(9 to 300, 15 to 200, 22 to 20),
            topArtistsShare = 0.2f,
            playsPerTrack = 1.2f,
            completionPercent = 45,
        )
        assertThat(a.code).isEqualTo("DEWS")
        assertThat(a.name).isEqualTo("Daylight Nomad")
    }

    @Test
    fun `archetype needs some listening`() {
        val a = WrappedInsightsBuilder.archetype(List(24) { 0L }, 0.5f, 2f, null)
        assertThat(a).isEqualTo(Archetype.UNKNOWN)
    }

    // ── Badges ──────────────────────────────────────────────────────────────

    @Test
    fun `one short song earns exactly the first badge`() {
        val badges = WrappedInsightsBuilder.badges(
            totalMinutes = 3, totalPlays = 1, distinctArtists = 1, longestStreakDays = 1,
            maxPlaysOfOneTrack = 1, minutesByHour = clock(14 to 3), biggestDayMinutes = 3,
            bingePlays = 1, weekdaysActive = 1,
        )
        assertThat(badges.filter { it.earned }.map { it.id }).containsExactly("first_spin")
        assertThat(badges.first { it.id == "hour_one" }.fraction).isWithin(0.001f).of(0.05f)
    }

    @Test
    fun `heavy listening unlocks the long haul badges`() {
        val badges = WrappedInsightsBuilder.badges(
            totalMinutes = 5_000, totalPlays = 1_200, distinctArtists = 80, longestStreakDays = 31,
            maxPlaysOfOneTrack = 64, minutesByHour = clock(1 to 150, 6 to 30), biggestDayMinutes = 200,
            bingePlays = 12, weekdaysActive = 7,
        )
        val earned = badges.filter { it.earned }.map { it.id }
        assertThat(earned).containsAtLeast(
            "full_day", "marathon", "week_streak", "month_streak", "all_week",
            "on_loop", "fifty_spins", "wide_ears", "night_shift",
        )
        assertThat(earned).doesNotContain("early_bird")
        assertThat(badges.all { it.fraction in 0f..1f }).isTrue()
    }

    @Test
    fun `non stop duration reads naturally`() {
        assertThat(WrappedInsightsBuilder.nonStop(45)).isEqualTo("45 min")
        assertThat(WrappedInsightsBuilder.nonStop(60)).isEqualTo("1 hour")
        assertThat(WrappedInsightsBuilder.nonStop(600)).isEqualTo("10 hours")
        assertThat(WrappedInsightsBuilder.nonStop(2_160)).isEqualTo("1.5 days")
    }
}
