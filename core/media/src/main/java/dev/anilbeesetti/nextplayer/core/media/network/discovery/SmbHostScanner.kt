package dev.anilbeesetti.nextplayer.core.media.network.discovery

import kotlinx.coroutines.flow.Flow

data class DiscoveredSmbHost(
    val address: String,
    val name: String? = null,
)

/** Discovers SMB servers (TCP port 445) reachable on the local network. */
interface SmbHostScanner {
    /** Emits each host as soon as it responds, and completes when the sweep finishes. */
    fun scan(): Flow<DiscoveredSmbHost>
}
