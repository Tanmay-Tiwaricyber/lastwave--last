package com.lastwave.app.data.repository

import com.lastwave.app.data.local.db.AlbumAggregate
import com.lastwave.app.data.local.db.ArtistAggregate
import com.lastwave.app.data.local.db.HourAggregate
import com.lastwave.app.data.local.db.ListenEventDao
import com.lastwave.app.data.local.db.ListenEventEntity
import com.lastwave.app.data.local.db.TrackAggregate
import com.lastwave.app.data.local.db.WrappedMarkerDao
import com.lastwave.app.data.local.db.WrappedMarkerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ────────────────────────────────────────────────────────────────────────────
 *  LastWave Wrapped (v5.4.0, deepened in v5.5.0)
 * ────────────────────────────────────────────────────────────────────────────
 * Two responsibilities:
 *
 *  1. [recordListen] — append a [ListenEventEntity] whenever a track finishes
 *     or is skipped. Called from `MusicPlayer.handleTrackTransition`, next to
 *     the existing play-stats write, so a single code path keeps both.
 *
 *  2. [buildReport] — aggregate a window ([WrappedPeriod]) into a
 *     [WrappedReport]. Empty windows fall back to [WrappedPeriod.AllTime] so
 *     the entry point never opens onto a blank screen.
 */
