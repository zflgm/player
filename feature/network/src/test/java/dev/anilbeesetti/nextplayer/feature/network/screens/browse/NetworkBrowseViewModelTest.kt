package dev.anilbeesetti.nextplayer.feature.network.screens.browse

import android.net.Uri
import androidx.activity.ComponentActivity
import dev.anilbeesetti.nextplayer.core.common.service.system.SystemService
import dev.anilbeesetti.nextplayer.core.data.repository.NetworkConnectionRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PlaylistRepository
import dev.anilbeesetti.nextplayer.core.data.repository.fake.FakeMediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.fake.FakePreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.network.NetworkClient
import dev.anilbeesetti.nextplayer.core.media.network.NetworkClientFactory
import dev.anilbeesetti.nextplayer.core.media.network.NetworkDirectoryScanner
import dev.anilbeesetti.nextplayer.core.media.network.NetworkUri
import dev.anilbeesetti.nextplayer.core.media.network.sftp.HostKeyMismatch
import dev.anilbeesetti.nextplayer.core.model.M3UPlaylist
import dev.anilbeesetti.nextplayer.core.model.M3UPlaylistItem
import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import dev.anilbeesetti.nextplayer.core.model.NetworkFile
import dev.anilbeesetti.nextplayer.core.model.NetworkProtocol
import dev.anilbeesetti.nextplayer.core.model.PlaylistRecord
import dev.anilbeesetti.nextplayer.core.model.PlaylistSnapshotDiff
import dev.anilbeesetti.nextplayer.core.model.PlaylistSummary
import dev.anilbeesetti.nextplayer.core.model.PlaylistType
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.feature.network.MainDispatcherRule
import java.io.InputStream
import java.util.Date
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetworkBrowseViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `wrapped host key mismatch retains trusted and presented fingerprints`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val mismatch = HostKeyMismatch(
                expectedFingerprint = "SHA256:trusted",
                presentedFingerprint = "SHA256:presented",
            )
            val viewModel = viewModel(
                connectResult = Result.failure(IllegalStateException("SSH failed", mismatch)),
            )

            advanceUntilIdle()

            assertEquals(
                NetworkBrowseError(
                    message = "SSH failed",
                    hostKeyMismatch = NetworkBrowseHostKeyMismatch(
                        trustedFingerprint = "SHA256:trusted",
                        presentedFingerprint = "SHA256:presented",
                    ),
                ),
                viewModel.state.value.error,
            )
        }

    @Test
    fun `ordinary connection error keeps its message without fingerprint details`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = viewModel(
                connectResult = Result.failure(IllegalStateException("Server unavailable")),
            )

            advanceUntilIdle()

            assertEquals("Server unavailable", viewModel.state.value.error?.message)
            assertNull(viewModel.state.value.error?.hostKeyMismatch)
        }

    @Test
    fun `playing a video queues only folder videos in display order and preserves the selected item`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val conn = connection().copy(protocol = NetworkProtocol.WEBDAV)
            val first = NetworkFile("a #1.mp4", "Series/a #1.mp4", false)
            val second = NetworkFile("B.mp4", "Series/B.mp4", false)
            val folder = NetworkFile("Subfolder", "Series/Subfolder", true)
            val requests = mutableListOf<Pair<List<Uri>, Uri>>()
            val viewModel = viewModel(
                conn = conn,
                files = listOf(second, folder, NetworkFile("Notes.txt", "Series/Notes.txt", false), first),
                onPlayVideos = { uris, startUri -> requests.add(uris to startUri) },
            )
            advanceUntilIdle()

            viewModel.onAction(NetworkBrowseAction.PlayVideo(second))
            viewModel.onAction(NetworkBrowseAction.PlayAll)
            viewModel.onAction(NetworkBrowseAction.PlayVideo(folder))
            viewModel.onAction(NetworkBrowseAction.PlayVideo(NetworkFile("Missing.mp4", "Missing.mp4", false)))

            val queue = listOf(first, second).map { NetworkUri.build(conn, it.path) }
            assertEquals(listOf(queue to queue[1], queue to queue[0]), requests)
        }

    @Test
    fun `play all does nothing when the folder has no videos`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = viewModel(
            files = listOf(NetworkFile("Subfolder", "/Subfolder", true)),
            onPlayVideos = { _, _ -> error("An empty folder must not start playback") },
        )
        advanceUntilIdle()

        viewModel.onAction(NetworkBrowseAction.PlayAll)
    }

    @Test
    fun `history tracks progress and descendants without mixing connections or sibling folders`() =
        runTest(mainDispatcherRule.testDispatcher) {
            NetworkProtocol.entries.forEach { protocol ->
                val conn = connection().copy(protocol = protocol)
                val path = if (protocol == NetworkProtocol.FTP || protocol == NetworkProtocol.SFTP) "/Series" else "Series"
                val mediaRepository = FakeMediaRepository()
                val video = Video.sample.copy(
                    uriString = NetworkUri.build(conn, "$path/Season #1/Episode 1.mp4").toString(),
                    lastPlayedAt = Date(100),
                    playbackPosition = 250,
                    duration = 1_000,
                )
                mediaRepository.videos.addAll(
                    listOf(
                        video,
                        video.copy(uriString = NetworkUri.build(conn.copy(id = 8), "$path/Other.mp4").toString(), lastPlayedAt = Date(200)),
                        video.copy(uriString = NetworkUri.build(conn.copy(host = "other.example"), "$path/Other.mp4").toString(), lastPlayedAt = Date(300)),
                        video.copy(uriString = NetworkUri.build(conn, "${path}2/Other.mp4").toString(), lastPlayedAt = Date(400)),
                        video.copy(uriString = "content://media/external/video/media/1", lastPlayedAt = Date(500)),
                    ),
                )
                val viewModel = viewModel(conn = conn, path = path, mediaRepository = mediaRepository, connectionDelayMillis = 1_000)
                advanceUntilIdle()

                assertEquals(setOf("$path/Season #1/Episode 1.mp4"), viewModel.state.value.playbackHistory.keys)
                assertEquals("$path/Season #1/Episode 1.mp4", viewModel.state.value.recentlyPlayedPath)
                assertEquals(0.25f, viewModel.state.value.playbackHistory.values.single().playedPercentage)

                val finished = video.copy(
                    uriString = NetworkUri.build(conn, "$path/Episode 2.mp4").toString(),
                    lastPlayedAt = Date(600),
                    playbackPosition = -1,
                )
                mediaRepository.videos.add(finished)
                mediaRepository.notifyMediaChanged()
                advanceUntilIdle()
                viewModel.onAction(NetworkBrowseAction.Retry)
                advanceUntilIdle()

                assertEquals("$path/Episode 2.mp4", viewModel.state.value.recentlyPlayedPath)
                assertEquals(1f, viewModel.state.value.playbackHistory["$path/Episode 2.mp4"]?.playedPercentage)

                mediaRepository.videos.clear()
                mediaRepository.notifyMediaChanged()
                advanceUntilIdle()
                assertNull(viewModel.state.value.recentlyPlayedPath)
                assertTrue(viewModel.state.value.playbackHistory.isEmpty())
            }
        }

    @Test
    fun `display preferences update without reloading the folder`() = runTest(mainDispatcherRule.testDispatcher) {
        val preferences = FakePreferencesRepository()
        preferences.updateApplicationPreferences { it.copy(markLastPlayedMedia = false, showPlayedProgress = false) }
        val viewModel = viewModel(preferencesRepository = preferences)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.preferences.markLastPlayedMedia)
        assertFalse(viewModel.state.value.preferences.showPlayedProgress)

        preferences.updateApplicationPreferences { it.copy(markLastPlayedMedia = true, showPlayedProgress = true) }
        advanceUntilIdle()

        assertTrue(viewModel.state.value.preferences.markLastPlayedMedia)
        assertTrue(viewModel.state.value.preferences.showPlayedProgress)
    }

    @Test
    fun `snapshot dialog suggests the folder name and toggles subfolder scanning`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = viewModel(path = "/Series/Season 1")
            advanceUntilIdle()

            viewModel.onAction(NetworkBrowseAction.ShowSnapshotDialog)
            val ask = viewModel.state.value.snapshot as NetworkSnapshotState.Ask
            assertEquals("Season 1", ask.suggestedName)
            assertTrue(ask.recursive)
            assertFalse(ask.failed)

            viewModel.onAction(NetworkBrowseAction.ToggleSubfolders)
            val toggled = viewModel.state.value.snapshot as NetworkSnapshotState.Ask
            assertFalse(toggled.recursive)

            viewModel.onAction(NetworkBrowseAction.DismissSnapshotDialog)
            assertNull(viewModel.state.value.snapshot)
        }

    @Test
    fun `creating a snapshot writes discovered videos in batches and reports the total`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val conn = connection().copy(protocol = NetworkProtocol.SMB)
            val playlists = FakePlaylistRepository()
            val files = (1..550).map { NetworkFile("Video $it.mp4", "Series/Video $it.mp4", false) }
            val scanner = FakeDirectoryScanner(files)
            val systemService = FakeSystemService()
            val viewModel = viewModel(
                conn = conn,
                path = "Series",
                playlistRepository = playlists,
                directoryScanner = scanner,
                systemService = systemService,
            )
            advanceUntilIdle()

            viewModel.onAction(NetworkBrowseAction.ShowSnapshotDialog)
            viewModel.onAction(NetworkBrowseAction.CreateSnapshot(name = "Series", recursive = false))
            advanceUntilIdle()

            assertEquals(listOf("Series" to false), scanner.scans)
            assertEquals(1, playlists.creations.size)
            assertEquals("Series", playlists.creations.single().first)
            val source = Uri.parse(playlists.creations.single().second)
            assertEquals("smb", source.scheme)
            assertEquals("7", source.getQueryParameter("cid"))
            assertEquals("false", source.getQueryParameter("subdirs"))
            assertEquals("/Series", source.path)

            assertEquals(listOf(500, 50), playlists.appends.map { it.second.size })
            assertTrue(playlists.appends.all { it.first == playlists.nextId })
            val lastUri = Uri.parse(playlists.appends.last().second.last())
            assertEquals("/Series/Video 550.mp4", lastUri.path)
            assertEquals("7", lastUri.getQueryParameter("cid"))

            assertNull(viewModel.state.value.snapshot)
            assertEquals(
                listOf("string-${R.string.snapshot_created}".format(550)),
                systemService.toasts,
            )
        }

    @Test
    fun `dismissing a running snapshot deletes the half-built playlist`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val playlists = FakePlaylistRepository()
            val files = (1..500).map { NetworkFile("Video $it.mp4", "/Series/Video $it.mp4", false) }
            val scanner = FakeDirectoryScanner(files, hangAfter = 500)
            val viewModel = viewModel(
                path = "/Series",
                playlistRepository = playlists,
                directoryScanner = scanner,
            )
            advanceUntilIdle()

            viewModel.onAction(NetworkBrowseAction.CreateSnapshot(name = "Series", recursive = true))
            runCurrent()

            assertEquals(NetworkSnapshotState.Building(500), viewModel.state.value.snapshot)
            assertEquals(listOf(500), playlists.appends.map { it.second.size })
            assertTrue(playlists.deletions.isEmpty())

            viewModel.onAction(NetworkBrowseAction.DismissSnapshotDialog)
            advanceUntilIdle()

            assertEquals(listOf(playlists.nextId), playlists.deletions)
            assertNull(viewModel.state.value.snapshot)
        }

    @Test
    fun `a failed snapshot scan deletes the playlist and offers a retry`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val playlists = FakePlaylistRepository()
            val scanner = FakeDirectoryScanner(
                files = listOf(NetworkFile("Video.mp4", "/Series/Video.mp4", false)),
                failure = IllegalStateException("Series is unreadable"),
            )
            val systemService = FakeSystemService()
            val viewModel = viewModel(
                path = "/Series",
                playlistRepository = playlists,
                directoryScanner = scanner,
                systemService = systemService,
            )
            advanceUntilIdle()

            viewModel.onAction(NetworkBrowseAction.ShowSnapshotDialog)
            viewModel.onAction(NetworkBrowseAction.ToggleSubfolders)
            viewModel.onAction(NetworkBrowseAction.CreateSnapshot(name = "Series", recursive = false))
            advanceUntilIdle()

            assertEquals(listOf(playlists.nextId), playlists.deletions)
            val ask = viewModel.state.value.snapshot as NetworkSnapshotState.Ask
            assertTrue(ask.failed)
            assertFalse(ask.recursive)
            assertEquals("Series", ask.suggestedName)
            assertTrue(systemService.toasts.isEmpty())
        }

    @Test
    fun `adding a folder to an existing playlist appends without creating or deleting a row`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val playlists = FakePlaylistRepository()
            playlists.playlists.value = listOf(
                PlaylistSummary(id = 9, name = "Series", type = PlaylistType.NETWORK, itemCount = 3, lastRefreshedAt = null),
                PlaylistSummary(id = 10, name = "Local list", type = PlaylistType.LOCAL, itemCount = 5, lastRefreshedAt = null),
            )
            val scanner = FakeDirectoryScanner(listOf(NetworkFile("a.mp4", "/Series/a.mp4", false)))
            val systemService = FakeSystemService()
            val viewModel = viewModel(
                path = "/Series",
                playlistRepository = playlists,
                directoryScanner = scanner,
                systemService = systemService,
            )
            advanceUntilIdle()

            viewModel.onAction(NetworkBrowseAction.ShowSnapshotDialog)
            val ask = viewModel.state.value.snapshot as NetworkSnapshotState.Ask
            assertEquals(listOf(9L), ask.targets.map { it.id })
            assertNull(ask.targetId)

            viewModel.onAction(NetworkBrowseAction.SelectSnapshotTarget(9))
            viewModel.onAction(NetworkBrowseAction.CreateSnapshot(name = "ignored", recursive = true))
            advanceUntilIdle()

            assertTrue(playlists.creations.isEmpty())
            assertTrue(playlists.deletions.isEmpty())
            assertEquals(9L, playlists.appends.single().first)
            assertNull(viewModel.state.value.snapshot)
            assertEquals(listOf("string-${R.string.snapshot_items_added}".format(1)), systemService.toasts)
        }

    @Test
    fun `a folder holding only subfolders forces subfolder scanning`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = viewModel(
                path = "/Series",
                files = listOf(NetworkFile("Season 1", "/Series/Season 1", true)),
            )
            advanceUntilIdle()

            viewModel.onAction(NetworkBrowseAction.ShowSnapshotDialog)
            val ask = viewModel.state.value.snapshot as NetworkSnapshotState.Ask
            assertTrue(ask.subfoldersRequired)
            assertTrue(ask.recursive)

            viewModel.onAction(NetworkBrowseAction.ToggleSubfolders)
            assertTrue((viewModel.state.value.snapshot as NetworkSnapshotState.Ask).recursive)
        }

    @Test
    fun `an empty folder does not leave a new playlist behind`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val playlists = FakePlaylistRepository()
            val systemService = FakeSystemService()
            val viewModel = viewModel(
                path = "/Series",
                playlistRepository = playlists,
                directoryScanner = FakeDirectoryScanner(emptyList()),
                systemService = systemService,
            )
            advanceUntilIdle()

            viewModel.onAction(NetworkBrowseAction.ShowSnapshotDialog)
            viewModel.onAction(NetworkBrowseAction.CreateSnapshot(name = "Series", recursive = true))
            advanceUntilIdle()

            assertEquals(listOf(playlists.nextId), playlists.deletions)
            val ask = viewModel.state.value.snapshot as NetworkSnapshotState.Ask
            assertTrue(ask.emptyResult)
            assertFalse(ask.failed)
            assertEquals(listOf("string-${R.string.snapshot_scan_empty}"), systemService.toasts)
        }

    private fun viewModel(
        connectResult: Result<Unit> = Result.success(Unit),
        conn: NetworkConnection = connection(),
        path: String? = null,
        files: List<NetworkFile> = emptyList(),
        mediaRepository: FakeMediaRepository = FakeMediaRepository(),
        preferencesRepository: FakePreferencesRepository = FakePreferencesRepository(),
        playlistRepository: PlaylistRepository = FakePlaylistRepository(),
        directoryScanner: NetworkDirectoryScanner = FakeDirectoryScanner(emptyList()),
        systemService: SystemService = FakeSystemService(),
        onPlayVideos: (List<Uri>, Uri) -> Unit = { _, _ -> },
        connectionDelayMillis: Long = 0,
    ): NetworkBrowseViewModel {
        val client = FakeNetworkClient(connectResult, files)
        val factory = NetworkClientFactory { client }
        return NetworkBrowseViewModel(
            input = NetworkBrowseViewModel.Input(connectionId = conn.id, path = path),
            output = NetworkBrowseViewModel.Output(navigateUp = {}, playVideos = onPlayVideos, openFolder = { _, _ -> }),
            repository = FakeRepository(conn, connectionDelayMillis),
            clientFactory = factory,
            playlistRepository = playlistRepository,
            directoryScanner = directoryScanner,
            systemService = systemService,
            mediaRepository = mediaRepository,
            preferencesRepository = preferencesRepository,
        )
    }

    private fun connection() = NetworkConnection(
        id = 7,
        name = "Media server",
        protocol = NetworkProtocol.SFTP,
        host = "sftp.example",
        username = "media",
        hostKeyFingerprint = "SHA256:trusted",
    )
}

