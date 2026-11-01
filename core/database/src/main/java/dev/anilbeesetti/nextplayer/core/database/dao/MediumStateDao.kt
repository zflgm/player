package dev.anilbeesetti.nextplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.anilbeesetti.nextplayer.core.database.entities.MediumStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediumStateDao {

    @Upsert
    suspend fun upsert(mediumState: MediumStateEntity)

    @Upsert
    suspend fun upsertAll(mediaStates: List<MediumStateEntity>)

    @Query("SELECT * FROM media_state WHERE uri = :uri")
    suspend fun get(uri: String): MediumStateEntity?

    @Query("SELECT * FROM media_state WHERE uri = :uri")
    fun getAsFlow(uri: String): Flow<MediumStateEntity?>

    @Query("SELECT * FROM media_state")
    fun getAll(): Flow<List<MediumStateEntity>>

    @Query("SELECT * FROM media_state WHERE uri IN (:uris)")
    suspend fun getAllByUris(uris: List<String>): List<MediumStateEntity>

    @Query("UPDATE media_state SET last_played_time = NULL")
    suspend fun clearPlaybackHistory()

    @Query("DELETE FROM media_state WHERE uri in (:uris)")
    suspend fun delete(uris: List<String>)

    // ----- atomic column updates -----
    // The old read-copy-upsert pattern lost concurrent writes: saving a position while a track
    // change was mid-flight overwrote one with the other. Each setter touches exactly one column,
    // so writers to different columns can never clobber each other.

    @Query("INSERT OR IGNORE INTO media_state (uri) VALUES (:uri)")
    suspend fun ensureRow(uri: String)

    @Query("UPDATE media_state SET playback_position = :position WHERE uri = :uri")
    suspend fun patchPosition(uri: String, position: Long)

    @Query("UPDATE media_state SET playback_speed = :speed WHERE uri = :uri")
    suspend fun patchSpeed(uri: String, speed: Float)

    @Query("UPDATE media_state SET audio_track_index = :trackIndex WHERE uri = :uri")
    suspend fun patchAudioTrack(uri: String, trackIndex: Int)

    @Query("UPDATE media_state SET subtitle_track_index = :trackIndex WHERE uri = :uri")
    suspend fun patchSubtitleTrack(uri: String, trackIndex: Int)

    @Query("UPDATE media_state SET video_scale = :scale WHERE uri = :uri")
    suspend fun patchScale(uri: String, scale: Float)

    @Query("UPDATE media_state SET subtitle_delay = :delayMs WHERE uri = :uri")
    suspend fun patchSubtitleDelay(uri: String, delayMs: Long)

    @Query("UPDATE media_state SET subtitle_speed = :speed WHERE uri = :uri")
    suspend fun patchSubtitleSpeed(uri: String, speed: Float)

    @Query("UPDATE media_state SET last_played_time = :playedAt WHERE uri = :uri")
    suspend fun patchLastPlayedTime(uri: String, playedAt: Long)

    @Query("UPDATE media_state SET duration = :duration WHERE uri = :uri")
    suspend fun patchDuration(uri: String, duration: Long)

    @Query("UPDATE media_state SET external_audio = :value WHERE uri = :uri")
    suspend fun patchExternalAudio(uri: String, value: String)

    @Query("UPDATE media_state SET external_subs = :value WHERE uri = :uri")
    suspend fun patchExternalSubs(uri: String, value: String)

    @Transaction
    suspend fun setPosition(uri: String, position: Long) {
        ensureRow(uri)
        patchPosition(uri, position)
    }

    @Transaction
    suspend fun setSpeed(uri: String, speed: Float) {
        ensureRow(uri)
        patchSpeed(uri, speed)
    }

    @Transaction
    suspend fun setAudioTrack(uri: String, trackIndex: Int) {
        ensureRow(uri)
        patchAudioTrack(uri, trackIndex)
    }

    @Transaction
    suspend fun setSubtitleTrack(uri: String, trackIndex: Int) {
        ensureRow(uri)
        patchSubtitleTrack(uri, trackIndex)
    }

    @Transaction
    suspend fun setScale(uri: String, scale: Float) {
        ensureRow(uri)
        patchScale(uri, scale)
    }

    @Transaction
    suspend fun setSubtitleDelay(uri: String, delayMs: Long) {
        ensureRow(uri)
        patchSubtitleDelay(uri, delayMs)
    }

    @Transaction
    suspend fun setSubtitleSpeed(uri: String, speed: Float) {
        ensureRow(uri)
        patchSubtitleSpeed(uri, speed)
    }

    @Transaction
    suspend fun setLastPlayed(uri: String, playedAt: Long, duration: Long?) {
        ensureRow(uri)
        patchLastPlayedTime(uri, playedAt)
        if (duration != null) patchDuration(uri, duration)
    }

    @Transaction
    suspend fun setExternalAudio(uri: String, value: String) {
        ensureRow(uri)
        patchExternalAudio(uri, value)
    }

    @Transaction
    suspend fun setExternalSubs(uri: String, value: String) {
        ensureRow(uri)
        patchExternalSubs(uri, value)
    }
}
