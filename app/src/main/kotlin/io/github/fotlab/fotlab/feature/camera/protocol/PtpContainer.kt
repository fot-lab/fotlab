package io.github.fotlab.fotlab.feature.camera.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/** A parsed PTP container after transport-specific framing has been removed. */
data class WireContainer(
    val type: Int,
    val code: Int,
    val transactionId: Long,
    val params: List<Long>,
    val payload: ByteArray,
)

/** A raw PTP/IP packet, kept typed because each packet type has a different payload. */
data class IpPacket(
    val length: Int,
    val type: Int,
    val payload: ByteArray,
)

object PtpIpType {
    const val INIT_COMMAND_REQUEST: Int = 1
    const val INIT_COMMAND_ACK: Int = 2
    const val INIT_EVENT_REQUEST: Int = 3
    const val INIT_EVENT_ACK: Int = 4
    const val INIT_FAIL: Int = 5
    const val COMMAND_REQUEST: Int = 6
    const val COMMAND_RESPONSE: Int = 7
    const val EVENT: Int = 8
    const val START_DATA: Int = 9
    const val DATA: Int = 10
    const val CANCEL: Int = 11
    const val END_DATA: Int = 12
    const val PING: Int = 13
    const val PONG: Int = 14
}

/** Little-endian primitives and container framing for the two PTP bearers. */
object PtpContainer {
    const val TYPE_COMMAND: Int = 1
    const val TYPE_DATA: Int = 2
    const val TYPE_RESPONSE: Int = 3
    const val TYPE_EVENT: Int = 4

    const val USB_HEADER_BYTES: Int = 12
    const val IP_HEADER_BYTES: Int = 8
    const val IP_COMMAND_HEADER_BYTES: Int = 18
    const val IP_RESPONSE_HEADER_BYTES: Int = 14
    const val MAX_PACKET_BYTES: Int = 2_000_000

