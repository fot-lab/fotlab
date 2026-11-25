package io.github.fotlab.fotlab.feature.camera.protocol

/** PTP operation, response and vendor extension codes used by this feature. */
object PtpOpcode {
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

    /** Sony uses its own 0x92xx command family for browsing and transfer. */
    object Sony {
        const val CAPABILITY: Int = 0x9202
        const val STORAGE_IDS: Int = 0x9209
        const val CONTENT_LIST: Int = 0x923C
        const val PARTIAL_OBJECT: Int = 0x923D
        const val THUMB: Int = 0x923E
        const val DELETE_OBJECT: Int = 0x9250
    }

    /** Canon's 0x91xx extensions replace most of the standard browsing family. */
    object Canon {
        const val STORAGE_IDS: Int = 0x9101
        const val SESSION: Int = 0x910A
        const val OBJECT_HANDLES: Int = 0x9116
        const val OBJECT_HANDLES_ALT: Int = 0x917A
        const val OBJECT_INFO_EX: Int = 0x9173
        const val PARTIAL_OBJECT: Int = 0x9174
        const val THUMB: Int = 0x910B
        const val SET_PROPERTY: Int = 0x9110
    }

    /** Nikon keeps standard object info but uses vendor commands for listing and range reads. */
    object Nikon {
        const val SESSION: Int = 0x90C4
        const val OBJECT_HANDLES: Int = 0x90C7
        const val LARGE_OBJECT_LENGTH: Int = 0x9421
        const val PARTIAL_OBJECT: Int = 0x9431
        const val THUMB: Int = 0x9203
    }

    /** Fujifilm USB uses a separate 0xD2xx/0xDFxx command family. */
    object Fuji {
        const val SESSION: Int = 0xDF01
        const val DEVICE_INFO: Int = 0xDF00
        const val OBJECT_HANDLES: Int = 0xD212
        const val OBJECT_HANDLES_FALLBACK: Int = 0xD401
        const val PARTIAL_OBJECT: Int = 0xD244
        const val STORAGE_ID: Long = 0x10000001L
    }

    /** Some devices mark a 64-bit object length with this sentinel in the standard field. */
    const val SIZE_32_SENTINEL: Long = 0xFFFFFFFFL
}
