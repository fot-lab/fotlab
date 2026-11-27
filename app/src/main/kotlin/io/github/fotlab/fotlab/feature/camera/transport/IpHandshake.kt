package io.github.fotlab.fotlab.feature.camera.transport

import io.github.fotlab.fotlab.feature.camera.profile.CameraProfile
import io.github.fotlab.fotlab.feature.camera.profile.GuidVariant
import io.github.fotlab.fotlab.feature.camera.protocol.IpPacket
import io.github.fotlab.fotlab.feature.camera.protocol.PtpContainer
import io.github.fotlab.fotlab.feature.camera.protocol.PtpIpType
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Three TCP links make up a full PTP/IP session on the wire.
 *
 * | Field | Port | Purpose |
 * |-------|------|---------|
 * | command | 15740 (type-12) / 55740 (type-11) | Commands + inline command-ack data |
 * | event   | same (type-12) / 55741 (type-11)  | Async notifications |
 * | data    | **55742** (always, optional)      | Fuji WiFi bulk reads |
 *
 * The data socket is NOT opened eagerly — we try it lazily when a caller
 * asks for it (see [IpHandshake.openDataSocket]). Cameras that do not
 * speak the independent-data variant will refuse the TCP connect and we
 * fall back to the command-ack payload path.
 */
data class IpLinks(
    val host: String,
    val command: Socket,
    val event: Socket?,
    val sessionId: Int,
    val responderGuid: String,
    val responderName: String,
) {
    /** Fixed independent-data port; cameras answer only when they have data to push. */
    val dataPort: Int = 55742
}

/**
 * PTP/IP init exchange on the command and event TCP connections.
 *
 * Two on-wire variants share the same CIPA header shape; the choice is
 * driven by [CameraProfile.extendedInit] (type-11 = Fuji WiFi VID 1227,
 * 82B init with magic 0x8F53E4F2, ports 55740/55741) vs the standard
 * CIPA type-12 (46B init, ports 15740 on both channels).
 */
object IpHandshake {
    private const val INITIATOR_NAME = "CameraClient"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val COMMAND_TIMEOUT_MS = 12_000
    /** Cameras may take longer to answer the one-shot init request than normal commands. */
    private const val INIT_ACK_TIMEOUT_MS = 30_000
    private const val EVENT_READ_TIMEOUT_MS = 1_000
    private const val DATA_CONNECT_TIMEOUT_MS = 1_000
    private const val DATA_READ_TIMEOUT_MS = 120_000

    /** Fixed port where PTP/IP cameras report their independent bulk-data channel. */
    private const val DATA_PORT = 55742

    /**
     * Standard PTP/IP ports — one responder handles command and event on the
     * same listening socket (CIPA DC-X005 §2.2.1).
     */
    private const val STANDARD_COMMAND_PORT = 15740
    private const val STANDARD_EVENT_PORT = 15740

    /**
     * Type-11 (Fuji WiFi VID 1227) ports — the event channel listens on the
     * adjacent odd port while command moves to 55740.
     */
    private const val TYPE11_COMMAND_PORT = 55740
    private const val TYPE11_EVENT_PORT = 55741

    private val PRIMARY_GUID = hex("25283BBA5823476CBA715185941CBE90")
    private val ALTERNATE_GUID = hex("05AC5E3583389640B13A70E5A68A336D")
    private val SECONDARY_GUID = hex("ADA5485D87B27F0BD3D5DED000000000")

    /** Camera-side network aliases that answer with a fixed GUID. */
    fun guid(variant: GuidVariant, host: String): ByteArray = when (host) {
        "192.168.122.1" -> ByteArray(16)
        "192.168.0.1" -> SECONDARY_GUID.copyOf()
        else -> when (variant) {
            GuidVariant.PRIMARY -> PRIMARY_GUID.copyOf()
            GuidVariant.ALTERNATE -> ALTERNATE_GUID.copyOf()
            GuidVariant.SECONDARY -> SECONDARY_GUID.copyOf()
            GuidVariant.ZERO -> ByteArray(16)
        }
    }