@Singleton
class WrappedRepository @Inject constructor(
    private val listenEventDao: ListenEventDao,
    private val markerDao: WrappedMarkerDao,
) {

    // ── Recording ──────────────────────────────────────────────────────────

    /** Appends one event. Any interval under 5s is noise, not a listen. */
    suspend fun recordListen(
        title: String,
        artist: String,
        album: String?,
        videoId: String?,
        artworkUrl: String?,
        trackKey: String,
        listenedMs: Long,
        completed: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        if (listenedMs < MIN_LISTEN_MS) return@withContext
        if (artist.equals("Podcast", ignoreCase = true)) return@withContext
        val hour = Calendar.getInstance().apply { timeInMillis = nowMillis }.get(Calendar.HOUR_OF_DAY)
        listenEventDao.insert(
            ListenEventEntity(
                title = title,
                artist = artist,
                album = album?.takeIf { it.isNotBlank() },
                videoId = videoId,
                artworkUrl = artworkUrl,
                trackKey = trackKey,
                listenedMs = listenedMs,
                playedAtMillis = nowMillis,
                hourOfDay = hour,
                completed = completed,
            ),
        )
        // Stamp "day one" the first time we ever see a listen.
        if (markerDao.get(WrappedMarkerEntity.KEY_FIRST_LISTEN_AT) == null) {
            markerDao.put(WrappedMarkerEntity(WrappedMarkerEntity.KEY_FIRST_LISTEN_AT, nowMillis))
        }
    }

    suspend fun firstListenAt(): Long? = withContext(Dispatchers.IO) {
        markerDao.get(WrappedMarkerEntity.KEY_FIRST_LISTEN_AT)
    }

    suspend fun hasAnyListening(): Boolean = withContext(Dispatchers.IO) {
        listenEventDao.count() > 0
    }

    /** Drops the oldest events beyond [ListenEventEntity.MAX_EVENTS]. */
    suspend fun prune() = withContext(Dispatchers.IO) {
        listenEventDao.prune()
    }

    /** Used by "clear listening history" in Settings. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        listenEventDao.clearAll()
        markerDao.clearAll()
    }

    /**
     * Short recent list for the Wrapped "on repeat lately" strip. Returns a
     * single row per track so repeats do not fill the strip.
     */
    suspend fun recentHighlights(limit: Int = 12, nowMillis: Long = System.currentTimeMillis()): List<ListenEventEntity> =
        withContext(Dispatchers.IO) {
            val window = WrappedWindow.forPeriod(WrappedPeriod.Last12Months, nowMillis)
            listenEventDao.recentInWindow(window.startMillis, window.endMillis, limit * 4)
                .distinctBy { it.trackKey }
                .take(limit)
        }

    // ── Reporting ──────────────────────────────────────────────────────────

    /**
     * Builds a full recap. When [period] has no data, returns the All-time
     * report instead (and the UI says so via [WrappedReport.window]).
     */
    suspend fun buildReport(
        period: WrappedPeriod,
        nowMillis: Long = System.currentTimeMillis(),
    ): WrappedReport = withContext(Dispatchers.IO) {
        val window = WrappedWindow.forPeriod(period, nowMillis)
        val report = buildForWindow(window, nowMillis)
        if (report.isEmpty && period != WrappedPeriod.AllTime) {
            buildForWindow(WrappedWindow.forPeriod(WrappedPeriod.AllTime, nowMillis), nowMillis)
        } else {
            report
        }
    }

    suspend fun buildForWindow(window: WrappedWindow, nowMillis: Long = System.currentTimeMillis()): WrappedReport {
        val s = window.startMillis
        val e = window.endMillis
        val totalMs = listenEventDao.totalMsInWindow(s, e) ?: 0L
        val plays = listenEventDao.playsInWindow(s, e)
        val tracks: List<TrackAggregate> = listenEventDao.topTracks(s, e, 5)
        val artists: List<ArtistAggregate> = listenEventDao.topArtists(s, e, 5)
        val albums: List<AlbumAggregate> = listenEventDao.topAlbums(s, e, 3)
        val hours: List<HourAggregate> = listenEventDao.hours(s, e)
        // Bucket by the listener's own calendar day, not UTC's.
        val offsetMs = java.util.TimeZone.getDefault().getOffset(nowMillis).toLong()
        val days = listenEventDao.listenedDays(s, e, offsetMs)

        val todayDayIndex = WrappedCalculator.localDayIndex(nowMillis, offsetMs)
        val (longest, current) = WrappedCalculator.streaks(days, todayDayIndex)
        val (personality, personalityLine) = WrappedCalculator.personality(WrappedCalculator.hourMinutes(hours))

        val allTopMs = tracks.firstOrNull()?.totalMs ?: 0L
        val distinctTracks = listenEventDao.distinctTracks(s, e)
        val distinctArtists = listenEventDao.distinctArtists(s, e)
        val minutesByHour = WrappedCalculator.hourMinutes(hours)
        val insights = if (plays > 0) {
            buildInsights(
                window = window,
                nowMillis = nowMillis,
                offsetMs = offsetMs,
                todayDayIndex = todayDayIndex,
                totalMs = totalMs,
                plays = plays,
                distinctTracks = distinctTracks,
                distinctArtists = distinctArtists,
                artists = artists,
                minutesByHour = minutesByHour,
                listenedDays = days,
                longestStreakDays = longest,
            )
        } else {
            WrappedInsights()
        }

        return WrappedReport(
            window = window,
            totalMinutes = totalMs / 60_000L,
            totalPlays = plays,
            distinctTracks = distinctTracks,
            distinctArtists = distinctArtists,
            activeDays = days.size,
            topTracks = WrappedCalculator.toEntries(tracks),
            topArtists = WrappedCalculator.toArtistEntries(artists),
            topAlbums = WrappedCalculator.toAlbumEntries(albums),
            minutesByHour = minutesByHour,
            longestStreakDays = longest,
            currentStreakDays = current,
            topTrackSharePercent = WrappedCalculator.topSharePercent(allTopMs, totalMs),
            listenerPersonality = personality,
            personalityLine = personalityLine,
            busiestDayMillis = null,
            firstListenAtMillis = listenEventDao.firstInWindow(s, e),
            lastListenAtMillis = listenEventDao.lastInWindow(s, e),
            insights = insights,
        )
    }

    /** The slower, richer half of the recap; only run when there is data. */
    private suspend fun buildInsights(
        window: WrappedWindow,
        nowMillis: Long,
        offsetMs: Long,
        todayDayIndex: Long,
        totalMs: Long,
        plays: Int,
        distinctTracks: Int,
        distinctArtists: Int,
        artists: List<ArtistAggregate>,
        minutesByHour: List<Long>,
        listenedDays: List<Long>,
        longestStreakDays: Int,
    ): WrappedInsights {
        val s = window.startMillis
        val e = window.endMillis

        val dayStats = listenEventDao.dayTotals(s, e, offsetMs).map {
            DayStat(it.day, (it.totalMs + 59_999L) / 60_000L, it.plays)
        }
        val months = WrappedInsightsBuilder.monthStats(
            WrappedDates.monthsFor(window.period, todayDayIndex),
            dayStats,
        )
        val weekdayMinutes = WrappedInsightsBuilder.weekdayMinutes(dayStats)

        val biggestDay = dayStats.maxByOrNull { it.minutes }?.let { d ->
            val dayStart = d.dayIndex * DAY_MS - offsetMs
            val top = listenEventDao.topTracks(dayStart, dayStart + DAY_MS, 1).firstOrNull()
            BiggestDay(d.dayIndex, d.minutes, d.plays, top?.title, top?.artist)
        }
        val binge = listenEventDao.biggestBinge(s, e, offsetMs)?.takeIf { it.plays >= 2 }?.let {
            Binge(it.title, it.artist, it.artworkUrl, it.day, it.plays)
        }
        val completion = listenEventDao.completedCount(s, e) * 100 / plays
        val mostSkipped = listenEventDao.mostSkipped(s, e)?.let {
            HabitTrack(it.title, it.artist, it.artworkUrl, it.plays, it.skips)
        }
        val neverSkipped = listenEventDao.neverSkipped(s, e)?.let {
            HabitTrack(it.title, it.artist, it.artworkUrl, it.plays, it.skips)
        }
        val nightAnthem = listenEventDao.topTrackInHours(s, e, 0, 5)?.let { WrappedCalculator.toEntries(listOf(it)).first() }
        val morningAnthem = listenEventDao.topTrackInHours(s, e, 5, 11)?.let { WrappedCalculator.toEntries(listOf(it)).first() }

        val topArtist = artists.firstOrNull()?.let { a ->
            ArtistDetail(
                name = a.artist,
                artworkUrl = a.artworkUrl,
                minutes = a.totalMs / 60_000L,
                plays = a.plays,
                daysListened = listenEventDao.daysForArtist(s, e, a.artist, offsetMs),
                topTrack = listenEventDao.topTrackForArtist(s, e, a.artist)?.title,
                firstListenMillis = listenEventDao.firstListenForArtist(a.artist),
                sharePercent = WrappedCalculator.topSharePercent(a.totalMs, totalMs),
            )
        }
        val firstTrack = listenEventDao.firstEventInWindow(s, e)?.let {
            FirstTrack(it.title, it.artist, it.artworkUrl, it.playedAtMillis)
        }

        // "New to you" is only honest when the log reaches back before this
        // period began; otherwise every artist would count as a discovery.
        val firstEver = listenEventDao.firstEventAt()
        val knowsEarlierHistory = window.period != WrappedPeriod.AllTime && firstEver != null && firstEver < s
        val newArtists = if (knowsEarlierHistory) listenEventDao.newArtistCount(s, e) else null
        val topNewArtist = if (knowsEarlierHistory) {
            listenEventDao.topNewArtist(s, e)?.let { WrappedCalculator.toArtistEntries(listOf(it)).first() }
        } else {
            null
        }

        val previousWindow = WrappedWindow.previous(window.period, nowMillis)
        val previousMinutes = previousWindow
            ?.let { listenEventDao.totalMsInWindow(it.startMillis, it.endMillis) }
            ?.takeIf { it > 0L }
            ?.let { it / 60_000L }

        val topArtistsShare = if (totalMs > 0L) artists.sumOf { it.totalMs }.toFloat() / totalMs.toFloat() else 0f
        val playsPerTrack = if (distinctTracks > 0) plays.toFloat() / distinctTracks.toFloat() else 1f

        return WrappedInsights(
            days = dayStats,
            months = months,
            minutesByWeekday = weekdayMinutes,
            biggestDay = biggestDay,
            binge = binge,
            completionPercent = completion,
            mostSkipped = mostSkipped,
            neverSkipped = neverSkipped,
            nightAnthem = nightAnthem,
            morningAnthem = morningAnthem,
            topArtist = topArtist,
            firstTrack = firstTrack,
            newArtists = newArtists,
            topNewArtist = topNewArtist,
            previousMinutes = previousMinutes,
            previousLabel = previousWindow?.period?.label?.takeIf { previousMinutes != null },
            periodComplete = e <= nowMillis,
            longestStreak = WrappedInsightsBuilder.longestStreakRange(listenedDays),
            archetype = WrappedInsightsBuilder.archetype(minutesByHour, topArtistsShare, playsPerTrack, completion),
            badges = WrappedInsightsBuilder.badges(
                totalMinutes = totalMs / 60_000L,
                totalPlays = plays,
                distinctArtists = distinctArtists,
                longestStreakDays = longestStreakDays,
                maxPlaysOfOneTrack = listenEventDao.maxPlaysOfOneTrack(s, e),
                minutesByHour = minutesByHour,
                biggestDayMinutes = biggestDay?.minutes ?: 0L,
                bingePlays = binge?.plays ?: 1,
                weekdaysActive = weekdayMinutes.count { it > 0L },
            ),
        )
    }

    /** The window's most-listened tracks, for "play my top tracks". */
    suspend fun topTracksForPlayback(window: WrappedWindow, limit: Int = 50): List<TrackAggregate> =
        withContext(Dispatchers.IO) {
            listenEventDao.topTracks(window.startMillis, window.endMillis, limit)
        }

    companion object {
        private const val MIN_LISTEN_MS = 5_000L
        private const val DAY_MS = 86_400_000L
    }
}
