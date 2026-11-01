package dev.anilbeesetti.nextplayer.core.data.repository

import dev.anilbeesetti.nextplayer.core.data.security.SecretCipher
import dev.anilbeesetti.nextplayer.core.database.dao.NetworkConnectionDao
import dev.anilbeesetti.nextplayer.core.database.entities.NetworkConnectionEntity
import dev.anilbeesetti.nextplayer.core.model.NetworkAuthentication
import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import dev.anilbeesetti.nextplayer.core.model.NetworkProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalNetworkConnectionRepositoryTest {
    private val dao = FakeNetworkConnectionDao()
    private val cipher = FakeSecretCipher()
    private val repository = LocalNetworkConnectionRepository(dao, cipher)

    @Test
    fun `upsert and get connection preserves authentication settings`() = runTest {
        val connection = NetworkConnection(
            name = "SFTP",
            protocol = NetworkProtocol.SFTP,
            host = "10.0.2.2",
            username = "alice",
            authentication = NetworkAuthentication.SSH_KEY,
            privateKeyFileName = "123.key",
            privateKeyPassphrase = "passphrase",
            hostKeyFingerprint = "SHA256:abc",
        )

        assertEquals(1L, repository.upsert(connection))
        assertEquals(connection.copy(id = 1), repository.getConnection(1))
    }

    @Test
    fun `unknown stored authentication falls back to password`() = runTest {
        dao.seed(
            NetworkConnectionEntity(
                id = 7,
                name = "Legacy",
                protocol = NetworkProtocol.SFTP.name,
                host = "10.0.2.2",
                port = 22,
                authentication = "UNKNOWN",
            ),
        )

        assertEquals(
            NetworkAuthentication.PASSWORD,
            repository.getConnection(7)?.authentication,
        )
    }

    @Test
    fun `password and passphrase are stored encrypted`() = runTest {
        repository.upsert(
            NetworkConnection(
                name = "NAS",
                protocol = NetworkProtocol.SMB,
                host = "10.0.2.2",
                username = "bob",
                password = "hunter2",
                privateKeyPassphrase = "key-pass",
            ),
        )

        val stored = dao.stored()
        assertEquals("enc:2retnuh", stored.password)
        assertEquals("enc:ssap-yek", stored.privateKeyPassphrase)
        // Reading back still yields the real secrets.
        val loaded = repository.getConnection(1)!!
        assertEquals("hunter2", loaded.password)
        assertEquals("key-pass", loaded.privateKeyPassphrase)
    }

    @Test
    fun `plaintext rows written before encryption still read correctly`() = runTest {
        dao.seed(
            NetworkConnectionEntity(
                id = 5,
                name = "Old",
                protocol = NetworkProtocol.FTP.name,
                host = "10.0.2.2",
                password = "plain-secret",
                privateKeyPassphrase = "",
            ),
        )

        assertEquals("plain-secret", repository.getConnection(5)?.password)
    }

    private class FakeSecretCipher : SecretCipher {
        override fun encrypt(plainText: String): String =
            if (plainText.isEmpty()) plainText else "enc:" + plainText.reversed()

        override fun decrypt(cipherText: String): String =
            if (cipherText.startsWith("enc:")) cipherText.removePrefix("enc:").reversed() else cipherText
    }

    private class FakeNetworkConnectionDao : NetworkConnectionDao {
        private val connections = MutableStateFlow<List<NetworkConnectionEntity>>(emptyList())

        fun seed(connection: NetworkConnectionEntity) {
            connections.value = connections.value + connection
        }

        fun stored(): NetworkConnectionEntity = connections.value.last()

        override suspend fun upsert(connection: NetworkConnectionEntity): Long {
            val id = connection.id.takeIf { it != 0L } ?: 1L
            val savedConnection = connection.copy(id = id)
            connections.value = connections.value.filterNot { it.id == id } + savedConnection
            return id
        }

        override fun getAll(): Flow<List<NetworkConnectionEntity>> = connections

        override suspend fun getById(id: Long): NetworkConnectionEntity? =
            connections.value.firstOrNull { it.id == id }

        override suspend fun deleteById(id: Long) {
            connections.value = connections.value.filterNot { it.id == id }
        }
    }
}
