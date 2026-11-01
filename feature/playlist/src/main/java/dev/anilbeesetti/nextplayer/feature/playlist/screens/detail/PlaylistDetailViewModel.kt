package dev.anilbeesetti.nextplayer.feature.playlist.screens.detail

import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.common.service.system.SystemService
import dev.anilbeesetti.nextplayer.core.data.playlist.M3UParser
import dev.anilbeesetti.nextplayer.core.data.repository.NetworkConnectionRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PlaylistRepository
import dev.anilbeesetti.nextplayer.core.domain.ObservePlaylistUseCase
import dev.anilbeesetti.nextplayer.core.media.network.NetworkClientFactory
import dev.anilbeesetti.nextplayer.core.media.network.NetworkDirectoryScanner
import dev.anilbeesetti.nextplayer.core.media.network.NetworkUri
import dev.anilbeesetti.nextplayer.core.model.M3UPlaylist
import dev.anilbeesetti.nextplayer.core.model.Playlist
import dev.anilbeesetti.nextplayer.core.model.PlaylistItem
import dev.anilbeesetti.nextplayer.core.model.PlaylistType
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.base.ActionState
import dev.anilbeesetti.nextplayer.core.ui.base.DataState
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class PlaylistDetailViewModel(
    observePlaylist: ObservePlaylistUseCase,
    private val playlistRepository: PlaylistRepository,
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
            val discovered = directoryScanner
                .scan(client, rootPath, NetworkUri.isRecursiveSnapshot(source))
                .map { file -> NetworkUri.build(connection, file.path).toString() }
                .toList()
            val diff = playlistRepository.refreshNetworkSnapshot(input.playlistId, discovered)
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
}
