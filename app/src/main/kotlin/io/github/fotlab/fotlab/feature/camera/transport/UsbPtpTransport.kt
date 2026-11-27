package io.github.fotlab.fotlab.feature.camera.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import io.github.fotlab.fotlab.feature.camera.discovery.UsbCameraDiscovery
import io.github.fotlab.fotlab.feature.camera.model.CameraEvent
import io.github.fotlab.fotlab.feature.camera.model.CameraLinkException
import io.github.fotlab.fotlab.feature.camera.protocol.PtpContainer
import io.github.fotlab.fotlab.feature.camera.protocol.PtpTransactionResult
import io.github.fotlab.fotlab.feature.camera.protocol.PtpTransport
import io.github.fotlab.fotlab.feature.camera.protocol.WireContainer
import java.io.IOException
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

/** USB bulk transport for standard PTP containers. */
class UsbPtpTransport(
    private val device: UsbDevice,
    private val discovery: UsbCameraDiscovery,
) : PtpTransport {

    override val label: String get() = "USB ${device.productName.orEmpty()}".trim()

    override var onLinkBroken: (() -> Unit)? = null

    private val mutex = Mutex()
    private val events = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 64)
    private var connection: UsbDeviceConnection? = null
    private var bulkOut: UsbEndpoint? = null
    private var bulkIn: UsbEndpoint? = null
    private var interruptIn: UsbEndpoint? = null
    private var eventJob: Job? = null
    private var linkBroken: Boolean = false

    override fun events(): Flow<CameraEvent> = events.asSharedFlow()

    override suspend fun open() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (connection != null) return@withLock
            val opened = discovery.open(device)
                ?: throw IOException("Cannot open USB device ${device.deviceName}")
            val usbInterface = runCatching { device.getInterface(0) }.getOrNull()
                ?: throw IOException("USB device has no interface 0")
            if (!opened.claimInterface(usbInterface, true)) {
                opened.close()
                throw IOException("Cannot claim USB interface 0")
            }
            val endpoints = findEndpoints(usbInterface)
            connection = opened
            bulkOut = endpoints.out
            bulkIn = endpoints.input
            interruptIn = endpoints.interrupt
            // Class-interface request used during PTP link setup.
            opened.controlTransfer(0x21, 0x66, 0, 0, null, 0, 1_500)
            interruptIn?.let { startEventReader(opened, it) }
        }
        Unit
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            eventJob?.cancel()
            eventJob = null
            linkBroken = false
            connection?.close()
            connection = null
            bulkOut = null
            bulkIn = null
            interruptIn = null
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
            val link = connection ?: throw CameraLinkException("USB PTP link is not open")
            val out = bulkOut ?: throw CameraLinkException("USB bulk OUT endpoint missing")
            val input = bulkIn ?: throw CameraLinkException("USB bulk IN endpoint missing")

            try {
                send(link, out, PtpContainer.usbCommand(code, transactionId, params))
                if (dataOut != null) {
                    send(link, out, PtpContainer.usbData(code, transactionId, dataOut))
                }

                var data: ByteArray? = null
                var response: WireContainer
                while (true) {
                    val container = readContainer(link, input)
                    when (container.type) {
                        PtpContainer.TYPE_DATA -> data = container.payload
                        PtpContainer.TYPE_RESPONSE -> {
                            response = container
                            break
                        }
                    }
                }
                PtpTransactionResult(response.code, response.params, data)
            } catch (error: IOException) {
                markLinkBroken()
                throw CameraLinkException(
                    "USB PTP link failed: ${error.message ?: error.javaClass.simpleName}",
                    error,
                )
            }
        }
    }

    private data class Endpoints(val out: UsbEndpoint?, val input: UsbEndpoint?, val interrupt: UsbEndpoint?)

    private fun findEndpoints(usbInterface: UsbInterface): Endpoints {
        var out: UsbEndpoint? = null
        var input: UsbEndpoint? = null
        var interrupt: UsbEndpoint? = null
        for (index in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(index) ?: continue
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (endpoint.direction == UsbConstants.USB_DIR_OUT) out = endpoint else input = endpoint
            } else if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                interrupt = endpoint
            }
        }
        if (out == null || input == null) {
            throw IOException("USB interface lacks bulk IN/OUT endpoints")
        }
        return Endpoints(out, input, interrupt)
    }

    private fun send(link: UsbDeviceConnection, endpoint: UsbEndpoint, packet: ByteArray) {
        var offset = 0
        val packetSize = endpoint.maxPacketSize.coerceAtLeast(64)
        while (offset < packet.size) {
            val length = minOf(packetSize, packet.size - offset)
            val written = link.bulkTransfer(endpoint, packet, offset, length, USB_TIMEOUT_MS)
            if (written <= 0) throw IOException("USB bulk write failed: $written")
            offset += written
        }
    }

    private fun readContainer(link: UsbDeviceConnection, endpoint: UsbEndpoint): WireContainer {
        val header = ByteArray(PtpContainer.USB_HEADER_BYTES)
        readFully(link, endpoint, header, 0, header.size)
        val declared = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
        if (declared !in PtpContainer.USB_HEADER_BYTES..PtpContainer.MAX_PACKET_BYTES) {
            throw IOException("Invalid USB PTP container length: $declared")
        }
        val raw = if (declared == header.size) {
            header
        } else {
            val rest = ByteArray(declared - header.size)
            readFully(link, endpoint, rest, 0, rest.size)
            header + rest
        }
        return PtpContainer.parseUsb(raw)
    }

    private fun readFully(link: UsbDeviceConnection, endpoint: UsbEndpoint, target: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val count = link.bulkTransfer(endpoint, target, offset + done, length - done, USB_TIMEOUT_MS)
            if (count <= 0) throw IOException("USB bulk read failed: $count")
            done += count
        }
    }

    private fun startEventReader(link: UsbDeviceConnection, endpoint: UsbEndpoint) {
        eventJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive && !linkBroken) {
                val buffer = ByteArray(512)
                val count = runCatching {
                    link.bulkTransfer(endpoint, buffer, buffer.size, 1_000)
                }.getOrNull()
                // A PTP/USB container has a fixed 12-byte header:
                // u32 length | u16 type | u16 code | u32 transactionId | params.
                // The interrupt endpoint returns that container verbatim; there is
                // no 6-byte prefix to strip. Skipping 6 bytes misaligns the header
                // and makes parseUsb reject every ordinary event container.
                if (count != null && count >= PtpContainer.USB_HEADER_BYTES) {
                    runCatching {
                        val container = PtpContainer.parseUsb(buffer.copyOfRange(0, count))
                        if (container.type == PtpContainer.TYPE_EVENT) {
                            events.tryEmit(
                                CameraEvent(container.code, container.transactionId, container.params),
                            )
                        }
                    }
                } else if (count != null && count < 0) {
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

    /** Exposed so the session can report device identity if discovery had no name. */
    fun productName(): String = device.productName.orEmpty()

    fun vendorId(): Int = device.vendorId

    private companion object {
        const val USB_TIMEOUT_MS: Int = 20_000
    }
}
