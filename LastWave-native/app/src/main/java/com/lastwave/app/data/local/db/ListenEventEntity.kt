package com.lastwave.app.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * ────────────────────────────────────────────────────────────────────────────
 *  Raw listening events — the storage behind LastWave Wrapped (v5.4.0)
 * ────────────────────────────────────────────────────────────────────────────
 * [SongPlayStatsEntity] keeps a running *total* per track, which is right for
 * recommendations but cannot answer "what did I listen to this year". Wrapped
 * needs time-windowed facts, so every completed listening interval appends one
 * row here. Rows are tiny (~60 bytes) and pruned to [MAX_EVENTS] so the
 * database cannot grow without bound.
 *
 * [hourOfDay] is stored alongside the timestamp because SQLite's strftime uses
 * UTC; the recap's "listening clock" wants the user's local hour.
 */
@Entity(
    tableName = "listen_events",
    indices = [Index("playedAtMillis"), Index("trackKey")],
)
data class ListenEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val artist: String,
    val album: String? = null,
    val videoId: String? = null,
    val artworkUrl: String? = null,
    val trackKey: String,
    /** Duration actually listened, which is the honest "minutes" number. */
    val listenedMs: Long,
    /** Wall-clock timestamp of the listen. */
    val playedAtMillis: Long,
    /** Local hour 0..23, used for the listening-clock insight. */
    val hourOfDay: Int,
    /** True when the track played to (near) the end. */
    val completed: Boolean,
) {
    companion object {
        /** ~2 years of heavy listening; oldest rows are dropped beyond this. */
        const val MAX_EVENTS = 60_000
    }
}

/** Aggregate row: a track with its windowed totals. */
data class TrackAggregate(
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val videoId: String?,
    val totalMs: Long,
    val plays: Int,
)

/** Aggregate row: an artist with its windowed totals. */
data class ArtistAggregate(
    val artist: String,
    val totalMs: Long,
    val plays: Int,
    val artworkUrl: String?,
)

/** Aggregate row: an album with its windowed totals. */
data class AlbumAggregate(
    val album: String,
    val artist: String,
    val totalMs: Long,
    val artworkUrl: String?,
)

/** Aggregate row: listens per local hour (0..23). */
data class HourAggregate(
    val hourOfDay: Int,
    val totalMs: Long,
)

/** Aggregate row: one local calendar day (v5.5.0). */
data class DayAggregate(
    val day: Long,
    val totalMs: Long,
    val plays: Int,
)

/** Aggregate row: the most times one track was played in a single day. */
data class BingeAggregate(
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val day: Long,
    val plays: Int,
)

/** Aggregate row: how often a track was finished versus abandoned. */
data class HabitAggregate(
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val plays: Int,
    val skips: Int,
)

@Dao
interface ListenEventDao {

    @androidx.room.Insert
    suspend fun insert(entity: ListenEventEntity): Long

    @Query("DELETE FROM listen_events WHERE id NOT IN (SELECT id FROM listen_events ORDER BY playedAtMillis DESC LIMIT :keep)")
    suspend fun prune(keep: Int = ListenEventEntity.MAX_EVENTS)

    @Query("SELECT COUNT(*) FROM listen_events")
    suspend fun count(): Int

    @Query("SELECT MIN(playedAtMillis) FROM listen_events")
    suspend fun firstEventAt(): Long?

    /**
     * AutoEQ / podcast rows never belong in a music recap. Everything else is
     * filtered by window in the caller so one [sinceMillis] drives all cards.
     */
    @Query(
        """
        SELECT MAX(title) AS title, MAX(artist) AS artist,
               MAX(artworkUrl) AS artworkUrl, MAX(videoId) AS videoId,
               SUM(listenedMs) AS totalMs, COUNT(*) AS plays
        FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
        GROUP BY trackKey
        ORDER BY totalMs DESC
        LIMIT :limit
        """
    )
    suspend fun topTracks(sinceMillis: Long, untilMillis: Long, limit: Int): List<TrackAggregate>

    @Query(
        """
        SELECT artist, SUM(listenedMs) AS totalMs, COUNT(*) AS plays,
               MAX(artworkUrl) AS artworkUrl
        FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
        GROUP BY artist
        ORDER BY totalMs DESC
        LIMIT :limit
        """
    )
    suspend fun topArtists(sinceMillis: Long, untilMillis: Long, limit: Int): List<ArtistAggregate>

