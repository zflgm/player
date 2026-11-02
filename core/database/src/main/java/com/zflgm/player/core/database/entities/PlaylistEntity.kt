package com.zflgm.player.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val type: String = "LOCAL",
    val source: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_refreshed_at")
    val lastRefreshedAt: Long? = null,
    /** Display rank among playlists; ties fall back to creation order. */
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,
    /** The ordering the rows are kept in ([PlaylistItemSortMode] name); null means hand-arranged. */
    @ColumnInfo(name = "item_sort")
    val itemSort: String? = null,
)

data class PlaylistSummaryEntity(
    val id: Long,
    val name: String,
    val type: String,
    @ColumnInfo(name = "item_count")
    val itemCount: Int,
    @ColumnInfo(name = "last_refreshed_at")
    val lastRefreshedAt: Long?,
)

data class PlaylistWithItems(
    @Embedded
    val playlist: PlaylistEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "playlist_id",
    )
    val items: List<PlaylistItemEntity>,
)