private class FakeRepository(
    private val connection: NetworkConnection,
    private val connectionDelayMillis: Long,
) : NetworkConnectionRepository {
    override fun getConnections(): Flow<List<NetworkConnection>> = flowOf(listOf(connection))

    override suspend fun getConnection(id: Long): NetworkConnection {
        delay(connectionDelayMillis)
        return connection
    }

    override suspend fun upsert(connection: NetworkConnection): Long = error("Not used")

    override suspend fun delete(id: Long) = error("Not used")
}

private class FakeNetworkClient(
    private val connectResult: Result<Unit>,
    private val files: List<NetworkFile>,
) : NetworkClient {
    override val rootPath: String = "/"

    override suspend fun connect(): Result<Unit> = connectResult

    override suspend fun disconnect() = Unit

    override fun isConnected(): Boolean = false

    override suspend fun listFiles(path: String): Result<List<NetworkFile>> = Result.success(files)

    override suspend fun fileSize(path: String): Long = error("Not used")

    override suspend fun openStream(path: String, offset: Long): InputStream = error("Not used")
}

private class FakeDirectoryScanner(
    private val files: List<NetworkFile>,
    private val hangAfter: Int? = null,
    private val failure: Throwable? = null,
) : NetworkDirectoryScanner {
    val scans = mutableListOf<Pair<String, Boolean>>()

    override fun scan(client: NetworkClient, rootPath: String, recursive: Boolean): Flow<NetworkFile> = flow {
        scans += rootPath to recursive
        files.forEachIndexed { index, file ->
            emit(file)
            if (hangAfter != null && index + 1 == hangAfter) delay(Long.MAX_VALUE)
        }
        failure?.let { throw it }
    }
}

