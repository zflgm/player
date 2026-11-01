package dev.anilbeesetti.nextplayer.core.media.network.clients

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import dev.anilbeesetti.nextplayer.core.media.network.NetworkClient
import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import dev.anilbeesetti.nextplayer.core.model.NetworkFile
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SMB2/3 client backed by smbj. [NetworkConnection.path] holds only the share name; browse paths
 * are relative to the share root.
 */
class SmbClient(private val connection: NetworkConnection) : NetworkClient {

    private var client: SMBClient? = null
    private var smbConnection: Connection? = null
    private var session: Session? = null
    private var diskShare: DiskShare? = null

    private val leaseLock = Any()
    private var currentLease: FileLease? = null
    private val retiredLeases = mutableListOf<FileLease>()

    private val shareName: String get() = connection.path.trim('/')

    override val rootPath: String = ""

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(shareName.isNotEmpty() && !shareName.contains('/')) {
                "Path must be just the share name (e.g. Media), without any folders."
            }
            // Disable signing/encryption: avoids Key.getEncoded() crashes on Android.
            val config = SmbConfig.builder()
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(35, TimeUnit.SECONDS)
                .withDialects(
                    SMB2Dialect.SMB_3_1_1,
                    SMB2Dialect.SMB_3_0_2,
                    SMB2Dialect.SMB_3_0,
                    SMB2Dialect.SMB_2_1,
                    SMB2Dialect.SMB_2_0_2,
                )
                .withDfsEnabled(false)
                .withMultiProtocolNegotiate(true)
                .withSigningRequired(false)
                .withEncryptData(false)
                // Add large buffer sizes (e.g., 8MB)
                .withReadBufferSize(8 * 1024 * 1024)
                .withWriteBufferSize(8 * 1024 * 1024)
                .build()

            val smbClient = SMBClient(config)
            val conn = smbClient.connect(connection.host, connection.effectivePort)
            val authContext = if (connection.isAnonymous) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(connection.username, connection.password.toCharArray(), null)
            }
            val sess = conn.authenticate(authContext)
            // Verify the share is reachable, then keep the tree connect open: file operations
            // reuse it instead of reconnecting per call.
            val share = sess.connectShare(shareName) as? DiskShare
                ?: error("Share '$shareName' is not a disk share")
            share.list("")

            // A fresh session invalidates any handles cached from the previous one.
            clearLeases()

            client = smbClient
            smbConnection = conn
            session = sess
            diskShare = share
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        clearLeases()
        runCatching { diskShare?.close() }
        runCatching { session?.close() }
        runCatching { smbConnection?.close() }
        runCatching { client?.close() }
        diskShare = null
        session = null
        smbConnection = null
        client = null
    }

    override fun isConnected(): Boolean = session != null && smbConnection?.isConnected == true

    override suspend fun listFiles(path: String): Result<List<NetworkFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val relative = path.trim('/')
            ensureShare().list(smbPath(relative)).mapNotNull { info ->
                val name = info.fileName
                if (name == "." || name == ".." || name.endsWith("$")) return@mapNotNull null
                val isDirectory = info.fileAttributes and 0x10L != 0L // FILE_ATTRIBUTE_DIRECTORY
                NetworkFile(
                    name = name,
                    path = if (relative.isEmpty()) name else "$relative/$name",
                    isDirectory = isDirectory,
                    size = if (isDirectory) 0 else info.endOfFile,
                    modified = info.lastWriteTime?.toEpochMillis(),
                )
            }
        }
    }

    override suspend fun fileSize(path: String): Long = withContext(Dispatchers.IO) {
        runCatching {
            if (!isConnected()) connect().getOrThrow()
            val lease = acquireLease(path)
            try {
                // Cached on the open handle, so repeat calls cost no round trips.
                lease.file.fileInformation.standardInformation.endOfFile
            } finally {
                releaseLease(lease)
            }
        }.getOrDefault(-1L)
    }

    override suspend fun openStream(path: String, offset: Long): InputStream = withContext(Dispatchers.IO) {
        if (!isConnected()) connect().getOrThrow()
        val lease = acquireLease(path)
        SmbPrefetchStream(lease, offset, ::releaseLease)
    }

    private fun ensureShare(): DiskShare {
        diskShare?.let { return it }
        val sess = session ?: error("Not connected")
        return (sess.connectShare(shareName) as DiskShare).also { diskShare = it }
    }

    /** Returns the file handle for [path], opening it on first use and keeping it for later seeks. */
    private fun acquireLease(path: String): FileLease = synchronized(leaseLock) {
        val existing = currentLease?.takeIf { it.path == path }
        if (existing != null) {
            existing.borrows++
            existing
        } else {
            val fresh = FileLease(path, openReadFile(ensureShare(), path))
            currentLease?.let { old ->
                if (old.borrows == 0) {
                    runCatching { old.file.close() }
                } else {
                    // A stream is still reading it; close once that stream releases it.
                    retiredLeases.add(old)
                }
            }
            currentLease = fresh
            fresh.borrows = 1
            fresh
        }
    }

    private fun releaseLease(lease: FileLease) {
        synchronized(leaseLock) {
            lease.borrows--
            if (lease.borrows == 0 && retiredLeases.remove(lease)) {
                runCatching { lease.file.close() }
            }
        }
    }

    private fun clearLeases() {
        synchronized(leaseLock) {
            runCatching { currentLease?.file?.close() }
            retiredLeases.forEach { runCatching { it.file.close() } }
            retiredLeases.clear()
            currentLease = null
        }
    }

    private fun openReadFile(share: DiskShare, path: String) = share.openFile(
        smbPath(path.trim('/')),
        EnumSet.of(AccessMask.GENERIC_READ),
        null,
        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
        SMB2CreateDisposition.FILE_OPEN,
        null,
    )

    private fun smbPath(relative: String): String = relative.replace('/', '\\')
}

private class FileLease(val path: String, val file: File) {
    var borrows = 0
}

/**
 * Sequential read stream that keeps two SMB reads in flight over a ring of three 2MB chunks, so a
 * chunk still being fetched doesn't stall the reads behind it. Borrows [lease] for its lifetime;
 * closing releases the lease but leaves the cached handle open for the next seek.
 */
private class SmbPrefetchStream(
    private val lease: FileLease,
    private val startOffset: Long,
    private val onClosed: (FileLease) -> Unit,
) : InputStream() {

    private companion object {
        const val SLOT_BYTES = 2 * 1024 * 1024
        const val SLOTS = 3
        const val READERS = 2
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

    private val readers = Array(READERS) {
        thread(name = "smb-prefetch", isDaemon = true) { readerLoop() }
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
                        val n = lease.file.read(buffer, fileOffset + read, read, SLOT_BYTES - read)
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
                failure?.let { throw IOException("SMB read failed", it) }
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
                    throw IOException("Interrupted while waiting for SMB data", e)
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
        readers.forEach { it.interrupt() }
        onClosed(lease)
    }
}
