package com.lastwave.app.ui.wrapped

import com.lastwave.app.data.repository.ArtistDetail
import com.lastwave.app.data.repository.BiggestDay
import com.lastwave.app.data.repository.Binge
import com.lastwave.app.data.repository.DayStat
import com.lastwave.app.data.repository.FirstTrack
import com.lastwave.app.data.repository.HabitTrack
import com.lastwave.app.data.repository.WrappedDates
import com.lastwave.app.data.repository.WrappedEntry
import com.lastwave.app.data.repository.WrappedInsights
import com.lastwave.app.data.repository.WrappedInsightsBuilder
import com.lastwave.app.data.repository.WrappedPeriod
import com.lastwave.app.data.repository.WrappedReport
import com.lastwave.app.data.repository.WrappedWindow

/** Made-up but realistic recaps for drawing the story without a device. */
internal object WrappedSamples {

    val HOURS = listOf<Long>(
        310, 180, 60, 10, 0, 0, 20, 90, 160, 140, 120, 150,
        210, 190, 170, 220, 260, 300, 340, 420, 520, 610, 560, 430,
    )

    private fun entry(title: String, subtitle: String, minutes: Long, plays: Int, art: String?) =
        WrappedEntry(title, subtitle, art, null, minutes * 60_000L, plays)

    /** A full year (or one month) of uneven listening. [art] maps a slot 0..5 to an image path. */
    fun full(period: WrappedPeriod, dayCount: Int, art: (Int) -> String? = { null }): WrappedReport {
        val today = WrappedDates.dayIndexOf(2026, 10, 12)
        val first = when (period) {
            is WrappedPeriod.Month -> WrappedDates.dayIndexOf(period.year, period.month0, 1)
            else -> WrappedDates.dayIndexOf(2026, 0, 1)
        }
        val days = (0 until dayCount).mapNotNull { i ->
            val seed = (i * 37 + 11) % 97
            if (seed % 5 == 0) null else DayStat(first + i, 8L + (seed * seed) % 190, 2 + seed % 30)
        }.filter { it.dayIndex <= today }
        val totalMinutes = days.sumOf { it.minutes }
        val totalPlays = days.sumOf { it.plays }
        val busiest = days.maxBy { it.minutes }
        val streak = WrappedInsightsBuilder.longestStreakRange(days.map { it.dayIndex })
        val streakDays = streak?.let { (it.last - it.first + 1).toInt() } ?: 0
        val weekdays = WrappedInsightsBuilder.weekdayMinutes(days)
        val tracks = listOf(
            entry("Tum Hi Ho", "Arijit Singh", 412, 96, art(0)),
            entry("A very long track title that has to be clipped somewhere", "An equally long artist name", 388, 81, art(1)),
            entry("Kesariya", "Arijit Singh, Pritam", 301, 70, art(2)),
            entry("Blinding Lights", "The Weeknd", 254, 66, art(3)),
            entry("Heat Waves", "Glass Animals", 201, 51, art(4)),
        )
        val artists = listOf(
            entry("Arijit Singh", "702 plays", 2140, 702, art(0)),
            entry("Pritam", "410 plays", 1320, 410, art(1)),
            entry("The Weeknd", "288 plays", 904, 288, art(2)),
            entry("A. R. Rahman", "240 plays", 811, 240, art(3)),
            entry("Glass Animals", "122 plays", 402, 122, art(4)),
        )
        val insights = WrappedInsights(
            days = days,
            months = WrappedInsightsBuilder.monthStats(WrappedDates.monthsFor(period, today), days),
            minutesByWeekday = weekdays,
            biggestDay = BiggestDay(busiest.dayIndex, busiest.minutes, busiest.plays, "Tum Hi Ho", "Arijit Singh"),
            binge = Binge("Kesariya", "Arijit Singh, Pritam", art(2), busiest.dayIndex, 12),
            completionPercent = 81,
            mostSkipped = HabitTrack("Heat Waves", "Glass Animals", art(4), 51, 29),
            neverSkipped = HabitTrack("Tum Hi Ho", "Arijit Singh", art(0), 96, 0),
            nightAnthem = tracks[3],
            morningAnthem = tracks[2],
            topArtist = ArtistDetail("Arijit Singh", art(0), 2140, 702, 188, "Tum Hi Ho", null, 9),
            firstTrack = FirstTrack("Channa Mereya", "Arijit Singh", art(5), 1_767_240_000_000L),
            newArtists = 58,
            topNewArtist = artists[4],
            previousMinutes = (totalMinutes * 0.8).toLong(),
            previousLabel = "2025",
            periodComplete = true,
            longestStreak = streak,
            archetype = WrappedInsightsBuilder.archetype(HOURS, 0.62f, 3.4f, 81),
            badges = WrappedInsightsBuilder.badges(
                totalMinutes = totalMinutes,
                totalPlays = totalPlays,
                distinctArtists = 143,
                longestStreakDays = streakDays,
                maxPlaysOfOneTrack = 38,
                minutesByHour = HOURS,
                biggestDayMinutes = busiest.minutes,
                bingePlays = 12,
                weekdaysActive = weekdays.count { it > 0L },
            ),
        )
        return WrappedReport(
            window = WrappedWindow(period, 0L, 1L),
            totalMinutes = totalMinutes,
            totalPlays = totalPlays,
            distinctTracks = 812,
            distinctArtists = 143,
            activeDays = days.size,
            topTracks = tracks,
            topArtists = artists,
            topAlbums = emptyList(),
            minutesByHour = HOURS,
            longestStreakDays = streakDays,
            currentStreakDays = 4,
            topTrackSharePercent = 3,
            listenerPersonality = "Night owl",
            personalityLine = "Most of your music lands after 10pm. The night is yours.",
            busiestDayMillis = null,
            firstListenAtMillis = null,
            lastListenAtMillis = null,
            insights = insights,
        )
    }

