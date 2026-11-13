package com.lastwave.app.data.repository

import java.util.Calendar
import java.util.TimeZone

/**
 * ────────────────────────────────────────────────────────────────────────────
 *  Wrapped insights (v5.5.0) — pure Kotlin, zero Android dependencies
 * ────────────────────────────────────────────────────────────────────────────
 * Everything beyond the headline totals: the day-by-day calendar, weekday and
 * month rhythm, habits, the listener archetype and badges. As with
 * [WrappedCalculator], the repository only fetches rows; the decisions about
 * what they *mean* live here so they can be unit-tested on the JVM.
 *
 * A "day index" throughout is a local calendar day counted from 1970-01-01,
 * i.e. `(utcMillis + zoneOffset) / 86_400_000`. Treating that index as a UTC
 * date gives back the listener's own year/month/day without needing their
 * time zone again.
 */

/** Calendar helpers that work on local day indexes. */
object WrappedDates {
    const val DAY_MS = 86_400_000L

    val MONTHS_SHORT = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val MONTHS_LONG = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    /** Monday-first, matching the calendar grid. */
    val WEEKDAYS_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val WEEKDAYS_LONG = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    fun dayIndexOf(year: Int, month0: Int, day: Int): Long {
        val millis = Calendar.getInstance(utc).apply {
            clear()
            set(year, month0, day)
        }.timeInMillis
        return Math.floorDiv(millis, DAY_MS)
    }

    /** (year, month 0..11, day of month) for a local day index. */
    fun ymd(dayIndex: Long): Triple<Int, Int, Int> {
        val c = Calendar.getInstance(utc).apply { timeInMillis = dayIndex * DAY_MS }
        return Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
    }

    /** 0 = Monday … 6 = Sunday. (1970-01-01 was a Thursday.) */
    fun weekdayOf(dayIndex: Long): Int = Math.floorMod(dayIndex + 3L, 7L).toInt()

    /** "3 Mar 2026" */
    fun label(dayIndex: Long): String {
        val (y, m, d) = ymd(dayIndex)
        return "$d ${MONTHS_SHORT[m]} $y"
    }

    /** "3 Mar" */
    fun shortLabel(dayIndex: Long): String {
        val (_, m, d) = ymd(dayIndex)
        return "$d ${MONTHS_SHORT[m]}"
    }

    fun monthGrid(year: Int, month0: Int): MonthGrid {
        val first = dayIndexOf(year, month0, 1)
        val next = if (month0 == 11) dayIndexOf(year + 1, 0, 1) else dayIndexOf(year, month0 + 1, 1)
        return MonthGrid(
            year = year,
            month0 = month0,
            firstDayIndex = first,
            daysInMonth = (next - first).toInt(),
            leadingBlanks = weekdayOf(first),
        )
    }

    /**
     * The months a recap's calendar should draw: the whole year for a year
     * recap, the single month for a month recap, otherwise the twelve months
     * ending with the current one.
     */
    fun monthsFor(period: WrappedPeriod, todayDayIndex: Long): List<MonthGrid> = when (period) {
        is WrappedPeriod.Year -> (0..11).map { monthGrid(period.year, it) }
        is WrappedPeriod.Month -> listOf(monthGrid(period.year, period.month0))
        else -> {
            val (y, m, _) = ymd(todayDayIndex)
            (11 downTo 0).map { back ->
                val total = y * 12 + m - back
                monthGrid(Math.floorDiv(total, 12), Math.floorMod(total, 12))
            }
        }
    }
}

/** One local calendar day that had listening in it. */
data class DayStat(
    val dayIndex: Long,
    /** Rounded up, so a day with any listening is never shown as empty. */
    val minutes: Long,
    val plays: Int,
)

/** Layout of one calendar month, Monday-first. */
data class MonthGrid(
    val year: Int,
    val month0: Int,
    val firstDayIndex: Long,
    val daysInMonth: Int,
    /** Empty cells before the 1st (0 when the month starts on a Monday). */
    val leadingBlanks: Int,
) {
    val shortLabel: String get() = WrappedDates.MONTHS_SHORT[month0]
    val lastDayIndex: Long get() = firstDayIndex + daysInMonth - 1
}

data class MonthStat(
    val grid: MonthGrid,
    val minutes: Long,
    val activeDays: Int,
)

data class BiggestDay(
    val dayIndex: Long,
    val minutes: Long,
    val plays: Int,
    val topTrack: String?,
    val topArtist: String?,
)

data class Binge(
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val dayIndex: Long,
    val plays: Int,
)

data class HabitTrack(
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val plays: Int,
    val skips: Int,
)

data class ArtistDetail(
    val name: String,
    val artworkUrl: String?,
    val minutes: Long,
    val plays: Int,
    val daysListened: Int,
    val topTrack: String?,
    val firstListenMillis: Long?,
    val sharePercent: Int,
)

data class FirstTrack(
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val playedAtMillis: Long,
)