private class FakePlaylistRepository : PlaylistRepository {
    val nextId = 42L
    val playlists = MutableStateFlow<List<PlaylistSummary>>(emptyList())
    val creations = mutableListOf<Pair<String, String>>()
    val appends = mutableListOf<Pair<Long, List<String>>>()
    val deletions = mutableListOf<Long>()

    override fun observePlaylists(): Flow<List<PlaylistSummary>> = playlists

    override suspend fun createNetworkSnapshot(name: String, source: String): Long {
        creations += name to source
        return nextId
    }

    override suspend fun appendNetworkSnapshotItems(playlistId: Long, videoUris: List<String>): Int {
        appends += playlistId to videoUris
        return videoUris.size
    }

    override suspend fun delete(playlistId: Long) {
        deletions += playlistId
    }

    override fun observePlaylist(playlistId: Long): Flow<PlaylistRecord?> = error("Not used")

    override suspend fun getPlaylist(playlistId: Long): PlaylistRecord? = error("Not used")

    override suspend fun create(name: String, videoUris: List<String>): Long = error("Not used")

    override suspend fun createM3U(type: PlaylistType, source: String, playlist: M3UPlaylist): Long = error("Not used")

    override suspend fun replaceM3UItems(playlistId: Long, items: List<M3UPlaylistItem>) = error("Not used")

