package com.lastwave.app.data.repository

import java.util.Calendar

/**
 * ────────────────────────────────────────────────────────────────────────────
 *  Wrapped aggregation — pure Kotlin, zero Android dependencies
 * ────────────────────────────────────────────────────────────────────────────
 * Kept separate from the repository so the interesting logic (streaks, busiest
 * hour windows, "day one" detection, empty-period handling) can be unit-tested
 * on the JVM instead of only on a device. The repository feeds these functions
 * plain rows; the UI only ever sees the finished [WrappedReport].
 */

/** Which slice of time a recap covers. */
sealed class WrappedPeriod {
    abstract val label: String
    abstract val storyTitle: String

    /** A calendar year, e.g. 2026. */
    data class Year(val year: Int) : WrappedPeriod() {
        override val label: String get() = year.toString()
        override val storyTitle: String get() = "$year Wrapped"
    }

    /** One calendar month (v5.5.0) — a recap does not have to wait for December. */
    data class Month(val year: Int, val month0: Int) : WrappedPeriod() {
        override val label: String get() = "${WrappedDates.MONTHS_SHORT[month0]} $year"
        override val storyTitle: String get() = "${WrappedDates.MONTHS_LONG[month0]}, wrapped"
    }

    /** The trailing 365 days, for people who want "the last year". */
    data object Last12Months : WrappedPeriod() {
        override val label: String get() = "Last 12 months"
        override val storyTitle: String get() = "Your year in sound"
    }

    /** Everything ever recorded — the fallback when a year is empty. */
    data object AllTime : WrappedPeriod() {
        override val label: String get() = "All time"
        override val storyTitle: String get() = "Your listening, so far"
    }
}

/** A resolved window: half-open [startMillis, endMillis). */
data class WrappedWindow(
    val period: WrappedPeriod,
    val startMillis: Long,
    val endMillis: Long,
) {
    companion object {
        private const val DAY_MS = 86_400_000L

        fun forPeriod(period: WrappedPeriod, nowMillis: Long, timeZone: java.util.TimeZone = java.util.TimeZone.getDefault()): WrappedWindow {
            val cal = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
            return when (period) {
                is WrappedPeriod.Year -> {
                    val start = Calendar.getInstance(timeZone).apply {
                        clear()
                        set(period.year, Calendar.JANUARY, 1, 0, 0, 0)
                    }.timeInMillis
                    val end = Calendar.getInstance(timeZone).apply {
                        clear()
                        set(period.year + 1, Calendar.JANUARY, 1, 0, 0, 0)
                    }.timeInMillis
                    // A recap for a year that has not happened yet is clamped.
                    WrappedWindow(period, start, minOf(end, nowMillis + 1))
                }

                is WrappedPeriod.Month -> {
                    val start = Calendar.getInstance(timeZone).apply {
                        clear()
                        set(period.year, period.month0, 1, 0, 0, 0)
                    }.timeInMillis
                    val end = Calendar.getInstance(timeZone).apply {
                        clear()
                        set(period.year, period.month0 + 1, 1, 0, 0, 0)
                    }.timeInMillis
                    WrappedWindow(period, start, minOf(end, nowMillis + 1))
                }

                WrappedPeriod.Last12Months ->
                    WrappedWindow(period, nowMillis - 365L * DAY_MS, nowMillis + DAY_MS)

                WrappedPeriod.AllTime ->
                    WrappedWindow(period, 0L, nowMillis + DAY_MS)
            }
        }

        /**
         * The period to compare against: the year or month before. Rolling
         * and all-time recaps have nothing meaningful to compare with.
         */
        fun previous(
            period: WrappedPeriod,
            nowMillis: Long,
            timeZone: java.util.TimeZone = java.util.TimeZone.getDefault(),
        ): WrappedWindow? = when (period) {
            is WrappedPeriod.Year -> forPeriod(WrappedPeriod.Year(period.year - 1), nowMillis, timeZone)
            is WrappedPeriod.Month -> {
                val total = period.year * 12 + period.month0 - 1
                forPeriod(
                    WrappedPeriod.Month(Math.floorDiv(total, 12), Math.floorMod(total, 12)),
                    nowMillis,
                    timeZone,
                )
            }
            else -> null
        }

        /** Every year that has at least one second of data, newest first. */
        fun yearsWithData(
            firstListenAtMillis: Long?,
            nowMillis: Long,
            timeZone: java.util.TimeZone = java.util.TimeZone.getDefault(),
        ): List<Int> {
            val first = firstListenAtMillis ?: return emptyList()
            if (first <= 0L) return emptyList()
            val firstYear = Calendar.getInstance(timeZone).apply { timeInMillis = first }.get(Calendar.YEAR)
            val nowYear = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }.get(Calendar.YEAR)
            if (firstYear > nowYear) return emptyList()
            return (nowYear downTo firstYear).toList()
        }
    }
}

/** One ranked entry in a top-5 list. */
data class WrappedEntry(
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
    val videoId: String?,
    val totalMs: Long,
    val plays: Int,
)