/**
 * One axis of the archetype. [position] runs 0..1 from [left] to [right];
 * the half it lands in decides the letter.
 */
data class ArchetypeAxis(
    val left: String,
    val right: String,
    val leftLetter: Char,
    val rightLetter: Char,
    val position: Float,
) {
    val isRight: Boolean get() = position >= 0.5f
    val letter: Char get() = if (isRight) rightLetter else leftLetter
    val winner: String get() = if (isRight) right else left
}

data class Archetype(
    val name: String,
    /** Four letters, one per axis, e.g. "NLRF". */
    val code: String,
    val tagline: String,
    val axes: List<ArchetypeAxis>,
) {
    /** "N · L · R · F" */
    val spacedCode: String get() = code.toList().joinToString(" · ")

    companion object {
        val UNKNOWN = Archetype(
            name = "Explorer",
            code = "----",
            tagline = "Play a little more and this fills in.",
            axes = emptyList(),
        )
    }
}

data class Badge(
    val id: String,
    val title: String,
    val description: String,
    val progress: Long,
    val goal: Long,
) {
    val earned: Boolean get() = progress >= goal
    val fraction: Float get() = if (goal <= 0L) 1f else (progress.toFloat() / goal.toFloat()).coerceIn(0f, 1f)
}

/** Everything the richer recap needs beyond [WrappedReport]'s totals. */
data class WrappedInsights(
    val days: List<DayStat> = emptyList(),
    val months: List<MonthStat> = emptyList(),
    /** Minutes per weekday, Monday first. */
    val minutesByWeekday: List<Long> = List(7) { 0L },
    val biggestDay: BiggestDay? = null,
    val binge: Binge? = null,
    /** Share of plays that ran to the end; null when there are no plays. */
    val completionPercent: Int? = null,
    val mostSkipped: HabitTrack? = null,
    val neverSkipped: HabitTrack? = null,
    val nightAnthem: WrappedEntry? = null,
    val morningAnthem: WrappedEntry? = null,
    val topArtist: ArtistDetail? = null,
    val firstTrack: FirstTrack? = null,
    /**
     * Artists first heard in this period. Null when the listening log does not
     * reach back before the period started — in that case *every* artist would
     * look new, which would be a made-up number.
     */
    val newArtists: Int? = null,
    val topNewArtist: WrappedEntry? = null,
    val previousMinutes: Long? = null,
    val previousLabel: String? = null,
    /** True once the period is over, so comparing totals is fair. */
    val periodComplete: Boolean = false,
    /** First and last day index of the longest streak. */
    val longestStreak: LongRange? = null,
    val archetype: Archetype = Archetype.UNKNOWN,
    val badges: List<Badge> = emptyList(),
) {
    val maxDayMinutes: Long get() = days.maxOfOrNull { it.minutes } ?: 0L

    /** 0 = Monday … 6 = Sunday; null when nothing was played. */
    val topWeekday: Int?
        get() {
            val best = minutesByWeekday.indices.maxByOrNull { minutesByWeekday[it] } ?: return null
            return best.takeIf { minutesByWeekday[it] > 0L }
        }

    val badgesEarned: Int get() = badges.count { it.earned }

    fun minutesByDay(): Map<Long, Long> = days.associate { it.dayIndex to it.minutes }
}

object WrappedInsightsBuilder {

    fun weekdayMinutes(days: List<DayStat>): List<Long> {
        val out = LongArray(7)
        for (d in days) out[WrappedDates.weekdayOf(d.dayIndex)] += d.minutes
        return out.toList()
    }

    fun monthStats(grids: List<MonthGrid>, days: List<DayStat>): List<MonthStat> = grids.map { grid ->
        var minutes = 0L
        var active = 0
        for (d in days) {
            if (d.dayIndex in grid.firstDayIndex..grid.lastDayIndex) {
                minutes += d.minutes
                if (d.minutes > 0L) active++
            }
        }
        MonthStat(grid, minutes, active)
    }

    /** First and last day of the longest run of consecutive days. */
    fun longestStreakRange(dayIndexes: List<Long>): LongRange? {
        if (dayIndexes.isEmpty()) return null
        val days = dayIndexes.distinct().sorted()
        var bestStart = days[0]
        var bestEnd = days[0]
        var runStart = days[0]
        for (i in 1 until days.size) {
            if (days[i] - days[i - 1] != 1L) runStart = days[i]
            if (days[i] - runStart > bestEnd - bestStart) {
                bestStart = runStart
                bestEnd = days[i]
            }
        }
        return bestStart..bestEnd
    }

    /** Heatmap shade 0..4 for a day, relative to the busiest day. */
    fun intensity(minutes: Long, maxMinutes: Long): Int {
        if (minutes <= 0L || maxMinutes <= 0L) return 0
        val ratio = minutes.toDouble() / maxMinutes.toDouble()
        return when {
            ratio >= 0.75 -> 4
            ratio >= 0.5 -> 3
            ratio >= 0.25 -> 2
            else -> 1
        }
    }

