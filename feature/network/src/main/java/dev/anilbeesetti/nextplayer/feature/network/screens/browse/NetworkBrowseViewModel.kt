package dev.anilbeesetti.nextplayer.feature.network.screens.browse

import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.common.service.system.SystemService
import dev.anilbeesetti.nextplayer.core.data.repository.MediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.NetworkConnectionRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PlaylistRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.network.NetworkClient
import dev.anilbeesetti.nextplayer.core.media.network.NetworkClientFactory
import dev.anilbeesetti.nextplayer.core.media.network.NetworkDirectoryScanner
import dev.anilbeesetti.nextplayer.core.media.network.NetworkUri
import dev.anilbeesetti.nextplayer.core.media.network.isNetworkVideoFile
import dev.anilbeesetti.nextplayer.core.media.network.sftp.HostKeyMismatch
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import dev.anilbeesetti.nextplayer.core.model.NetworkFile
import dev.anilbeesetti.nextplayer.core.model.PlaylistType
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.chunked
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

data class NetworkBrowseUiState(
    val title: String = "",
    val files: List<NetworkFile> = emptyList(),
    val playbackHistory: Map<String, Video> = emptyMap(),
    val recentlyPlayedPath: String? = null,
    val preferences: ApplicationPreferences = ApplicationPreferences(),
    val isLoading: Boolean = true,
    val error: NetworkBrowseError? = null,
    val snapshot: NetworkSnapshotState? = null,
)

/** Building a snapshot playlist from the folder on screen. */
sealed interface NetworkSnapshotState {
    data class Ask(
        val suggestedName: String,
        val recursive: Boolean = true,
        val failed: Boolean = false,
        val emptyResult: Boolean = false,
        /** The folder holds only sub-folders, so scanning them is the only way to find videos. */
        val subfoldersRequired: Boolean = false,
        val targets: List<NetworkPlaylistTarget> = emptyList(),
        val targetId: Long? = null,
    ) : NetworkSnapshotState

    data class Building(val discovered: Int) : NetworkSnapshotState
}

/** An existing network playlist the scanned videos can be added to. */
data class NetworkPlaylistTarget(val id: Long, val name: String, val itemCount: Int)

data class NetworkBrowseHostKeyMismatch(
    val trustedFingerprint: String,
    val presentedFingerprint: String,
)

data class NetworkBrowseError(
    val message: String?,
    val hostKeyMismatch: NetworkBrowseHostKeyMismatch? = null,
)

/**
 * Browses a single folder on a network connection. Each folder is its own navigation destination
 * (like the media picker), so back navigation returns to the already-loaded parent instantly.
 */
