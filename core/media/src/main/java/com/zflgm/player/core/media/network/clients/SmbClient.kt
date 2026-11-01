package com.zflgm.player.core.media.network.clients

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
import com.zflgm.player.core.media.network.NetworkClient
import com.zflgm.player.core.media.network.PrefetchInputStream
import com.zflgm.player.core.model.NetworkConnection
import com.zflgm.player.core.model.NetworkFile
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit
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
        PrefetchInputStream(
            reader = { fileOffset, buffer, destOffset, length ->
                lease.file.read(buffer, fileOffset, destOffset, length)
            },
            startOffset = offset,
            onClosed = { releaseLease(lease) },
        )
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
