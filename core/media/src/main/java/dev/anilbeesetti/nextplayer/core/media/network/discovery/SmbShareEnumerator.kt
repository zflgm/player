package dev.anilbeesetti.nextplayer.core.media.network.discovery

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.PipeShare
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

data class SmbShareEntry(
    val name: String,
    val type: Long,
    val remark: String,
) {
    val isDisk: Boolean get() = type and SHARE_TYPE_FILTER == 0L

    internal companion object {
        // STYPE_* in the low nibble plus STYPE_SPECIAL (bit 31): hidden disk
        // shares like ADMIN$ carry the special bit and must not be listed.
        const val SHARE_TYPE_FILTER = 0x8FFFFFFFL
    }
}

interface SmbShareEnumerator {
    /** Lists the shares of [host] via srvsvc NetrShareEnum; throws [IOException] on failure. */
    suspend fun enumerate(host: String, port: Int, username: String, password: String): List<SmbShareEntry>
}

class SmbEnumerationException(message: String) : IOException(message)

@Single
class DefaultSmbShareEnumerator : SmbShareEnumerator {

    override suspend fun enumerate(
        host: String,
        port: Int,
        username: String,
        password: String,
    ): List<SmbShareEntry> = withContext(Dispatchers.IO) {
        enumerateBlocking(host, port, username, password)
    }

    private fun enumerateBlocking(host: String, port: Int, username: String, password: String): List<SmbShareEntry> {
        val config = SmbConfig.builder()
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(25, TimeUnit.SECONDS)
            .withSigningRequired(false)
            .withEncryptData(false)
            .build()
        return SMBClient(config).use { client ->
            client.connect(host, port).use { connection ->
                connection
                    .authenticate(AuthenticationContext(username, password.toCharArray(), null))
                    .use { session ->
                        (session.connectShare("IPC$") as? PipeShare
                            ?: throw SmbEnumerationException("IPC$ is not a named pipe share")).use { ipc ->
                            val pipe = ipc.open(
                                SRVSVC_PIPE,
                                SMB2ImpersonationLevel.Impersonation,
                                setOf(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
                                emptySet<FileAttributes>(),
                                setOf(
                                    SMB2ShareAccess.FILE_SHARE_READ,
                                    SMB2ShareAccess.FILE_SHARE_WRITE,
                                    SMB2ShareAccess.FILE_SHARE_DELETE,
                                ),
                                SMB2CreateDisposition.FILE_OPEN,
                                emptySet<SMB2CreateOptions>(),
                            )
                            try {
                                val dcerpc = DcerpcOverPipe(pipe)
                                dcerpc.bind()
                                val stub = dcerpc.call(OPNUM_NETR_SHARE_ENUM, ndrShareEnumRequest(host))
                                parseShareEnumReply(stub).filter { it.isDisk }
                            } finally {
                                runCatching { pipe.close() }
                            }
                        }
                    }
            }
        }
    }

    internal companion object {
        const val SRVSVC_PIPE = "srvsvc"
        const val OPNUM_NETR_SHARE_ENUM = 15
        const val SHARE_INFO_LEVEL_1 = 1L

        /** [MS-SRVS] NetrShareEnum level-1 request stub (NDR, little-endian). */
        fun ndrShareEnumRequest(host: String): ByteArray {
            val w = NdrWriter()
            w.u32(0x00020000)
            w.conformantVaryingString("\\\\" + host)
            w.u32(SHARE_INFO_LEVEL_1)
            w.u32(SHARE_INFO_LEVEL_1)
            w.u32(0x00020004) // pointer to SHARE_INFO_1_CONTAINER
            w.u32(0) // EntriesRead
            w.u32(0) // Buffer: NULL
            w.u32(0xFFFFFFFF) // PreferedMaximumLength
            w.u32(0x00020008) // ResumeHandle referent
            w.u32(0)
            return w.toByteArray()
        }

        fun parseShareEnumReply(stub: ByteArray): List<SmbShareEntry> {
            val r = NdrReader(stub)
            val level = r.u32()
            r.u32() // union switch
            var count = 0L
            if (r.u32() != 0L) { // container referent
                count = r.u32()
                if (r.u32() == 0L) count = 0 // buffer referent
            }
            // conformant array: max count precedes the elements
            if (count > 0 && r.u32() != count) {
                throw SmbEnumerationException("NetrShareEnum max count mismatch")
            }
            // Conformant array elements are 12 bytes each: a count the stub cannot hold is malformed,
            // and must not reach ArrayList's capacity argument.
            if (count > r.remaining / 12) {
                throw SmbEnumerationException("NetrShareEnum entry count exceeds the stub size")
            }
            val fixed = ArrayList<Triple<Long, Long, Long>>(count.toInt())
            repeat(count.toInt()) { fixed += Triple(r.u32(), r.u32(), r.u32()) }
            val entries = ArrayList<SmbShareEntry>(count.toInt())
            for ((nameRef, type, remarkRef) in fixed) {
                val name = if (nameRef != 0L) r.deferredString() else ""
                val remark = if (remarkRef != 0L) r.deferredString() else ""
                entries += SmbShareEntry(name, type, remark)
            }
            r.u32() // TotalEntries
            if (r.u32() != 0L) r.u32() // ResumeHandle
            val returnCode = r.u32()
            if (level != SHARE_INFO_LEVEL_1) {
                throw SmbEnumerationException("Unexpected share info level $level")
            }
            if (returnCode != 0L) {
                throw SmbEnumerationException("NetrShareEnum failed (0x%08x)".format(returnCode))
            }
            return entries
        }
    }
}
