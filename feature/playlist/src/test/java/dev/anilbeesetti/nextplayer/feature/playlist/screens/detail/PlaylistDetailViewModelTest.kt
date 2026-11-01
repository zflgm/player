package dev.anilbeesetti.nextplayer.feature.playlist.screens.detail

import android.content.Context
import android.net.Uri
import dev.anilbeesetti.nextplayer.core.data.playlist.M3UParser
import dev.anilbeesetti.nextplayer.core.data.repository.fake.FakeMediaRepository
import dev.anilbeesetti.nextplayer.core.domain.ObservePlaylistUseCase
import dev.anilbeesetti.nextplayer.core.media.network.DefaultNetworkDirectoryScanner
import dev.anilbeesetti.nextplayer.core.media.network.NetworkClientFactory
import dev.anilbeesetti.nextplayer.core.media.network.NetworkUri
import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import dev.anilbeesetti.nextplayer.core.model.NetworkFile
import dev.anilbeesetti.nextplayer.core.model.NetworkProtocol
import dev.anilbeesetti.nextplayer.core.model.PlaylistItemRecord
import dev.anilbeesetti.nextplayer.core.model.PlaylistRecord
import dev.anilbeesetti.nextplayer.core.model.PlaylistSnapshotDiff
import dev.anilbeesetti.nextplayer.core.model.PlaylistType
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.base.DataState
import dev.anilbeesetti.nextplayer.feature.playlist.FakeNetworkClient
import dev.anilbeesetti.nextplayer.feature.playlist.FakeNetworkConnectionRepository
import dev.anilbeesetti.nextplayer.feature.playlist.FakePlaylistRepository
import dev.anilbeesetti.nextplayer.feature.playlist.FakeSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaylistDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var repository: FakePlaylistRepository
    private lateinit var systemService: FakeSystemService
    private lateinit var mediaRepository: FakeMediaRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = RuntimeEnvironment.getApplication()
        repository = FakePlaylistRepository()
        systemService = FakeSystemService()
        mediaRepository = FakeMediaRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun fileRefreshReplacesItemsAndPreservesSavedName() = runTest(dispatcher) {
        val sourceFile = kotlin.io.path.createTempFile(suffix = ".m3u").toFile()
        sourceFile.writeText("#PLAYLIST:New source name\nhttps://media.example/new")
        repository.playlist.value = linkedRecord(Uri.fromFile(sourceFile).toString())
        val viewModel = viewModel()
        try {
            runCurrent()
            viewModel.onAction(PlaylistDetailUiAction.Refresh)
            advanceUntilIdle()

            assertEquals(
                listOf("https://media.example/new"),
                repository.replacementCalls.single().second.map { it.uri },
            )
            val playlist = (viewModel.state.value.playlistDataState as DataState.Success).value
            assertEquals("Saved name", playlist?.name)
            assertFalse(viewModel.state.value.isRefreshing)
            assertEquals(1, systemService.toasts.size)
        } finally {
            sourceFile.delete()
        }
    }

    @Test
    fun failedRefreshKeepsCachedItemsAndSkipsReplacement() = runTest(dispatcher) {
        repository.playlist.value = linkedRecord("file:///missing/list.m3u")
        val viewModel = viewModel()
        runCurrent()

        viewModel.onAction(PlaylistDetailUiAction.Refresh)
        advanceUntilIdle()

        val playlist = (viewModel.state.value.playlistDataState as DataState.Success).value
        assertEquals(listOf("https://media.example/cached"), playlist?.items?.map { it.uri })
        assertTrue(repository.replacementCalls.isEmpty())
        assertFalse(viewModel.state.value.isRefreshing)
        assertEquals(1, systemService.toasts.size)
    }

    @Test
    fun localRefreshIsIgnored() = runTest(dispatcher) {
        repository.playlist.value = linkedRecord(source = null).copy(type = PlaylistType.LOCAL)
        val viewModel = viewModel()
        runCurrent()

        viewModel.onAction(PlaylistDetailUiAction.Refresh)
        advanceUntilIdle()

        assertTrue(repository.replacementCalls.isEmpty())
        assertTrue(systemService.toasts.isEmpty())
    }

    @Test
    fun networkRefreshReScansTheSnapshotFolderAndReportsTheDiff() = runTest(dispatcher) {
        val connection = snapshotConnection()
        val source = NetworkUri.snapshotSource(connection, "Media/Series", recursive = false)
        repository.playlist.value = snapshotRecord(source)
        repository.snapshotDiff = PlaylistSnapshotDiff(added = 2, removed = 1)
        val client = FakeNetworkClient(
            filesByPath = mapOf(
                "Media/Series" to listOf(
                    NetworkFile("b.mkv", "Media/Series/b.mkv", isDirectory = false),
                    NetworkFile("a.mkv", "Media/Series/a.mkv", isDirectory = false),
                    NetworkFile("Sub", "Media/Series/Sub", isDirectory = true),
                    NetworkFile("notes.txt", "Media/Series/notes.txt", isDirectory = false),
                ),
            ),
        )
        val viewModel = viewModel(connection = connection, client = client)
        runCurrent()

        viewModel.onAction(PlaylistDetailUiAction.Refresh)
        advanceUntilIdle()

        assertEquals(
            listOf(
                7L to listOf(
                    NetworkUri.build(connection, "Media/Series/a.mkv").toString(),
                    NetworkUri.build(connection, "Media/Series/b.mkv").toString(),
                ),
            ),
            repository.snapshotRefreshes,
        )
        assertTrue(repository.replacementCalls.isEmpty())
        assertEquals(1, client.disconnects)
        assertFalse(viewModel.state.value.isRefreshing)
        assertEquals(
            listOf("string-${R.string.snapshot_refresh_result}".format(2, 1)),
            systemService.toasts,
        )
    }

    @Test
    fun networkRefreshOfAnUnchangedFolderSaysSo() = runTest(dispatcher) {
        val connection = snapshotConnection()
        repository.playlist.value = snapshotRecord(NetworkUri.snapshotSource(connection, "Series", recursive = true))
        val client = FakeNetworkClient(
            filesByPath = mapOf(
                "Series" to listOf(
                    NetworkFile("Episode.mp4", "Series/Episode.mp4", isDirectory = false),
                    NetworkFile("Season 2", "Series/Season 2", isDirectory = true),
                ),
                "Series/Season 2" to listOf(
                    NetworkFile("Finale.mp4", "Series/Season 2/Finale.mp4", isDirectory = false),
                ),
            ),
        )
        val viewModel = viewModel(connection = connection, client = client)
        runCurrent()

        viewModel.onAction(PlaylistDetailUiAction.Refresh)
        advanceUntilIdle()

        assertEquals(
            listOf(
                NetworkUri.build(connection, "Series/Episode.mp4").toString(),
                NetworkUri.build(connection, "Series/Season 2/Finale.mp4").toString(),
            ),
            repository.snapshotRefreshes.single().second,
        )
        assertEquals(
            listOf("string-${R.string.snapshot_refresh_unchanged}"),
            systemService.toasts,
        )
    }

    @Test
    fun networkRefreshWithoutItsConnectionSkipsTheScan() = runTest(dispatcher) {
        repository.playlist.value = snapshotRecord("smb://nas/Media/Series?cid=3&subdirs=false")
        val client = FakeNetworkClient(emptyMap())
        val viewModel = viewModel(connection = null, client = client)
        runCurrent()

        viewModel.onAction(PlaylistDetailUiAction.Refresh)
        advanceUntilIdle()

        assertTrue(repository.snapshotRefreshes.isEmpty())
        assertEquals(0, client.disconnects)
        assertEquals(listOf("Snapshot connection no longer exists"), systemService.toasts)
    }

    @Test
    fun nameSortRewritesTheStoredOrderOfALocalPlaylist() = runTest(dispatcher) {
        val titlesByUri = mapOf(
            "file:///video/ep10" to "Episode 10",
            "file:///video/ep2" to "Episode 2",
            "file:///video/ep1" to "Episode 1",
        )
        mediaRepository.videos += titlesByUri.keys.map(::libraryVideo)
        repository.playlist.value = PlaylistRecord(
            id = 7,
            name = "Local list",
            type = PlaylistType.LOCAL,
            source = null,
            items = titlesByUri.entries.mapIndexed { position, entry ->
                PlaylistItemRecord(position = position, uri = entry.key, title = entry.value)
            },
            lastRefreshedAt = null,
        )
        val viewModel = viewModel()
        runCurrent()

        viewModel.onAction(PlaylistDetailUiAction.SortBy(PlaylistSort.NAME_ASCENDING))
        advanceUntilIdle()

        assertEquals(
            listOf("file:///video/ep1", "file:///video/ep2", "file:///video/ep10"),
            repository.orderReplacements.single(),
        )
    }

    @Test
    fun networkSnapshotSortsIntoTheStoredOrderToo() = runTest(dispatcher) {
        repository.playlist.value = snapshotRecord("smb://nas/Media/Series?cid=3&subdirs=false")
        val viewModel = viewModel()
        runCurrent()

        viewModel.onAction(PlaylistDetailUiAction.SortBy(PlaylistSort.NAME_DESCENDING))
        advanceUntilIdle()

        assertEquals(
            listOf(
                "smb://nas/Media/Series/Second.mp4?cid=3",
                "smb://nas/Media/Series/Deleted.mp4?cid=3",
            ),
            repository.orderReplacements.single(),
        )
    }

    @Test
    fun restoreAddedOrderAsksTheRepositoryThatStillKnowsIt() = runTest(dispatcher) {
        repository.playlist.value = snapshotRecord("smb://nas/Media/Series?cid=3&subdirs=false")
        val viewModel = viewModel()
        runCurrent()

        viewModel.onAction(PlaylistDetailUiAction.SortBy(PlaylistSort.INSERTION_ORDER))
        advanceUntilIdle()

        assertEquals(1, repository.insertionOrderRestorations)
        assertTrue(repository.orderReplacements.isEmpty())
    }

    @Test
    fun linkedM3UPlaylistIgnoresEverySort() = runTest(dispatcher) {
        repository.playlist.value = linkedRecord("https://media.example/list.m3u").copy(
            items = listOf(
                PlaylistItemRecord(position = 0, uri = "https://media.example/z", title = "Z"),
                PlaylistItemRecord(position = 1, uri = "https://media.example/a", title = "A"),
            ),
        )
        val viewModel = viewModel()
        runCurrent()

        viewModel.onAction(PlaylistDetailUiAction.SortBy(PlaylistSort.NAME_ASCENDING))
        advanceUntilIdle()
        viewModel.onAction(PlaylistDetailUiAction.SortBy(PlaylistSort.INSERTION_ORDER))
        advanceUntilIdle()

        assertTrue(repository.orderReplacements.isEmpty())
        assertEquals(0, repository.insertionOrderRestorations)
    }

    private fun libraryVideo(uri: String) = Video(
        id = uri.hashCode().toLong(),
        path = uri.removePrefix("file:///"),
        duration = 1_000,
        uriString = uri,
        nameWithExtension = uri.substringAfterLast('/'),
        width = 1920,
        height = 1080,
        size = 1_000,
    )

    private fun viewModel(
        connection: NetworkConnection? = snapshotConnection(),
        client: FakeNetworkClient = FakeNetworkClient(emptyMap()),
    ) = PlaylistDetailViewModel(
        observePlaylist = ObservePlaylistUseCase(repository, mediaRepository),
        playlistRepository = repository,
        m3uParser = M3UParser(context, Dispatchers.Unconfined),
        networkConnectionRepository = FakeNetworkConnectionRepository(listOfNotNull(connection)),
        clientFactory = NetworkClientFactory { client },
        directoryScanner = DefaultNetworkDirectoryScanner(),
        systemService = systemService,
        input = PlaylistDetailViewModel.Input(7),
        output = PlaylistDetailViewModel.Output(
            navigateUp = {},
            playPlaylist = { _, _ -> },
        ),
    )

    private fun snapshotConnection() = NetworkConnection(
        id = 3,
        name = "NAS",
        protocol = NetworkProtocol.SMB,
        host = "nas",
        path = "Media",
    )

    private fun snapshotRecord(source: String) = PlaylistRecord(
        id = 7,
        name = "Series snapshot",
        type = PlaylistType.NETWORK,
        source = source,
        items = listOf(
            PlaylistItemRecord(
                position = 0,
                uri = "smb://nas/Media/Series/Deleted.mp4?cid=3",
                title = "Deleted",
            ),
            PlaylistItemRecord(
                position = 1,
                uri = "smb://nas/Media/Series/Second.mp4?cid=3",
                title = "Second",
            ),
        ),
        lastRefreshedAt = 123,
    )

    private fun linkedRecord(source: String?) = PlaylistRecord(
        id = 7,
        name = "Saved name",
        type = PlaylistType.M3U_FILE,
        source = source,
        items = listOf(
            PlaylistItemRecord(
                position = 0,
                uri = "https://media.example/cached",
                title = "Cached",
            ),
        ),
        lastRefreshedAt = 123,
    )
}
