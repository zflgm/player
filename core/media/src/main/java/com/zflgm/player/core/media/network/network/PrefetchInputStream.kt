package com.zflgm.player.core.media.network

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/** Reads [length] bytes at [fileOffset] into [buffer] at [offset]; returns bytes read or -1 on EOF. */
typealias OffsetReader = (fileOffset: Long, buffer: ByteArray, offset: Int, length: Int) -> Int

/**
 * Chunked read-ahead stream shared by every network protocol.
 *
 * SMB and SFTP pass a random-access [OffsetReader] and several reader threads fetch chunks in
 * parallel. Sequential protocols (FTP, WebDAV) pass a reader that ignores [fileOffset] and reads
 * forward from their open stream, with [readers] = 1 — two threads on one sequential stream would
 * interleave. Seeking is the caller's problem: the player reopens the source at a new offset.
 *
 * The pipeline keeps [SLOTS] chunks of [SLOT_BYTES] in flight so a slow network stall is hidden
 * behind previously fetched data instead of stalling the decoder.
 */
class PrefetchInputStream(
    private val reader: OffsetReader,
    private val startOffset: Long,
    private val readers: Int = DEFAULT_READERS,
    private val onClosed: () -> Unit = {},
) : InputStream() {

    private companion object {
        const val SLOT_BYTES = 2 * 1024 * 1024
        const val SLOTS = 3
        const val DEFAULT_READERS = 2
    }

    private val lock = ReentrantLock()
    private val condition = lock.newCondition()
    private val buffers = Array(SLOTS) { ByteArray(SLOT_BYTES) }
    private val slotChunk = LongArray(SLOTS) { -1L }
    private val slotLength = IntArray(SLOTS)
    private val slotReady = BooleanArray(SLOTS)
    private var nextToProduce = 0L
    private var nextToConsume = 0L
    private var consumeOffset = 0
    private var lastChunk = Long.MAX_VALUE
    private var failure: Throwable? = null
    private var closed = false

    private val readerThreads = Array(readers) {
        thread(name = "net-prefetch", isDaemon = true) { readerLoop() }
    }

    private fun readerLoop() {
        try {
            while (true) {
                val chunk = claimChunk()
                if (chunk < 0) return

                var read = 0
                var eof = false
                try {
                    val buffer = buffers[(chunk % SLOTS).toInt()]
                    val fileOffset = startOffset + chunk * SLOT_BYTES
                    while (read < SLOT_BYTES) {
                        val n = reader(fileOffset + read, buffer, read, SLOT_BYTES - read)
                        if (n <= 0) {
                            eof = true
                            break
                        }
                        read += n
                    }
                } catch (e: Exception) {
                    fail(e)
                    return
                }

                lock.lock()
                try {
                    if (closed) return
                    val slot = (chunk % SLOTS).toInt()
                    slotLength[slot] = read
                    slotReady[slot] = true
                    if (eof) lastChunk = minOf(lastChunk, chunk)
                    condition.signalAll()
                } finally {
                    lock.unlock()
                }
            }
        } catch (_: InterruptedException) {
            // Closed while parked: the consumer was already unblocked by the closed flag.
        } catch (t: Throwable) {
            fail(t)
        }
    }

    /** Blocks until a chunk index is handed out, or returns -1 when there is nothing left to read. */
    private fun claimChunk(): Long {
        lock.lock()
        try {
            while (true) {
                if (closed || failure != null) return -1
                if (nextToProduce > lastChunk) return -1
                val candidate = nextToProduce
                if (candidate > nextToConsume + SLOTS - 1) {
                    condition.await()
                    continue
                }
                val slot = (candidate % SLOTS).toInt()
                if (slotChunk[slot] != -1L) {
                    condition.await()
                    continue
                }
                nextToProduce++
                slotChunk[slot] = candidate
                slotLength[slot] = 0
                slotReady[slot] = false
                return candidate
            }
        } finally {
            lock.unlock()
        }
    }

    private fun fail(t: Throwable) {
        lock.lock()
        try {
            if (!closed) {
                failure = t
                condition.signalAll()
            }
        } finally {
            lock.unlock()
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        lock.lock()
        try {
            while (true) {
                if (closed) throw IOException("Stream closed")
                failure?.let { throw IOException("Network prefetch read failed", it) }
                if (nextToConsume > lastChunk) return -1
                val slot = (nextToConsume % SLOTS).toInt()
                if (slotChunk[slot] == nextToConsume && slotReady[slot]) {
                    val available = slotLength[slot] - consumeOffset
                    if (available <= 0) {
                        finishChunk(slot)
                        return -1
                    }
                    val n = minOf(available, len)
                    System.arraycopy(buffers[slot], consumeOffset, b, off, n)
                    consumeOffset += n
                    if (consumeOffset == slotLength[slot]) finishChunk(slot)
                    return n
                }
                try {
                    condition.await()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while waiting for network data", e)
                }
            }
        } finally {
            lock.unlock()
        }
    }

    private fun finishChunk(slot: Int) {
        slotChunk[slot] = -1L
        slotReady[slot] = false
        consumeOffset = 0
        nextToConsume++
        condition.signalAll()
    }

    override fun close() {
        lock.lock()
        try {
            if (closed) return
            closed = true
            condition.signalAll()
        } finally {
            lock.unlock()
        }
        readerThreads.forEach { it.interrupt() }
        onClosed()
    }
}
