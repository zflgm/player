package dev.anilbeesetti.nextplayer.core.media.network

import android.net.Uri
import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import dev.anilbeesetti.nextplayer.core.model.NetworkProtocol
import java.util.Collections
import java.util.UUID

/**
 * Playback URIs for files reached over SMB/FTP/SFTP/WebDAV.
 *
 * A file on a *saved* connection gets `smb://192.168.1.10/Movies/foo.mkv?cid=3`. The same file
 * always produces the same URI, which matters because the player stores playback position and
 * history keyed by the media id. The connection id is carried in the query rather than inferred
 * from the host, so two connections to the same server stay distinct.
 *
 * A URI *without* `cid` — one the user typed or another app handed over — is resolved by
 * [adHocTargetOf] straight from its own parts, so `ftp://user:pass@host/dir/clip.mp4` plays
 * without saving a connection first. SFTP additionally needs the server's host key pinned as
 * `?fp=SHA256:…`; without it the connection is refused rather than trusted blindly, because
 * nothing along the playback path can stop and ask the user to vouch for an unknown server.
 *
 * Path handling differs per protocol because
 * [dev.anilbeesetti.nextplayer.core.model.NetworkFile.path] is opaque to everyone but the client
 * that produced it: SMB and WebDAV paths are relative to the share/base, FTP and SFTP paths are
 * absolute. Saved URIs hold that value verbatim; ad-hoc URIs additionally carry the SMB share as
 * their first path segment, since there is no saved connection to hold it.
 */
object NetworkUri {

    private const val CONNECTION_ID_PARAM = "cid"
    private const val HOST_KEY_PARAM = "fp"
    private const val SUBFOLDERS_PARAM = "subdirs"
    private const val SESSION_ID_PARAM = "sid"

    private const val SCHEME_SMB = "smb"
    private const val SCHEME_FTP = "ftp"
    private const val SCHEME_SFTP = "sftp"
    private const val SCHEME_WEBDAV = "webdav"
    private const val SCHEME_WEBDAVS = "webdavs"

    /** The connection to play through and the path to open on it. */
    data class Target(val connection: NetworkConnection, val filePath: String)

    /** Every scheme these URIs use, for telling network media items from local ones. */
    val schemes: Set<String> = setOf(
        SCHEME_SMB, SCHEME_FTP, SCHEME_SFTP, SCHEME_WEBDAV, SCHEME_WEBDAVS,
    )

    fun isNetworkUri(uri: Uri): Boolean = uri.scheme?.lowercase() in schemes

    fun schemeOf(connection: NetworkConnection): String = when (connection.protocol) {
        NetworkProtocol.SMB -> SCHEME_SMB
        NetworkProtocol.FTP -> SCHEME_FTP
        NetworkProtocol.SFTP -> SCHEME_SFTP
        NetworkProtocol.WEBDAV -> if (connection.useHttps) SCHEME_WEBDAVS else SCHEME_WEBDAV
    }

    fun build(connection: NetworkConnection, filePath: String): Uri = Uri.Builder()
        .scheme(schemeOf(connection))
        .encodedAuthority(
            if (connection.port != null) "${connection.host}:${connection.port}" else connection.host,
        )
        .apply { filePath.split('/').filter(String::isNotEmpty).forEach(::appendPath) }
        .appendQueryParameter(CONNECTION_ID_PARAM, connection.id.toString())
        .build()

    fun connectionIdOf(uri: Uri): Long? =
        uri.getQueryParameter(CONNECTION_ID_PARAM)?.toLongOrNull()

    /**
     * A snapshot playlist's `source`: the folder it was taken from plus whether the scan walked
     * sub-folders, so a refresh re-scans exactly what the user agreed to.
     */
    fun snapshotSource(connection: NetworkConnection, folderPath: String, recursive: Boolean): String =
        build(connection, folderPath).buildUpon()
            .appendQueryParameter(SUBFOLDERS_PARAM, recursive.toString())
            .build()
            .toString()

    fun isRecursiveSnapshot(uri: Uri): Boolean =
        uri.getQueryParameter(SUBFOLDERS_PARAM)?.toBoolean() ?: true

    /** Restores the path in the form the [NetworkClient] for [protocol] expects. */
    fun filePathOf(uri: Uri, protocol: NetworkProtocol): String {
        val path = uri.pathSegments.joinToString("/")
        return when (protocol) {
            NetworkProtocol.FTP, NetworkProtocol.SFTP -> "/$path"
            NetworkProtocol.SMB, NetworkProtocol.WEBDAV -> path
        }
    }