    /**
     * Four independent readings of how someone listens:
     *
     *  - **Day / Night** — share of minutes between 20:00 and 05:00.
     *  - **Explorer / Loyalist** — how much of the time the top five artists take.
     *  - **Wanderer / Repeater** — average plays per distinct track.
     *  - **Skipper / Finisher** — how often a started song is finished.
     */
    fun archetype(
        minutesByHour: List<Long>,
        topArtistsShare: Float,
        playsPerTrack: Float,
        completionPercent: Int?,
    ): Archetype {
        val total = minutesByHour.sum()
        if (total <= 0L) return Archetype.UNKNOWN

        var night = 0L
        for (h in 0 until 24) {
            if (h >= 20 || h < 5) night += minutesByHour.getOrElse(h) { 0L }
        }
        val clock = ArchetypeAxis("Day", "Night", 'D', 'N', (night.toFloat() / total.toFloat()).coerceIn(0f, 1f))
        val variety = ArchetypeAxis("Explorer", "Loyalist", 'E', 'L', topArtistsShare.coerceIn(0f, 1f))
        // 1 play per track sits fully left; 5 or more sits fully right.
        val repeat = ArchetypeAxis("Wanderer", "Repeater", 'W', 'R', ((playsPerTrack - 1f) / 4f).coerceIn(0f, 1f))
        // 40% finished sits fully left, 90% fully right; 65% is the midpoint.
        val finished = (completionPercent ?: 65).toFloat()
        val patience = ArchetypeAxis("Skipper", "Finisher", 'S', 'F', ((finished - 40f) / 50f).coerceIn(0f, 1f))

        val prefix = if (clock.isRight) "Midnight" else "Daylight"
        val (noun, nounLine) = when {
            variety.isRight && repeat.isRight -> "Devotee" to "A few artists, played again and again."
            variety.isRight -> "Curator" to "A tight circle of artists, but rarely the same song twice."
            repeat.isRight -> "Collector" to "Wide taste, with favourites you keep coming back to."
            else -> "Nomad" to "Always on to the next artist and the next song."
        }
        val patienceLine = if (patience.isRight) "You let songs play out." else "You skip until it feels right."
        val axes = listOf(clock, variety, repeat, patience)
        return Archetype(
            name = "$prefix $noun",
            code = axes.map { it.letter }.joinToString(""),
            tagline = "$nounLine $patienceLine",
            axes = axes,
        )
    }

    fun badges(
        totalMinutes: Long,
        totalPlays: Int,
        distinctArtists: Int,
        longestStreakDays: Int,
        maxPlaysOfOneTrack: Int,
        minutesByHour: List<Long>,
        biggestDayMinutes: Long,
        bingePlays: Int,
        weekdaysActive: Int,
    ): List<Badge> {
        fun hours(from: Int, toExclusive: Int): Long {
            var sum = 0L
            for (h in from until toExclusive) sum += minutesByHour.getOrElse(h) { 0L }
            return sum
        }
        return listOf(
            Badge("first_spin", "First spin", "Play your first track", totalPlays.toLong(), 1),
            Badge("hour_one", "Hour one", "Listen for 60 minutes", totalMinutes, 60),
            Badge("full_day", "Full day", "24 hours of listening", totalMinutes, 1_440),
            Badge("marathon", "Marathon", "3 hours in a single day", biggestDayMinutes, 180),
            Badge("week_streak", "Seven straight", "Listen 7 days in a row", longestStreakDays.toLong(), 7),
            Badge("month_streak", "Thirty straight", "Listen 30 days in a row", longestStreakDays.toLong(), 30),
            Badge("all_week", "Every weekday", "Listen on all 7 days of the week", weekdaysActive.toLong(), 7),
            Badge("on_loop", "On loop", "One track 10 times in a day", bingePlays.toLong(), 10),
            Badge("fifty_spins", "Fifty spins", "Play one track 50 times", maxPlaysOfOneTrack.toLong(), 50),
            Badge("wide_ears", "Wide ears", "Listen to 50 different artists", distinctArtists.toLong(), 50),
            Badge("night_shift", "Night shift", "2 hours between midnight and 5 am", hours(0, 5), 120),
            Badge("early_bird", "Early bird", "2 hours between 5 and 8 am", hours(5, 8), 120),
        )
    }

    /** "1.4 days", "9 hours" — how long the listening would last end to end. */
    fun nonStop(totalMinutes: Long): String {
        if (totalMinutes < 60L) return "$totalMinutes min"
        if (totalMinutes < 1_440L) {
            val h = totalMinutes / 60
            return if (h == 1L) "1 hour" else "$h hours"
        }
        val tenths = totalMinutes * 10 / 1_440
        return "${tenths / 10}.${tenths % 10} days"
    }
}
