package com.lastwave.app.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Tiny key/value table for LastWave Wrapped bookkeeping (v5.4.0).
 *
 * Currently one row: [KEY_FIRST_LISTEN_AT] — the timestamp of the very first
 * listen ever recorded. Wrapped uses it to offer a recap for *any* year the
 * user has data in (e.g. a "2025 in review" card), even if the event log has
 * since been pruned.
 */
@Entity(tableName = "wrapped_markers")
data class WrappedMarkerEntity(
    @PrimaryKey val key: String,
    val value: Long,
) {
    companion object {
        const val KEY_FIRST_LISTEN_AT = "first_listen_at"
    }
}

@Dao
interface WrappedMarkerDao {
    @Query("SELECT value FROM wrapped_markers WHERE key = :key LIMIT 1")
    suspend fun get(key: String): Long?

    @androidx.room.Upsert
    suspend fun put(entity: WrappedMarkerEntity)

    @Query("DELETE FROM wrapped_markers")
    suspend fun clearAll()
}
