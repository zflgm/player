package com.zflgm.player.feature.playlist.screens.detail

import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import com.zflgm.player.core.common.service.system.SystemService
import com.zflgm.player.core.data.playlist.M3UParser
import com.zflgm.player.core.data.repository.MediaRepository
import com.zflgm.player.core.data.repository.NetworkConnectionRepository
import com.zflgm.player.core.data.repository.PlaylistRepository
import com.zflgm.player.core.domain.ObservePlaylistUseCase
import com.zflgm.player.core.media.network.NetworkClient
import com.zflgm.player.core.media.network.NetworkClientFactory
import com.zflgm.player.core.media.network.NetworkDirectoryScanner
import com.zflgm.player.core.media.network.NetworkUri
import com.zflgm.player.core.media.network.ScanEvent
import com.zflgm.player.core.model.M3UPlaylist
import com.zflgm.player.core.model.NetworkConnection
import com.zflgm.player.core.model.NetworkFile
import com.zflgm.player.core.model.Playlist
import com.zflgm.player.core.model.PlaylistItem
import com.zflgm.player.core.model.PlaylistItemSortMode
import com.zflgm.player.core.model.PlaylistType
import com.zflgm.player.core.model.Video
import com.zflgm.player.core.ui.R
import com.zflgm.player.core.ui.base.ActionState
import com.zflgm.player.core.ui.base.DataState
import com.zflgm.player.core.ui.base.MviViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class PlaylistDetailViewModel(
    observePlaylist: ObservePlaylistUseCase,
    private val playlistRepository: PlaylistRepository,
    private val mediaRepository: MediaRepository,
    private val m3uParser: M3UParser,
    private val networkConnectionRepository: NetworkConnectionRepository,
    private val clientFactory: NetworkClientFactory,
    private val directoryScanner: NetworkDirectoryScanner,
    private val systemService: SystemService,
    @InjectedParam private val input: Input,
    @InjectedParam internal var output: Output,
) : MviViewModel<PlaylistDetailUiState, PlaylistDetailUiAction>() {

    data class Input(
        val playlistId: Long,
    )

    data class Output(
        val navigateUp: () -> Unit,
        val playPlaylist: (playlistId: Long, startUri: Uri) -> Unit,
    )

    private val stateInternal = MutableStateFlow(PlaylistDetailUiState())
    override val state: StateFlow<PlaylistDetailUiState> = stateInternal.asStateFlow()

    private var refreshJob: Job? = null
    private var addClient: NetworkClient? = null

    // Rows were just added under an automatic order; the next emission re-applies it, because the
    // repository only knows the order it was told, not the one the mode implies.
    private var reapplyOrderOnNextEmission = false

    init {
        viewModelScope.launch {
            observePlaylist(input.playlistId).collect { playlist ->
                stateInternal.update { currentState ->
                    currentState.copy(
                        playlistDataState = DataState.Success(playlist),
                        canRefresh = playlist.canRefresh(),
                    )
                }
                if (reapplyOrderOnNextEmission && playlist != null) {
                    reapplyOrderOnNextEmission = false
                    reapplyAutomaticOrder(playlist)
                }
            }
        }
        // Add-sheet sources: the local library grouped into folders, and saved connections.
        viewModelScope.launch {
            mediaRepository.observeVideos().collect { videos ->
                stateInternal.update {
                    it.copy(
                        addFolders = videos.groupBy { video -> video.parentPath }
                            .map { (parentPath, folderVideos) ->
                                AddFolderEntry(
                                    name = parentPath.trimEnd('/').substringAfterLast('/').ifBlank { parentPath },
                                    path = parentPath,
                                    videoUris = folderVideos.map { video -> video.uriString },
                                )
                            }
                            .sortedBy { folder -> folder.name.lowercase() },
                        addLocalVideos = videos.sortedBy { video -> video.displayName.lowercase() },
                    )
                }
            }
        }
        viewModelScope.launch {
            networkConnectionRepository.getConnections().collect { connections ->
                stateInternal.update { it.copy(addConnections = connections) }
            }
        }
    }

    override fun onAction(action: PlaylistDetailUiAction) {
        when (action) {
            is PlaylistDetailUiAction.OnNavigateUpClick -> output.navigateUp()
            is PlaylistDetailUiAction.OnSearchClick -> stateInternal.update {
                it.copy(isSearching = true)
            }
            is PlaylistDetailUiAction.OnSearchQueryChange -> stateInternal.update {
                it.copy(searchQuery = action.query)
            }
            is PlaylistDetailUiAction.OnCloseSearchClick -> stateInternal.update {
                it.copy(isSearching = false, searchQuery = "")
            }
            is PlaylistDetailUiAction.OnPlay -> play(action.startUri)
            is PlaylistDetailUiAction.Refresh -> refresh()
            is PlaylistDetailUiAction.SortBy -> sortBy(action.sort)
            is PlaylistDetailUiAction.UseCustomOrder -> useCustomOrder()
            is PlaylistDetailUiAction.ShowRemoveDialogFor -> stateInternal.update {
                it.copy(showRemoveDialogFor = action.item)
            }
            is PlaylistDetailUiAction.DismissRemoveDialog -> stateInternal.update {
                it.copy(showRemoveDialogFor = null)
            }
            is PlaylistDetailUiAction.RemoveVideo -> removeVideo(action.videoUri)
            is PlaylistDetailUiAction.ReplaceOrder -> replaceOrder(action.orderedUris)
            is PlaylistDetailUiAction.OnShowAddSheet -> stateInternal.update {
                it.copy(showAddSheet = true, addSelection = emptySet())
            }
            is PlaylistDetailUiAction.OnDismissAddSheet -> {
                closeAddClient()
                stateInternal.update {
                    it.copy(showAddSheet = false, addSelection = emptySet(), addBrowse = null)
                }
            }
            is PlaylistDetailUiAction.OnAddTabChange -> stateInternal.update {
                it.copy(addTab = action.tab)
            }
            is PlaylistDetailUiAction.OnToggleAddFolder -> toggleAddFolder(action.folder)
            is PlaylistDetailUiAction.OnToggleAddVideo -> toggleAddVideo(action.uri)
            is PlaylistDetailUiAction.OnConfirmAddLocal -> confirmAddLocal()
            is PlaylistDetailUiAction.OnOpenAddConnection -> openAddConnection(action.connection)
            is PlaylistDetailUiAction.OnAddBrowseUp -> addBrowseUp()
            is PlaylistDetailUiAction.OnAddBrowseInto -> addBrowseInto(action.folder)
            is PlaylistDetailUiAction.OnAddCurrentFolder -> addNetworkFolder()
        }
    }

    private fun play(startUri: Uri) {
        markVideoPlayed(startUri.toString())
        output.playPlaylist(input.playlistId, startUri)
    }

    /**
     * Keeps an automatically ordered list in its order after rows arrive: "name A-Z" would
     * otherwise end with a stray new episode at the bottom.
     */
    private suspend fun reapplyAutomaticOrder(playlist: Playlist) {
        if (!playlist.itemSort.isAutomatic) return
        val sort = playlist.itemSort.toPlaylistSortOrNull() ?: return
        // Appended rows already sit at the end in insertion order.
        if (sort == PlaylistSort.INSERTION_ORDER) return
        playlistRepository.replaceOrder(
            input.playlistId,
            playlistUrisForSort(playlist.items, sort),
        )
    }

    private fun sortBy(sort: PlaylistSort) {
        val playlist = currentPlaylist() ?: return
        if (!playlist.isOrderEditable) return
        if (sort == PlaylistSort.INSERTION_ORDER) {
            // Only the rows know how they were added: the list on screen is already read back by position.
            updatePlaylist(
                block = {
                    playlistRepository.restoreInsertionOrder(input.playlistId)
                    playlistRepository.setItemSort(input.playlistId, sort.toItemSortMode())
                },
            )
            return
        }
        val orderedUris = playlistUrisForSort(playlist.items, sort)
        updatePlaylist(
            block = {
                playlistRepository.replaceOrder(input.playlistId, orderedUris)
                playlistRepository.setItemSort(input.playlistId, sort.toItemSortMode())
            },
        )
    }

    private fun refresh() {
        val playlist = currentPlaylist() ?: return
        if (!playlist.canRefresh() || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            stateInternal.update { it.copy(isRefreshing = true) }
            try {
                when (playlist.type) {
                    PlaylistType.NETWORK -> refreshSnapshot(playlist)
                    PlaylistType.LOCAL -> refreshNetworkFolders(playlist)
                    else -> {
                        val parsed = parseLinkedSource(playlist).getOrThrow()
                        playlistRepository.replaceM3UItems(input.playlistId, parsed.items)
                        showToast(systemService.getString(R.string.playlist_refresh_succeeded))
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                showToast(
                    error.message?.takeIf(String::isNotBlank)
                        ?: systemService.getString(R.string.playlist_refresh_failed),
                )
            } finally {
                stateInternal.update { it.copy(isRefreshing = false) }
            }
        }
    }

    /** Re-scans the snapshot's own folder, then reconciles: untouched rows keep their progress. */
    private suspend fun refreshSnapshot(playlist: Playlist) {
        val source = playlist.source?.toUri()
            ?: throw IllegalStateException("Snapshot source is missing")
        val connectionId = NetworkUri.connectionIdOf(source)
            ?: throw IllegalStateException("Snapshot connection is missing")
        val connection = networkConnectionRepository.getConnection(connectionId)
            ?: throw IllegalStateException("Snapshot connection no longer exists")
        val client = clientFactory.create(connection)
        try {
            client.connect().getOrThrow()
            val rootPath = NetworkUri.filePathOf(source, connection.protocol)
            val discovered = mutableListOf<String>()
            val unreadableFolders = mutableListOf<String>()
            directoryScanner
                .scan(client, rootPath, NetworkUri.isRecursiveSnapshot(source))
                .collect { event ->
                    when (event) {
                        is ScanEvent.Video ->
                            discovered += NetworkUri.build(connection, event.file.path).toString()

                        is ScanEvent.FolderUnreadable -> unreadableFolders += event.path
                    }
                }
            // Rows inside folders that could not be listed stay: an unreadable folder says nothing
            // about the files in it, and reconciling against a partial scan would delete them.
            val preserved = playlist.items
                .map { it.uri }
                .filter { uri ->
                    unreadableFolders.any { folder ->
                        uri.isUnderFolderUri(NetworkUri.build(connection, folder).toString())
                    }
                }
            val diff = playlistRepository.refreshNetworkSnapshot(input.playlistId, discovered + preserved)
            if (diff.added > 0) reapplyOrderOnNextEmission = true
            if (unreadableFolders.isNotEmpty()) {
                showToast(
                    systemService.getString(R.string.snapshot_scan_partial).format(unreadableFolders.size),
                )
            }
            showToast(
                if (diff.added == 0 && diff.removed == 0) {
                    systemService.getString(R.string.snapshot_refresh_unchanged)
                } else {
                    systemService.getString(R.string.snapshot_refresh_result)
                        .format(diff.added, diff.removed)
                },
            )
        } finally {
            runCatching { client.disconnect() }
        }
    }

    /**
     * Re-scans the network folders feeding a hand-built playlist and appends whatever appeared on
     * the server since. Nothing is removed: an emptied folder says nothing about files the user
     * put there on purpose. Playlists built before folders were recorded fall back to the folders
     * their network items live in.
     */
    private suspend fun refreshNetworkFolders(playlist: Playlist) {
        val folders = (
            playlistRepository.getNetworkSources(playlist.id) +
                playlist.items.mapNotNull { item ->
                    NetworkUri.parentFolderSourceOf(Uri.parse(item.uri))
                }
            )
            .distinct()
            .take(MAX_REFRESH_FOLDERS)
        if (folders.isEmpty()) {
            showToast(systemService.getString(R.string.snapshot_refresh_unchanged))
            return
        }
        var added = 0
        var unreadable = 0
        for (source in folders) {
            val sourceUri = Uri.parse(source)
            val connectionId = NetworkUri.connectionIdOf(sourceUri) ?: continue
            val connection = networkConnectionRepository.getConnection(connectionId) ?: continue
            val client = clientFactory.create(connection)
            try {
                client.connect().getOrThrow()
                val discovered = mutableListOf<String>()
                directoryScanner
                    .scan(
                        client = client,
                        rootPath = NetworkUri.filePathOf(sourceUri, connection.protocol),
                        recursive = NetworkUri.isRecursiveSnapshot(sourceUri),
                    )
                    .collect { event ->
                        when (event) {
                            is ScanEvent.Video ->
                                discovered += NetworkUri.build(connection, event.file.path).toString()

                            is ScanEvent.FolderUnreadable -> unreadable++
                        }
                    }
                if (discovered.isNotEmpty()) {
                    added += playlistRepository.addVideos(playlist.id, discovered)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // One unreachable share must not hide the results of the folders that did answer.
                unreadable++
            } finally {
                runCatching { client.disconnect() }
            }
        }
        playlistRepository.markNetworkSourcesRefreshed(playlist.id, System.currentTimeMillis())
        if (added > 0) reapplyOrderOnNextEmission = true
        if (unreadable > 0) {
            showToast(systemService.getString(R.string.snapshot_scan_partial).format(unreadable))
        }
        showToast(
            if (added == 0) {
                systemService.getString(R.string.snapshot_refresh_unchanged)
            } else {
                systemService.getString(R.string.refresh_added_result).format(added)
            },
        )
    }

    private suspend fun parseLinkedSource(playlist: Playlist): Result<M3UPlaylist> {
        val source = playlist.source
            ?: return Result.failure(IllegalStateException("Linked playlist source is missing"))
        return when (playlist.type) {
            PlaylistType.M3U_URL -> m3uParser.parseUrl(source)
            PlaylistType.M3U_FILE -> m3uParser.parseUri(source.toUri())
            PlaylistType.NETWORK -> Result.failure(
                IllegalStateException("Network snapshots are re-scanned, not parsed"),
            )
            PlaylistType.LOCAL -> Result.failure(
                IllegalStateException("Local playlists do not have a linked source"),
            )
        }
    }

    private fun removeVideo(videoUri: String) {
        updatePlaylist(
            block = { playlistRepository.removeVideo(input.playlistId, videoUri) },
            onSuccess = {
                stateInternal.update { it.copy(showRemoveDialogFor = null) }
            },
        )
    }

    private fun replaceOrder(orderedUris: List<String>) {
        updatePlaylist(
            block = {
                playlistRepository.replaceOrder(input.playlistId, orderedUris)
                // Reordering by hand is what "custom" means from now on.
                playlistRepository.setItemSort(input.playlistId, PlaylistItemSortMode.CUSTOM)
            },
        )
    }

    private fun useCustomOrder() {
        updatePlaylist(
            block = { playlistRepository.setItemSort(input.playlistId, PlaylistItemSortMode.CUSTOM) },
        )
    }

    private fun markVideoPlayed(videoUri: String) {
        viewModelScope.launch {
            try {
                playlistRepository.markVideoPlayed(input.playlistId, videoUri)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Playback should still start if saving the resume item fails.
            }
        }
    }

    private fun updatePlaylist(
        block: suspend () -> Unit,
        onSuccess: () -> Unit = {},
    ) {
        if (state.value.updateActionState.isRunning) return
        viewModelScope.launch {
            stateInternal.update { it.copy(updateActionState = ActionState.Running) }
            try {
                block()
                onSuccess()
                stateInternal.update { it.copy(updateActionState = ActionState.Success) }
            } catch (cancellation: CancellationException) {
                stateInternal.update { it.copy(updateActionState = ActionState.Idle) }
                throw cancellation
            } catch (error: Throwable) {
                val message = systemService.getString(R.string.playlist_update_failed)
                stateInternal.update {
                    it.copy(updateActionState = ActionState.Failed(Error(message, error)))
                }
                showToast(message)
            }
        }
    }

    private fun Playlist?.canRefresh(): Boolean =
        this != null && (
            type != PlaylistType.LOCAL ||
                items.any { item -> NetworkUri.isNetworkUri(Uri.parse(item.uri)) }
            )

    private fun currentPlaylist(): Playlist? =
        (state.value.playlistDataState as? DataState.Success)?.value

    // ----- add sheet: local library -----

    private fun toggleAddFolder(folder: AddFolderEntry) {
        stateInternal.update { state ->
            val allSelected = folder.videoUris.all { it in state.addSelection }
            val selection = if (allSelected) {
                state.addSelection - folder.videoUris.toSet()
            } else {
                state.addSelection + folder.videoUris
            }
            state.copy(addSelection = selection)
        }
    }

    private fun toggleAddVideo(uri: String) {
        stateInternal.update { state ->
            state.copy(
                addSelection = if (uri in state.addSelection) state.addSelection - uri else state.addSelection + uri,
            )
        }
    }

    private fun confirmAddLocal() {
        val uris = state.value.addSelection.toList()
        if (uris.isEmpty()) return
        updatePlaylist(
            block = {
                playlistRepository.addVideos(input.playlistId, uris)
                reapplyOrderOnNextEmission = true
            },
            onSuccess = {
                closeAddClient()
                stateInternal.update {
                    it.copy(showAddSheet = false, addSelection = emptySet(), addBrowse = null)
                }
                showToast(systemService.getString(R.string.add_items_succeeded).format(uris.size))
            },
        )
    }

    // ----- add sheet: network folders -----

    private fun openAddConnection(connection: NetworkConnection) {
        closeAddClient()
        stateInternal.update {
            it.copy(
                addBrowse = AddBrowseState(
                    connectionId = connection.id,
                    connectionName = connection.name,
                    pathStack = listOf(""),
                    folders = emptyList(),
                    isLoading = true,
                ),
            )
        }
        viewModelScope.launch {
            try {
                val client = clientFactory.create(connection)
                client.connect().getOrThrow()
                addClient = client
                addBrowseLoad(client)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                stateInternal.update {
                    it.copy(addBrowse = it.addBrowse?.copy(isLoading = false, error = error.message))
                }
            }
        }
    }

    private fun addBrowseInto(folder: NetworkFile) {
        val browse = state.value.addBrowse ?: return
        val client = addClient ?: return
        stateInternal.update {
            it.copy(
                addBrowse = browse.copy(
                    pathStack = browse.pathStack + folder.path,
                    folders = emptyList(),
                    isLoading = true,
                    error = null,
                ),
            )
        }
        viewModelScope.launch { addBrowseLoad(client) }
    }

    private fun addBrowseUp() {
        val browse = state.value.addBrowse ?: return
        if (browse.pathStack.size <= 1) {
            // At the connection root, one level up is the connection list itself.
            closeAddClient()
            stateInternal.update { it.copy(addBrowse = null) }
            return
        }
        val client = addClient ?: return
        stateInternal.update {
            it.copy(
                addBrowse = browse.copy(
                    pathStack = browse.pathStack.dropLast(1),
                    folders = emptyList(),
                    isLoading = true,
                    error = null,
                ),
            )
        }
        viewModelScope.launch { addBrowseLoad(client) }
    }

    private suspend fun addBrowseLoad(client: NetworkClient) {
        val path = state.value.addBrowse?.pathStack?.lastOrNull() ?: return
        client.listFiles(path).fold(
            onSuccess = { files ->
                stateInternal.update {
                    it.copy(
                        addBrowse = it.addBrowse?.copy(
                            folders = files.filter { file -> file.isDirectory }
                                .sortedBy { file -> file.name.lowercase() },
                            isLoading = false,
                        ),
                    )
                }
            },
            onFailure = { error ->
                stateInternal.update {
                    it.copy(addBrowse = it.addBrowse?.copy(isLoading = false, error = error.message))
                }
            },
        )
    }

    private fun addNetworkFolder() {
        val browse = state.value.addBrowse ?: return
        val client = addClient ?: return
        val connection = state.value.addConnections.firstOrNull { it.id == browse.connectionId } ?: return
        val folderPath = browse.pathStack.lastOrNull() ?: return
        if (state.value.updateActionState.isRunning || state.value.isAddingItems) return
        viewModelScope.launch {
            stateInternal.update { it.copy(isAddingItems = true) }
            try {
                val uris = mutableListOf<String>()
                var unreadable = 0
                directoryScanner
                    .scan(client, folderPath, recursive = true)
                    .collect { event ->
                        when (event) {
                            is ScanEvent.Video ->
                                uris += NetworkUri.build(connection, event.file.path).toString()

                            is ScanEvent.FolderUnreadable -> unreadable++
                        }
                    }
                if (uris.isEmpty()) {
                    showToast(systemService.getString(R.string.snapshot_scan_empty))
                } else {
                    playlistRepository.addVideos(input.playlistId, uris)
                    // Remember the folder, otherwise this list has nothing to re-scan later.
                    playlistRepository.addNetworkSource(
                        playlistId = input.playlistId,
                        source = NetworkUri.snapshotSource(connection, folderPath, recursive = true),
                    )
                    reapplyOrderOnNextEmission = true
                    closeAddClient()
                    stateInternal.update {
                        it.copy(showAddSheet = false, addSelection = emptySet(), addBrowse = null)
                    }
                    showToast(systemService.getString(R.string.add_items_succeeded).format(uris.size))
                }
                if (unreadable > 0) {
                    showToast(systemService.getString(R.string.snapshot_scan_partial).format(unreadable))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                showToast(systemService.getString(R.string.playlist_update_failed))
            } finally {
                stateInternal.update { it.copy(isAddingItems = false) }
            }
        }
    }

    private fun closeAddClient() {
        val client = addClient ?: return
        addClient = null
        viewModelScope.launch { runCatching { client.disconnect() } }
    }

    /** Whether this uri lives inside the folder [folderUri] (both NetworkUri strings). */
    private fun String.isUnderFolderUri(folderUri: String): Boolean {
        val base = folderUri.substringBefore('?').trimEnd('/')
        val path = substringBefore('?')
        return path == base || path.startsWith("$base/")
    }

    private fun showToast(message: String) {
        systemService.showToast(message, Toast.LENGTH_SHORT)
    }
}

data class PlaylistDetailUiState(
    val playlistDataState: DataState<Playlist?> = DataState.Loading,
    val updateActionState: ActionState = ActionState.Idle,
    val isRefreshing: Boolean = false,
    val isSearching: Boolean = false,
    val searchQuery: String = "",
    val showRemoveDialogFor: PlaylistItem? = null,
    val showAddSheet: Boolean = false,
    val addTab: AddSheetTab = AddSheetTab.LOCAL,
    val addFolders: List<AddFolderEntry> = emptyList(),
    val addLocalVideos: List<Video> = emptyList(),
    val addSelection: Set<String> = emptySet(),
    val addConnections: List<NetworkConnection> = emptyList(),
    val addBrowse: AddBrowseState? = null,
    val isAddingItems: Boolean = false,
    /** True when a refresh has something to re-scan: a linked source, or network items. */
    val canRefresh: Boolean = false,
)

enum class AddSheetTab { LOCAL, NETWORK }

data class AddFolderEntry(
    val name: String,
    val path: String,
    val videoUris: List<String>,
)

data class AddBrowseState(
    val connectionId: Long,
    val connectionName: String,
    /** Stack of client paths, innermost last; the top level is the connection's root. */
    val pathStack: List<String>,
    val folders: List<NetworkFile> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

sealed interface PlaylistDetailUiAction {
    data object OnNavigateUpClick : PlaylistDetailUiAction
    data object OnSearchClick : PlaylistDetailUiAction
    data class OnSearchQueryChange(val query: String) : PlaylistDetailUiAction
    data object OnCloseSearchClick : PlaylistDetailUiAction
    data class OnPlay(val startUri: Uri) : PlaylistDetailUiAction
    data object Refresh : PlaylistDetailUiAction
    data class SortBy(val sort: PlaylistSort) : PlaylistDetailUiAction
    data object UseCustomOrder : PlaylistDetailUiAction
    data class ShowRemoveDialogFor(val item: PlaylistItem) : PlaylistDetailUiAction
    data object DismissRemoveDialog : PlaylistDetailUiAction
    data class RemoveVideo(val videoUri: String) : PlaylistDetailUiAction
    data class ReplaceOrder(val orderedUris: List<String>) : PlaylistDetailUiAction
    data object OnShowAddSheet : PlaylistDetailUiAction
    data object OnDismissAddSheet : PlaylistDetailUiAction
    data class OnAddTabChange(val tab: AddSheetTab) : PlaylistDetailUiAction
    data class OnToggleAddFolder(val folder: AddFolderEntry) : PlaylistDetailUiAction
    data class OnToggleAddVideo(val uri: String) : PlaylistDetailUiAction
    data object OnConfirmAddLocal : PlaylistDetailUiAction
    data class OnOpenAddConnection(val connection: NetworkConnection) : PlaylistDetailUiAction
    data object OnAddBrowseUp : PlaylistDetailUiAction
    data class OnAddBrowseInto(val folder: NetworkFile) : PlaylistDetailUiAction
    data object OnAddCurrentFolder : PlaylistDetailUiAction
}

/** Enough for any realistic playlist; guards against scanning a whole server by accident. */
private const val MAX_REFRESH_FOLDERS = 32
