package io.github.fotlab.fotlab.feature.camera.action

import io.github.fotlab.fotlab.feature.camera.model.CameraLinkException
import io.github.fotlab.fotlab.feature.camera.model.PtpResponseException
import io.github.fotlab.fotlab.feature.camera.protocol.PtpSession
import io.github.fotlab.fotlab.feature.camera.protocol.PtpTransactionResult
import io.github.fotlab.fotlab.feature.camera.profile.CameraProfile
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpCanon
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpNikon
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpSony
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpStd
import kotlinx.coroutines.delay

/**
 * Context passed to every action routine. Holds exactly what a routine
 * needs to pick opcodes / HTTP methods, shape parameters, and decode
 * results. Routine code never hard-codes a vendor or transport branch —
 * [path] carries the full 4-segment encoding and [ObjectUrlCache] covers
 * URL sharing across HTTP list → thumbnail/download.
 *
 * Construction is owned by [CameraRoutine]; routines never build their own.
 */
data class CameraActionContext(
    /** Full 4-segment path — drives all opcode / HTTP-method selection. */
    val path: TransportPath,
    /** When true, every PTP operation forces ISO standard regardless of [path]. */
    val ptpStdOnly: Boolean,
    /** PTP session — non-null when [path.isPtp], null otherwise. */
    val ptpSession: PtpSession?,
    /** Camera profile with chunk sizes, GUID variant, raw opcode tuning. */
    val profile: CameraProfile,
    /** HTTP transport — non-null when [path.isHttp], null for PTP. */
    val httpTransport: io.github.fotlab.fotlab.feature.camera.transport.SonyHttpTransport? = null,
    /** URL cache populated by HTTP list; synthetic handle → SonyObjectUrls. */
    val objectUrlCache: ObjectUrlCache = ObjectUrlCache(),
) {
    /** Shortcut — the segment after ptpStdOnly override is applied. */
    val segment: Segment get() = path.effectiveSegment(ptpStdOnly)

    /** Whether this path's vendor segment is a USB-only block (Sony 0x92xx). */
    val isSonyUsb92xx: Boolean get() = path == TransportPath.PTP_USB_SONY_0X92XX

    /** Human-readable label for logs. */
    val label: String
        get() = when (path) {
            TransportPath.PTP_USB_STD_ISO,
            TransportPath.PTP_IP_STD_ISO,
            TransportPath.PTP_USB_SONY_0X92XX,
            TransportPath.PTP_USB_CANON_0X91XX,
            TransportPath.PTP_IP_CANON_0X91XX,
            TransportPath.PTP_USB_NIKON_0X90XX,
            TransportPath.PTP_IP_NIKON_0X90XX,
            TransportPath.PTP_USB_FUJI_0XD2XX -> ptpSession?.label ?: "ptp"
            TransportPath.HTTP_IP_SONY_NEX -> httpTransport?.label ?: "sony-http"
        }
}

/**
 * Maps object handles (including synthetic ones from HTTP lists) to
 * transport-specific URLs for thumbnail / download. HTTP list responses
 * already advertise these URLs inline — we fill this cache during
 * [ListObjectsAction.executeHttp] and later routines just read it.
 * PTP paths leave the cache empty.
 */
class ObjectUrlCache {
    private val entries = HashMap<Long, SonyHttpContentAdapter.SonyObjectUrls>()
    operator fun set(handle: Long, urls: SonyHttpContentAdapter.SonyObjectUrls) { entries[handle] = urls }
    operator fun get(handle: Long): SonyHttpContentAdapter.SonyObjectUrls? = entries[handle]
    fun clear() = entries.clear()
}

// ────────────────────────────────────────────────────────────────────────
// PTP executor — standard-first + busy retry, all in one place
// ────────────────────────────────────────────────────────────────────────

/**
 * All PTP-side actions go through this executor. Encapsulates the
 * standard-first fallback, bounded Device_Busy / Store_Not_Available retry,
 * and the skip-standard bypass for vendor-only segments (e.g. Sony 0x92xx).
 *
 * Every parameter is derived from [CameraActionContext.path] by callers —
 * this class itself never reads vendor or transport fields; it only runs
 * the list of opcodes it is handed.
 */
