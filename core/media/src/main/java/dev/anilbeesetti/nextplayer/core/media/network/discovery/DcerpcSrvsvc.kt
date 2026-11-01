package dev.anilbeesetti.nextplayer.core.media.network.discovery

import com.hierynomus.smbj.share.NamedPipe
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Minimal DCE/RPC-over-named-pipe client: one bind, then requests by opnum. */
internal class DcerpcOverPipe(private val pipe: NamedPipe) {

    private var callId = 0

    fun bind() {
        val body = ByteArrayOutputStream().apply {
            putU16(MAX_FRAG)
            putU16(MAX_FRAG)
            putU32(0) // assoc group id
            putU32(1) // one presentation context (+3 pad)
            putU16(0) // context id
            putU16(1) // one transfer syntax
            write(SRVSVC_SYNTAX)
            write(NDR_SYNTAX)
        }
        verifyBindAck(pipe.transact(pdu(PTYPE_BIND, body)))
    }

    fun call(opnum: Int, stub: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply {
            putU32(stub.size.toLong()) // alloc hint
            putU16(0) // context id
            putU16(opnum)
            write(stub)
        }
        val received = ByteArrayOutputStream()
        received.write(pipe.transact(pdu(PTYPE_REQUEST, body)))
        while (!fragmentsComplete(received.toByteArray())) {
            val buffer = ByteArray(MAX_FRAG)
            val read = pipe.read(buffer)
            if (read <= 0) break
            received.write(buffer, 0, read)
        }
        return joinDcerpcStub(received.toByteArray())
    }

    private fun pdu(ptype: Int, body: ByteArrayOutputStream): ByteArray {
        val bytes = body.toByteArray()
        val out = ByteArrayOutputStream(HEADER_LEN + bytes.size)
        out.write(
            byteArrayOf(
                5, 0, ptype.toByte(),
                PFC_FIRST_LAST.toByte(), 0x10, 0, 0, 0,
            ),
        )
        out.putU16(HEADER_LEN + bytes.size)
        out.putU16(0) // auth length
        out.putU32((++callId).toLong())
        out.write(bytes)
        return out.toByteArray()
    }

    private fun verifyBindAck(ack: ByteArray) {
        if (ack.size < 34 || ack[2].toInt() != PTYPE_BIND_ACK) {
            throw SmbEnumerationException("Invalid srvsvc bind response")
        }
        val secAddrLen = le16(ack, 24)
        val results = (26 + secAddrLen + 3) and 3.inv()
        if (results + 8 > ack.size || ack[results].toInt() < 1 || le16(ack, results + 4) != 0) {
            throw SmbEnumerationException("srvsvc bind rejected")
        }
    }

    private fun fragmentsComplete(bytes: ByteArray): Boolean {
        var offset = 0
        while (offset < bytes.size) {
            if (bytes.size - offset < HEADER_LEN) return false
            val fragLen = le16(bytes, offset + 8)
            if (fragLen < HEADER_LEN) throw SmbEnumerationException("Invalid DCERPC fragment length")
            if (bytes.size - offset < fragLen) return false
            if (bytes[offset + 3].toInt() and PFC_LAST_FRAG != 0) return true
            offset += fragLen
        }
        return false
    }

    private companion object {
        const val HEADER_LEN = 16
        const val PTYPE_REQUEST = 0
        const val PTYPE_BIND = 11
        const val PTYPE_BIND_ACK = 12
        const val PFC_LAST_FRAG = 0x02
        const val PFC_FIRST_LAST = 0x03
        const val MAX_FRAG = 4280

        // Wire-format UUIDs (mixed-endian) + interface versions for the bind context:
        // srvsvc 4b324fc8-1670-01d3-1278-5a47bf6ee188 v3.0, NDR 8a885d04-1ceb-11c9-9fe8-08002b104860 v2.
        val SRVSVC_SYNTAX = byteArrayOf(
            0xc8.toByte(), 0x4f, 0x32, 0x4b, 0x70, 0x16, 0xd3.toByte(), 0x01,
            0x12, 0x78, 0x5a, 0x47, 0xbf.toByte(), 0x6e, 0xe1.toByte(), 0x88.toByte(),
            0x03, 0x00, 0x00, 0x00,
        )
        val NDR_SYNTAX = byteArrayOf(
            0x04, 0x5d, 0x88.toByte(), 0x8a.toByte(), 0xeb.toByte(), 0x1c, 0xc9.toByte(), 0x11,
            0x9f.toByte(), 0xe8.toByte(), 0x08, 0x00, 0x2b, 0x10, 0x48, 0x60,
            0x02, 0x00, 0x00, 0x00,
        )
    }
}

