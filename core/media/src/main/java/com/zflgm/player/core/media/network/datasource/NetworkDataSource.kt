package com.zflgm.player.core.media.network.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads a file on a saved network connection (SMB/FTP/SFTP/WebDAV) for the Media3 player.
 *
 * Media3 ships no data source for these protocols, so this adapts [NetworkClient.openStream],
 * which can start at an arbitrary byte offset and therefore supports seeking directly. The player
 * closes and reopens the source at a new offset for each seek.
 *
 * Weak networks get two safety nets inside this class: [open] is bounded by a timeout so a stalled
 * handshake surfaces as an IOException the player's retry policy can act on instead of hanging the
 * loader thread, and [read] transparently reopens the stream at the exact byte it stopped at when
 * a connection drops mid-file, so a Wi-Fi blip does not end playback.
 */
@UnstableApi
class NetworkDataSource(
    private val sessions: NetworkSessions,
) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var stream: InputStream? = null
    private var target: NetworkTarget? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var nextReadPosition: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        nextReadPosition = dataSpec.position
        transferInitializing(dataSpec)

        try {
            runBlocking {
                val resolved = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
                    sessions.target(dataSpec.uri)
                } ?: throw IOException("Timed out opening ${dataSpec.uri}")
                target = resolved
                val fileSize = resolved.client.fileSize(resolved.filePath)

                bytesRemaining = when {
                    dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                    fileSize >= 0 -> (fileSize - dataSpec.position).coerceAtLeast(0)
                    else -> C.LENGTH_UNSET.toLong()
                }
                stream = resolved.client.openStream(resolved.filePath, dataSpec.position)
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Failed to open ${dataSpec.uri}", e)
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val readLength = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }

        var retries = 0
        while (true) {
            val bytesRead = try {
                stream?.read(buffer, offset, readLength) ?: return C.RESULT_END_OF_INPUT
            } catch (e: IOException) {
                if (retries >= MAX_READ_RETRIES) throw e
                retries++
                reopenAtCurrentPosition(retries, e)
                continue
            }
            // A premature end-of-file with bytes still owed usually means the server dropped the
            // connection; treat it like a read error instead of truncating playback.
            if (bytesRead == -1) {
                val premature = bytesRemaining == C.LENGTH_UNSET.toLong() || bytesRemaining > 0
                if (!premature || retries >= MAX_READ_RETRIES) return C.RESULT_END_OF_INPUT
                retries++
                reopenAtCurrentPosition(retries, IOException("Premature end of stream"))
                continue
            }

            nextReadPosition += bytesRead
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= bytesRead
            bytesTransferred(bytesRead)
            return bytesRead
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            stream?.close()
        } finally {
            stream = null
            target = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    /**
     * Reopens the stream at [nextReadPosition] after a drop, re-resolving the session so a dead
     * connection is re-established first. Short backoff between attempts gives the network a
     * moment to come back before spending another retry.
     */
    private fun reopenAtCurrentPosition(retry: Int, cause: IOException) {
        val currentUri = uri ?: throw cause
        try {
            Thread.sleep(retry * READ_RETRY_BACKOFF_MS)
            runBlocking {
                val resolved = withTimeoutOrNull(RECOVER_TIMEOUT_MS) {
                    sessions.target(currentUri)
                } ?: throw IOException("Timed out recovering $currentUri", cause)
                target = resolved
                stream = resolved.client.openStream(resolved.filePath, nextReadPosition)
            }
        } catch (e: IOException) {
            if (retry >= MAX_READ_RETRIES) throw e
        }
    }

    private companion object {
        /** Bounded so a stalled handshake surfaces as a load error the player can retry. */
        const val OPEN_TIMEOUT_MS = 35_000L
        const val RECOVER_TIMEOUT_MS = 15_000L
        const val MAX_READ_RETRIES = 2
        const val READ_RETRY_BACKOFF_MS = 500L
    }
}
