package dev.anilbeesetti.nextplayer.core.media.network.discovery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live srvsvc enumeration against a real server; runs only when SMB_HOST is set:
 * SMB_HOST=127.0.0.1 SMB_USER=x SMB_PASS=y ./gradlew :core:media:testDebugUnitTest --tests *Live*
 */
class SmbShareEnumeratorLiveTest {

    @Test
    fun `enumerates disk shares of the configured host`() {
        val host = System.getenv("SMB_HOST")
        assumeTrue("SMB_HOST is not set", host != null)
        val port = System.getenv("SMB_PORT")?.toIntOrNull() ?: 445
        val user = checkNotNull(System.getenv("SMB_USER")) { "SMB_USER is required" }
        val pass = checkNotNull(System.getenv("SMB_PASS")) { "SMB_PASS is required" }

        val shares = runBlocking { DefaultSmbShareEnumerator().enumerate(host, port, user, pass) }

        println("disk shares of $host: " + shares.joinToString { "${it.name}(${it.remark})" })
        assertTrue(shares.isNotEmpty())
    }
}