private const val RESPONSE_HEADER_LEN = 24
private const val PTYPE_RESPONSE = 2
private const val PTYPE_FAULT = 3

/** C706 12.6.4.4 fixes the response header at 24 bytes; a fault PDU adds its status word. */
internal fun joinDcerpcStub(bytes: ByteArray): ByteArray {
    val stub = ByteArrayOutputStream()
    var offset = 0
    while (offset + RESPONSE_HEADER_LEN <= bytes.size) {
        val fragLen = le16(bytes, offset + 8)
        val pduType = bytes[offset + 2].toInt()
        val minLength = if (pduType == PTYPE_FAULT) RESPONSE_HEADER_LEN + 4 else RESPONSE_HEADER_LEN
        if (fragLen < minLength || bytes.size - offset < fragLen) {
            throw SmbEnumerationException("Invalid DCERPC fragment length")
        }
        when (pduType) {
            PTYPE_RESPONSE -> stub.write(bytes, offset + RESPONSE_HEADER_LEN, fragLen - RESPONSE_HEADER_LEN)
            PTYPE_FAULT -> throw SmbEnumerationException(
                "DCERPC fault (status=0x%08x)".format(le32(bytes, offset + RESPONSE_HEADER_LEN)),
            )
            else -> throw SmbEnumerationException("Unexpected DCERPC PDU type")
        }
        offset += fragLen
    }
    return stub.toByteArray()
}

/** NDR fields are 4-byte aligned relative to the stub start, except UTF-16 string data. */
internal class NdrWriter {
    private val out = ByteArrayOutputStream()

    fun u32(value: Long) {
        out.putU32(value)
    }

    /** [string] body: max/offset/actual counts then UTF-16 data plus NUL, padded to 4 bytes. */
    fun conformantVaryingString(text: String) {
        val count = text.length + 1
        u32(count.toLong())
        u32(0)
        u32(count.toLong())
        out.write(text.toByteArray(StandardCharsets.UTF_16LE))
        out.write(byteArrayOf(0, 0))
        while (out.size() % 4 != 0) out.write(0)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

internal class NdrReader(private val bytes: ByteArray) {
    private var offset = 0

    val remaining: Int
        get() = bytes.size - offset

    fun u32(): Long {
        offset = (offset + 3) and 3.inv()
        if (offset + 4 > bytes.size) throw SmbEnumerationException("NDR underflow")
        val value = le32(bytes, offset)
        offset += 4
        return value
    }

    /** Deferred conformant-varying string, terminating NUL removed. */
    fun deferredString(): String {
        u32() // max count
        u32() // offset
        val byteLength = u32() * 2
        if (byteLength > bytes.size - offset) throw SmbEnumerationException("NDR string underflow")
        val size = byteLength.toInt()
        val text = String(bytes, offset, size, StandardCharsets.UTF_16LE)
        offset += size
        offset = (offset + 3) and 3.inv()
        return text.trimEnd('\u0000')
    }
}

internal fun ByteArrayOutputStream.putU16(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
}

internal fun ByteArrayOutputStream.putU32(value: Long) {
    write(value.toInt() and 0xFF)
    write(((value shr 8) and 0xFF).toInt())
    write(((value shr 16) and 0xFF).toInt())
    write(((value shr 24) and 0xFF).toInt())
}

internal fun le16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

internal fun le32(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)
