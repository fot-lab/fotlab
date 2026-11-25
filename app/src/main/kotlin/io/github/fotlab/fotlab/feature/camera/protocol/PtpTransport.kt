package io.github.fotlab.fotlab.feature.camera.protocol

import io.github.fotlab.fotlab.feature.camera.model.CameraEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Response of one serialized PTP transaction, with the optional data phase attached. */
data class PtpTransactionResult(
    val responseCode: Int,
    val params: List<Long> = emptyList(),
    val data: ByteArray? = null,
)

/**
 * Common bearer contract. Implementations own framing and link lifetime; they
 * do not know about file semantics, object handles or brand profiles.
 */
interface PtpTransport {
    val label: String

    /**
     * Hook registered by the facade when the transport is handed over to a
     * session. The transport invokes it exactly once when it detects the wire
     * is broken beyond recovery — a closed socket, a vanished USB endpoint, a
     * persistent read error. The callback is the facade's only path to learn
     * about failures that happen outside an explicit [transact] call (for
     * example in an event reader loop). It is never invoked on a normal
     * [close].
     */
    var onLinkBroken: (() -> Unit)?

    /** Bring up the link (USB claim or TCP init handshake). */
    suspend fun open()

    /** Tear down the link and release the underlying handle. */
    suspend fun close()

    /**
     * Send one operation and consume its data/response phases.
     * Implementations serialize concurrent calls.
     */
    suspend fun transact(
        transactionId: Int,
        code: Int,
        params: IntArray = intArrayOf(),
        dataOut: ByteArray? = null,
        expectDataIn: Boolean = false,
    ): PtpTransactionResult

    /** Asynchronous events; an empty flow is valid for links without an event channel. */
    fun events(): Flow<CameraEvent> = emptyFlow()
}
