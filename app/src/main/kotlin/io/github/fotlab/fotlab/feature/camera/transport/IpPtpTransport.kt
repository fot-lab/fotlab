package io.github.fotlab.fotlab.feature.camera.transport

import io.github.fotlab.fotlab.feature.camera.model.CameraEvent
import io.github.fotlab.fotlab.feature.camera.model.CameraLinkException
import io.github.fotlab.fotlab.feature.camera.profile.CameraProfile
import io.github.fotlab.fotlab.feature.camera.protocol.IpPacket
import io.github.fotlab.fotlab.feature.camera.protocol.PtpContainer
import io.github.fotlab.fotlab.feature.camera.protocol.PtpIpType
import io.github.fotlab.fotlab.feature.camera.protocol.PtpTransactionResult
import io.github.fotlab.fotlab.feature.camera.protocol.PtpTransport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
                    // SendObject data phase: START_DATA(9) + END_DATA(12, all payload
                    // inline in END_DATA, no intermediate DATA(10) chunks).
                    output.write(PtpContainer.ipStartData(transactionId, dataOut.size.toLong()))
                    output.write(PtpContainer.ipData(transactionId, dataOut, end = true))
                }
                output.flush()

                if (!expectDataIn) {
                    return@withLock waitForResponse(link.command.getInputStream(), transactionId)
                }

                // ReceiveObject data phase — two on-the-wire paths observed
                // across current PTP/IP devices:
                //
                // 1. type-12 (Canon/Nikon/Sony): responder pushes payload-bearing
                //    COMMAND_RESPONSE(type=7) whose payload IS the raw file bytes,
                //    then a COMMAND_RESPONSE(type=7) with the status code.
                //    [note: both frames are PtpIpType.COMMAND_RESPONSE; we
                //    disambiguate by transactionId]
                //
                // 2. type-11 (Fuji WiFi VID 1227): responder pushes raw TCP to the
                //    independent 55742 data socket — first 8B LE long totalBytes,
                //    then the file payload, then socket close.
                //
                // We dispatch by profile.extendedInit and fall back gracefully
                // when the camera behaves differently.

                if (profile.extendedInit) {
                    val dataSocket = IpHandshake.openDataSocket(link.host)
                    if (dataSocket != null) {
                        return@withLock readDataSocket(link.command.getInputStream(), dataSocket, transactionId)
                    }
                }

                return@withLock readCommandAckPayload(link.command.getInputStream(), transactionId)
            } catch (error: IOException) {
                markLinkBroken()
                throw CameraLinkException("PTP/IP link failed: ${error.message ?: error.javaClass.simpleName}", error)
            }
        }
    }

    /** Read a single COMMAND_RESPONSE matching [transactionId]; no data phase. */
    private fun waitForResponse(commandIn: InputStream, transactionId: Int): PtpTransactionResult {
        while (true) {
            val packet = IpHandshake.readPacket(commandIn)
            if (packet.type != PtpIpType.COMMAND_RESPONSE) continue
            val response = PtpContainer.parseIpCodePacket(packet)
            if (response.transactionId != transactionId.toLong()) continue
            return PtpTransactionResult(response.code, response.params, null)
        }
    }

    /**
     * type-12 Canon/Nikon/Sony path. The responder pushes payload-bearing
     * COMMAND_RESPONSE(type=7) whose payload IS the data stream, followed by
     * another COMMAND_RESPONSE(type=7) with the status code (disambiguated
     * by transactionId).
     *
     * Tolerates three variants so cameras are not locked into one behaviour:
     *   a) COMMAND_RESPONSE carries the full payload → read its payload directly
     *   b) CIPA-standard START_DATA(9) declares size → drain DATA×N + END_DATA
     *   c) COMMAND_RESPONSE comes first, with data already packed into payload
     */
    private fun readCommandAckPayload(commandIn: InputStream, transactionId: Int): PtpTransactionResult {
        val chunks = mutableListOf<ByteArray>()
        var responseCode = -1
        var responseParams: List<Long> = emptyList()

        // Variant (c): COMMAND_RESPONSE arrives directly, no separate data phase.
        // Its payload may already hold the file data.
        val responseFirst = IpHandshake.readPacket(commandIn)
        if (responseFirst.type == PtpIpType.COMMAND_RESPONSE) {
            val parsed = PtpContainer.parseIpCodePacket(responseFirst)
            if (parsed.transactionId == transactionId.toLong()) {
                return PtpTransactionResult(parsed.code, parsed.params, responseFirst.payload)
            }
        }

        // Variant (a): COMMAND_RESPONSE carries all data inline in its payload.
        // We already fall through here when the first COMMAND_RESPONSE tid did
        // not match — collect its payload and continue looking for the matching
        // COMMAND_RESPONSE that terminates the data phase.
        if (responseFirst.type == PtpIpType.COMMAND_RESPONSE) {
            chunks.add(responseFirst.payload)
        }

        // Variant (b): CIPA standard START_DATA → DATA×N → END_DATA → COMMAND_RESPONSE.
        // Also catches stray DATA/END_DATA frames from a camera that mixes variants.
        while (true) {
            val packet = IpHandshake.readPacket(commandIn)
            when (packet.type) {
                PtpIpType.START_DATA -> continue
                PtpIpType.DATA, PtpIpType.END_DATA -> chunks.add(PtpContainer.parseIpDataPayload(packet))
                PtpIpType.COMMAND_RESPONSE -> {
                    val response = PtpContainer.parseIpCodePacket(packet)
                    if (response.transactionId == transactionId.toLong()) {
                        responseCode = response.code
                        responseParams = response.params
                        break
                    }
                }
            }
        }

        val all = ByteArray(chunks.sumOf { it.size })
        var pos = 0
        for (c in chunks) {
            c.copyInto(all, destinationOffset = pos)
            pos += c.size
        }
        return PtpTransactionResult(responseCode, responseParams, all)
    }

    /**
     * type-11 Fuji WiFi VID 1227 path.
     *
     * The responder pushes raw TCP on port 55742: 8B LE long totalBytes prefix,
     * then exactly (totalBytes - 8) bytes of file payload, then closes the socket.
     * We read the prefix to know how much payload to expect, read it all, then
     * discard any remaining command-channel frames until we see COMMAND_RESPONSE.
     */
    private fun readDataSocket(
        commandIn: InputStream,
        dataSocket: Socket,
        transactionId: Int,
    ): PtpTransactionResult {
        var responseCode = -1
        var responseParams: List<Long> = emptyList()
        val dataIn = dataSocket.getInputStream()
        val out = ByteArrayOutputStream()
        try {
            // Read the 8B LE long prefix that Fuji uses to declare total length
            // (including the 8B itself). Subtract 8 to get actual payload size.
            val prefix = ByteArray(8)
            val prefixRead = runCatching { readFully(dataIn, prefix) }.getOrDefault(false)
            if (prefixRead) {
                val total = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN).long
                val payloadLen = (total - 8).coerceAtLeast(0)
                val buf = ByteArray(minOf(payloadLen, 8 * 1024 * 1024L).toInt())
                var done = 0L
                while (done < payloadLen) {
                    val n = dataIn.read(buf, 0, minOf(buf.size.toLong(), payloadLen - done).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                }
            }

            // Drain command channel for the status frame.
            while (true) {
                val packet = try { IpHandshake.readPacket(commandIn) } catch (_: IOException) { break }
                if (packet.type == PtpIpType.COMMAND_RESPONSE) {
                    val response = PtpContainer.parseIpCodePacket(packet)
                    if (response.transactionId == transactionId.toLong()) {
                        responseCode = response.code
                        responseParams = response.params
                        break
                    }
                }
            }
        } finally {
            runCatching { dataSocket.close() }
        }
        return PtpTransactionResult(responseCode, responseParams, out.toByteArray())
    }

    private fun readFully(input: InputStream, target: ByteArray): Boolean {
        var done = 0
        while (done < target.size) {
            val count = input.read(target, done, target.size - done)
            if (count < 0) return false
            done += count
        }
        return true
    }

    private fun startEventReader(socket: Socket) {
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