@KoinViewModel
class NetworkBrowseViewModel(
    @InjectedParam private val input: Input,
    @InjectedParam internal var output: Output,
    private val repository: NetworkConnectionRepository,
    private val clientFactory: NetworkClientFactory,
    private val playlistRepository: PlaylistRepository,
    private val directoryScanner: NetworkDirectoryScanner,
    private val systemService: SystemService,
    mediaRepository: MediaRepository,
    preferencesRepository: PreferencesRepository,
) : MviViewModel<NetworkBrowseUiState, NetworkBrowseAction>() {

    data class Input(val connectionId: Long, val path: String?)
    data class Output(
        val navigateUp: () -> Unit,
        val playVideos: (List<Uri>, Uri) -> Unit,
        val openFolder: (Long, String) -> Unit,
    )

    private val connectionId = input.connectionId
    private val path = input.path

    private val connection = MutableStateFlow<NetworkConnection?>(null)
    private val networkTargets = MutableStateFlow<List<NetworkPlaylistTarget>>(emptyList())
    private var client: NetworkClient? = null
    private var currentPath: String? = path

    private val stateInternal = MutableStateFlow(NetworkBrowseUiState())
    override val state: StateFlow<NetworkBrowseUiState> = stateInternal.asStateFlow()

    private var snapshotJob: Job? = null

    init {
        viewModelScope.launch {
            combine(connection, mediaRepository.observePlaybackHistory()) { conn, history ->
                val folderPrefix = currentPath?.trimEnd('/').orEmpty()
                if (conn == null) {
                    emptyMap()
                } else {
                    history.mapNotNull { video ->
                        val uri = video.uriString.toUri()
                        if (!NetworkUri.isNetworkUri(uri) || NetworkUri.connectionIdOf(uri) != connectionId) return@mapNotNull null
                        val filePath = NetworkUri.filePathOf(uri, conn.protocol)
                        if (uri != NetworkUri.build(conn, filePath)) return@mapNotNull null
                        if (folderPrefix.isNotEmpty() && !filePath.startsWith("$folderPrefix/")) return@mapNotNull null
                        filePath to video
                    }.toMap()
                }
            }.collect { playbackHistory ->
                stateInternal.update {
                    it.copy(
                        playbackHistory = playbackHistory,
                        recentlyPlayedPath = playbackHistory.maxByOrNull { it.value.lastPlayedAt?.time ?: Long.MIN_VALUE }?.key,
                    )
                }
            }
        }
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect { preferences ->
                stateInternal.update { it.copy(preferences = preferences) }
            }
        }
        viewModelScope.launch {
            playlistRepository.observePlaylists()
                .map { playlists ->
                    playlists.filter { it.type == PlaylistType.NETWORK }
                        .map { NetworkPlaylistTarget(id = it.id, name = it.name, itemCount = it.itemCount) }
                }
                .collect { targets ->
                    networkTargets.value = targets
                    stateInternal.update {
                        val ask = it.snapshot as? NetworkSnapshotState.Ask ?: return@update it
                        it.copy(snapshot = ask.copy(targets = targets))
                    }
                }
        }
        connectAndLoad()
    }

    /** Loads the connection, (re)establishes the client, then lists the current folder. */
    private fun connectAndLoad() {
        stateInternal.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val conn = connection.value ?: repository.getConnection(connectionId)
            if (conn == null) {
                stateInternal.update {
                    it.copy(
                        isLoading = false,
                        error = NetworkBrowseError("Connection not found"),
                    )
                }
                return@launch
            }
            val activeClient = client ?: clientFactory.create(conn).also { client = it }
            if (currentPath == null) currentPath = activeClient.rootPath
            connection.value = conn
            if (!activeClient.isConnected()) {
                val connected = activeClient.connect()
                if (connected.isFailure) {
                    stateInternal.update {
                        it.copy(
                            title = title(conn),
                            isLoading = false,
                            error = connected.exceptionOrNull()?.toNetworkBrowseError(),
                        )
                    }
                    return@launch
                }
            }
            loadCurrent()
        }
    }

    private fun loadCurrent() {
        val client = client ?: return
        val conn = connection.value ?: return
        val path = currentPath ?: return
        stateInternal.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            client.listFiles(path).fold(
                onSuccess = { files ->
                    val visible = files
                        .filter { it.isDirectory || isNetworkVideoFile(it.name) }
                        .sortedWith(compareByDescending<NetworkFile> { it.isDirectory }.thenBy { it.name.lowercase() })
                    stateInternal.update {
                        it.copy(
                            title = title(conn),
                            files = visible,
                            isLoading = false,
                        )
                    }
                },
                onFailure = { e ->
                    stateInternal.update { it.copy(isLoading = false, error = e.toNetworkBrowseError()) }
                },
            )
        }
    }

    /** Root folder shows the connection name; nested folders show the last path segment. */
    private fun title(conn: NetworkConnection): String =
        path?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: conn.name

    override fun onAction(action: NetworkBrowseAction) {
        when (action) {
            is NetworkBrowseAction.NavigateUp -> output.navigateUp()
            is NetworkBrowseAction.OpenFolder -> output.openFolder(connectionId, action.file.path)

            is NetworkBrowseAction.Retry -> retry()
            is NetworkBrowseAction.PlayVideo -> playVideo(action.file)
            is NetworkBrowseAction.PlayAll -> stateInternal.value.files.firstOrNull { !it.isDirectory }?.let(::playVideo)

            is NetworkBrowseAction.ShowSnapshotDialog -> showSnapshotDialog()
            is NetworkBrowseAction.DismissSnapshotDialog -> dismissSnapshotDialog()
            is NetworkBrowseAction.ToggleSubfolders -> toggleSubfolders()
            is NetworkBrowseAction.SelectSnapshotTarget -> selectSnapshotTarget(action.playlistId)
            is NetworkBrowseAction.CreateSnapshot -> createSnapshot(action.name, action.recursive)
        }
    }

    private fun showSnapshotDialog() {
        if (stateInternal.value.snapshot != null) return
        val conn = connection.value ?: return
        val foldersOnly = stateInternal.value.files.isNotEmpty() &&
            stateInternal.value.files.none { file -> !file.isDirectory }
        stateInternal.update {
            it.copy(
                snapshot = NetworkSnapshotState.Ask(
                    suggestedName = title(conn),
                    subfoldersRequired = foldersOnly,
                    targets = networkTargets.value,
                ),
            )
        }
    }

    private fun dismissSnapshotDialog() {
        snapshotJob?.cancel()
        snapshotJob = null
        stateInternal.update { it.copy(snapshot = null) }
    }

    private fun updateAsk(update: (NetworkSnapshotState.Ask) -> NetworkSnapshotState.Ask) {
        stateInternal.update {
            val ask = it.snapshot as? NetworkSnapshotState.Ask ?: return@update it
            it.copy(snapshot = update(ask))
        }
    }

    private fun toggleSubfolders() {
        updateAsk { if (it.subfoldersRequired) it else it.copy(recursive = !it.recursive) }
    }

    private fun selectSnapshotTarget(playlistId: Long?) {
        updateAsk { it.copy(targetId = playlistId) }
    }

    /**
     * Creates the playlist row first, then streams discovered files into it in batches: a ten
     * thousand file folder must show progress instead of freezing, and leaving mid-scan discards
     * the half-built list. Adding to an existing playlist never deletes anything.
     */
    private fun createSnapshot(name: String, recursive: Boolean) {
        if (snapshotJob?.isActive == true) return
        val conn = connection.value ?: return
        val client = client ?: return
        val folderPath = currentPath ?: return
        val ask = stateInternal.value.snapshot as? NetworkSnapshotState.Ask
        val targetId = ask?.targetId
        val createdNew = targetId == null
        stateInternal.update { it.copy(snapshot = NetworkSnapshotState.Building(discovered = 0)) }
        snapshotJob = viewModelScope.launch {
            val source = NetworkUri.snapshotSource(conn, folderPath, recursive)
            val playlistId = targetId ?: playlistRepository.createNetworkSnapshot(name, source)
            var discovered = 0
            var added = 0
            var failure: Throwable? = null
            try {
                directoryScanner.scan(client, folderPath, recursive)
                    .chunked(SNAPSHOT_BATCH_SIZE)
                    .collect { batch ->
                        discovered += batch.size
                        added += playlistRepository.appendNetworkSnapshotItems(
                            playlistId = playlistId,
                            videoUris = batch.map { file -> NetworkUri.build(conn, file.path).toString() },
                        )
                        stateInternal.update { it.copy(snapshot = NetworkSnapshotState.Building(discovered)) }
                    }
            } catch (cancellation: CancellationException) {
                if (createdNew) withContext(NonCancellable) { playlistRepository.delete(playlistId) }
                throw cancellation
            } catch (error: Throwable) {
                failure = error
                if (createdNew) withContext(NonCancellable) { playlistRepository.delete(playlistId) }
            }
            // A folder with nothing in it is not worth an empty playlist, nor a silent no-op.
            val emptyResult = failure == null && discovered == 0
            if (emptyResult && createdNew) playlistRepository.delete(playlistId)
            val reopened = ask ?: NetworkSnapshotState.Ask(suggestedName = folderPath)
            stateInternal.update {
                it.copy(
                    snapshot = if (failure == null && !emptyResult) {
                        null
                    } else {
                        reopened.copy(
                            recursive = recursive,
                            failed = failure != null,
                            emptyResult = emptyResult,
                            targets = networkTargets.value,
                        )
                    },
                )
            }
            val messageRes = when {
                failure != null -> return@launch
                emptyResult -> R.string.snapshot_scan_empty
                createdNew -> R.string.snapshot_created
                added == 0 -> R.string.snapshot_refresh_unchanged
                else -> R.string.snapshot_items_added
            }
            systemService.showToast(
                systemService.getString(messageRes).format(if (createdNew) discovered else added),
                Toast.LENGTH_SHORT,
            )
        }
    }

    private fun retry() {
        if (currentPath == null || client?.isConnected() != true) connectAndLoad() else loadCurrent()
    }

    private fun playVideo(file: NetworkFile) {
        val conn = connection.value ?: return
        val videos = stateInternal.value.files.filter { !it.isDirectory }
        if (file !in videos) return
        output.playVideos(videos.map { NetworkUri.build(conn, it.path) }, NetworkUri.build(conn, file.path))
    }

    override fun onCleared() {
        val client = client ?: return
        // Best-effort disconnect on a detached IO scope, since viewModelScope is already cancelled.
        CoroutineScope(Dispatchers.IO).launch { runCatching { client.disconnect() } }
    }
}

