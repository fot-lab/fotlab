package io.github.fotlab.fotlab.feature.camera.protocol.opcode

/**
 * ISO/PIMA 15740 standard PTP operation codes.
 *
 * These opcodes are identical across USB and PTP/IP transports — the
 * transport framing differs (bulk containers vs TCP packet types) but the
 * operation codes are the same. All conforming PTP devices answer these;
 * vendor extensions (Sony/Canon/Nikon/Fuji in sibling files) are optional.
 */
object PtpStd {
    // ── Operations ────────────────────────────────────────────────────────
    const val UNDEFINED: Int = 0x1000
    const val GET_DEVICE_INFO: Int = 0x1001
    const val OPEN_SESSION: Int = 0x1002
    const val CLOSE_SESSION: Int = 0x1003
    const val GET_STORAGE_IDS: Int = 0x1004
    const val GET_STORAGE_INFO: Int = 0x1005
    const val GET_NUM_OBJECTS: Int = 0x1006
    const val GET_OBJECT_HANDLES: Int = 0x1007
    const val GET_OBJECT_INFO: Int = 0x1008
    const val GET_OBJECT: Int = 0x1009
    const val GET_THUMB: Int = 0x100A
    const val DELETE_OBJECT: Int = 0x100B
    const val SEND_OBJECT_INFO: Int = 0x100C
    const val SEND_OBJECT: Int = 0x100D
    const val INITIATE_CAPTURE: Int = 0x100E
    const val GET_DEVICE_PROP_DESC: Int = 0x1014
    const val GET_DEVICE_PROP_VALUE: Int = 0x1015
    const val SET_DEVICE_PROP_VALUE: Int = 0x1016
    const val GET_PARTIAL_OBJECT: Int = 0x101B

    // ── Response codes ────────────────────────────────────────────────────
    object Response {
        const val UNDEFINED: Int = 0x2000
        const val OK: Int = 0x2001
        const val GENERAL_ERROR: Int = 0x2002
        const val SESSION_NOT_OPEN: Int = 0x2003
        const val INVALID_TRANSACTION_ID: Int = 0x2004
        const val OPERATION_NOT_SUPPORTED: Int = 0x2005
        const val PARAMETER_NOT_SUPPORTED: Int = 0x2006
        const val INCOMPLETE_TRANSFER: Int = 0x2007
        const val INVALID_STORAGE_ID: Int = 0x2008
        const val INVALID_OBJECT_HANDLE: Int = 0x2009
        const val NO_THUMBNAIL_PRESENT: Int = 0x2010
        const val STORE_NOT_AVAILABLE: Int = 0x2013
        const val DEVICE_BUSY: Int = 0x2019
        const val TRANSACTION_CANCELLED: Int = 0x201F
    }

    // ── Events ────────────────────────────────────────────────────────────
    object Event {
        const val CANCEL_TRANSACTION: Int = 0x4001
        const val OBJECT_ADDED: Int = 0x4002
        const val OBJECT_REMOVED: Int = 0x4003
        const val STORE_ADDED: Int = 0x4004
        const val STORE_REMOVED: Int = 0x4005
        const val DEVICE_PROP_CHANGED: Int = 0x4006
        const val OBJECT_INFO_CHANGED: Int = 0x4007
        const val DEVICE_INFO_CHANGED: Int = 0x4008
        const val CAPTURE_COMPLETE: Int = 0x400D
    }

    /** 64-bit object length sentinel: the device signals that the real length
     *  is too big for a u32 by writing this into the standard Size field. */
    const val SIZE_32_SENTINEL: Long = 0xFFFFFFFFL

    /** ParentObject argument sentinel — PIMA 15740 §10.4.7. */
    const val PARENT_ROOT: Long = 0xFFFFFFFFL
    const val PARENT_ALL: Long = 0x00000000L

    /** GetObjectHandles "aggregate all storages" sentinel. */
    const val ALL_STORAGES: Long = 0xFFFFFFFFL
}