/** The finished recap the UI renders. Empty fields simply hide their card. */
data class WrappedReport(
    val window: WrappedWindow,
    val totalMinutes: Long,
    val totalPlays: Int,
    val distinctTracks: Int,
    val distinctArtists: Int,
    val activeDays: Int,
    val topTracks: List<WrappedEntry>,
    val topArtists: List<WrappedEntry>,
    val topAlbums: List<WrappedEntry>,
    /** Minutes per local hour, index = hour 0..23; empty when unknown. */
    val minutesByHour: List<Long>,
    val longestStreakDays: Int,
    val currentStreakDays: Int,
    val topTrackSharePercent: Int,
    /** "Night owl" / "Morning person" / "All-day listener". */
    val listenerPersonality: String,
    val personalityLine: String,
    /** Reserved for a "biggest day" card; null until that query exists. */
    val busiestDayMillis: Long?,
    val firstListenAtMillis: Long?,
    val lastListenAtMillis: Long?,
    /** v5.5.0: calendar, rhythm, habits, archetype and badges. */
    val insights: WrappedInsights = WrappedInsights(),
) {
    val isEmpty: Boolean get() = totalPlays == 0 || totalMinutes <= 0L
    val totalHours: Long get() = totalMinutes / 60L
}

object WrappedCalculator {

    private const val DAY_MS = 86_400_000L

    /** Human personality buckets, tuned by listening clock. */
    fun personality(minutesByHour: List<Long>): Pair<String, String> {
        val total = minutesByHour.sum()
        if (total <= 0L) return "Explorer" to "You listen wherever the day takes you."
        fun window(from: Int, toExclusive: Int): Long {
            var sum = 0L
            for (h in from until toExclusive) sum += minutesByHour.getOrElse(h) { 0L }
            return sum
        }
        val night = window(22, 24) + window(0, 5)
        val morning = window(5, 12)
        val day = window(12, 18)
        val evening = window(18, 22)
        val dominant = listOf(
            "Night owl" to night,
            "Morning person" to morning,
            "Afternoon driver" to day,
            "Evening listener" to evening,
        ).maxBy { it.second }
        val pct = (dominant.second * 100.0 / total).toInt()
        val line = when (dominant.first) {
            "Night owl" -> "Most of your music lands after 10pm. The night is yours."
            "Morning person" -> "You start the day with sound — most listens land before noon."
            "Afternoon driver" -> "Your afternoons have a soundtrack. Midday is your zone."
            else -> "You wind down with music — most of it lands in the evening."
        }
        return if (pct < 34) {
            // No single window dominates: the listener spreads it out.
            "All-day listener" to "Your listening is spread evenly from morning to midnight."
        } else {
            dominant.first to line
        }
    }

    /**
     * Longest run of consecutive calendar days with at least one listen, plus
     * the run that is still alive (ending today or yesterday).
     */
    fun streaks(listenedDays: List<Long>, todayDayIndex: Long): Pair<Int, Int> {
        if (listenedDays.isEmpty()) return 0 to 0
        val days = listenedDays.distinct().sorted()
        var longest = 1
        var run = 1
        for (i in 1 until days.size) {
            run = if (days[i] - days[i - 1] == 1L) run + 1 else 1
            if (run > longest) longest = run
        }
        // Current streak: walk back from today (or yesterday, since a streak is
        // still alive until the day after tomorrow starts).
        val set = days.toHashSet()
        var current = 0
        var cursor = if (set.contains(todayDayIndex)) todayDayIndex else todayDayIndex - 1
        if (!set.contains(cursor)) return longest to 0
        while (set.contains(cursor)) {
            current++
            cursor--
        }
        return longest to current
    }

    /** Calendar-day index in the listener's zone (floor, so pre-1970 is safe). */
    fun localDayIndex(millis: Long, utcOffsetMillis: Long): Long =
        Math.floorDiv(millis + utcOffsetMillis, DAY_MS)

    /** Share of total time taken by the #1 track, for the "obsession" card. */
    fun topSharePercent(topMs: Long, totalMs: Long): Int {
        if (totalMs <= 0L) return 0
        return ((topMs * 100.0) / totalMs).toInt().coerceIn(0, 100)
    }

    fun toEntries(
        tracks: List<com.lastwave.app.data.local.db.TrackAggregate>,
    ): List<WrappedEntry> = tracks.map {
        WrappedEntry(
            title = it.title,
            subtitle = it.artist,
            artworkUrl = it.artworkUrl,
            videoId = it.videoId,
            totalMs = it.totalMs,
            plays = it.plays,
        )
    }

    fun toArtistEntries(
        artists: List<com.lastwave.app.data.local.db.ArtistAggregate>,
    ): List<WrappedEntry> = artists.map {
        WrappedEntry(
            title = it.artist,
            subtitle = "${it.plays} plays",
            artworkUrl = it.artworkUrl,
            videoId = null,
            totalMs = it.totalMs,
            plays = it.plays,
        )
    }

    fun toAlbumEntries(
        albums: List<com.lastwave.app.data.local.db.AlbumAggregate>,
    ): List<WrappedEntry> = albums.map {
        WrappedEntry(
            title = it.album,
            subtitle = it.artist,
            artworkUrl = it.artworkUrl,
            videoId = null,
            totalMs = it.totalMs,
            plays = 0,
        )
    }

    /** Expands sparse hour rows into a dense 24-slot minute array. */
    fun hourMinutes(hours: List<com.lastwave.app.data.local.db.HourAggregate>): List<Long> {
        val out = LongArray(24)
        for (row in hours) {
            if (row.hourOfDay in 0..23) out[row.hourOfDay] = row.totalMs / 60_000L
        }
        return out.toList()
    }

    fun formatDuration(minutes: Long): String {
        if (minutes < 60L) return "$minutes min"
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0L) "$h hr" else "$h hr $m min"
    }

    /** "Day one" — the first listen in the window was also the user's first ever. */
    fun isDayOne(windowFirstMillis: Long?, allTimeFirstMillis: Long?): Boolean {
        if (windowFirstMillis == null || allTimeFirstMillis == null) return false
        return kotlin.math.abs(windowFirstMillis - allTimeFirstMillis) < DAY_MS
    }
}