sealed interface NetworkBrowseAction {
    data object NavigateUp : NetworkBrowseAction
    data class OpenFolder(val file: NetworkFile) : NetworkBrowseAction

    data object Retry : NetworkBrowseAction
    data class PlayVideo(val file: NetworkFile) : NetworkBrowseAction
    data object PlayAll : NetworkBrowseAction

    data object ShowSnapshotDialog : NetworkBrowseAction
    data object DismissSnapshotDialog : NetworkBrowseAction
    data object ToggleSubfolders : NetworkBrowseAction

    /** Null playlistId means the videos go into a playlist that is still to be created. */
    data class SelectSnapshotTarget(val playlistId: Long?) : NetworkBrowseAction
    data class CreateSnapshot(val name: String, val recursive: Boolean) : NetworkBrowseAction
}

/** Rows written per database transaction while a snapshot is being scanned. */
private const val SNAPSHOT_BATCH_SIZE = 500

private fun Throwable.toNetworkBrowseError(): NetworkBrowseError {
    val mismatch = generateSequence(this) { it.cause }
        .filterIsInstance<HostKeyMismatch>()
        .firstOrNull()
    return NetworkBrowseError(
        message = message,
        hostKeyMismatch = mismatch?.let {
            NetworkBrowseHostKeyMismatch(
                trustedFingerprint = it.expectedFingerprint,
                presentedFingerprint = it.presentedFingerprint,
            )
        },
    )
}
