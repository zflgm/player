package dev.anilbeesetti.nextplayer.core.media.network

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PrefetchInputStreamTest {

    private val data = ByteArray(CHUNK * 2 + 123) { (it % 251).toByte() }

    @Test
    fun `random access mode reads across chunk boundaries in order`() {
        val stream = PrefetchInputStream(
            reader = randomAccessReader(data),
            startOffset = 0,
        )

        val read = stream.readBytes()

        assertArrayEquals(data, read)
    }

    @Test
    fun `random access mode starts at the requested offset`() {
        val start = CHUNK.toLong() + 7
        val stream = PrefetchInputStream(
            reader = randomAccessReader(data),
            startOffset = start,
        )

        val read = stream.readBytes()

        assertArrayEquals(data.copyOfRange(start.toInt(), data.size), read)
    }

    @Test
    fun `sequential mode with one reader serves the stream in order`() {
        var position = 0
        val stream = PrefetchInputStream(
            reader = { _, buffer, offset, length ->
                val n = minOf(length, data.size - position)
                if (n <= 0) {
                    -1
                } else {
                    System.arraycopy(data, position, buffer, offset, n)
                    position += n
                    n
                }
            },
            startOffset = 0,
            readers = 1,
        )

        assertArrayEquals(data, stream.readBytes())
    }

    @Test
    fun `empty stream ends immediately`() {
        val stream = PrefetchInputStream(
            reader = { _, _, _, _ -> -1 },
            startOffset = 0,
        )

        assertEquals(-1, stream.read())
        assertEquals(0, stream.readBytes().size)
    }

    @Test
    fun `reader failure surfaces as IOException`() {
        val stream = PrefetchInputStream(
            reader = { _, _, _, _ -> throw IOException("connection dropped") },
            startOffset = 0,
        )

        assertThrows(IOException::class.java) { stream.readBytes() }
    }

    @Test
    fun `close runs the close chain exactly once`() {
        val closed = AtomicInteger()
        val stream = PrefetchInputStream(
            reader = randomAccessReader(data),
            startOffset = 0,
            onClosed = { closed.incrementAndGet() },
        )
        stream.readBytes()

        stream.close()
        stream.close()

        assertEquals(1, closed.get())
    }

    @Test
    fun `reading a closed stream fails instead of hanging`() {
        val stream = PrefetchInputStream(
            reader = randomAccessReader(data),
            startOffset = 0,
        )
        stream.close()

        assertThrows(IOException::class.java) { stream.read() }
    }

    private fun randomAccessReader(source: ByteArray): OffsetReader =
        { fileOffset, buffer, offset, length ->
            val start = fileOffset.toInt()
            if (start >= source.size) {
                -1
            } else {
                val n = minOf(length, source.size - start)
                System.arraycopy(source, start, buffer, offset, n)
                n
            }
        }

    private companion object {
        /** Mirrors PrefetchInputStream's slot size so data crosses several chunk boundaries. */
        const val CHUNK = 2 * 1024 * 1024
    }
}
