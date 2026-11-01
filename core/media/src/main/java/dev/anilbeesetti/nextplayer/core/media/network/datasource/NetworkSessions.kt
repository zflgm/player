package dev.anilbeesetti.nextplayer.core.media.network.datasource

import android.net.Uri
import dev.anilbeesetti.nextplayer.core.media.network.NetworkClient
import dev.anilbeesetti.nextplayer.core.media.network.NetworkClientFactory
import dev.anilbeesetti.nextplayer.core.media.network.NetworkConnectionResolver
import dev.anilbeesetti.nextplayer.core.media.network.NetworkUri
import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single

/** A connected client and the path to read on it. */
class NetworkTarget(
    val client: NetworkClient,
    val filePath: String,
)

/**
 * The shared pool of connected [NetworkClient]s — one per connection, reused by both the browse
 * screens and the player.
 *
 * Previously every browse destination kept its own client, so walking five folders deep held five
 * NAS sessions open (and servers start refusing at their session cap), plus a separate one for
 * playback. The pool caps that at [MAX_POOLED_CONNECTIONS]; the least recently used client is
 * disconnected when a new one pushes past the cap. Clients reconnect on demand if a session died.
 */
@Single
class NetworkSessions(
    private val resolver: NetworkConnectionResolver,
    private val clientFactory: NetworkClientFactory,
) {
    private val mutex = Mutex()

    /** Access-ordered LRU keyed by connection id; eviction disconnects the dropped client. */
    private val clients = object : LinkedHashMap<Long, NetworkClient>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, NetworkClient>): Boolean {
            if (size <= MAX_POOLED_CONNECTIONS) return false
            disconnectDetached(eldest.value)
            return true
        }
    }

    /** Resolves [uri] to a connected client, reusing the pooled one for its connection. */
    suspend fun target(uri: Uri): NetworkTarget = mutex.withLock {
        val target = resolve(uri)
        NetworkTarget(clientFor(target.connection), target.filePath)
    }

    /** The shared, connected client for [connection] — the same pool the player reads through. */
    suspend fun browseClient(connection: NetworkConnection): NetworkClient = mutex.withLock {
        clientFor(connection)
    }

    /** Disconnects every pooled client. The pool stays usable — the next request reconnects. */
    suspend fun release() = mutex.withLock {
        clients.values.forEach(::disconnectDetached)
        clients.clear()
    }

    private suspend fun resolve(uri: Uri): NetworkUri.Target {
        val connectionId = NetworkUri.connectionIdOf(uri)
            ?: return NetworkUri.adHocTargetOf(uri)
                ?: error("Not a playable network uri: $uri")

        val saved = resolver.connection(connectionId)
            ?: error("No saved network connection with id $connectionId")
        return NetworkUri.Target(saved, NetworkUri.filePathOf(uri, saved.protocol))
    }

    private suspend fun clientFor(connection: NetworkConnection): NetworkClient {
        clients[connection.id]?.let { existing ->
            if (!existing.isConnected()) existing.connect().getOrThrow()
            return existing
        }

        val fresh = clientFactory.create(connection)
        fresh.connect().getOrThrow()
        clients[connection.id] = fresh
        return fresh
    }

    private fun disconnectDetached(client: NetworkClient) {
        CoroutineScope(Dispatchers.IO).launch { runCatching { client.disconnect() } }
    }

    private companion object {
        const val MAX_POOLED_CONNECTIONS = 3
    }
}
