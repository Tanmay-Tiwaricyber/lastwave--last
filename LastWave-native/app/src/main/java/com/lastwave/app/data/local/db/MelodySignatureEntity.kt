package com.lastwave.app.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * One song's tune, cached so "sing to find" never has to decode the same file
 * twice (v5.7.0).
 *
 * The payload is not audio: it is the sequence of notes the analyser heard, as
 * a starting pitch plus semitone steps, and how long each note held. Two
 * kilobytes for a whole song, which is why this can be kept for the entire
 * library. [stamp] is the size of the file it was derived from, so replacing a
 * download invalidates the row instead of silently keeping a stale tune.
 *
 * A row with [noteCount] below `4` means "looked, found nothing" (an
 * instrumental, a talk track, a file the decoder could not read) and is kept so
 * the app does not re-decode it on every search.
 */
@Entity(
    tableName = "melody_signatures",
    indices = [Index(value = ["trackKey"], unique = true)],
)
data class MelodySignatureEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val trackKey: String,
    /** First entry is a MIDI note; the rest are semitone steps. See [com.lastwave.app.playback.melody.MelodyTracker.intervals]. */
    val intervals: ByteArray,
    /** Frames each note held, at [frameSeconds] per frame. */
    val noteFrames: ByteArray,
    val frameSeconds: Float,
    val durationSeconds: Float,
    val noteCount: Int,
    val stamp: Long,
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean =
        other is MelodySignatureEntity &&
            other.trackKey == trackKey &&
            other.intervals.contentEquals(intervals) &&
            other.noteFrames.contentEquals(noteFrames) &&
            other.noteCount == noteCount &&
            other.stamp == stamp

    override fun hashCode(): Int = trackKey.hashCode() * 31 + noteCount
}

@Dao
interface MelodySignatureDao {

    @Query("SELECT * FROM melody_signatures")
    suspend fun getAll(): List<MelodySignatureEntity>

    @Query("SELECT trackKey FROM melody_signatures")
    suspend fun indexedKeys(): List<String>

    @Query("SELECT COUNT(*) FROM melody_signatures")
    suspend fun count(): Int

    @Upsert
    suspend fun put(entity: MelodySignatureEntity)

    @Query("DELETE FROM melody_signatures WHERE trackKey = :trackKey")
    suspend fun deleteKey(trackKey: String)

    @Query("DELETE FROM melody_signatures")
    suspend fun clearAll()
}
