package dev.anilbeesetti.nextplayer.core.media.network.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import dev.anilbeesetti.nextplayer.core.media.network.NetworkClient
import dev.anilbeesetti.nextplayer.core.media.network.NetworkClientFactory
import dev.anilbeesetti.nextplayer.core.media.network.NetworkConnectionResolver
import dev.anilbeesetti.nextplayer.core.media.network.NetworkUri
import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import dev.anilbeesetti.nextplayer.core.model.NetworkFile
import dev.anilbeesetti.nextplayer.core.model.NetworkProtocol
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The player opens a fresh source per seek and must survive a mid-file connection drop: read
 * failures re-open the stream at the last committed offset instead of ending playback.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkDataSourceTest {

    private val data = ByteArray(20) { it.toByte() }

    @Test
    fun `open starts at the requested position`() {
        val client = FakeClient(data)
        val source = source(client)

        source.open(spec(position = 5))
        val out = ByteArray(20)
        val read = source.read(out, 0, 20)

        assertEquals(15, read)
        assertArrayEquals(data.copyOfRange(5, 20), out.copyOfRange(0, 15))
        assertEquals(listOf(5L), client.openedOffsets)
    }

    @Test
    fun `a dropped connection is reopened and playback continues`() {
        val client = FakeClient(data).apply { eofAfterFirstStreamBytes = 5 }
        val source = source(client)

        source.open(spec(position = 0))
        val out = ByteArray(20)
        var total = 0
        while (true) {
            val read = source.read(out, total, 20 - total)
            if (read == C.RESULT_END_OF_INPUT) break
            total += read
        }

        assertEquals(20, total)
        assertArrayEquals(data, out)
        // The second open resumes exactly where the first stream stopped committing bytes.
        assertEquals(listOf(0L, 5L), client.openedOffsets)
    }

    @Test
    fun `read failures give up after bounded retries instead of looping forever`() {
        val client = FakeClient(data).apply { throwOnRead = true }
        val source = source(client)

        source.open(spec(position = 0))
        val out = ByteArray(20)
        try {
            source.read(out, 0, 20)
            fail("expected IOException after retries are exhausted")
        } catch (expected: IOException) {
            // initial open + two recovery attempts
            assertEquals(3, client.openedOffsets.size)
        }
    }

    private fun source(client: FakeClient): NetworkDataSource = NetworkDataSource(
        sessions = NetworkSessions(
            resolver = NetworkConnectionResolver { id -> if (id == CONNECTION.id) CONNECTION else null },
            clientFactory = NetworkClientFactory { client },
        ),
    )

    private fun spec(position: Long) = DataSpec.Builder()
        .setUri(PLAYBACK_URI)
        .setPosition(position)
        .build()

    private class FakeClient(private val data: ByteArray) : NetworkClient {
        val openedOffsets = mutableListOf<Long>()

        /** When >= 0, the first stream serves this many bytes and then ends prematurely. */
        var eofAfterFirstStreamBytes: Int = -1

        /** Every read throws, to exercise the retry bound. */
        var throwOnRead: Boolean = false

        private var openedCount = 0

        override val rootPath: String = ""

        override suspend fun connect(): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() = Unit
        override fun isConnected(): Boolean = true
        override suspend fun listFiles(path: String): Result<List<NetworkFile>> = Result.success(emptyList())
        override suspend fun fileSize(path: String): Long = data.size.toLong()

        override suspend fun openStream(path: String, offset: Long): InputStream {
            openedOffsets += offset
            val eofAfter = eofAfterFirstStreamBytes.takeIf { openedCount == 0 && it >= 0 }
            openedCount++
            return object : InputStream() {
                private var pos = offset.toInt()
                private var served = 0

                override fun read(): Int {
                    if (throwOnRead) throw IOException("connection dropped")
                    if (eofAfter != null && served >= eofAfter) return -1
                    if (pos >= data.size) return -1
                    served++
                    return data[pos++].toInt() and 0xFF
                }
            }
        }
    }

    private companion object {
        val CONNECTION = NetworkConnection(
            id = 3,
            name = "NAS",
            protocol = NetworkProtocol.SMB,
            host = "192.168.1.10",
        )
        val PLAYBACK_URI: Uri = NetworkUri.build(CONNECTION, "Movies/ep.mkv")
    }
}