    /**
     * Builds a throwaway connection for a URI that names its own server, e.g.
     * `smb://user:pass@host/share/dir/clip.mkv`. Credentials come from the user info; leaving it
     * out connects anonymously. Returns null if the URI is not one this can serve.
     *
     * Sanitized URIs (no user info, carrying `sid`) resolve through [sanitizeForTransport]'s
     * in-memory session instead; a stale sid resolves to null rather than falling back to an
     * anonymous connection that would just fail authentication confusingly.
     */
    fun adHocTargetOf(uri: Uri): Target? {
        val scheme = uri.scheme?.lowercase() ?: return null
        val protocol = when (scheme) {
            SCHEME_SMB -> NetworkProtocol.SMB
            SCHEME_FTP -> NetworkProtocol.FTP
            SCHEME_SFTP -> NetworkProtocol.SFTP
            SCHEME_WEBDAV, SCHEME_WEBDAVS -> NetworkProtocol.WEBDAV
            else -> return null
        }
        val host = uri.host?.takeIf(String::isNotBlank) ?: return null
        val segments = uri.pathSegments.filter(String::isNotEmpty)
        if (segments.isEmpty()) return null

        // SMB addresses a file as <share>/<path>, and the share belongs to the connection. Every
        // other protocol treats the whole path as the file, relative to a root the server picks.
        val root = if (protocol == NetworkProtocol.SMB) segments.first() else ""
        val fileSegments = if (protocol == NetworkProtocol.SMB) segments.drop(1) else segments
        if (fileSegments.isEmpty()) return null

        val sessionId = uri.getQueryParameter(SESSION_ID_PARAM)
        val connection = if (sessionId != null) {
            adHocSessions[sessionId] ?: return null
        } else {
            val userInfo = uri.userInfo?.let(Uri::decode).orEmpty()
            NetworkConnection(
                id = AD_HOC_ID,
                name = host,
                protocol = protocol,
                host = host,
                port = uri.port.takeIf { it > 0 },
                path = root,
                username = userInfo.substringBefore(':'),
                password = userInfo.substringAfter(':', missingDelimiterValue = ""),
                useHttps = scheme == SCHEME_WEBDAVS,
                hostKeyFingerprint = uri.getQueryParameter(HOST_KEY_PARAM).orEmpty(),
            )
        }
        val filePath = fileSegments.joinToString("/")
        return Target(
            connection = connection,
            filePath = when (protocol) {
                NetworkProtocol.FTP, NetworkProtocol.SFTP -> "/$filePath"
                NetworkProtocol.SMB, NetworkProtocol.WEBDAV -> filePath
            },
        )
    }

    /**
     * Strips user info from a network URI before it enters an Intent, MediaItem, media id,
     * media session or playback history. Credentials are parked in this process' memory under a
     * random session id the returned URI carries instead, so nothing parceled, logged or
     * broadcast can expose them. Returns [uri] unchanged when there is nothing to strip.
     *
     * The tradeoff is deliberate: an ad-hoc link plays for the lifetime of the process only, and
     * replaying it after a restart asks for credentials again instead of persisting them.
     */
    fun sanitizeForTransport(uri: Uri): Uri {
        if (!isNetworkUri(uri)) return uri
        val userInfo = uri.userInfo ?: return uri
        if (userInfo.isBlank()) return uri
        val connection = adHocTargetOf(uri)?.connection ?: return uri
        val sessionId = UUID.randomUUID().toString()
        adHocSessions[sessionId] = connection
        val authority = if (uri.port > 0) "${uri.host}:${uri.port}" else uri.host.orEmpty()
        return uri.buildUpon()
            .encodedAuthority(authority)
            .appendQueryParameter(SESSION_ID_PARAM, sessionId)
            .build()
    }

    /** Credentials for sanitized ad-hoc URIs. Process lifetime only; never persisted. */
    private val adHocSessions: MutableMap<String, NetworkConnection> = Collections.synchronizedMap(
        object : LinkedHashMap<String, NetworkConnection>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NetworkConnection>): Boolean =
                size > MAX_AD_HOC_SESSIONS
        },
    )

    private const val MAX_AD_HOC_SESSIONS = 32

    /** Saved connections are row ids, which Room starts at 1, so 0 can only be an ad-hoc one. */
    private const val AD_HOC_ID = 0L
}
