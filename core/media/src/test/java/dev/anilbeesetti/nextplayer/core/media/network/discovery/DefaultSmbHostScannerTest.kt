package dev.anilbeesetti.nextplayer.core.media.network.discovery

import java.net.Inet4Address
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultSmbHostScannerTest {

    private fun address(text: String) = Inet4Address.getByName(text) as Inet4Address

    @Test
    fun `a 24-bit subnet yields 254 usable hosts without network and broadcast`() {
        val hosts = DefaultSmbHostScanner.hostAddressesInCidr(address("192.168.1.55"), 24)

        assertEquals(254, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
    }

    @Test
    fun `a 30-bit subnet yields the two usable hosts`() {
        val hosts = DefaultSmbHostScanner.hostAddressesInCidr(address("10.0.0.6"), 30)

        assertEquals(listOf("10.0.0.5", "10.0.0.6"), hosts)
    }

    @Test
    fun `a 22-bit subnet stays under the host cap and is kept`() {
        val hosts = DefaultSmbHostScanner.hostAddressesInCidr(address("172.16.5.9"), 22)

        assertEquals(1022, hosts.size)
        assertEquals("172.16.4.1", hosts.first())
        assertEquals("172.16.7.254", hosts.last())
    }

    @Test
    fun `subnets above the cap are clamped to the local 24-bit range`() {
        val hosts = DefaultSmbHostScanner.hostAddressesInCidr(address("10.20.30.40"), 16)

        assertEquals(254, hosts.size)
        assertEquals("10.20.30.1", hosts.first())
        assertEquals("10.20.30.254", hosts.last())
    }

    @Test
    fun `invalid prefix lengths fall back to a 24-bit range`() {
        assertEquals(254, DefaultSmbHostScanner.hostAddressesInCidr(address("192.168.0.7"), 0).size)
        assertEquals(254, DefaultSmbHostScanner.hostAddressesInCidr(address("192.168.0.7"), 32).size)
    }
}