    override suspend fun refreshNetworkSnapshot(
        playlistId: Long,
        discoveredUris: List<String>,
    ): PlaylistSnapshotDiff = error("Not used")

    override suspend fun rename(playlistId: Long, name: String) = error("Not used")

    override suspend fun addVideos(playlistId: Long, videoUris: List<String>): Int = error("Not used")

    override suspend fun removeVideo(playlistId: Long, videoUri: String) = error("Not used")

    override suspend fun replaceOrder(playlistId: Long, orderedUris: List<String>) = error("Not used")
    override suspend fun restoreInsertionOrder(playlistId: Long) = error("Not used")

    override suspend fun markVideoPlayed(playlistId: Long, videoUri: String) = error("Not used")

    override suspend fun countFilePlaylistsBySource(source: String): Int = error("Not used")
}

private class FakeSystemService : SystemService {
    val toasts = mutableListOf<String>()

    override fun initialize(activity: ComponentActivity) = Unit

    override suspend fun pickFolder(): Uri? = null

    override fun getString(stringResId: Int): String = "string-$stringResId"

    override fun getQuantityString(
        pluralsResId: Int,
        quantity: Int,
        vararg formatArgs: Any,
    ): String = "quantity-$pluralsResId"

    override fun showToast(text: String, duration: Int) {
        toasts += text
    }
}
