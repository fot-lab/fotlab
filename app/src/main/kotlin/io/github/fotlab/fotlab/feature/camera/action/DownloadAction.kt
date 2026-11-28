package io.github.fotlab.fotlab.feature.camera.action

import io.github.fotlab.fotlab.feature.camera.model.toPtpInt
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpSony
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpStd
import java.io.IOException
import java.io.OutputStream

/**
 * Download one object's bytes into an [OutputStream].
 *
 * PTP paths chunk by profile size using GetPartialObject — Sony 0x923D takes
 * a 5-param shape (handle + packedFormat + 64-bit offset + size) instead of
 * the standard 3-param. All variants look up opcodes via [Opcodes.partialObject].
 *
 * HTTP path pulls the best URL (rawUrl or jpegUrl) from [ObjectUrlCache]
 * populated by the list action. Sony HTTP returns the whole file at once —
 * no chunking, no offset params.
 */
object DownloadAction {

    private const val MAX_STANDARD_OFFSET: Long = 0xFFFFFFFFL

    suspend fun execute(
        ctx: CameraActionContext,
        handle: Long,
        totalBytes: Long,
        output: OutputStream,
        progress: (Long) -> Unit = {},
    ): Long = when (ctx.path) {
        TransportPath.HTTP_IP_SONY_NEX -> executeHttp(ctx, handle, output, progress)
        TransportPath.PTP_USB_STD_ISO,
        TransportPath.PTP_IP_STD_ISO,
        TransportPath.PTP_USB_SONY_0X92XX,
        TransportPath.PTP_USB_CANON_0X91XX,
        TransportPath.PTP_IP_CANON_0X91XX,
        TransportPath.PTP_USB_NIKON_0X90XX,
        TransportPath.PTP_IP_NIKON_0X90XX,
        TransportPath.PTP_USB_FUJI_0XD2XX -> executePtp(ctx, handle, totalBytes, output, progress)
    }

    private suspend fun executePtp(
        ctx: CameraActionContext,
        handle: Long,
        totalBytes: Long,
        output: OutputStream,
        progress: (Long) -> Unit,
    ): Long {
        val pair = Opcodes.partialObject(ctx)
        val skipStandard = ctx.path.isVendorSegment && pair.standard == null
        val packedFormat = PtpSony.STILL_IMAGE_PACKED_FORMAT

        // Sony 0x923D shape: (handle 24-bit low) + packedFormat + (offset 64-bit LE) + size
        // Only active when the effective segment is still SONY_0X92XX (ptpStdOnly=false);
        // when ptpStdOnly=true, effectiveSegment drops to ISO and we use standard shape.
        val useSonyShape = ctx.segment == Segment.SONY_0X92XX
        val chunk = ctx.profile.chunkSize(ctx.profile.preferredChunkBytes)

        var offset = 0L
        while (offset < totalBytes) {
            val size = minOf(chunk.toLong(), totalBytes - offset).toInt()
            val params = if (useSonyShape) {
                sonyPartialParams(handle, offset, size, packedFormat)
            } else {
                intArrayOf(handle.toPtpInt(), offset.toPtpInt(), size)
            }
            val result = PtpExecutor.execute(
                ctx = ctx, standard = pair.standard, vendor = pair.vendor,
                params = params, expectDataIn = true, skipStandard = skipStandard || useSonyShape,
            )
            val bytes = result.data ?: throw IOException("Missing data phase for object $handle")
            output.write(bytes); output.flush()
            offset += bytes.size; progress(offset)
            if (bytes.size < size) break
        }
        return offset
    }

    private suspend fun executeHttp(
        ctx: CameraActionContext,
        handle: Long,
        output: OutputStream,
        progress: (Long) -> Unit,
    ): Long {
        val urls = ctx.objectUrlCache[handle]
            ?: throw IllegalStateException(
                "No URL cache entry for handle $handle — list objects before downloading",
            )
        val url = urls.bestUrl
            ?: throw IOException("Sony HTTP object $handle has no downloadable URL")
        val bytes = SonyHttpExecutor.download(ctx, url)
        output.write(bytes); output.flush()
        progress(bytes.size.toLong())
        return bytes.size.toLong()
    }

    private fun sonyPartialParams(handle: Long, offset: Long, size: Int, packedFormat: Long): IntArray = intArrayOf(
        (handle and 0x00FFFFFFL).toPtpInt(), packedFormat.toPtpInt(),
        offset.toPtpInt(), (offset ushr 32).toPtpInt(), size,
    )
}