    @Query(
        """
        SELECT album, MAX(artist) AS artist, SUM(listenedMs) AS totalMs,
               MAX(artworkUrl) AS artworkUrl
        FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
          AND album IS NOT NULL AND album != ''
        GROUP BY album
        ORDER BY totalMs DESC
        LIMIT :limit
        """
    )
    suspend fun topAlbums(sinceMillis: Long, untilMillis: Long, limit: Int): List<AlbumAggregate>

    @Query(
        """
        SELECT hourOfDay, SUM(listenedMs) AS totalMs
        FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
        GROUP BY hourOfDay
        ORDER BY hourOfDay
        """
    )
    suspend fun hours(sinceMillis: Long, untilMillis: Long): List<HourAggregate>

    @Query(
        """
        SELECT COUNT(DISTINCT trackKey) FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
        """
    )
    suspend fun distinctTracks(sinceMillis: Long, untilMillis: Long): Int

    @Query(
        """
        SELECT COUNT(DISTINCT artist) FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
        """
    )
    suspend fun distinctArtists(sinceMillis: Long, untilMillis: Long): Int

    @Query(
        """
        SELECT MIN(playedAtMillis) FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
        """
    )
    suspend fun firstInWindow(sinceMillis: Long, untilMillis: Long): Long?

    @Query(
        """
        SELECT MAX(playedAtMillis) FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
        """
    )
    suspend fun lastInWindow(sinceMillis: Long, untilMillis: Long): Long?

    @Query(
        """
        SELECT COUNT(*) FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
        """
    )
    suspend fun playsInWindow(sinceMillis: Long, untilMillis: Long): Int

    @Query(
        """
        SELECT SUM(listenedMs) FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
        """
    )
    suspend fun totalMsInWindow(sinceMillis: Long, untilMillis: Long): Long?

    /**
     * Distinct *local* days with at least one listen — powers the streak card.
     * [utcOffsetMillis] shifts the timestamp before bucketing; without it a
     * listener in IST would have their day roll over at 05:30 in the morning.
     */
    @Query(
        """
        SELECT DISTINCT (playedAtMillis + :utcOffsetMillis) / 86400000 AS day
        FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
        ORDER BY day
        """
    )
    suspend fun listenedDays(sinceMillis: Long, untilMillis: Long, utcOffsetMillis: Long): List<Long>

    @Query(
        """
        SELECT * FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
        ORDER BY playedAtMillis DESC
        LIMIT :limit
        """
    )
    suspend fun recentInWindow(sinceMillis: Long, untilMillis: Long, limit: Int): List<ListenEventEntity>

    // ── v5.5.0: deeper recap queries ────────────────────────────────────────

    /** Minutes and plays per *local* day; see [listenedDays] for the offset. */
    @Query(
        """
        SELECT (playedAtMillis + :utcOffsetMillis) / 86400000 AS day,
               SUM(listenedMs) AS totalMs, COUNT(*) AS plays
        FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
        GROUP BY day
        ORDER BY day
        """
    )
    suspend fun dayTotals(sinceMillis: Long, untilMillis: Long, utcOffsetMillis: Long): List<DayAggregate>

    @Query(
        """
        SELECT MAX(title) AS title, MAX(artist) AS artist, MAX(artworkUrl) AS artworkUrl,
               (playedAtMillis + :utcOffsetMillis) / 86400000 AS day, COUNT(*) AS plays
        FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
        GROUP BY trackKey, day
        ORDER BY plays DESC, day DESC
        LIMIT 1
        """
    )
    suspend fun biggestBinge(sinceMillis: Long, untilMillis: Long, utcOffsetMillis: Long): BingeAggregate?

    @Query(
        """
        SELECT COALESCE(SUM(completed), 0) FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
        """
    )
    suspend fun completedCount(sinceMillis: Long, untilMillis: Long): Int

    /** Abandoned at least half the time, over at least four plays. */
    @Query(
        """
        SELECT MAX(title) AS title, MAX(artist) AS artist, MAX(artworkUrl) AS artworkUrl,
               COUNT(*) AS plays,
               SUM(CASE WHEN completed = 0 THEN 1 ELSE 0 END) AS skips
        FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
        GROUP BY trackKey
        HAVING plays >= 4 AND skips * 2 >= plays
        ORDER BY skips DESC, plays DESC
        LIMIT 1
        """
    )
    suspend fun mostSkipped(sinceMillis: Long, untilMillis: Long): HabitAggregate?