    private fun le16(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

    private fun le32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun le64(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun ByteBuffer.u16(): Int = short.toInt() and 0xFFFF

    private fun ByteBuffer.u32(): Long = int.toLong() and 0xFFFFFFFFL

    // --- USB bulk containers: u32 len | u16 type | u16 code | u32 tid | params ---

    fun usbCommand(code: Int, transactionId: Int, params: IntArray): ByteArray {
        val out = ByteBuffer.allocate(USB_HEADER_BYTES + params.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(out.capacity())
        out.putShort(TYPE_COMMAND.toShort())
        out.putShort(code.toShort())
        out.putInt(transactionId)
        params.forEach { out.putInt(it) }
        return out.array()
    }

    fun usbData(code: Int, transactionId: Int, payload: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(USB_HEADER_BYTES + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(out.capacity())
        out.putShort(TYPE_DATA.toShort())
        out.putShort(code.toShort())
        out.putInt(transactionId)
        out.put(payload)
        return out.array()
    }

    fun parseUsb(raw: ByteArray): WireContainer {
        require(raw.size >= USB_HEADER_BYTES) { "PTP container too short: ${raw.size}" }
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val length = buffer.int
        require(length in USB_HEADER_BYTES..raw.size) { "Invalid PTP container length: $length" }
        val type = buffer.u16()
        val code = buffer.u16()
        val tid = buffer.u32()
        val bodyLength = length - USB_HEADER_BYTES
        val params = mutableListOf<Long>()
        var payload = ByteArray(0)
        if (type == TYPE_COMMAND || type == TYPE_RESPONSE || type == TYPE_EVENT) {
            repeat(bodyLength / 4) { params += buffer.u32() }
        } else {
            payload = ByteArray(bodyLength)
            buffer.get(payload)
        }
        return WireContainer(type, code, tid, params, payload)
    }

    // --- PTP/IP packets: u32 len | u32 type | typed payload ---

    /** Init request payload: 16-byte GUID, UTF-16LE initiator name, terminator, optional tail. */
    fun ipInitCommand(guid: ByteArray, name: String): ByteArray {
        require(guid.size == 16) { "PTP/IP GUID must be 16 bytes" }
        val nameBytes = encodeUtf16Le(name)
        val payloadSize = 16 + nameBytes.size + 4
        val out = ByteBuffer.allocate(IP_HEADER_BYTES + payloadSize)
            .order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(out.capacity())
        out.putInt(PtpIpType.INIT_COMMAND_REQUEST)
        out.put(guid)
        out.put(nameBytes)
        out.putShort(0) // string terminator
        out.putShort(0) // reserved tail present on the camera variants we support
        return out.array()
    }

    fun ipInitEvent(sessionId: Int): ByteArray {
        val out = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(12)
        out.putInt(PtpIpType.INIT_EVENT_REQUEST)
        out.putInt(sessionId)
        return out.array()
    }

    fun ipCommand(code: Int, transactionId: Int, params: IntArray, hasDataPhase: Boolean): ByteArray {
        val out = ByteBuffer.allocate(IP_COMMAND_HEADER_BYTES + params.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(out.capacity())
        out.putInt(PtpIpType.COMMAND_REQUEST)
        out.putInt(if (hasDataPhase) 2 else 1)
        out.putShort(code.toShort())
        out.putInt(transactionId)
        params.forEach { out.putInt(it) }
        return out.array()
    }

    fun ipStartData(transactionId: Int, totalBytes: Long): ByteArray {
        val out = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(20)
        out.putInt(PtpIpType.START_DATA)
        out.putInt(transactionId)
        out.putLong(totalBytes)
        return out.array()
    }

    fun ipData(transactionId: Int, payload: ByteArray, end: Boolean): ByteArray {
        val out = ByteBuffer.allocate(12 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(out.capacity())
        out.putInt(if (end) PtpIpType.END_DATA else PtpIpType.DATA)
        out.putInt(transactionId)
        out.put(payload)
        return out.array()
    }

    fun ipCancel(transactionId: Int): ByteArray {
        val out = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(12)
        out.putInt(PtpIpType.CANCEL)
        out.putInt(transactionId)
        return out.array()
    }

    fun parseIp(raw: ByteArray): IpPacket {
        require(raw.size >= IP_HEADER_BYTES) { "PTP/IP packet too short: ${raw.size}" }
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val length = buffer.int
        val type = buffer.int
        require(length in IP_HEADER_BYTES..raw.size) { "Invalid PTP/IP packet length: $length" }
        val payload = ByteArray(length - IP_HEADER_BYTES)
        buffer.get(payload)
        return IpPacket(length, type, payload)
    }

    /** Command-response and event payloads share the same shape. */
    fun parseIpCodePacket(packet: IpPacket): WireContainer {
        require(packet.payload.size >= 6) { "PTP/IP code packet too short" }
        val buffer = ByteBuffer.wrap(packet.payload).order(ByteOrder.LITTLE_ENDIAN)
        val code = buffer.u16()
        val tid = buffer.u32()
        val params = mutableListOf<Long>()
        while (buffer.remaining() >= 4) params += buffer.u32()
        val type = if (packet.type == PtpIpType.EVENT) TYPE_EVENT else TYPE_RESPONSE
        return WireContainer(type, code, tid, params, ByteArray(0))
    }

    fun parseIpDataPayload(packet: IpPacket): ByteArray = packet.payload.copyOfRange(4, packet.payload.size)

    fun ipDataTransactionId(packet: IpPacket): Long {
        require(packet.payload.size >= 4) { "PTP/IP data packet too short" }
        return ByteBuffer.wrap(packet.payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN).u32()
    }

    fun decodeUtf16Le(bytes: ByteArray, maxChars: Int = 128): String {
        if (bytes.size < 2) return ""
        val chars = CharArray(minOf(maxChars, bytes.size / 2))
        for (i in chars.indices) {
            val low = bytes[i * 2].toInt() and 0xFF
            val high = bytes[i * 2 + 1].toInt() and 0xFF
            chars[i] = ((high shl 8) or low).toChar()
        }
        val end = chars.indexOf('\u0000')
        return String(chars, 0, if (end >= 0) end else chars.size)
    }

    fun encodeUtf16Le(value: String): ByteArray =
        StandardCharsets.UTF_16LE.encode(value).array()

    fun hex(value: ByteArray): String = value.joinToString("") { "%02X".format(it) }
}
