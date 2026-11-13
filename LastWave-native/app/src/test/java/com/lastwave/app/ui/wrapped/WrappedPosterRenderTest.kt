package com.lastwave.app.ui.wrapped

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Typeface
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.repository.DayStat
import com.lastwave.app.data.repository.WrappedDates
import com.lastwave.app.data.repository.WrappedEntry
import com.lastwave.app.data.repository.WrappedInsights
import com.lastwave.app.data.repository.WrappedInsightsBuilder
import com.lastwave.app.data.repository.WrappedPeriod
import com.lastwave.app.data.repository.WrappedReport
import com.lastwave.app.data.repository.WrappedWindow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws every share-poster style with the real Android canvas code (Robolectric
 * native graphics) and writes the PNGs to `build/wrapped-posters/`, so the
 * layouts can be looked at without a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class WrappedPosterRenderTest {

    private fun font(name: String, fallback: Typeface): Typeface =
        runCatching { Typeface.createFromFile(File("src/main/res/font/$name.ttf")) }.getOrNull() ?: fallback

    private fun typefaces(): WrappedPoster.Typefaces {
        val system = WrappedPoster.Typefaces.SYSTEM
        return WrappedPoster.Typefaces(
            sans = font("plex_sans_regular", system.sans),
            sansBold = font("plex_sans_semibold", system.sansBold),
            mono = font("plex_mono_medium", system.mono),
            monoRegular = font("plex_mono_regular", system.monoRegular),
        )
    }

    private fun entry(title: String, subtitle: String, minutes: Long, plays: Int) =
        WrappedEntry(title, subtitle, null, null, minutes * 60_000L, plays)

    private fun sampleReport(period: WrappedPeriod, dayCount: Int): WrappedReport {
        val today = WrappedDates.dayIndexOf(2026, 10, 12)
        val first = when (period) {
            is WrappedPeriod.Month -> WrappedDates.dayIndexOf(period.year, period.month0, 1)
            else -> WrappedDates.dayIndexOf(2026, 0, 1)
        }
        // Deterministic, uneven listening so the heatmap has texture.
        val days = (0 until dayCount).mapNotNull { i ->
            val seed = (i * 37 + 11) % 97
            if (seed % 5 == 0) null else DayStat(first + i, 8L + (seed * seed) % 190, 2 + seed % 30)
        }.filter { it.dayIndex <= today }
        val hours = listOf<Long>(310, 180, 60, 10, 0, 0, 20, 90, 160, 140, 120, 150, 210, 190, 170, 220, 260, 300, 340, 420, 520, 610, 560, 430)
        val totalMinutes = days.sumOf { it.minutes }
        val totalPlays = days.sumOf { it.plays }
        val busiest = days.maxBy { it.minutes }
        val streak = WrappedInsightsBuilder.longestStreakRange(days.map { it.dayIndex })
        val streakDays = streak?.let { (it.last - it.first + 1).toInt() } ?: 0
        val weekdays = WrappedInsightsBuilder.weekdayMinutes(days)
        val archetype = WrappedInsightsBuilder.archetype(hours, 0.62f, 3.4f, 81)

        val insights = WrappedInsights(
            days = days,
            months = WrappedInsightsBuilder.monthStats(WrappedDates.monthsFor(period, today), days),
            minutesByWeekday = weekdays,
            completionPercent = 81,
            longestStreak = streak,
            archetype = archetype,
            badges = WrappedInsightsBuilder.badges(
                totalMinutes = totalMinutes,
                totalPlays = totalPlays,
                distinctArtists = 143,
                longestStreakDays = streakDays,
                maxPlaysOfOneTrack = 64,
                minutesByHour = hours,
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
            topTracks = listOf(
                entry("Tum Hi Ho", "Arijit Singh", 412, 96),
                entry("A very long track title that has to be clipped somewhere", "An equally long artist name", 388, 81),
                entry("Kesariya", "Arijit Singh, Pritam", 301, 70),
                entry("Blinding Lights", "The Weeknd", 254, 66),
                entry("Heat Waves", "Glass Animals", 201, 51),
            ),
            topArtists = listOf(
                entry("Arijit Singh", "702 plays", 2140, 702),
                entry("Pritam", "410 plays", 1320, 410),
                entry("The Weeknd", "288 plays", 904, 288),
                entry("A. R. Rahman", "240 plays", 811, 240),
                entry("Glass Animals", "122 plays", 402, 122),
            ),
            topAlbums = emptyList(),
            minutesByHour = hours,
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

    private fun save(bitmap: Bitmap, name: String) {
        val dir = File("build/wrapped-posters").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** True when the picture is more than a flat background. */
    private fun hasAccent(bitmap: Bitmap): Boolean {
        for (y in 0 until bitmap.height step 4) {
            for (x in 0 until bitmap.width step 4) {
                if (bitmap.getPixel(x, y) == 0xFF05DF72.toInt()) return true
            }
        }
        return false
    }

    @Test
    fun everyStyleRendersForAYear() {
        val report = sampleReport(WrappedPeriod.Year(2026), 365)
        for (style in WrappedPoster.Style.entries) {
            assertThat(WrappedPoster.supports(style, report)).isTrue()
            val bitmap = WrappedPoster.render(report, typefaces(), emptyMap(), style)
            assertThat(bitmap.width).isEqualTo(WrappedPoster.WIDTH)
            assertThat(bitmap.height).isEqualTo(WrappedPoster.HEIGHT)
            assertThat(hasAccent(bitmap)).isTrue()
            save(bitmap, "year-${style.name.lowercase()}")
        }
    }

    @Test
    fun monthRecapSkipsTheYearGrid() {
        val report = sampleReport(WrappedPeriod.Month(2026, 10), 30)
        assertThat(WrappedPoster.supports(WrappedPoster.Style.CALENDAR, report)).isFalse()
        for (style in listOf(WrappedPoster.Style.SUMMARY, WrappedPoster.Style.RECEIPT)) {
            val bitmap = WrappedPoster.render(report, typefaces(), emptyMap(), style)
            assertThat(hasAccent(bitmap)).isTrue()
            save(bitmap, "month-${style.name.lowercase()}")
        }
    }

    @Test
    fun aNearlyEmptyRecapStillRenders() {
        val base = sampleReport(WrappedPeriod.Month(2026, 10), 1)
        val report = base.copy(
            topTracks = base.topTracks.take(1),
            topArtists = base.topArtists.take(1),
            insights = WrappedInsights(),
        )
        for (style in listOf(WrappedPoster.Style.SUMMARY, WrappedPoster.Style.RECEIPT)) {
            val bitmap = WrappedPoster.render(report, typefaces(), emptyMap(), style)
            assertThat(bitmap.width).isEqualTo(WrappedPoster.WIDTH)
            save(bitmap, "sparse-${style.name.lowercase()}")
        }
    }
}