    /** Played at least five times and finished every single time. */
    @Query(
        """
        SELECT MAX(title) AS title, MAX(artist) AS artist, MAX(artworkUrl) AS artworkUrl,
               COUNT(*) AS plays,
               SUM(CASE WHEN completed = 0 THEN 1 ELSE 0 END) AS skips
        FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
        GROUP BY trackKey
        HAVING plays >= 5 AND skips = 0
        ORDER BY plays DESC
        LIMIT 1
        """
    )
    suspend fun neverSkipped(sinceMillis: Long, untilMillis: Long): HabitAggregate?

    /** Top track inside a local-hour band, e.g. 0..5 for "after midnight". */
    @Query(
        """
        SELECT MAX(title) AS title, MAX(artist) AS artist,
               MAX(artworkUrl) AS artworkUrl, MAX(videoId) AS videoId,
               SUM(listenedMs) AS totalMs, COUNT(*) AS plays
        FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
          AND hourOfDay >= :fromHour AND hourOfDay < :toHour
        GROUP BY trackKey
        ORDER BY totalMs DESC
        LIMIT 1
        """
    )
    suspend fun topTrackInHours(sinceMillis: Long, untilMillis: Long, fromHour: Int, toHour: Int): TrackAggregate?

    @Query(
        """
        SELECT MAX(title) AS title, MAX(artist) AS artist,
               MAX(artworkUrl) AS artworkUrl, MAX(videoId) AS videoId,
               SUM(listenedMs) AS totalMs, COUNT(*) AS plays
        FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
          AND artist = :artist
        GROUP BY trackKey
        ORDER BY totalMs DESC
        LIMIT 1
        """
    )
    suspend fun topTrackForArtist(sinceMillis: Long, untilMillis: Long, artist: String): TrackAggregate?

    @Query(
        """
        SELECT COUNT(DISTINCT (playedAtMillis + :utcOffsetMillis) / 86400000) FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
          AND artist = :artist
        """
    )
    suspend fun daysForArtist(sinceMillis: Long, untilMillis: Long, artist: String, utcOffsetMillis: Long): Int

    @Query("SELECT MIN(playedAtMillis) FROM listen_events WHERE artist = :artist")
    suspend fun firstListenForArtist(artist: String): Long?

    @Query(
        """
        SELECT * FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
        ORDER BY playedAtMillis ASC
        LIMIT 1
        """
    )
    suspend fun firstEventInWindow(sinceMillis: Long, untilMillis: Long): ListenEventEntity?

    /** Artists whose first-ever listen falls inside the window. */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT artist FROM listen_events
            GROUP BY artist
            HAVING MIN(playedAtMillis) >= :sinceMillis AND MIN(playedAtMillis) < :untilMillis
        )
        """
    )
    suspend fun newArtistCount(sinceMillis: Long, untilMillis: Long): Int

    @Query(
        """
        SELECT artist, SUM(listenedMs) AS totalMs, COUNT(*) AS plays,
               MAX(artworkUrl) AS artworkUrl
        FROM listen_events
        WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
          AND artist IN (
              SELECT artist FROM listen_events
              GROUP BY artist
              HAVING MIN(playedAtMillis) >= :sinceMillis
          )
        GROUP BY artist
        ORDER BY totalMs DESC
        LIMIT 1
        """
    )
    suspend fun topNewArtist(sinceMillis: Long, untilMillis: Long): ArtistAggregate?

    @Query(
        """
        SELECT COALESCE(MAX(c), 0) FROM (
            SELECT COUNT(*) AS c FROM listen_events
            WHERE playedAtMillis >= :sinceMillis AND playedAtMillis < :untilMillis
            GROUP BY trackKey
        )
        """
    )
    suspend fun maxPlaysOfOneTrack(sinceMillis: Long, untilMillis: Long): Int

    @Query("DELETE FROM listen_events WHERE playedAtMillis < :beforeMillis")
    suspend fun deleteOlderThan(beforeMillis: Long)

    @Query("DELETE FROM listen_events")
    suspend fun clearAll()
}
