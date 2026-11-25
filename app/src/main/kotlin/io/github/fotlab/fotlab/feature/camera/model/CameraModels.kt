package io.github.fotlab.fotlab.feature.camera.model

import java.io.IOException

/**
 * Device-independent values shared by the camera feature. Nothing here holds an
 * Android binder, socket or USB handle: transports and the session convert their
 * wire data into these types before anything else sees it.
 */

enum class CameraLink { USB, IP }

/** Which kinds of media the object list shows. */
enum class PtpMediaFilter {
    ALL,
    IMAGE,
    VIDEO,
    ;

    fun accepts(format: Long): Boolean = when (this) {
        ALL -> PtpFormats.isMedia(format)
        IMAGE -> PtpFormats.isImage(format)
        VIDEO -> PtpFormats.isVideo(format)
    }
}
data class PtpStorage(
    val id: Long,
    val type: Int = 0,
    val fileSystem: Int = 0,
    val access: Int = 0,
    val capacityBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val description: String = "",
)

/** Decoded object-info dataset. Empty string fields are normal on some devices. */
data class PtpObjectInfo(
    val handle: Long,
    val storageId: Long = 0L,
    val format: Long = 0L,
    val protectionStatus: Int = 0,
    val sizeBytes: Long = 0L,
    val thumbFormat: Long = 0L,
    val thumbBytes: Long = 0L,
    val pixelWidth: Long = 0L,
    val pixelHeight: Long = 0L,
    val parent: Long = 0L,
    val name: String = "",
    val capturedAt: Long = 0L,
    val modifiedAt: Long = 0L,
)

/** Grid-facing projection; object info is fetched lazily and can be re-read. */
data class PtpObject(
    val handle: Long,
    val name: String,
    val format: Long,
    val sizeBytes: Long,
    val storageId: Long = 0L,
    val parent: Long = 0L,
    val hasThumbnail: Boolean = false,
) {
    /** Folder entries are associations, not media objects. */
    val isFolder: Boolean get() = format == PtpFormats.ASSOCIATION
}

object PtpFormats {
    const val UNDEFINED: Long = 0x3000L
    const val ASSOCIATION: Long = 0x3001L
    const val AVI: Long = 0x300AL
    const val MPEG: Long = 0x300BL
    const val ASF: Long = 0x300CL
    const val QUICKTIME: Long = 0x300DL
    const val JPEG: Long = 0x3801L
    const val TIFF_EP: Long = 0x3802L
    const val BMP: Long = 0x3804L
    const val CIFF: Long = 0x3805L
    const val GIF: Long = 0x3807L
    const val JFIF: Long = 0x3808L
    const val PNG: Long = 0x380BL
    const val TIFF: Long = 0x380DL
    const val TIFF_IT: Long = 0x380EL
    const val JP2: Long = 0x380FL
    const val JPX: Long = 0x3810L

    private val IMAGE_FORMATS = longArrayOf(
        JPEG, TIFF_EP, BMP, CIFF, GIF, JFIF, PNG, TIFF, TIFF_IT, JP2, JPX,
        0x3803L, 0x3809L, 0x380AL, 0x3811L, 0x3812L, 0xB10BL,
    ).toSet()

    private val VIDEO_FORMATS = longArrayOf(
        AVI, MPEG, ASF, QUICKTIME,
        0xB980L, 0xB981L, 0xB982L, 0xB902L, 0xB104L, 0xB105L, 0xB201L,
    ).toSet()

    /** Folders are enumerated separately, so they are not media objects. */
    fun isMedia(format: Long): Boolean = format in IMAGE_FORMATS || format in VIDEO_FORMATS

    fun isImage(format: Long): Boolean = format in IMAGE_FORMATS

    fun isVideo(format: Long): Boolean = format in VIDEO_FORMATS

    /** Used as the media type when writing a downloaded object to MediaStore. */
    fun mimeType(format: Long): String = when {
        format == JPEG || format == JFIF -> "image/jpeg"
        format == TIFF || format == TIFF_EP || format == TIFF_IT -> "image/tiff"
        format == PNG -> "image/png"
        format == JP2 || format == JPX -> "image/jp2"
        format == BMP -> "image/bmp"
        format == GIF -> "image/gif"
        isImage(format) -> "image/*"
        format == AVI -> "video/x-msvideo"
        format == MPEG -> "video/mpeg"
        format == QUICKTIME -> "video/quicktime"
        isVideo(format) -> "video/*"
        else -> "application/octet-stream"
    }

    /** Best-effort extension for the public-downloads fallback below API 29. */
    fun extension(format: Long, fallbackName: String): String = when {
        fallbackName.contains('.') -> fallbackName.substringAfterLast('.')
        format == JPEG || format == JFIF -> "jpg"
        format == TIFF || format == TIFF_EP || format == TIFF_IT -> "tif"
        format == PNG -> "png"
        format == JP2 || format == JPX -> "jp2"
        format == BMP -> "bmp"
        format == GIF -> "gif"
        format == AVI -> "avi"
        format == MPEG -> "mpg"
        format == QUICKTIME -> "mov"
        isImage(format) -> "jpg"
        isVideo(format) -> "mp4"
        else -> "bin"
    }
}

enum class TransferKind { DOWNLOAD, UPLOAD }

enum class TransferState { RUNNING, COMPLETED, FAILED, CANCELLED }

data class TransferProgress(
    val kind: TransferKind,
    val objectName: String,
    val objectIndex: Int = 1,
    val objectCount: Int = 1,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val state: TransferState = TransferState.RUNNING,
    val failure: Throwable? = null,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (completedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

/** Asynchronous PTP event, decoded from the interrupt or event connection. */
data class CameraEvent(
    val code: Int,
    val transactionId: Long = 0L,
    val params: List<Long> = emptyList(),
)

object CameraEventCode {
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

/** Thrown when a PTP response code is anything other than OK. */
class PtpResponseException(
    val operation: Int,
    val responseCode: Int,
    val responseParams: List<Long> = emptyList(),
) : IOException("PTP operation 0x${operation.toString(16)} failed with response 0x${responseCode.toString(16)}")

/**
 * Raised when the link itself breaks: a closed socket, a vanished USB endpoint, a
 * truncated packet. It is deliberately distinct from [PtpResponseException] —
 * a response exception means the device answered, so the link is alive and only
 * the request failed, while this one means the session can no longer be used.
 */
class CameraLinkException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Parent handle of the storage root in the object hierarchy. */
const val PTP_PARENT_ROOT: Long = 0L

/** Parent handle that asks for every object, whatever folder it sits in. */
const val PTP_PARENT_ALL: Long = 0xFFFFFFFFL
