package dev.anilbeesetti.nextplayer.core.media.network.discovery

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SmbShareEnumeratorTest {

    @Test
    fun `request stub encodes server name then the empty level-1 container`() {
        val stub = DefaultSmbShareEnumerator.ndrShareEnumRequest("192.168.99.99")

        // "\\192.168.99.99" = 15 chars + NUL = 16 => 3 count u32s + 32 data bytes
        assertEquals(80, stub.size)
        assertEquals(0x00020000L, le32(stub, 0))
        assertEquals(16L, le32(stub, 4))
        assertEquals(0L, le32(stub, 8))
        assertEquals(16L, le32(stub, 12))
        assertEquals(
            "\\\\192.168.99.99\u0000",
            String(stub, 16, 32, StandardCharsets.UTF_16LE),
        )
        assertEquals(1L, le32(stub, 48)) // level
        assertEquals(1L, le32(stub, 52)) // union switch
        assertEquals(0x00020004L, le32(stub, 56)) // container referent
        assertEquals(0L, le32(stub, 60)) // EntriesRead
        assertEquals(0L, le32(stub, 64)) // Buffer NULL
        assertEquals(0xFFFFFFFFL, le32(stub, 68)) // PreferedMaximumLength
        assertEquals(0x00020008L, le32(stub, 72)) // ResumeHandle referent
        assertEquals(0L, le32(stub, 76))
    }

    private fun syntheticReply(vararg entries: Pair<String, Long>): ByteArray {
        val w = NdrWriter()
        w.u32(1) // level
        w.u32(1) // union switch
        w.u32(0x00020000) // container referent
        w.u32(entries.size.toLong())
        w.u32(0x00000002) // buffer referent
        w.u32(entries.size.toLong()) // conformant max count, before the elements
        entries.forEach { (_, type) ->
            w.u32(0x00000001) // name referent
            w.u32(type)
            w.u32(0x00000001) // remark referent
        }
        entries.forEach { (name, _) ->
            w.conformantVaryingString(name)
            w.conformantVaryingString("remark of $name")
        }
        w.u32(entries.size.toLong()) // TotalEntries
        w.u32(0) // ResumeHandle: NULL
        w.u32(0) // return code
        return w.toByteArray()
    }

    @Test
    fun `reply round trip decodes names types and remarks`() {
        val reply = syntheticReply("Data" to 0L, "USBData" to 0L)

        val entries = DefaultSmbShareEnumerator.parseShareEnumReply(reply)

        assertEquals(
            listOf(
                SmbShareEntry("Data", 0, "remark of Data"),
                SmbShareEntry("USBData", 0, "remark of USBData"),
            ),
            entries,
        )
    }

    @Test
    fun `special and ipc shares are filtered out`() {
        val reply = syntheticReply(
            "Data" to 0L,
            "ADMIN$" to 0x80000000L,
            "IPC$" to 0x80000003L,
            "print" to 0x00000001L,
        )

        val visible = DefaultSmbShareEnumerator.parseShareEnumReply(reply).filter { it.isDisk }

        assertEquals(listOf("Data"), visible.map { it.name })
        assertFalse(SmbShareEntry("ADMIN$", 0x80000000L, "").isDisk)
        assertTrue(SmbShareEntry("Disk", 0L, "").isDisk)
    }

    @Test
    fun `null buffer yields no entries`() {
        val w = NdrWriter()
        w.u32(1)
        w.u32(1)
        w.u32(0) // container referent NULL
        w.u32(0) // TotalEntries
        w.u32(0) // ResumeHandle NULL
        w.u32(0)
        assertEquals(emptyList<SmbShareEntry>(), DefaultSmbShareEnumerator.parseShareEnumReply(w.toByteArray()))
    }

    @Test(expected = SmbEnumerationException::class)
    fun `non zero return code throws`() {
        val w = NdrWriter()
        w.u32(1)
        w.u32(1)
        w.u32(0)
        w.u32(0)
        w.u32(0)
        w.u32(5) // ACCESS_DENIED
        DefaultSmbShareEnumerator.parseShareEnumReply(w.toByteArray())
    }

    @Test(expected = SmbEnumerationException::class)
    fun `entry count the stub cannot hold is rejected before allocating`() {
        val w = NdrWriter()
        w.u32(1) // level
        w.u32(1) // union switch
        w.u32(0x00020000) // container referent
        w.u32(0xFFFFFFFF) // EntriesRead, a server supplied 32 bit value
        w.u32(0x00000002) // buffer referent
        w.u32(0xFFFFFFFF) // conformant max count
        DefaultSmbShareEnumerator.parseShareEnumReply(w.toByteArray())
    }

    @Test(expected = SmbEnumerationException::class)
    fun `unexpected info level throws`() {
        val w = NdrWriter()
        w.u32(0) // level 0 response to a level 1 request
        w.u32(0)
        w.u32(0)
        w.u32(0)
        w.u32(0)
        w.u32(0)
        DefaultSmbShareEnumerator.parseShareEnumReply(w.toByteArray())
    }

    private fun dcerpcFragment(ptype: Int, declaredLength: Int, afterHeader: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(24 + afterHeader.size)
        out.write(byteArrayOf(5, 0, ptype.toByte(), 3, 0x10, 0, 0, 0))
        out.putU16(declaredLength)
        out.putU16(0) // auth length
        out.putU32(1) // call id
        out.write(ByteArray(8)) // alloc hint, p_cont_id, cancel count, reserved
        out.write(afterHeader)
        return out.toByteArray()
    }

    @Test
    fun `response fragments are joined in arrival order`() {
        val first = dcerpcFragment(2, 28, byteArrayOf(1, 2, 3, 4))
        val second = dcerpcFragment(2, 26, byteArrayOf(5, 6))

        assertEquals(
            listOf(1.toByte(), 2, 3, 4, 5, 6),
            joinDcerpcStub(first + second).toList(),
        )
    }

    @Test(expected = SmbEnumerationException::class)
    fun `fragment declaring less than the response header is rejected`() {
        joinDcerpcStub(dcerpcFragment(2, 20, byteArrayOf(1, 2, 3, 4)))
    }

    @Test(expected = SmbEnumerationException::class)
    fun `zero length fragment is rejected`() {
        joinDcerpcStub(dcerpcFragment(2, 0, ByteArray(0)))
    }

    @Test(expected = SmbEnumerationException::class)
    fun `fragment longer than the bytes received is rejected`() {
        joinDcerpcStub(dcerpcFragment(2, 60, ByteArray(0)))
    }

    @Test(expected = SmbEnumerationException::class)
    fun `fault fragment without its status word is rejected`() {
        joinDcerpcStub(dcerpcFragment(3, 24, ByteArray(0)))
    }

    @Test
    fun `fault fragment reports its status code`() {
        // STATUS_ACCESS_DENIED, little endian at the end of the fault header
        val fault = dcerpcFragment(3, 28, byteArrayOf(0x22, 0, 0, 0xC0.toByte()))
        try {
            joinDcerpcStub(fault)
            fail("Expected the fault PDU to be reported")
        } catch (expected: SmbEnumerationException) {
            assertTrue(expected.message.orEmpty().contains("status=0xc0000022"))
        }
    }

    @Test(expected = SmbEnumerationException::class)
    fun `deferred string claiming more bytes than the stub holds is rejected`() {
        val w = NdrWriter()
        w.u32(1) // max count
        w.u32(0) // offset
        w.u32(0xFFFFFFFF) // actual count, overflows Int once doubled
        NdrReader(w.toByteArray()).deferredString()
    }

    @Test(expected = SmbEnumerationException::class)
    fun `reading past the end of the stub is rejected`() {
        NdrReader(ByteArray(2)).u32()
    }
}