object PtpExecutor {

    /** Retry Device_Busy / Store_Not_Available up to N times with linear backoff. */
    private const val MAX_BUSY_RETRIES = 2
    private const val BUSY_RETRY_BASE_MS = 400L

    /**
     * Execute one PTP operation with standard-first fallback and busy retry.
     *
     * @param ctx action context — provides session + ptpStdOnly flag
     * @param standard standard ISO/PIMA opcode, or null when segment has no standard counterpart
     * @param vendor   vendor extension opcode, or null when ISO-only path
     * @param params   operation parameters as raw ints
     * @param expectDataIn true when the operation carries a data-in phase
     * @param dataOut   optional data-out payload
     * @param skipStandard when true, ISO standard opcode is not attempted at all —
     *                    needed for Sony 0x92xx on USB (standard returns empty/truncated
     *                    data instead of refusing, so fallback never triggers)
     */
    suspend fun execute(
        ctx: CameraActionContext,
        standard: Int?,
        vendor: Int?,
        params: IntArray,
        expectDataIn: Boolean = true,
        dataOut: ByteArray? = null,
        skipStandard: Boolean = false,
    ): PtpTransactionResult {
        val session = ctx.ptpSession
            ?: throw CameraLinkException("No PTP session available")

        if (ctx.ptpStdOnly) {
            val effectiveStandard = standard
                ?: throw IllegalStateException(
                    "No standard opcode available; cannot honour ptpStdOnly=true",
                )
            return withBusyRetry(session, effectiveStandard, params, dataOut, expectDataIn)
        }

        val candidates = buildList {
            if (!skipStandard && standard != null) add(standard)
            if (vendor != null) add(vendor)
        }.distinct()

        if (candidates.isEmpty()) {
            throw IllegalStateException("No opcode candidates for this operation")
        }

        var lastError: PtpResponseException? = null
        for ((index, code) in candidates.withIndex()) {
            val notLast = index < candidates.size - 1
            val result = withBusyRetryCancellable(session, code, params, dataOut, expectDataIn)
            when (result) {
                is RetryOk -> return result.result
                is RetryTransientBusy -> {
                    if (index > 0) break
                    lastError = result.lastError
                }
                is RetryNotSupported -> {
                    if (notLast) { /* fall through */ }
                    else lastError = result.lastError
                }
            }
        }
        throw lastError ?: PtpResponseException(
            op = vendor ?: standard ?: -1,
            responseCode = PtpStd.Response.GENERAL_ERROR,
        )
    }

    /**
     * Standard-first, no busy retry. Used for per-object queries
     * (ObjectInfo, Thumbnail) where the device should answer immediately.
     */
    suspend fun executeNoRetry(
        ctx: CameraActionContext,
        standard: Int?,
        vendor: Int?,
        params: IntArray,
        expectDataIn: Boolean = true,
        dataOut: ByteArray? = null,
        skipStandard: Boolean = false,
    ): PtpTransactionResult {
        val session = ctx.ptpSession
            ?: throw CameraLinkException("No PTP session available")

        if (ctx.ptpStdOnly) {
            val effectiveStandard = standard
                ?: throw IllegalStateException(
                    "No standard opcode available; cannot honour ptpStdOnly=true",
                )
            return session.execute(effectiveStandard, params, dataOut, expectDataIn)
        }

        val candidates = buildList {
            if (!skipStandard && standard != null) add(standard)
            if (vendor != null) add(vendor)
        }.distinct()

        for (code in candidates) {
            return try {
                session.execute(code, params, dataOut, expectDataIn)
            } catch (error: PtpResponseException) {
                val unsupported = error.responseCode == PtpStd.Response.OPERATION_NOT_SUPPORTED ||
                    error.responseCode == PtpStd.Response.PARAMETER_NOT_SUPPORTED
                if (unsupported && code != candidates.last()) continue
                throw error
            }
        }
        throw IllegalStateException("No opcode candidates")
    }

