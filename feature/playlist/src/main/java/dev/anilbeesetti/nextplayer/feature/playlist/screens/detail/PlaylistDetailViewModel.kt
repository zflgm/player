package dev.anilbeesetti.nextplayer.feature.playlist.screens.detail

import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.common.service.system.SystemService
import dev.anilbeesetti.nextplayer.core.data.playlist.M3UParser
import dev.anilbeesetti.nextplayer.core.data.repository.MediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.NetworkConnectionRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PlaylistRepository
import dev.anilbeesetti.nextplayer.core.domain.ObservePlaylistUseCase
import dev.anilbeesetti.nextplayer.core.media.network.NetworkClient
import dev.anilbeesetti.nextplayer.core.media.network.NetworkClientFactory
import dev.anilbeesetti.nextplayer.core.media.network.NetworkDirectoryScanner
import dev.anilbeesetti.nextplayer.core.media.network.NetworkUri
import dev.anilbeesetti.nextplayer.core.media.network.ScanEvent
import dev.anilbeesetti.nextplayer.core.model.M3UPlaylist
import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import dev.anilbeesetti.nextplayer.core.model.NetworkFile
import dev.anilbeesetti.nextplayer.core.model.Playlist
import dev.anilbeesetti.nextplayer.core.model.PlaylistItem
import dev.anilbeesetti.nextplayer.core.model.PlaylistType
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.base.ActionState
import dev.anilbeesetti.nextplayer.core.ui.base.DataState
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
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

    init {
        viewModelScope.launch {
            observePlaylist(input.playlistId).collect { playlist ->
                stateInternal.update { currentState ->
                    currentState.copy(
                        playlistDataState = DataState.Success(playlist),
                        isReordering = currentState.isReordering &&
                            playlist?.isOrderEditable == true &&
                            playlist.items.size > 1,
                    )
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
                it.copy(isSearching = true, isReordering = false)
            }
            is PlaylistDetailUiAction.OnSearchQueryChange -> stateInternal.update {
                it.copy(searchQuery = action.query)
            }
            is PlaylistDetailUiAction.OnCloseSearchClick -> stateInternal.update {
                it.copy(isSearching = false, searchQuery = "")
            }
            is PlaylistDetailUiAction.OnReorderClick -> {
                if (currentPlaylist()?.isOrderEditable == true) {
                    stateInternal.update {
                        it.copy(isReordering = true, isSearching = false, searchQuery = "")
                    }
                }
            }
            is PlaylistDetailUiAction.OnFinishReorderingClick -> stateInternal.update {
                it.copy(isReordering = false)
            }
            is PlaylistDetailUiAction.OnPlay -> play(action.startUri)
            is PlaylistDetailUiAction.Refresh -> refresh()
            is PlaylistDetailUiAction.SortBy -> sortBy(action.sort)
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

    private fun sortBy(sort: PlaylistSort) {
        val playlist = currentPlaylist() ?: return
        if (!playlist.isOrderEditable) return
        if (sort == PlaylistSort.INSERTION_ORDER) {
            // Only the rows know how they were added: the list on screen is already read back by position.
            updatePlaylist(block = { playlistRepository.restoreInsertionOrder(input.playlistId) })
            return
        }
        val orderedUris = playlistUrisForSort(playlist.items, sort)
        updatePlaylist(
            block = { playlistRepository.replaceOrder(input.playlistId, orderedUris) },
        )
    }

    private fun refresh() {
        val playlist = currentPlaylist() ?: return
        if (playlist.type == PlaylistType.LOCAL || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            stateInternal.update { it.copy(isRefreshing = true) }
            try {
                if (playlist.type == PlaylistType.NETWORK) {
                    refreshSnapshot(playlist)
                } else {
                    val parsed = parseLinkedSource(playlist).getOrThrow()
                    playlistRepository.replaceM3UItems(input.playlistId, parsed.items)
                    showToast(systemService.getString(R.string.playlist_refresh_succeeded))
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
            },
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
            block = { playlistRepository.addVideos(input.playlistId, uris) },
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
        val client = addClient ?: return
        if (browse.pathStack.size <= 1) return
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
    val isReordering: Boolean = false,
    val showRemoveDialogFor: PlaylistItem? = null,
    val showAddSheet: Boolean = false,
    val addTab: AddSheetTab = AddSheetTab.LOCAL,
    val addFolders: List<AddFolderEntry> = emptyList(),
    val addLocalVideos: List<Video> = emptyList(),
    val addSelection: Set<String> = emptySet(),
    val addConnections: List<NetworkConnection> = emptyList(),
    val addBrowse: AddBrowseState? = null,
    val isAddingItems: Boolean = false,
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
    data object OnReorderClick : PlaylistDetailUiAction
    data object OnFinishReorderingClick : PlaylistDetailUiAction
    data class OnPlay(val startUri: Uri) : PlaylistDetailUiAction
    data object Refresh : PlaylistDetailUiAction
    data class SortBy(val sort: PlaylistSort) : PlaylistDetailUiAction
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