    suspend fun open(host: String, port: Int, profile: CameraProfile): IpLinks =
        withContext(Dispatchers.IO) {
            val extendedInit = profile.extendedInit
            val guid = guid(profile.guidVariant, host)

            // Pick ports by protocol variant — caller's `port` arg still wins
            // when it differs (explicit profile.initPort override).
            val commandPort = port
            val eventPort = when {
                extendedInit && port == TYPE11_COMMAND_PORT -> TYPE11_EVENT_PORT
                else -> commandPort
            }

            val command = connect(host, commandPort, COMMAND_TIMEOUT_MS)
            try {
                val init = if (extendedInit) {
                    PtpContainer.ipInitCommandType11(guid, INITIATOR_NAME)
                } else {
                    PtpContainer.ipInitCommand(guid, INITIATOR_NAME)
                }
                command.getOutputStream().apply {
                    write(init)
                    flush()
                }

                val ack = try {
                    command.soTimeout = INIT_ACK_TIMEOUT_MS
                    readPacket(command.getInputStream())
                } finally {
                    command.soTimeout = COMMAND_TIMEOUT_MS
                }
                if (ack.type == PtpIpType.INIT_FAIL) {
                    val detail = runCatching { PtpContainer.parseInitFail(ack).toString() }
                        .getOrDefault("INIT_FAIL")
                    throw IOException("PTP/IP init refused: $detail")
                }
                if (ack.type != PtpIpType.INIT_COMMAND_ACK) {
                    throw IOException("Unexpected PTP/IP init response: ${ack.type}")
                }
                val payload = ack.payload
                if (payload.size < 20) throw IOException("Short PTP/IP init ack")
                val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                val sessionId = buffer.int
                val guidBytes = ByteArray(16).also { buffer.get(it) }
                val name = PtpContainer.decodeUtf16Le(payload.copyOfRange(20, payload.size))

                // Event channel — second TCP connection. We tolerate cameras that
                // do not speak the event channel (Sony in HTTP-only mode, some
                // low-end Canon). In those cases `event` is null and callers
                // continue with command-channel transactions only.
                val event: Socket? = runCatching {
                    val ev = connect(host, eventPort, EVENT_READ_TIMEOUT_MS)
                    try {
                        ev.soTimeout = EVENT_READ_TIMEOUT_MS
                        ev.getOutputStream().apply {
                            write(PtpContainer.ipInitEvent(sessionId))
                            flush()
                        }
                        val evAck = readPacket(ev.getInputStream())
                        when (evAck.type) {
                            PtpIpType.INIT_EVENT_ACK -> ev
                            PtpIpType.INIT_FAIL, PtpIpType.INIT_COMMAND_ACK -> {
                                ev.close()
                                null
                            }
                            else -> {
                                ev.close()
                                null
                            }
                        }
                    } catch (_: IOException) {
                        runCatching { ev.close() }
                        null
                    }
                }.getOrNull()

                IpLinks(host, command, event, sessionId, PtpContainer.hex(guidBytes), name)
            } catch (error: Throwable) {
                runCatching { command.close() }
                throw error
            }
        }

    /**
     * Open the independent data channel. Callers invoke this lazily, immediately
     * before a GetObject (ReceiveObject, opcode 4124) that would stream a large
     * file. Cameras that speak the channel answer on port 55742 with a pure TCP
     * byte stream — no PTP headers, no START/DATA/END framing. Cameras that do
     * not speak it either refuse the connection silently or close their end;
     * both are treated as "fall back to command-socket START/DATA/END".
     */
    suspend fun openDataSocket(host: String): Socket? = withContext(Dispatchers.IO) {
        runCatching {
            val socket = connect(host, DATA_PORT, DATA_CONNECT_TIMEOUT_MS)
            socket.soTimeout = DATA_READ_TIMEOUT_MS
            socket
        }.getOrNull()
    }

    private fun connect(host: String, port: Int, soTimeout: Int): Socket =
        Socket().apply {
            this.soTimeout = soTimeout
            this.tcpNoDelay = true
            this.reuseAddress = true
            this.keepAlive = true
            connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        }

    /** Read one length-prefixed PTP/IP packet; shared by handshake and transport. */
    fun readPacket(input: InputStream): IpPacket {
        val header = readFully(input, 8)
        val declared = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
        if (declared !in PtpContainer.IP_HEADER_BYTES..PtpContainer.MAX_PACKET_BYTES) {
            throw IOException("Invalid PTP/IP packet length: $declared")
        }
        val rest = readFully(input, declared - PtpContainer.IP_HEADER_BYTES)
        return PtpContainer.parseIp(header + rest)
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val target = ByteArray(length)
        var done = 0
        while (done < length) {
            val count = input.read(target, done, length - done)
            if (count < 0) throw IOException("PTP/IP connection closed")
            done += count
        }
        return target
    }

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0) { "Odd hex string" }
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
