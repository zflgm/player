package com.zflgm.player.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A network folder a playlist pulls files from.
 *
 * Files are copied in as plain rows, so hand-added clips and scanned clips behave the same on the
 * list. The folder itself is remembered here so "refresh" knows exactly what the user linked and
 * can append whatever appeared on the server since — without it, a playlist of network files has
 * no way back to the folders they came from.
 */
@Entity(
    tableName = "playlist_source",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["playlist_id", "source"], unique = true)],
)
data class PlaylistSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    /** Snapshot-style URI: the folder plus whether the original add walked sub-folders. */
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "last_refreshed_at") val lastRefreshedAt: Long? = null,
)
