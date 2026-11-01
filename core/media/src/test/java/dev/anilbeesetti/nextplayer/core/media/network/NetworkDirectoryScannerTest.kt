package dev.anilbeesetti.nextplayer.core.media.network

import dev.anilbeesetti.nextplayer.core.model.NetworkFile
import java.io.InputStream
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkDirectoryScannerTest {

    @Test
    fun `flat scan emits only videos of the root folder`() = runTest {
        val client = FakeClient(
            "/" to listOf(
                file("B.mkv", "/B.mkv"),
                file("a.mp4", "/a.mp4"),
                file("Poster.jpg", "/Poster.jpg"),
                folder("Sub", "/Sub"),
            ),
        )

        val discovered = DefaultNetworkDirectoryScanner().scan(client, "/", recursive = false).toList()

        assertEquals(listOf("/a.mp4", "/B.mkv"), discovered.map { it.path })
        assertEquals(listOf("/"), client.requested)
    }

    @Test
    fun `recursive scan walks every subfolder depth first`() = runTest {
        val client = FakeClient(
            "/Series" to listOf(
                file("Episode 1.mp4", "/Series/Episode 1.mp4"),
                folder("Season 2", "/Series/Season 2"),
                folder("Extras", "/Series/Extras"),
            ),
            "/Series/Extras" to listOf(file("Bloopers.mp4", "/Series/Extras/Bloopers.mp4")),
            "/Series/Season 2" to listOf(
                file("Episode 2.mp4", "/Series/Season 2/Episode 2.mp4"),
                folder("Raw", "/Series/Season 2/Raw"),
            ),
            "/Series/Season 2/Raw" to listOf(file("raw.MKV", "/Series/Season 2/Raw/raw.MKV")),
        )

        val discovered = DefaultNetworkDirectoryScanner().scan(client, "/Series", recursive = true).toList()

        assertEquals(
            listOf(
                "/Series/Episode 1.mp4",
                "/Series/Extras/Bloopers.mp4",
                "/Series/Season 2/Episode 2.mp4",
                "/Series/Season 2/Raw/raw.MKV",
            ),
            discovered.map { it.path },
        )
        assertEquals(
            listOf("/Series", "/Series/Extras", "/Series/Season 2", "/Series/Season 2/Raw"),
            client.requested,
        )
    }

    @Test
    fun `unreadable subfolders are skipped without aborting the scan`() = runTest {
        val client = FakeClient(
            "/Movies" to listOf(
                folder("Private", "/Movies/Private"),
                folder("Public", "/Movies/Public"),
                file("Movie.mp4", "/Movies/Movie.mp4"),
            ),
            "/Movies/Public" to listOf(file("Other.mp4", "/Movies/Public/Other.mp4")),
            failures = setOf("/Movies/Private"),
        )

        val discovered = DefaultNetworkDirectoryScanner().scan(client, "/Movies", recursive = true).toList()

        assertEquals(listOf("/Movies/Movie.mp4", "/Movies/Public/Other.mp4"), discovered.map { it.path })
    }

    @Test
    fun `a symlinked folder is visited once`() = runTest {
        val client = FakeClient(
            "/Library" to listOf(
                folder("Films", "/Library/Films"),
                folder("Alias", "/Library"),
            ),
            "/Library/Films" to listOf(
                file("A.mp4", "/Library/Films/A.mp4"),
                folder("Back", "/Library"),
            ),
        )

        val discovered = DefaultNetworkDirectoryScanner().scan(client, "/Library", recursive = true).toList()

        assertEquals(listOf("/Library/Films/A.mp4"), discovered.map { it.path })
        assertEquals(listOf("/Library", "/Library/Films"), client.requested)
    }

    private fun file(name: String, path: String) = NetworkFile(name, path, isDirectory = false)

    private fun folder(name: String, path: String) = NetworkFile(name, path, isDirectory = true)

    private class FakeClient(
        vararg folders: Pair<String, List<NetworkFile>>,
        private val failures: Set<String> = emptySet(),
    ) : NetworkClient {
        private val entries = folders.toMap()
        val requested = mutableListOf<String>()

        override val rootPath: String = "/"

        override suspend fun connect(): Result<Unit> = Result.success(Unit)

        override suspend fun disconnect() = Unit

        override fun isConnected(): Boolean = true

        override suspend fun listFiles(path: String): Result<List<NetworkFile>> {
            requested += path
            if (path in failures) return Result.failure(IllegalStateException("Access denied"))
            return Result.success(entries.getValue(path))
        }

        override suspend fun fileSize(path: String): Long = 0

        override suspend fun openStream(path: String, offset: Long): InputStream = error("Not used")
    }
}
