package io.github.fotlab.fotlab.feature.camera.action

import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpCanon
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpNikon
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpSony
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpStd
import io.github.fotlab.fotlab.feature.camera.model.PtpResponseException
import java.io.IOException
import java.io.InputStream

/**
 * Create a new object on the device (download-to-camera).
 *
 * Standard ISO 15740 uses SendObject (0x1002) with 3 params:
 *   storageId, parent (folder handle), objectFormat
 * then a data-out phase carrying the file bytes.
 *
 * Vendor quirks:
 *   - Canon 0x91xx needs a 5-param shape (handle + storage + parent +
 *     nameCString + size). The caller must call CreateObject first to
 *     get the handle, then call SendObjectData with that handle.
 *   - Sony 0x92xx has no upload API — the path forbids write surfaces.
 */
object UploadAction {

    suspend fun execute(
        ctx: CameraActionContext,
        storageId: Long,
        parent: Long,
        name: String,
        sizeBytes: Long,
        source: InputStream,
        progress: (Long) -> Unit = {},
    ): Long = when (ctx.path) {
        TransportPath.HTTP_IP_SONY_NEX -> throw UnsupportedOperationException(
            "Sony HTTP API does not support upload",
        )
        TransportPath.PTP_USB_SONY_0X92XX,
        TransportPath.PTP_IP_STD_ISO,
        TransportPath.PTP_USB_STD_ISO,
        TransportPath.PTP_USB_FUJI_0XD2XX,
        TransportPath.PTP_USB_CANON_0X91XX,
        TransportPath.PTP_IP_CANON_0X91XX,
        TransportPath.PTP_USB_NIKON_0X90XX,
        TransportPath.PTP_IP_NIKON_0X90XX -> {
            // Canon's CREATE_OBJECT_EX / SEND_OBJECT_DATA shape is only valid when
            // the vendor segment is actually active (ptpStdOnly=false). When the
            // effectiveSegment drops back to ISO we route through executeStandard.
            if (ctx.segment == Segment.CANON_0X91XX) {
                executeCanon(ctx, storageId, parent, name, sizeBytes, source, progress)
            } else {
                executeStandard(ctx, storageId, parent, name, sizeBytes, source, progress)
            }
        }
    }

    private suspend fun executeStandard(
        ctx: CameraActionContext,
        storageId: Long,
        parent: Long,
        name: String,
        sizeBytes: Long,
        source: InputStream,
        progress: (Long) -> Unit,
    ): Long {
        val session = ctx.ptpSession ?: throw IOException("No PTP session")
        val bytes = source.readBytes()
        val chunkSize = bytes.size.coerceAtMost(ctx.profile.chunkSize(ctx.profile.preferredChunkBytes)).toInt()
        val result = session.execute(
            code = PtpStd.SEND_OBJECT,
            params = intArrayOf(
                storageId.toInt(), parent.toInt(),
                io.github.fotlab.fotlab.feature.camera.model.PtpFormats.JPEG.toInt(),
            ),
            dataOut = bytes,
            expectDataIn = false,
        )
        progress(bytes.size.toLong())
        return result.phase2ObjectHandle ?: -1L
    }

    private suspend fun executeCanon(
        ctx: CameraActionContext,
        storageId: Long,
        parent: Long,
        name: String,
        sizeBytes: Long,
        source: InputStream,
        progress: (Long) -> Unit,
    ): Long {
        val session = ctx.ptpSession ?: throw IOException("No PTP session")
        val bytes = source.readBytes()
        val nameBytes = (name + '\u0000').toByteArray(java.nio.charset.StandardCharsets.UTF_16LE)
        val params = intArrayOf(
            0, // handle unknown
            storageId.toInt(), parent.toInt(),
            nameBytes.size, sizeBytes.toInt(),
        )
        try {
            session.execute(
                code = PtpCanon.CREATE_OBJECT_EX,
                params = params + nameBytes.toIntArray(),
                dataOut = null, expectDataIn = true,
            )
        } catch (_: PtpResponseException) { /* no-op — CreateObjectEx may not exist */ }
        val handle = session.execute(
            code = PtpStd.SEND_OBJECT,
            params = intArrayOf(storageId.toInt(), parent.toInt(), io.github.fotlab.fotlab.feature.camera.model.PtpFormats.JPEG.toInt()),
            dataOut = bytes, expectDataIn = false,
        ).phase2ObjectHandle ?: -1L
        progress(bytes.size.toLong())
        return handle
    }
}
