package dev.anilbeesetti.nextplayer.feature.network.screens.list

import android.net.Uri
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.data.repository.NetworkConnectionRepository
import dev.anilbeesetti.nextplayer.core.media.network.discovery.DiscoveredSmbHost
import dev.anilbeesetti.nextplayer.core.media.network.discovery.SmbHostScanner
import dev.anilbeesetti.nextplayer.core.media.network.discovery.SmbShareEnumerator
import dev.anilbeesetti.nextplayer.core.media.network.discovery.SmbShareEntry
import dev.anilbeesetti.nextplayer.core.media.network.keys.SshKeyStore
import dev.anilbeesetti.nextplayer.core.model.NetworkAuthentication
import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import dev.anilbeesetti.nextplayer.core.model.NetworkProtocol
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

enum class ScanPhase { Idle, Running, Finished }

/** Credentials -> srvsvc enumeration -> bulk add of all disk shares for one discovered host. */
sealed interface HostConnectFlow {
    data object None : HostConnectFlow
    data class Credentials(val host: String, val failed: Boolean = false, val noShares: Boolean = false) : HostConnectFlow
    data class Enumerating(val host: String) : HostConnectFlow
}

data class NetworkUiState(
    val connections: List<NetworkConnection> = emptyList(),
    val isLoading: Boolean = true,
    val scanPhase: ScanPhase = ScanPhase.Idle,
    val discoveredHosts: List<DiscoveredSmbHost> = emptyList(),
    val connectFlow: HostConnectFlow = HostConnectFlow.None,
)