    // ── Internal retry machinery ──────────────────────────────────────────

    private sealed class RetryResult {
        data class Ok(val result: PtpTransactionResult) : RetryResult()
        data class TransientBusy(val lastError: PtpResponseException) : RetryResult()
        data class NotSupported(val lastError: PtpResponseException) : RetryResult()
    }

    private suspend fun withBusyRetry(
        session: PtpSession,
        code: Int,
        params: IntArray,
        dataOut: ByteArray?,
        expectDataIn: Boolean,
    ): PtpTransactionResult {
        var busyCount = 0
        while (true) {
            try {
                return session.execute(code, params, dataOut, expectDataIn)
            } catch (error: PtpResponseException) {
                val transient = error.responseCode == PtpStd.Response.DEVICE_BUSY ||
                    error.responseCode == PtpStd.Response.STORE_NOT_AVAILABLE
                if (!transient) throw error
                busyCount++
                if (busyCount >= MAX_BUSY_RETRIES) throw error
                delay(BUSY_RETRY_BASE_MS * busyCount)
            }
        }
    }

    private suspend fun withBusyRetryCancellable(
        session: PtpSession,
        code: Int,
        params: IntArray,
        dataOut: ByteArray?,
        expectDataIn: Boolean,
    ): RetryResult {
        var busyCount = 0
        var lastError: PtpResponseException? = null
        while (true) {
            try {
                return RetryOk(session.execute(code, params, dataOut, expectDataIn))
            } catch (error: PtpResponseException) {
                lastError = error
                val transient = error.responseCode == PtpStd.Response.DEVICE_BUSY ||
                    error.responseCode == PtpStd.Response.STORE_NOT_AVAILABLE
                val unsupported = error.responseCode == PtpStd.Response.OPERATION_NOT_SUPPORTED ||
                    error.responseCode == PtpStd.Response.PARAMETER_NOT_SUPPORTED
                when {
                    unsupported -> return RetryNotSupported(error)
                    transient -> {
                        busyCount++
                        if (busyCount >= MAX_BUSY_RETRIES) return RetryTransientBusy(error)
                        delay(BUSY_RETRY_BASE_MS * busyCount)
                    }
                    else -> throw error
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────
// Standard opcode tables keyed by Segment — callers never hard-code
// which vendor block maps to which opcode; they read from the table.
// ────────────────────────────────────────────────────────────────────────

/**
 * Lookup table that maps a [Segment] to the opcode pair every action routine
 * needs. Standard ISO opcode first (null when there is no ISO counterpart),
 * vendor opcode second (null when ISO-only path).
 *
 * Routines call [Opcodes.getListing(ctx)] / [Opcodes.getThumbnail(ctx)] etc.
 * instead of reading `profile.objectHandlesOpcode` or writing a `when (family)`
 * branch — this table is the single source of truth for opcode selection.
 *
 * When ptpStdOnly=true, callers pass `standard` only and skip `vendor`.
 */
object Opcodes {

    data class Pair(val standard: Int?, val vendor: Int?)

    /** GetStorageIDs + GetStorageInfo for each volume. */
    fun storages(ctx: CameraActionContext): Pair = when (ctx.segment) {
        Segment.ISO          -> Pair(PtpStd.GET_STORAGE_IDS, null)
        Segment.SONY_0X92XX   -> Pair(null, PtpSony.STORAGE_IDS)
        Segment.CANON_0X91XX  -> Pair(PtpStd.GET_STORAGE_IDS, PtpCanon.STORAGE_IDS)
        Segment.NIKON_0X90XX  -> Pair(PtpStd.GET_STORAGE_IDS, null)
        Segment.FUJI_0XD2XX   -> Pair(PtpStd.GET_STORAGE_IDS, null)
        Segment.NEX           -> throw IllegalStateException("storages() called on HTTP path")
    }

    /** GetObjectHandles / CONTENT_LIST for file listing. */
    fun objectHandles(ctx: CameraActionContext): Pair = when (ctx.segment) {
        Segment.ISO          -> Pair(PtpStd.GET_OBJECT_HANDLES, null)
        Segment.SONY_0X92XX  -> Pair(null, PtpSony.CONTENT_LIST)
        Segment.CANON_0X91XX -> Pair(PtpStd.GET_OBJECT_HANDLES, PtpCanon.OBJECT_HANDLES)
        Segment.NIKON_0X90XX -> Pair(PtpStd.GET_OBJECT_HANDLES, PtpNikon.OBJECT_HANDLES)
        Segment.FUJI_0XD2XX  -> Pair(PtpStd.GET_OBJECT_HANDLES, null)
        Segment.NEX          -> throw IllegalStateException("objectHandles() called on HTTP path")
    }

    /** GetObjectInfo — per-object metadata. */
    fun objectInfo(ctx: CameraActionContext): Pair = when (ctx.segment) {
        Segment.ISO          -> Pair(PtpStd.GET_OBJECT_INFO, null)
        Segment.SONY_0X92XX  -> Pair(PtpStd.GET_OBJECT_INFO, null)
        Segment.CANON_0X91XX -> Pair(PtpStd.GET_OBJECT_INFO, PtpCanon.OBJECT_INFO_EX)
        Segment.NIKON_0X90XX -> Pair(PtpStd.GET_OBJECT_INFO, null)
        Segment.FUJI_0XD2XX  -> Pair(PtpStd.GET_OBJECT_INFO, null)
        Segment.NEX          -> throw IllegalStateException("objectInfo() called on HTTP path")
    }

    /** GetPartialObject — range download in chunks. */
    fun partialObject(ctx: CameraActionContext): Pair = when (ctx.segment) {
        Segment.ISO          -> Pair(PtpStd.GET_PARTIAL_OBJECT, null)
        Segment.SONY_0X92XX  -> Pair(null, PtpSony.PARTIAL_OBJECT)
        Segment.CANON_0X91XX -> Pair(PtpStd.GET_PARTIAL_OBJECT, PtpCanon.PARTIAL_OBJECT)
        Segment.NIKON_0X90XX -> Pair(PtpStd.GET_PARTIAL_OBJECT, PtpNikon.PARTIAL_OBJECT)
        Segment.FUJI_0XD2XX  -> Pair(PtpStd.GET_PARTIAL_OBJECT, PtpSony.PARTIAL_OBJECT /* placeholder */)
        Segment.NEX          -> throw IllegalStateException("partialObject() called on HTTP path")
    }

    /** GetThumb — thumbnail bytes. */
    fun thumbnail(ctx: CameraActionContext): Pair = when (ctx.segment) {
        Segment.ISO          -> Pair(PtpStd.GET_THUMB, null)
        Segment.SONY_0X92XX  -> Pair(null, PtpSony.THUMB)
        Segment.CANON_0X91XX -> Pair(PtpStd.GET_THUMB, PtpCanon.THUMB)
        Segment.NIKON_0X90XX -> Pair(PtpStd.GET_THUMB, PtpNikon.THUMB)
        Segment.FUJI_0XD2XX  -> Pair(PtpStd.GET_THUMB, null)
        Segment.NEX          -> throw IllegalStateException("thumbnail() called on HTTP path")
    }

    /** DeleteObject — remote delete. */
    fun delete(ctx: CameraActionContext): Pair = when (ctx.segment) {
        Segment.ISO          -> Pair(PtpStd.DELETE_OBJECT, null)
        Segment.SONY_0X92XX  -> Pair(null, PtpSony.DELETE_OBJECT)
        Segment.CANON_0X91XX -> Pair(PtpStd.DELETE_OBJECT, null)
        Segment.NIKON_0X90XX -> Pair(PtpStd.DELETE_OBJECT, null)
        Segment.FUJI_0XD2XX  -> Pair(PtpStd.DELETE_OBJECT, null)
        Segment.NEX          -> throw IllegalStateException("delete() called on HTTP path")
    }
}