    /** The first evening after installing: one day, three songs. */
    fun firstEvening(): WrappedReport {
        val day = WrappedDates.dayIndexOf(2026, 10, 12)
        val hours = List(24) { h -> if (h == 21) 9L else if (h == 22) 5L else 0L }
        val days = listOf(DayStat(day, 14, 4))
        val tracks = listOf(
            entry("Tum Hi Ho", "Arijit Singh", 8, 2, null),
            entry("Kesariya", "Arijit Singh, Pritam", 4, 1, null),
            entry("Heat Waves", "Glass Animals", 2, 1, null),
        )
        val period = WrappedPeriod.Month(2026, 10)
        val insights = WrappedInsights(
            days = days,
            months = WrappedInsightsBuilder.monthStats(WrappedDates.monthsFor(period, day), days),
            minutesByWeekday = WrappedInsightsBuilder.weekdayMinutes(days),
            biggestDay = BiggestDay(day, 14, 4, "Tum Hi Ho", "Arijit Singh"),
            completionPercent = 75,
            topArtist = ArtistDetail("Arijit Singh", null, 12, 3, 1, "Tum Hi Ho", null, 85),
            firstTrack = FirstTrack("Tum Hi Ho", "Arijit Singh", null, 1_794_500_000_000L),
            longestStreak = day..day,
            archetype = WrappedInsightsBuilder.archetype(hours, 1f, 1.33f, 75),
            badges = WrappedInsightsBuilder.badges(14, 4, 2, 1, 2, hours, 14, 2, 1),
        )
        return WrappedReport(
            window = WrappedWindow(period, 0L, 1L),
            totalMinutes = 14,
            totalPlays = 4,
            distinctTracks = 3,
            distinctArtists = 2,
            activeDays = 1,
            topTracks = tracks,
            topArtists = listOf(
                entry("Arijit Singh", "3 plays", 12, 3, null),
                entry("Glass Animals", "1 plays", 2, 1, null),
            ),
            topAlbums = emptyList(),
            minutesByHour = hours,
            longestStreakDays = 1,
            currentStreakDays = 1,
            topTrackSharePercent = 57,
            listenerPersonality = "Evening listener",
            personalityLine = "You wind down with music \u2014 most of it lands in the evening.",
            busiestDayMillis = null,
            firstListenAtMillis = null,
            lastListenAtMillis = null,
            insights = insights,
        )
    }
}