@KoinViewModel
class NetworkViewModel(
    private val repository: NetworkConnectionRepository,
    private val sshKeyStore: SshKeyStore,
    private val hostScanner: SmbHostScanner,
    private val shareEnumerator: SmbShareEnumerator,
    @InjectedParam internal var output: Output,
) : MviViewModel<NetworkUiState, NetworkAction>() {

    data class Output(
        val addConnection: () -> Unit,
        val editConnection: (Long) -> Unit,
        val openConnection: (Long) -> Unit,
        val openSettings: () -> Unit,
        val openStream: (Uri) -> Unit,
        val connectToDiscoveredHost: (String) -> Unit,
    )

    private val stateInternal = MutableStateFlow(NetworkUiState())
    override val state: StateFlow<NetworkUiState> = stateInternal.asStateFlow()

    private var scanJob: Job? = null
    private var enumerationJob: Job? = null
    private var pendingCredentials: Credentials? = null

    private data class Credentials(val username: String, val password: String)

    init {
        viewModelScope.launch {
            repository.getConnections().collect { connections ->
                stateInternal.update { it.copy(connections = connections, isLoading = false) }
            }
        }
    }

    override fun onAction(action: NetworkAction) {
        when (action) {
            is NetworkAction.AddConnection -> output.addConnection()
            is NetworkAction.EditConnection -> output.editConnection(action.id)
            is NetworkAction.OpenConnection -> output.openConnection(action.id)
            is NetworkAction.OpenSettings -> output.openSettings()
            is NetworkAction.OpenStream -> output.openStream(action.uri)
            is NetworkAction.DeleteConnection -> deleteConnection(action.id)

            is NetworkAction.StartScan -> startScan()
            is NetworkAction.StopScan -> stopScan()
            is NetworkAction.PickDiscoveredHost -> pickDiscoveredHost(action.address)
            is NetworkAction.DismissConnectFlow -> dismissConnectFlow()
            is NetworkAction.EnterDetailsManually -> enterDetailsManually()
            is NetworkAction.SubmitCredentials -> submitCredentials(action)
        }
    }

    private fun pickDiscoveredHost(address: String) {
        stopScan()
        stateInternal.update { it.copy(connectFlow = HostConnectFlow.Credentials(address)) }
    }

    private fun dismissConnectFlow() {
        enumerationJob?.cancel()
        enumerationJob = null
        pendingCredentials = null
        stateInternal.update { it.copy(connectFlow = HostConnectFlow.None) }
    }

    private fun enterDetailsManually() {
        val host = (state.value.connectFlow as? HostConnectFlow.Credentials)?.host ?: return
        dismissConnectFlow()
        output.connectToDiscoveredHost(host)
    }

    private fun submitCredentials(action: NetworkAction.SubmitCredentials) {
        if (enumerationJob?.isActive == true) return
        pendingCredentials = Credentials(action.username, action.password)
        stateInternal.update { it.copy(connectFlow = HostConnectFlow.Enumerating(action.host)) }
        enumerationJob = viewModelScope.launch {
            val result: Result<List<SmbShareEntry>> = try {
                Result.success(shareEnumerator.enumerate(action.host, SMB_PORT, action.username, action.password))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                Result.failure(IOException("srvsvc enumeration failed"))
            }
            if (state.value.connectFlow != HostConnectFlow.Enumerating(action.host)) return@launch
            val shares = result.getOrNull()
            if (shares == null) {
                stateInternal.update { it.copy(connectFlow = HostConnectFlow.Credentials(action.host, failed = true)) }
                return@launch
            }
            if (shares.isEmpty()) {
                stateInternal.update {
                    it.copy(connectFlow = HostConnectFlow.Credentials(action.host, noShares = true))
                }
                return@launch
            }
            try {
                addAllShares(action.host, shares)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                stateInternal.update { it.copy(connectFlow = HostConnectFlow.Credentials(action.host, failed = true)) }
                return@launch
            }
            pendingCredentials = null
            stateInternal.update { it.copy(connectFlow = HostConnectFlow.None) }
        }
    }

    private suspend fun addAllShares(host: String, shares: List<SmbShareEntry>) {
        val credentials = pendingCredentials ?: return
        shares.forEach { share ->
            val shareName = share.name
            val existing = state.value.connections.firstOrNull {
                it.protocol == NetworkProtocol.SMB && it.host == host && it.path == shareName
            }
            val connection = if (existing != null) {
                existing.copy(username = credentials.username, password = credentials.password)
            } else {
                val nameTaken = state.value.connections.any { it.name == shareName }
                NetworkConnection(
                    name = if (nameTaken) "$shareName ($host)" else shareName,
                    protocol = NetworkProtocol.SMB,
                    host = host,
                    path = shareName,
                    username = credentials.username,
                    password = credentials.password,
                )
            }
            withContext(NonCancellable) { repository.upsert(connection) }
        }
    }

    private fun startScan() {
        if (scanJob?.isActive == true) return
        stateInternal.update { it.copy(scanPhase = ScanPhase.Running, discoveredHosts = emptyList()) }
        scanJob = viewModelScope.launch {
            try {
                hostScanner.scan().collect { host ->
                    stateInternal.update { it.copy(discoveredHosts = it.discoveredHosts + host) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Scanning is best effort: finish with whatever was discovered.
            }
            stateInternal.update { it.copy(scanPhase = ScanPhase.Finished) }
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        stateInternal.update { it.copy(scanPhase = ScanPhase.Idle, discoveredHosts = emptyList()) }
    }

    private fun deleteConnection(id: Long) {
        viewModelScope.launch {
            try {
                deleteConnectionAndCleanup(id, repository, sshKeyStore)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // There is no deletion error UI yet; key cleanup restores the row before failure.
            }
        }
    }
}

sealed interface NetworkAction {
    data object AddConnection : NetworkAction
    data class EditConnection(val id: Long) : NetworkAction
    data class OpenConnection(val id: Long) : NetworkAction
    data object OpenSettings : NetworkAction
    data class OpenStream(val uri: Uri) : NetworkAction
    data class DeleteConnection(val id: Long) : NetworkAction

    data object StartScan : NetworkAction
    data object StopScan : NetworkAction
    data class PickDiscoveredHost(val address: String) : NetworkAction
    data object DismissConnectFlow : NetworkAction
    data object EnterDetailsManually : NetworkAction
    data class SubmitCredentials(val host: String, val username: String, val password: String) : NetworkAction
}

private const val SMB_PORT = 445

internal suspend fun deleteConnectionAndCleanup(
    id: Long,
    repository: NetworkConnectionRepository,
    sshKeyStore: SshKeyStore,
) {
    val connection = repository.getConnection(id) ?: return
    withContext(NonCancellable) {
        repository.delete(id)
        if (
            connection.authentication == NetworkAuthentication.SSH_KEY &&
            connection.privateKeyFileName.isNotBlank()
        ) {
            try {
                sshKeyStore.delete(connection.privateKeyFileName)
            } catch (keyFailure: Throwable) {
                try {
                    repository.upsert(connection)
                } catch (rollbackFailure: Throwable) {
                    if (rollbackFailure !== keyFailure) {
                        keyFailure.addSuppressed(rollbackFailure)
                    }
                }
                throw keyFailure
            }
        }
    }
}
