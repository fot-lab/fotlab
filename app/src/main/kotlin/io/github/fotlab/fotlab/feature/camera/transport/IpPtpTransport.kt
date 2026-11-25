package io.github.fotlab.fotlab.feature.camera.transport

import io.github.fotlab.fotlab.feature.camera.model.CameraEvent
import io.github.fotlab.fotlab.feature.camera.model.CameraLinkException
import io.github.fotlab.fotlab.feature.camera.profile.CameraProfile
import io.github.fotlab.fotlab.feature.camera.protocol.IpPacket
import io.github.fotlab.fotlab.feature.camera.protocol.PtpContainer
import io.github.fotlab.fotlab.feature.camera.protocol.PtpIpType
import io.github.fotlab.fotlab.feature.camera.protocol.PtpTransactionResult
import io.github.fotlab.fotlab.feature.camera.protocol.PtpTransport
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** PTP/IP bearer: init handshake on connect, typed packets for every transaction. */
class IpPtpTransport(
    private val host: String,
    private val port: Int,
    private val profile: CameraProfile,
) : PtpTransport {

    override val label: String get() = "PTP/IP $host"

    override var onLinkBroken: (() -> Unit)? = null

    private val mutex = Mutex()
    private val events = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 64)
    private var links: IpLinks? = null
    private var eventJob: Job? = null
    private var linkBroken: Boolean = false

    override fun events(): Flow<CameraEvent> = events.asSharedFlow()

    override suspend fun open() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (links != null) return@withLock
            val opened = IpHandshake.open(host, port, profile)
            links = opened
            opened.event?.let { startEventReader(it) }
        }
        Unit
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            eventJob?.cancel()
            eventJob = null
            linkBroken = false
            links?.command?.let { runCatching { it.close() } }
            links?.event?.let { runCatching { it.close() } }
            links = null
        }
    }

    override suspend fun transact(
        transactionId: Int,
        code: Int,
        params: IntArray,
        dataOut: ByteArray?,
        expectDataIn: Boolean,
    ): PtpTransactionResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val link = links ?: throw CameraLinkException("PTP/IP link is not open")
            try {
                val output = link.command.getOutputStream()
                output.write(PtpContainer.ipCommand(code, transactionId, params, dataOut != null))
                if (dataOut != null) {
                    output.write(PtpContainer.ipStartData(transactionId, dataOut.size.toLong()))
                    output.write(PtpContainer.ipData(transactionId, dataOut, end = true))
                }
                output.flush()

                var data: ByteArray? = null
                while (true) {
                    val packet = IpHandshake.readPacket(link.command.getInputStream())
                    if (packet.type == PtpIpType.START_DATA) continue
                    if (packet.type == PtpIpType.DATA || packet.type == PtpIpType.END_DATA) {
                        val chunk = PtpContainer.parseIpDataPayload(packet)
                        data = data?.plus(chunk) ?: chunk
                        continue
                    }
                    if (packet.type != PtpIpType.COMMAND_RESPONSE) continue
                    val response = PtpContainer.parseIpCodePacket(packet)
                    if (response.transactionId != transactionId.toLong()) continue
                    return@withLock PtpTransactionResult(response.code, response.params, data)
                }
            } catch (error: IOException) {
                markLinkBroken()
                throw CameraLinkException("PTP/IP link failed: ${error.message ?: error.javaClass.simpleName}", error)
            }
        }
    }

    private fun startEventReader(socket: java.net.Socket) {
        eventJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive && !linkBroken) {
                try {
                    val packet: IpPacket = IpHandshake.readPacket(socket.getInputStream())
                    if (packet.type == PtpIpType.EVENT) {
                        val event = PtpContainer.parseIpCodePacket(packet)
                        events.tryEmit(CameraEvent(event.code, event.transactionId, event.params))
                    }
                } catch (error: IOException) {
                    markLinkBroken()
                    break
                }
            }
        }
    }

    private fun markLinkBroken() {
        if (linkBroken) return
        linkBroken = true
        eventJob?.cancel()
        val callback = onLinkBroken
        onLinkBroken = null
        callback?.invoke()
    }

    private fun OutputStream.write(data: ByteArray) {
        write(data, 0, data.size)
    }
}
