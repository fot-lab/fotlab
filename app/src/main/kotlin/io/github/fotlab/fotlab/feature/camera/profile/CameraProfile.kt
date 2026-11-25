package io.github.fotlab.fotlab.feature.camera.profile

import io.github.fotlab.fotlab.feature.camera.protocol.PtpOpcode

enum class CameraFamily { CANON, NIKON, SONY, FUJIFILM, GENERIC }

/** Which init-command GUID variant a device answers to. */
enum class GuidVariant { PRIMARY, ALTERNATE, ZERO, SECONDARY }

/**
 * All device-specific knobs used by the browsing/transfer path live here.
 * A model family is expressed as `copy(...)` of a base profile, so only fields
 * that really differ are restated.
 */
data class CameraProfile(
    val id: String,
    val family: CameraFamily,
    val sessionId: Int,
    val storageIdsOpcode: Int = PtpOpcode.GET_STORAGE_IDS,
    val objectHandlesOpcode: Int = PtpOpcode.GET_OBJECT_HANDLES,
    val objectInfoOpcode: Int = PtpOpcode.GET_OBJECT_INFO,
    val partialObjectOpcode: Int = PtpOpcode.GET_PARTIAL_OBJECT,
    val thumbOpcode: Int = PtpOpcode.GET_THUMB,
    val deleteOpcode: Int = PtpOpcode.DELETE_OBJECT,
    val largeLengthOpcode: Int? = null,
    val storageIdFallback: Long? = null,
    val minChunkBytes: Int = DEFAULT_MIN_CHUNK,
    val maxChunkBytes: Int = DEFAULT_MAX_CHUNK,
    val preferredChunkBytes: Int = DEFAULT_MIN_CHUNK,
    val preListDelayUsbMs: Long = 0L,
    val objectEventDelayMs: Long = 0L,
    val skipPropertyDescription: Boolean = false,
    val guidVariant: GuidVariant = GuidVariant.PRIMARY,
    val initPort: Int = DEFAULT_INIT_PORT,
    val extendedInit: Boolean = false,
) {
    /** Clamp a caller's chunk request into the device's tested range. */
    fun chunkSize(requested: Int): Int = requested.coerceIn(minChunkBytes, maxChunkBytes)

    companion object {
        const val DEFAULT_MIN_CHUNK: Int = 512 * 1024
        const val DEFAULT_MAX_CHUNK: Int = 3 * 1024 * 1024
        const val DEFAULT_INIT_PORT: Int = 15740

        fun generic(sessionId: Int): CameraProfile = CameraProfile(
            id = "generic",
            family = CameraFamily.GENERIC,
            sessionId = sessionId,
        )

        fun canon(sessionId: Int, guid: GuidVariant = GuidVariant.PRIMARY): CameraProfile =
            CameraProfile(
                id = "canon",
                family = CameraFamily.CANON,
                sessionId = sessionId,
                storageIdsOpcode = PtpOpcode.Canon.STORAGE_IDS,
                objectHandlesOpcode = PtpOpcode.Canon.OBJECT_HANDLES,
                objectInfoOpcode = PtpOpcode.Canon.OBJECT_INFO_EX,
                partialObjectOpcode = PtpOpcode.Canon.PARTIAL_OBJECT,
                thumbOpcode = PtpOpcode.Canon.THUMB,
                guidVariant = guid,
            )

        fun nikon(sessionId: Int, guid: GuidVariant = GuidVariant.PRIMARY): CameraProfile =
            CameraProfile(
                id = "nikon-d",
                family = CameraFamily.NIKON,
                sessionId = sessionId,
                objectHandlesOpcode = PtpOpcode.Nikon.OBJECT_HANDLES,
                partialObjectOpcode = PtpOpcode.Nikon.PARTIAL_OBJECT,
                thumbOpcode = PtpOpcode.Nikon.THUMB,
                largeLengthOpcode = PtpOpcode.Nikon.LARGE_OBJECT_LENGTH,
                maxChunkBytes = 5 * 1024 * 1024,
                guidVariant = guid,
            )

        fun sony(sessionId: Int, guid: GuidVariant = GuidVariant.PRIMARY): CameraProfile =
            CameraProfile(
                id = "sony",
                family = CameraFamily.SONY,
                sessionId = sessionId,
                storageIdsOpcode = PtpOpcode.Sony.STORAGE_IDS,
                objectHandlesOpcode = PtpOpcode.Sony.CONTENT_LIST,
                partialObjectOpcode = PtpOpcode.Sony.PARTIAL_OBJECT,
                thumbOpcode = PtpOpcode.Sony.THUMB,
                deleteOpcode = PtpOpcode.Sony.DELETE_OBJECT,
                guidVariant = guid,
            )

        fun fujiUsb(): CameraProfile = CameraProfile(
            id = "fuji-usb",
            family = CameraFamily.FUJIFILM,
            sessionId = 1,
            objectHandlesOpcode = PtpOpcode.Fuji.OBJECT_HANDLES,
            storageIdFallback = PtpOpcode.Fuji.STORAGE_ID,
            minChunkBytes = 1024 * 1024,
            maxChunkBytes = 3 * 1024 * 1024,
            preferredChunkBytes = 1024 * 1024,
        )
    }
}
