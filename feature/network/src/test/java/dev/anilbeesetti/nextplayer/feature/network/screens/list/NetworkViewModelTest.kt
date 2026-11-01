package dev.anilbeesetti.nextplayer.feature.network.screens.list

import android.net.Uri
import dev.anilbeesetti.nextplayer.core.data.repository.NetworkConnectionRepository
import dev.anilbeesetti.nextplayer.core.media.network.discovery.DiscoveredSmbHost
import dev.anilbeesetti.nextplayer.core.media.network.discovery.SmbHostScanner
import dev.anilbeesetti.nextplayer.core.media.network.discovery.SmbShareEnumerator
import dev.anilbeesetti.nextplayer.core.media.network.discovery.SmbShareEntry
import dev.anilbeesetti.nextplayer.core.media.network.keys.SshKeyStore
import dev.anilbeesetti.nextplayer.core.media.network.keys.StagedSshKey
import dev.anilbeesetti.nextplayer.core.model.NetworkAuthentication
import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import dev.anilbeesetti.nextplayer.core.model.NetworkProtocol
import java.io.IOException
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `deleting SSH key connection looks up then deletes row before key`() = runTest {
        val events = mutableListOf<String>()
        val repository = FakeNetworkConnectionRepository(
            connection = connection(privateKeyFileName = "living-room.key"),
            events = events,
        )
        val keyStore = FakeSshKeyStore(events)

        deleteConnectionAndCleanup(7, repository, keyStore)

        assertEquals(
            listOf("repository.get:7", "repository.delete:7", "key.delete:living-room.key"),
            events,
        )
    }

    @Test
    fun `missing connection stops after lookup`() = runTest {
        val events = mutableListOf<String>()
        val repository = FakeNetworkConnectionRepository(connection = null, events = events)
        val keyStore = FakeSshKeyStore(events)

        deleteConnectionAndCleanup(7, repository, keyStore)

        assertEquals(listOf("repository.get:7"), events)
    }

    @Test
    fun `lookup failure stops before repository deletion`() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("Room lookup failed")
        val repository = FakeNetworkConnectionRepository(
            connection = null,
            events = events,
            lookupFailure = failure,
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { deleteConnectionAndCleanup(7, repository, FakeSshKeyStore(events)) }
        }

        assertEquals(failure.message, thrown.message)
        assertSame(failure, thrown.cause ?: thrown)
        assertEquals(listOf("repository.get:7"), events)
    }

    @Test
    fun `repository failure retains SSH key`() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("Room delete failed")
        val repository = FakeNetworkConnectionRepository(
            connection = connection(privateKeyFileName = "living-room.key"),
            events = events,
            deleteFailure = failure,
        )
        val keyStore = FakeSshKeyStore(events)

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { deleteConnectionAndCleanup(7, repository, keyStore) }
        }

        assertEquals(failure.message, thrown.message)
        assertSame(failure, thrown.cause ?: thrown)
        assertEquals(listOf("repository.get:7", "repository.delete:7"), events)
    }

    @Test
    fun `password connection with stale key filename does not delete key`() = runTest {
        val events = mutableListOf<String>()
        val repository = FakeNetworkConnectionRepository(
            connection = connection(
                authentication = NetworkAuthentication.PASSWORD,
                privateKeyFileName = "stale.key",
            ),
            events = events,
        )

        deleteConnectionAndCleanup(7, repository, FakeSshKeyStore(events))

        assertEquals(listOf("repository.get:7", "repository.delete:7"), events)
    }

    @Test
    fun `SSH key connection without key filename does not delete key`() = runTest {
        val events = mutableListOf<String>()
        val repository = FakeNetworkConnectionRepository(
            connection = connection(privateKeyFileName = ""),
            events = events,
        )

        deleteConnectionAndCleanup(7, repository, FakeSshKeyStore(events))

        assertEquals(listOf("repository.get:7", "repository.delete:7"), events)
    }

    @Test
    fun `key deletion failure restores original connection`() {
        val events = mutableListOf<String>()
        val original = connection(privateKeyFileName = "living-room.key")
        val keyFailure = IllegalStateException("Key delete failed")
        val repository = FakeNetworkConnectionRepository(connection = original, events = events)
        val keyStore = FakeSshKeyStore(events, deleteFailure = keyFailure)

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { deleteConnectionAndCleanup(7, repository, keyStore) }
        }

        assertEquals(keyFailure.message, thrown.message)
        assertSame(keyFailure, thrown.cause ?: thrown)
        assertEquals(
            listOf(
                "repository.get:7",
                "repository.delete:7",
                "key.delete:living-room.key",
                "repository.upsert:7",
            ),
            events,
        )
        assertEquals(listOf(original), repository.upsertedConnections)
    }

    @Test
    fun `rollback failure is suppressed onto key deletion failure`() {
        val events = mutableListOf<String>()
        val keyFailure = IllegalStateException("Key delete failed")
        val rollbackFailure = IllegalArgumentException("Room rollback failed")
        val repository = FakeNetworkConnectionRepository(
            connection = connection(privateKeyFileName = "living-room.key"),
            events = events,
            upsertFailure = rollbackFailure,
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                deleteConnectionAndCleanup(
                    7,
                    repository,
                    FakeSshKeyStore(events, deleteFailure = keyFailure),
                )
            }
        }

        val originalKeyFailure = thrown.cause ?: thrown
        assertSame(keyFailure, originalKeyFailure)
        assertEquals(listOf(rollbackFailure), originalKeyFailure.suppressed.toList())
        assertEquals("repository.upsert:7", events.last())
    }

    @Test
    fun `cancellation after repository deletion cannot skip key cleanup`() = runTest {
        val events = mutableListOf<String>()
        val rowDeleted = CompletableDeferred<Unit>()
        val allowDeleteToReturn = CompletableDeferred<Unit>()
        val repository = FakeNetworkConnectionRepository(
            connection = connection(privateKeyFileName = "living-room.key"),
            events = events,
            afterDelete = {
                rowDeleted.complete(Unit)
                allowDeleteToReturn.await()
            },
        )
        val keyStore = FakeSshKeyStore(events, beforeDelete = { yield() })

        val deletion = launch {
            deleteConnectionAndCleanup(7, repository, keyStore)
        }
        rowDeleted.await()
        deletion.cancel()
        allowDeleteToReturn.complete(Unit)
        deletion.join()

        assertTrue(deletion.isCancelled)
        assertEquals(
            listOf("repository.get:7", "repository.delete:7", "key.delete:living-room.key"),
            events,
        )
    }

    @Test
    fun `public deletion path handles lookup delete and cleanup failures`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val lookupEvents = mutableListOf<String>()
            val deleteEvents = mutableListOf<String>()
            val cleanupEvents = mutableListOf<String>()
            NetworkViewModel(
                output = NetworkViewModel.Output({}, {}, {}, {}, {}, {}),
                repository = FakeNetworkConnectionRepository(
                    connection = null,
                    events = lookupEvents,
                    lookupFailure = IllegalStateException("lookup"),
                ),
                sshKeyStore = FakeSshKeyStore(lookupEvents),
                hostScanner = FakeSmbHostScanner(),
                shareEnumerator = FakeSmbShareEnumerator(),
            ).onAction(NetworkAction.DeleteConnection(1))

            NetworkViewModel(
                output = NetworkViewModel.Output({}, {}, {}, {}, {}, {}),
                repository = FakeNetworkConnectionRepository(
                    connection = connection(privateKeyFileName = "delete.key"),
                    events = deleteEvents,
                    deleteFailure = IllegalStateException("delete"),
                ),
                sshKeyStore = FakeSshKeyStore(deleteEvents),
                hostScanner = FakeSmbHostScanner(),
                shareEnumerator = FakeSmbShareEnumerator(),
            ).onAction(NetworkAction.DeleteConnection(2))

            NetworkViewModel(
                output = NetworkViewModel.Output({}, {}, {}, {}, {}, {}),
                repository = FakeNetworkConnectionRepository(
                    connection = connection(privateKeyFileName = "cleanup.key"),
                    events = cleanupEvents,
                ),
                sshKeyStore = FakeSshKeyStore(
                    cleanupEvents,
                    deleteFailure = IllegalStateException("cleanup"),
                ),
                hostScanner = FakeSmbHostScanner(),
                shareEnumerator = FakeSmbShareEnumerator(),
            ).onAction(NetworkAction.DeleteConnection(3))

            advanceUntilIdle()

            assertEquals(listOf("repository.get:1"), lookupEvents)
            assertEquals(listOf("repository.get:2", "repository.delete:2"), deleteEvents)
            assertEquals("repository.upsert:7", cleanupEvents.last())
        }

    @Test
    fun `scan collects discovered hosts then marks finished`() = runTest(mainDispatcherRule.testDispatcher) {
        val hosts = listOf(
            DiscoveredSmbHost("192.168.1.10", "NAS"),
            DiscoveredSmbHost("192.168.1.24"),
        )
        val viewModel = viewModelWith(scanner = FakeSmbHostScanner(hosts))

        viewModel.onAction(NetworkAction.StartScan)
        advanceUntilIdle()

        assertEquals(ScanPhase.Finished, viewModel.state.value.scanPhase)
        assertEquals(hosts, viewModel.state.value.discoveredHosts)
    }

    @Test
    fun `stop scan cancels running scan and clears results`() = runTest(mainDispatcherRule.testDispatcher) {
        val scanner = FakeSmbHostScanner(
            hosts = listOf(DiscoveredSmbHost("192.168.1.10")),
            hangAfterEmit = true,
        )
        val viewModel = viewModelWith(scanner = scanner)

        viewModel.onAction(NetworkAction.StartScan)
        advanceUntilIdle()
        assertEquals(ScanPhase.Running, viewModel.state.value.scanPhase)
        assertEquals(1, viewModel.state.value.discoveredHosts.size)

        viewModel.onAction(NetworkAction.StopScan)
        advanceUntilIdle()

        assertEquals(ScanPhase.Idle, viewModel.state.value.scanPhase)
        assertEquals(emptyList<DiscoveredSmbHost>(), viewModel.state.value.discoveredHosts)
        assertTrue(scanner.cancelled)
    }

    @Test
    fun `picking discovered host stops scan and asks for credentials`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = viewModelWith(scanner = FakeSmbHostScanner(listOf(DiscoveredSmbHost("192.168.1.24"))))

            viewModel.onAction(NetworkAction.StartScan)
            advanceUntilIdle()
            viewModel.onAction(NetworkAction.PickDiscoveredHost("192.168.1.24"))

            assertEquals(ScanPhase.Idle, viewModel.state.value.scanPhase)
            assertEquals(HostConnectFlow.Credentials("192.168.1.24"), viewModel.state.value.connectFlow)
        }

    @Test
    fun `submitting credentials adds all discovered shares and closes the dialog`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repository = FakeNetworkConnectionRepository(connection = null, events = mutableListOf())
            val viewModel = viewModelWith(
                enumerator = FakeSmbShareEnumerator(
                    listOf(SmbShareEntry("Data", 0, "data disk"), SmbShareEntry("SSDData", 0, "")),
                ),
                repository = repository,
            )

            viewModel.onAction(NetworkAction.PickDiscoveredHost("192.168.1.24"))
            viewModel.onAction(NetworkAction.SubmitCredentials("192.168.1.24", "smbuser", "secret"))
            advanceUntilIdle()

            assertEquals(
                listOf(
                    NetworkConnection(
                        name = "Data",
                        protocol = NetworkProtocol.SMB,
                        host = "192.168.1.24",
                        path = "Data",
                        username = "smbuser",
                        password = "secret",
                    ),
                    NetworkConnection(
                        name = "SSDData",
                        protocol = NetworkProtocol.SMB,
                        host = "192.168.1.24",
                        path = "SSDData",
                        username = "smbuser",
                        password = "secret",
                    ),
                ),
                repository.upsertedConnections,
            )
            assertEquals(HostConnectFlow.None, viewModel.state.value.connectFlow)
        }

    @Test
    fun `empty share list reports no shares instead of closing`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repository = FakeNetworkConnectionRepository(connection = null, events = mutableListOf())
            val viewModel = viewModelWith(
                enumerator = FakeSmbShareEnumerator(shares = emptyList()),
                repository = repository,
            )

            viewModel.onAction(NetworkAction.PickDiscoveredHost("192.168.1.24"))
            viewModel.onAction(NetworkAction.SubmitCredentials("192.168.1.24", "smbuser", "secret"))
            advanceUntilIdle()

            assertEquals(
                HostConnectFlow.Credentials("192.168.1.24", noShares = true),
                viewModel.state.value.connectFlow,
            )
            assertEquals(emptyList<NetworkConnection>(), repository.upsertedConnections)
        }

    @Test
    fun `share save failure reports failure instead of leaving the dialog enumerating`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repository = FakeNetworkConnectionRepository(
                connection = null,
                events = mutableListOf(),
                upsertFailure = IOException("database full"),
            )
            val viewModel = viewModelWith(
                enumerator = FakeSmbShareEnumerator(listOf(SmbShareEntry("Data", 0, ""))),
                repository = repository,
            )

            viewModel.onAction(NetworkAction.PickDiscoveredHost("192.168.1.24"))
            viewModel.onAction(NetworkAction.SubmitCredentials("192.168.1.24", "smbuser", "secret"))
            advanceUntilIdle()

            assertEquals(
                HostConnectFlow.Credentials("192.168.1.24", failed = true),
                viewModel.state.value.connectFlow,
            )
        }

    @Test
    fun `failed enumeration offers manual entry through the prefilled form`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val connected = mutableListOf<String>()
            val viewModel = viewModelWith(
                enumerator = FakeSmbShareEnumerator(failure = IOException("auth rejected")),
                onConnect = { connected += it },
            )

            viewModel.onAction(NetworkAction.PickDiscoveredHost("192.168.1.24"))
            viewModel.onAction(NetworkAction.SubmitCredentials("192.168.1.24", "bad", "creds"))
            advanceUntilIdle()
            assertEquals(HostConnectFlow.Credentials("192.168.1.24", failed = true), viewModel.state.value.connectFlow)

            viewModel.onAction(NetworkAction.EnterDetailsManually)

            assertEquals(listOf("192.168.1.24"), connected)
            assertEquals(HostConnectFlow.None, viewModel.state.value.connectFlow)
        }

    private fun viewModelWith(
        scanner: SmbHostScanner = FakeSmbHostScanner(),
        enumerator: SmbShareEnumerator = FakeSmbShareEnumerator(),
        repository: FakeNetworkConnectionRepository =
            FakeNetworkConnectionRepository(connection = null, events = mutableListOf()),
        onConnect: (String) -> Unit = {},
    ) = NetworkViewModel(
        output = NetworkViewModel.Output({}, {}, {}, {}, {}, onConnect),
        repository = repository,
        sshKeyStore = FakeSshKeyStore(mutableListOf()),
        hostScanner = scanner,
        shareEnumerator = enumerator,
    )

    private fun connection(
        authentication: NetworkAuthentication = NetworkAuthentication.SSH_KEY,
        privateKeyFileName: String,
    ) = NetworkConnection(
        id = 7,
        name = "Living room",
        protocol = NetworkProtocol.SFTP,
        host = "192.168.1.7",
        authentication = authentication,
        privateKeyFileName = privateKeyFileName,
    )
}

