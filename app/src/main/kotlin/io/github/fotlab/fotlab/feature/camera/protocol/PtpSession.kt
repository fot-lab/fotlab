package io.github.fotlab.fotlab.feature.camera.protocol

import io.github.fotlab.fotlab.feature.camera.model.CameraEvent
import io.github.fotlab.fotlab.feature.camera.model.CameraLinkException
import io.github.fotlab.fotlab.feature.camera.model.PtpResponseException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PtpDeviceIdentity(
    val manufacturer: String = "",
    val model: String = "",
)

/**
 * Owns transaction numbering and the OpenSession guard.
 *
 * Every operation except GetDeviceInfo and OpenSession goes through [execute],
 * which opens the session first when needed. A mutex is used because a delayed
 * OpenSession racing a listing command is the classic way to get an invalid
 * transaction id from a camera.
 *
 * Transport failures are normalised into [CameraLinkException] so the layer above
 * can tell "the device answered and refused" ([PtpResponseException]) from "the
 * link is gone" — the second case ends the session instead of being retried.
 */
class PtpSession(
    private val transport: PtpTransport,
    private val sessionId: Int,
) {
    private val mutex = Mutex()
    private val ids = AtomicInteger(0)
    private var sessionOpen = false
    private var closed = false
    private var identity = PtpDeviceIdentity()

    val label: String get() = transport.label

    fun events(): Flow<CameraEvent> = transport.events()

    suspend fun open(): PtpDeviceIdentity = mutex.withLock {
        if (!closed && sessionOpen) return@withLock identity
        link { transport.open() }
        val deviceInfo = callLocked(PtpOpcode.GET_DEVICE_INFO, intArrayOf(), null, true, false)
        identity = DeviceInfoDecoder.decode(deviceInfo.data)
        val opened = callLocked(
            PtpOpcode.OPEN_SESSION,
            intArrayOf(sessionId),
            null,
            false,
            false,
        )
        checkResponse(PtpOpcode.OPEN_SESSION, opened.responseCode)
        sessionOpen = true
        identity
    }

    suspend fun execute(
        code: Int,
        params: IntArray = intArrayOf(),
        dataOut: ByteArray? = null,
        expectDataIn: Boolean = false,
    ): PtpTransactionResult = mutex.withLock {
        check(!closed) { "PTP session is closed" }
        callLocked(code, params, dataOut, expectDataIn, true)
    }

    /** GetDeviceInfo is permitted before OpenSession, so expose it explicitly. */
    suspend fun deviceInfo(): PtpTransactionResult = mutex.withLock {
        check(!closed) { "PTP session is closed" }
        callLocked(PtpOpcode.GET_DEVICE_INFO, intArrayOf(), null, true, false)
    }

    suspend fun close() = mutex.withLock {
        if (closed) return@withLock
        closed = true
        if (sessionOpen) {
            runCatching {
                val tid = ids.incrementAndGet() and 0x7FFFFFFF
                val result = link { transport.transact(tid, PtpOpcode.CLOSE_SESSION) }
                checkResponse(PtpOpcode.CLOSE_SESSION, result.responseCode)
            }
            sessionOpen = false
        }
        runCatching { transport.close() }
    }

    private suspend fun callLocked(
        code: Int,
        params: IntArray,
        dataOut: ByteArray?,
        expectDataIn: Boolean,
        ensureSession: Boolean,
    ): PtpTransactionResult {
        if (ensureSession && !sessionOpen) {
            val opened = callLocked(PtpOpcode.OPEN_SESSION, intArrayOf(sessionId), null, false, false)
            checkResponse(PtpOpcode.OPEN_SESSION, opened.responseCode)
            sessionOpen = true
        }
        val tid = ids.incrementAndGet() and 0x7FFFFFFF
        val result = link { transport.transact(tid, code, params, dataOut, expectDataIn) }
        checkResponse(code, result.responseCode)
        return result
    }

    private fun checkResponse(code: Int, response: Int) {
        if (response != PtpOpcode.Response.OK) {
            throw PtpResponseException(code, response)
        }
    }

    /**
     * Normalise a transport failure. A device that answered with an error code
     * keeps its [PtpResponseException]; anything else that breaks the wire
     * becomes a [CameraLinkException], which ends the session.
     */
    private suspend fun <T> link(block: suspend () -> T): T = try {
        block()
    } catch (error: PtpResponseException) {
        throw error
    } catch (error: CameraLinkException) {
        throw error
    } catch (error: IOException) {
        throw CameraLinkException("PTP link failed: ${error.message ?: error.javaClass.simpleName}", error)
    }
}

/** Decodes the fields of the standard device-info dataset we actually use. */
object DeviceInfoDecoder {
    fun decode(raw: ByteArray?): PtpDeviceIdentity {
        if (raw == null || raw.size < 10) return PtpDeviceIdentity()
        return try {
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            buffer.short // standard version
            buffer.int // vendor extension id
            buffer.short // vendor extension version
            readString(buffer)
            buffer.short // functional mode
            skipCodeArray(buffer)
            skipCodeArray(buffer)
            skipCodeArray(buffer)
            skipCodeArray(buffer)
            PtpDeviceIdentity(readString(buffer), readString(buffer))
        } catch (_: RuntimeException) {
            PtpDeviceIdentity()
        }
    }

    private fun skipCodeArray(buffer: ByteBuffer) {
        if (buffer.remaining() < 4) throw IllegalArgumentException("truncated device info")
        val count = buffer.int and 0x7FFFFFFF
        if (count > 0 && buffer.remaining() >= count * 2) {
            buffer.position(buffer.position() + count * 2)
        }
    }

    /** PTP strings are a u8 count of UTF-16 code units (including terminator) plus the units. */
    private fun readString(buffer: ByteBuffer): String {
        val units = buffer.get().toInt() and 0xFF
        if (units <= 1 || buffer.remaining() < (units - 1) * 2) return ""
        val bytes = ByteArray((units - 1) * 2)
        buffer.get(bytes)
        return PtpContainer.decodeUtf16Le(bytes, units - 1)
    }
}
