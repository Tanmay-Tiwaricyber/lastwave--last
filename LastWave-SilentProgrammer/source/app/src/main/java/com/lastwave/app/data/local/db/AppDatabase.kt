package com.lastwave.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.lastwave.app.data.podcast.PodcastProgressDao
import com.lastwave.app.data.podcast.PodcastProgressEntity

@Database(
    entities = [
        ArtworkCacheEntity::class,
        RecommendationExclusionEntity::class,
        SavedPlaylistEntity::class,
        DownloadedTrackEntity::class,
        SongPlayStatsEntity::class,
        RelatedGraphEdgeEntity::class,
        PodcastProgressEntity::class,
        ListenEventEntity::class,
        WrappedMarkerEntity::class,
        MelodySignatureEntity::class,
    ],
    version = 17,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun artworkCacheDao(): ArtworkCacheDao
    abstract fun recommendationExclusionDao(): RecommendationExclusionDao
    abstract fun savedPlaylistDao(): SavedPlaylistDao
    abstract fun downloadedTrackDao(): DownloadedTrackDao
    abstract fun songPlayStatsDao(): SongPlayStatsDao
    abstract fun relatedGraphDao(): RelatedGraphDao
    abstract fun podcastProgressDao(): PodcastProgressDao
    abstract fun listenEventDao(): ListenEventDao
    abstract fun wrappedMarkerDao(): WrappedMarkerDao
    abstract fun melodySignatureDao(): MelodySignatureDao
}
