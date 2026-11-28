package io.github.fotlab.fotlab.feature.camera.profile

import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpCanon
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpFuji
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpNikon
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpSony
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpStd

enum class CameraFamily { CANON, NIKON, SONY, FUJIFILM, GENERIC }

/** Which init-command GUID variant a device answers to. */
enum class GuidVariant { PRIMARY, ALTERNATE, ZERO, SECONDARY }

/**
 * All device-specific knobs used by the browsing/transfer path live here.
 * A model family is expressed as `copy(...)` of a base profile, so only fields
 * that really differ are restated.
 *
 * Opcode selection is duplicated here and in the action layer (see
 * [io.github.fotlab.fotlab.feature.camera.action.CameraActionContext]):
 * this profile carries the raw int values that the old PtpFileOperations
 * still reads, while the new action layer picks opcodes directly from
 * family+protocol. Once PtpFileOperations is fully removed these opcode
 * fields can be dropped and only chunk/GUID tuning stays.
 */
data class CameraProfile(
    val id: String,
    val family: CameraFamily,
    val sessionId: Int,
    val storageIdsOpcode: Int = PtpStd.GET_STORAGE_IDS,
    val objectHandlesOpcode: Int = PtpStd.GET_OBJECT_HANDLES,
    val objectInfoOpcode: Int = PtpStd.GET_OBJECT_INFO,
    val partialObjectOpcode: Int = PtpStd.GET_PARTIAL_OBJECT,
    val thumbOpcode: Int = PtpStd.GET_THUMB,
    val deleteOpcode: Int = PtpStd.DELETE_OBJECT,
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
                storageIdsOpcode = PtpCanon.STORAGE_IDS,
                objectHandlesOpcode = PtpCanon.OBJECT_HANDLES,
                objectInfoOpcode = PtpCanon.OBJECT_INFO_EX,
                partialObjectOpcode = PtpCanon.PARTIAL_OBJECT,
                thumbOpcode = PtpCanon.THUMB,
                guidVariant = guid,
            )

        fun nikon(sessionId: Int, guid: GuidVariant = GuidVariant.PRIMARY): CameraProfile =
            CameraProfile(
                id = "nikon-d",
                family = CameraFamily.NIKON,
                sessionId = sessionId,
                objectHandlesOpcode = PtpNikon.OBJECT_HANDLES,
                partialObjectOpcode = PtpNikon.PARTIAL_OBJECT,
                thumbOpcode = PtpNikon.THUMB,
                largeLengthOpcode = PtpNikon.LARGE_OBJECT_LENGTH,
                maxChunkBytes = PtpNikon.MAX_CHUNK_BYTES,
                guidVariant = guid,
            )

        fun sony(sessionId: Int, guid: GuidVariant = GuidVariant.PRIMARY): CameraProfile =
            CameraProfile(
                id = "sony",
                family = CameraFamily.SONY,
                sessionId = sessionId,
                storageIdsOpcode = PtpSony.STORAGE_IDS,
                objectHandlesOpcode = PtpSony.CONTENT_LIST,
                partialObjectOpcode = PtpSony.PARTIAL_OBJECT,
                thumbOpcode = PtpSony.THUMB,
                deleteOpcode = PtpSony.DELETE_OBJECT,
                guidVariant = guid,
            )

        fun fujiUsb(): CameraProfile = CameraProfile(
            id = "fuji-usb",
            family = CameraFamily.FUJIFILM,
            sessionId = 1,
            objectHandlesOpcode = PtpFuji.OBJECT_HANDLES,
            storageIdFallback = PtpFuji.STORAGE_ID_FALLBACK,
            minChunkBytes = PtpFuji.MIN_CHUNK_BYTES,
            maxChunkBytes = PtpFuji.MAX_CHUNK_BYTES,
            preferredChunkBytes = PtpFuji.PREFERRED_CHUNK_BYTES,
        )
    }
}
