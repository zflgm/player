package com.zflgm.player.feature.playlist

import android.net.Uri
import androidx.activity.ComponentActivity
import com.zflgm.player.core.common.service.system.SystemService
import com.zflgm.player.core.data.repository.NetworkConnectionRepository
import com.zflgm.player.core.data.repository.PlaylistRepository
import com.zflgm.player.core.media.network.NetworkClient
import com.zflgm.player.core.model.M3UPlaylist
import com.zflgm.player.core.model.M3UPlaylistItem
import com.zflgm.player.core.model.NetworkConnection
import com.zflgm.player.core.model.NetworkFile
import com.zflgm.player.core.model.PlaylistItemSortMode
import com.zflgm.player.core.model.PlaylistRecord
import com.zflgm.player.core.model.PlaylistSnapshotDiff
import com.zflgm.player.core.model.PlaylistSummary
import com.zflgm.player.core.model.PlaylistType
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

internal data class CreateM3UCall(
    val type: PlaylistType,
    val source: String,
    val playlist: M3UPlaylist,
)

internal class FakePlaylistRepository : PlaylistRepository {
    val playlists = MutableStateFlow<List<PlaylistSummary>>(emptyList())
    val playlist = MutableStateFlow<PlaylistRecord?>(null)
    val createM3UCalls = mutableListOf<CreateM3UCall>()
    val replacementCalls = mutableListOf<Pair<Long, List<M3UPlaylistItem>>>()
    var createM3UFailure: Throwable? = null
    var replaceFailure: Throwable? = null
    var createdId: Long = 42

    override fun observePlaylists(): Flow<List<PlaylistSummary>> = playlists

    override fun observePlaylist(playlistId: Long): Flow<PlaylistRecord?> = playlist

    override suspend fun getPlaylist(playlistId: Long): PlaylistRecord? = playlist.value

    override suspend fun create(name: String, videoUris: List<String>): Long = createdId

    override suspend fun createM3U(
        type: PlaylistType,
        source: String,
        playlist: M3UPlaylist,
    ): Long {
        createM3UCalls += CreateM3UCall(type, source, playlist)
        createM3UFailure?.let { throw it }
        return createdId
    }

    override suspend fun replaceM3UItems(
        playlistId: Long,
        items: List<M3UPlaylistItem>,
    ) {
        replacementCalls += playlistId to items
        replaceFailure?.let { throw it }
    }

    val snapshotCreations = mutableListOf<Pair<String, String>>()
    val snapshotAppends = mutableListOf<Pair<Long, List<String>>>()
    val snapshotRefreshes = mutableListOf<Pair<Long, List<String>>>()
    var snapshotDiff = PlaylistSnapshotDiff(added = 0, removed = 0)

    override suspend fun createNetworkSnapshot(name: String, source: String): Long {
        snapshotCreations += name to source
        return createdId
    }

    override suspend fun appendNetworkSnapshotItems(playlistId: Long, videoUris: List<String>): Int {
        snapshotAppends += playlistId to videoUris
        return videoUris.size
    }

    override suspend fun refreshNetworkSnapshot(
        playlistId: Long,
        discoveredUris: List<String>,
    ): PlaylistSnapshotDiff {
        snapshotRefreshes += playlistId to discoveredUris
        return snapshotDiff
    }

    override suspend fun rename(playlistId: Long, name: String) = Unit

    override suspend fun delete(playlistId: Long) = Unit

    override suspend fun addVideos(playlistId: Long, videoUris: List<String>): Int = 0

    override suspend fun removeVideo(playlistId: Long, videoUri: String) = Unit

    val orderReplacements = mutableListOf<List<String>>()
    var insertionOrderRestorations = 0

    override suspend fun replaceOrder(playlistId: Long, orderedUris: List<String>) {
        orderReplacements += orderedUris
    }

    override suspend fun restoreInsertionOrder(playlistId: Long) {
        insertionOrderRestorations++
    }

    val playlistOrderReplacements = mutableListOf<List<Long>>()

    override suspend fun replacePlaylistOrder(orderedIds: List<Long>) {
        playlistOrderReplacements += orderedIds
    }

    val itemSortWrites = mutableListOf<PlaylistItemSortMode>()

    override suspend fun setItemSort(playlistId: Long, sort: PlaylistItemSortMode) {
        itemSortWrites += sort
    }

    val networkSources = mutableMapOf<Long, MutableList<String>>()

    override suspend fun addNetworkSource(playlistId: Long, source: String) {
        val sources = networkSources.getOrPut(playlistId) { mutableListOf() }
        if (source !in sources) sources += source
    }

    override suspend fun getNetworkSources(playlistId: Long): List<String> =
        networkSources[playlistId].orEmpty()

    override suspend fun markNetworkSourcesRefreshed(playlistId: Long, refreshedAt: Long) = Unit

    override suspend fun markVideoPlayed(playlistId: Long, videoUri: String) = Unit

    override suspend fun countFilePlaylistsBySource(source: String): Int = 0
}

internal class FakeSystemService : SystemService {
    val toasts = mutableListOf<String>()

    override fun initialize(activity: ComponentActivity) = Unit

    override suspend fun pickFolder(): Uri? = null

    override fun getString(stringResId: Int): String = "string-$stringResId"

    override fun getQuantityString(
        pluralsResId: Int,
        quantity: Int,
        vararg formatArgs: Any,
    ): String = "plurals-$pluralsResId-$quantity"

    override fun showToast(text: String, duration: Int) {
        toasts += text
    }
}

internal class FakeNetworkConnectionRepository(
    private val connections: List<NetworkConnection>,
) : NetworkConnectionRepository {
    override fun getConnections(): Flow<List<NetworkConnection>> = flowOf(connections)

    override suspend fun getConnection(id: Long): NetworkConnection? = connections.firstOrNull { it.id == id }

    override suspend fun upsert(connection: NetworkConnection): Long = error("Not used")

    override suspend fun delete(id: Long) = error("Not used")
}

internal class FakeNetworkClient(
    private val filesByPath: Map<String, List<NetworkFile>>,
    private val connectResult: Result<Unit> = Result.success(Unit),
) : NetworkClient {
    var disconnects = 0
        private set

    override val rootPath: String = ""

    override suspend fun connect(): Result<Unit> = connectResult

    override suspend fun disconnect() {
        disconnects++
    }

    override fun isConnected(): Boolean = true

    override suspend fun listFiles(path: String): Result<List<NetworkFile>> {
        val files = filesByPath[path] ?: return Result.failure(IllegalStateException("No such folder $path"))
        return Result.success(files)
    }

    override suspend fun fileSize(path: String): Long = error("Not used")

    override suspend fun openStream(path: String, offset: Long): InputStream = error("Not used")
}