private class FakeSmbHostScanner(
    private val hosts: List<DiscoveredSmbHost> = emptyList(),
    private val hangAfterEmit: Boolean = false,
) : SmbHostScanner {
    var cancelled = false
        private set

    override fun scan(): Flow<DiscoveredSmbHost> = flow {
        try {
            hosts.forEach { emit(it) }
            if (hangAfterEmit) awaitCancellation()
        } finally {
            if (hangAfterEmit) cancelled = true
        }
    }
}

private class FakeSmbShareEnumerator(
    private val shares: List<SmbShareEntry> = listOf(SmbShareEntry("Data", 0, "data disk")),
    private val failure: Throwable? = null,
) : SmbShareEnumerator {
    override suspend fun enumerate(
        host: String,
        port: Int,
        username: String,
        password: String,
    ): List<SmbShareEntry> {
        failure?.let { throw it }
        return shares
    }
}

private class FakeNetworkConnectionRepository(
    private val connection: NetworkConnection?,
    private val events: MutableList<String>,
    private val lookupFailure: Throwable? = null,
    private val deleteFailure: Throwable? = null,
    private val upsertFailure: Throwable? = null,
    private val afterDelete: suspend () -> Unit = {},
) : NetworkConnectionRepository {
    val upsertedConnections = mutableListOf<NetworkConnection>()

    override fun getConnections(): Flow<List<NetworkConnection>> = flowOf(emptyList())

    override suspend fun getConnection(id: Long): NetworkConnection? {
        events += "repository.get:$id"
        lookupFailure?.let { throw it }
        return connection
    }

    override suspend fun upsert(connection: NetworkConnection): Long {
        events += "repository.upsert:${connection.id}"
        upsertedConnections += connection
        upsertFailure?.let { throw it }
        return connection.id
    }

    override suspend fun delete(id: Long) {
        events += "repository.delete:$id"
        deleteFailure?.let { throw it }
        afterDelete()
    }
}

private class FakeSshKeyStore(
    private val events: MutableList<String>,
    private val deleteFailure: Throwable? = null,
    private val beforeDelete: suspend () -> Unit = {},
) : SshKeyStore {
    override suspend fun stage(uri: Uri): StagedSshKey = error("Not used")

    override fun resolve(fileName: String): File = error("Not used")

    override suspend fun commit(fileName: String): String = error("Not used")

    override suspend fun delete(fileName: String) {
        beforeDelete()
        events += "key.delete:$fileName"
        deleteFailure?.let { throw it }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
