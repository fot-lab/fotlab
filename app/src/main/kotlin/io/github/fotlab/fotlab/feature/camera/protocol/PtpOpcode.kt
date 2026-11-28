package io.github.fotlab.fotlab.feature.camera.protocol

import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpCanon
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpFuji
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpNikon
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpSony
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpStd

/**
 * Backward-compatibility shim — re-exports every opcode from the split files
 * under the historical `PtpOpcode` name so PtpSession, IpPtpTransport,
 * ObjectInfoDecoder and other late-phase code still compile without being
 * rewritten right now.
 *
 * The canonical source of truth lives in `protocol/opcode/`:
 * - [PtpStd]       — ISO/PIMA 15740 standard opcodes (USB + IP)
 * - [PtpSony]      — Sony 0x92xx vendor opcodes (USB only)
 * - [PtpCanon]     — Canon 0x91xx vendor opcodes
 * - [PtpNikon]     — Nikon 0x90xx / 0x94xx vendor opcodes
 * - [PtpFuji]      — Fuji 0xD2xx / 0xDFxx vendor opcodes (USB only)
 * - [io.github.fotlab.fotlab.feature.camera.protocol.opcode.SonyHttp] — Sony JSON-RPC HTTP methods
 *
 * New code should import from the split files directly. This shim exists
 * only while the old PtpFileOperations / PtpSession internals are being
 * migrated to the action layer.
 */
object PtpOpcode {
    // ── Standard operations ──────────────────────────────────────────────
    const val UNDEFINED: Int = PtpStd.UNDEFINED
    const val GET_DEVICE_INFO: Int = PtpStd.GET_DEVICE_INFO
    const val OPEN_SESSION: Int = PtpStd.OPEN_SESSION
    const val CLOSE_SESSION: Int = PtpStd.CLOSE_SESSION
    const val GET_STORAGE_IDS: Int = PtpStd.GET_STORAGE_IDS
    const val GET_STORAGE_INFO: Int = PtpStd.GET_STORAGE_INFO
    const val GET_NUM_OBJECTS: Int = PtpStd.GET_NUM_OBJECTS
    const val GET_OBJECT_HANDLES: Int = PtpStd.GET_OBJECT_HANDLES
    const val GET_OBJECT_INFO: Int = PtpStd.GET_OBJECT_INFO
    const val GET_OBJECT: Int = PtpStd.GET_OBJECT
    const val GET_THUMB: Int = PtpStd.GET_THUMB
    const val DELETE_OBJECT: Int = PtpStd.DELETE_OBJECT
    const val SEND_OBJECT_INFO: Int = PtpStd.SEND_OBJECT_INFO
    const val SEND_OBJECT: Int = PtpStd.SEND_OBJECT
    const val INITIATE_CAPTURE: Int = PtpStd.INITIATE_CAPTURE
    const val GET_DEVICE_PROP_DESC: Int = PtpStd.GET_DEVICE_PROP_DESC
    const val GET_DEVICE_PROP_VALUE: Int = PtpStd.GET_DEVICE_PROP_VALUE
    const val SET_DEVICE_PROP_VALUE: Int = PtpStd.SET_DEVICE_PROP_VALUE
    const val GET_PARTIAL_OBJECT: Int = PtpStd.GET_PARTIAL_OBJECT

    // ── Response codes ────────────────────────────────────────────────────
    object Response {
        const val UNDEFINED: Int = PtpStd.Response.UNDEFINED
        const val OK: Int = PtpStd.Response.OK
        const val GENERAL_ERROR: Int = PtpStd.Response.GENERAL_ERROR
        const val SESSION_NOT_OPEN: Int = PtpStd.Response.SESSION_NOT_OPEN
        const val INVALID_TRANSACTION_ID: Int = PtpStd.Response.INVALID_TRANSACTION_ID
        const val OPERATION_NOT_SUPPORTED: Int = PtpStd.Response.OPERATION_NOT_SUPPORTED
        const val PARAMETER_NOT_SUPPORTED: Int = PtpStd.Response.PARAMETER_NOT_SUPPORTED
        const val INCOMPLETE_TRANSFER: Int = PtpStd.Response.INCOMPLETE_TRANSFER
        const val INVALID_STORAGE_ID: Int = PtpStd.Response.INVALID_STORAGE_ID
        const val INVALID_OBJECT_HANDLE: Int = PtpStd.Response.INVALID_OBJECT_HANDLE
        const val NO_THUMBNAIL_PRESENT: Int = PtpStd.Response.NO_THUMBNAIL_PRESENT
        const val STORE_NOT_AVAILABLE: Int = PtpStd.Response.STORE_NOT_AVAILABLE
        const val DEVICE_BUSY: Int = PtpStd.Response.DEVICE_BUSY
        const val TRANSACTION_CANCELLED: Int = PtpStd.Response.TRANSACTION_CANCELLED
    }

    // ── Sony 0x92xx vendor opcodes (USB only) ──────────────────────────────
    object Sony {
        const val CAPABILITY: Int = PtpSony.CAPABILITY
        const val STORAGE_IDS: Int = PtpSony.STORAGE_IDS
        const val CONTENT_LIST: Int = PtpSony.CONTENT_LIST
        const val PARTIAL_OBJECT: Int = PtpSony.PARTIAL_OBJECT
        const val THUMB: Int = PtpSony.THUMB
        const val DELETE_OBJECT: Int = PtpSony.DELETE_OBJECT
    }

    // ── Canon 0x91xx vendor opcodes ────────────────────────────────────────
    object Canon {
        const val STORAGE_IDS: Int = PtpCanon.STORAGE_IDS
        const val SESSION: Int = PtpCanon.SESSION
        const val OBJECT_HANDLES: Int = PtpCanon.OBJECT_HANDLES
        const val OBJECT_HANDLES_ALT: Int = PtpCanon.OBJECT_HANDLES_ALT
        const val OBJECT_INFO_EX: Int = PtpCanon.OBJECT_INFO_EX
        const val PARTIAL_OBJECT: Int = PtpCanon.PARTIAL_OBJECT
        const val THUMB: Int = PtpCanon.THUMB
        const val SET_PROPERTY: Int = PtpCanon.SET_PROPERTY
    }

    // ── Nikon 0x90xx / 0x94xx vendor opcodes ────────────────────────────────
    object Nikon {
        const val SESSION: Int = PtpNikon.SESSION
        const val OBJECT_HANDLES: Int = PtpNikon.OBJECT_HANDLES
        const val LARGE_OBJECT_LENGTH: Int = PtpNikon.LARGE_OBJECT_LENGTH
        const val PARTIAL_OBJECT: Int = PtpNikon.PARTIAL_OBJECT
        const val THUMB: Int = PtpNikon.THUMB
    }

    // ── Fuji 0xD2xx / 0xDFxx vendor opcodes (USB only) ──────────────────────
    object Fuji {
        const val SESSION: Int = PtpFuji.SESSION
        const val DEVICE_INFO: Int = PtpFuji.DEVICE_INFO
        const val OBJECT_HANDLES: Int = PtpFuji.OBJECT_HANDLES
        const val OBJECT_HANDLES_FALLBACK: Int = PtpFuji.OBJECT_HANDLES_FALLBACK
        const val PARTIAL_OBJECT: Int = PtpFuji.PARTIAL_OBJECT
        const val STORAGE_ID: Long = PtpFuji.STORAGE_ID_FALLBACK
    }

    /** 64-bit object length sentinel — device writes this when the real length is too big for u32. */
    const val SIZE_32_SENTINEL: Long = PtpStd.SIZE_32_SENTINEL
}
