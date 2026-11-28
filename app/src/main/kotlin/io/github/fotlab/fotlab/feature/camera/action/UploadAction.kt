package io.github.fotlab.fotlab.feature.camera.action

import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpStd
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
        TransportPath.HTTP_IP_SONY_NEX,
        TransportPath.PTP_USB_SONY_0X92XX -> throw UnsupportedOperationException(
            "Sony paths do not support upload",
        )
        TransportPath.PTP_IP_STD_ISO,
        TransportPath.PTP_USB_STD_ISO,
        TransportPath.PTP_USB_FUJI_0XD2XX,
        TransportPath.PTP_USB_CANON_0X91XX,
        TransportPath.PTP_IP_CANON_0X91XX,
        TransportPath.PTP_USB_NIKON_0X90XX,
        TransportPath.PTP_IP_NIKON_0X90XX -> executeStandard(ctx, storageId, parent, name, sizeBytes, source, progress)
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
}
