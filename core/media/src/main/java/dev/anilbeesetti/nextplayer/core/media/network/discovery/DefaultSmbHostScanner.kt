package dev.anilbeesetti.nextplayer.core.media.network.discovery

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

@Single
class DefaultSmbHostScanner : SmbHostScanner {

    override fun scan(): Flow<DiscoveredSmbHost> = channelFlow {
        val io = Dispatchers.IO.limitedParallelism(PARALLELISM)
        val candidates = withContext(io) { localHostCandidates() }
        candidates.forEach { address ->
            launch(io) {
                if (!probeSmbPort(address)) return@launch
                // send, not trySend: a full buffer must wait instead of dropping the host.
                send(DiscoveredSmbHost(address = address, name = resolveName(address)))
            }
        }
    }

    private suspend fun localHostCandidates(): List<String> = withContext(Dispatchers.IO) {
        val ownAddresses = mutableSetOf<String>()
        val ranges = mutableListOf<Pair<Inet4Address, Int>>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@withContext emptyList()
        while (interfaces.hasMoreElements()) {
            val nic = interfaces.nextElement()
            if (!nic.isUp || nic.isLoopback) continue
            nic.interfaceAddresses.forEach { entry ->
                val address = entry.address as? Inet4Address ?: return@forEach
                if (!address.isSiteLocalAddress) return@forEach
                ownAddresses += address.hostAddress
                ranges += address to entry.networkPrefixLength.toInt()
            }
        }
        ranges.flatMap { (address, prefix) -> hostAddressesInCidr(address, prefix) }
            .filter { it !in ownAddresses }
            .distinct()
    }

    private fun probeSmbPort(address: String): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, SMB_PORT), CONNECT_TIMEOUT_MS)
            true
        }
    } catch (failure: Exception) {
        false
    }

    private fun resolveName(address: String): String? = runCatching {
        val canonical = InetAddress.getByName(address).canonicalHostName
        canonical
            ?.takeIf { name -> name.any { it.isLetter() } && name != address }
            ?.substringBefore('.')
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    internal companion object {
        const val SMB_PORT = 445
        const val CONNECT_TIMEOUT_MS = 350
        const val PARALLELISM = 64
        const val MAX_HOSTS_PER_LINK = 1024
        private const val MAX_HOST_BITS = 10 // 2^10 - 2 = 1022 <= cap < 2^11 - 2

        /**
         * Usable host addresses of the subnet containing [address]; network and broadcast
         * addresses are excluded. Prefixes outside 8..30, or subnets larger than
         * [MAX_HOSTS_PER_LINK] hosts, are clamped to the /24 containing [address].
         */
        fun hostAddressesInCidr(address: Inet4Address, prefixLength: Int): List<String> {
            val raw = if (prefixLength in 8..30) prefixLength else 24
            val prefix = if (32 - raw > MAX_HOST_BITS) 24 else raw
            val full = toUnsignedLong(address.address)
            val mask = (-1L shl (32 - prefix)) and 0xFFFFFFFFL
            val network = full and mask
            val broadcast = network or (0xFFFFFFFFL ushr prefix)
            return ((network + 1)..<broadcast).map { unsigned ->
                "${(unsigned shr 24) and 0xFF}.${(unsigned shr 16) and 0xFF}." +
                    "${(unsigned shr 8) and 0xFF}.${unsigned and 0xFF}"
            }
        }

        private fun toUnsignedLong(bytes: ByteArray): Long {
            require(bytes.size == 4)
            var result = 0L
            for (byte in bytes) {
                result = (result shl 8) or (byte.toLong() and 0xFF)
            }
            return result
        }
    }
}
