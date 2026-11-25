package io.github.fotlab.fotlab.feature.camera.transport

import io.github.fotlab.fotlab.feature.camera.profile.CameraProfile
import io.github.fotlab.fotlab.feature.camera.profile.GuidVariant
import io.github.fotlab.fotlab.feature.camera.protocol.IpPacket
import io.github.fotlab.fotlab.feature.camera.protocol.PtpContainer
import io.github.fotlab.fotlab.feature.camera.protocol.PtpIpType
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class IpLinks(
    val command: Socket,
    val event: Socket?,
    val sessionId: Int,
    val responderGuid: String,
    val responderName: String,
)

/** PTP/IP init exchange on the command and event TCP connections. */
object IpHandshake {
    private const val INITIATOR_NAME = "CameraClient"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val COMMAND_TIMEOUT_MS = 12_000
    private const val EVENT_ACCEPT_TIMEOUT_MS = 5_000

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
            val command = Socket().apply {
                soTimeout = COMMAND_TIMEOUT_MS
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }
            // The responder connects back to us for the event channel. Bind before
            // the init request so the callback cannot arrive before we listen.
            val eventServer = runCatching { ServerSocket(port) }.getOrNull()
            try {
                val request = PtpContainer.ipInitCommand(guid(profile.guidVariant, host), INITIATOR_NAME)
                command.getOutputStream().write(request)
                command.getOutputStream().flush()

                val ack = PtpContainer.parseIp(readPacket(command.getInputStream()))
                if (ack.type != PtpIpType.INIT_COMMAND_ACK) {
                    throw IOException("Unexpected PTP/IP init response: ${ack.type}")
                }
                val payload = ack.payload
                if (payload.size < 20) throw IOException("Short PTP/IP init ack")
                val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                val sessionId = buffer.int
                val guidBytes = ByteArray(16).also { buffer.get(it) }
                val name = PtpContainer.decodeUtf16Le(payload.copyOfRange(20, payload.size))

                val event = eventServer?.let { server ->
                    server.soTimeout = EVENT_ACCEPT_TIMEOUT_MS
                    val accepted = runCatching { server.accept() }.getOrNull()
                    if (accepted != null) {
                        accepted.soTimeout = COMMAND_TIMEOUT_MS
                        val initEvent = PtpContainer.ipInitEvent(sessionId)
                        accepted.getOutputStream().write(initEvent)
                        accepted.getOutputStream().flush()
                        val eventAck = PtpContainer.parseIp(readPacket(accepted.getInputStream()))
                        if (eventAck.type != PtpIpType.INIT_EVENT_ACK) {
                            accepted.close()
                            null
                        } else {
                            accepted
                        }
                    } else {
                        null
                    }
                }

                IpLinks(command, event, sessionId, PtpContainer.hex(guidBytes), name)
            } catch (error: Throwable) {
                runCatching { command.close() }
                throw error
            } finally {
                eventServer?.let { runCatching { it.close() } }
            }
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
