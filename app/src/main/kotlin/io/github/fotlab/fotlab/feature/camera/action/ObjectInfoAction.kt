package io.github.fotlab.fotlab.feature.camera.action

import io.github.fotlab.fotlab.feature.camera.model.PtpObjectInfo
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpNikon
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpStd
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Per-object metadata — format, size, name, parent, thumbnail size.
 *
 * HTTP paths have no separate object info call; everything is bundled in
 * the list response. The [SonyHttpContentAdapter] already fills the
 * [ObjectUrlCache] with all the information we need during [ListObjectsAction].
 */
object ObjectInfoAction {

    suspend fun execute(ctx: CameraActionContext, handle: Long): PtpObjectInfo = when (ctx.path) {
        TransportPath.HTTP_IP_SONY_NEX -> throw UnsupportedOperationException(
            "Sony HTTP has no separate objectInfo — use list response",
        )
        TransportPath.PTP_USB_STD_ISO,
        TransportPath.PTP_IP_STD_ISO,
        TransportPath.PTP_USB_SONY_0X92XX,
        TransportPath.PTP_USB_CANON_0X91XX,
        TransportPath.PTP_IP_CANON_0X91XX,
        TransportPath.PTP_USB_NIKON_0X90XX,
        TransportPath.PTP_IP_NIKON_0X90XX,
        TransportPath.PTP_USB_FUJI_0XD2XX -> executePtp(ctx, handle)
    }

    private suspend fun executePtp(ctx: CameraActionContext, handle: Long): PtpObjectInfo {
        val pair = Opcodes.objectInfo(ctx)
        val skipStandard = ctx.path.isVendorSegment && pair.standard == null

        val result = PtpExecutor.executeNoRetry(
            ctx = ctx, standard = pair.standard, vendor = pair.vendor,
            params = intArrayOf(handle.toInt()), expectDataIn = true, skipStandard = skipStandard,
        )

        var info = decodeObjectInfo(result.data, handle)

        // 64-bit size repair — Nikon LARGE_OBJECT_LENGTH (0x9421) only
        if (info.sizeBytes == PtpStd.SIZE_32_SENTINEL && ctx.path == TransportPath.PTP_USB_NIKON_0X90XX) {
            val extended = PtpExecutor.executeNoRetry(
                ctx = ctx, standard = null, vendor = PtpNikon.LARGE_OBJECT_LENGTH,
                params = intArrayOf(handle.toInt()), expectDataIn = true,
            )
            val raw = extended.data
            if (raw != null && raw.size >= 8) {
                info = info.copy(sizeBytes = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).long)
            }
        }
        return info
    }

    private fun decodeObjectInfo(raw: ByteArray?, handle: Long): PtpObjectInfo {
        if (raw == null) return PtpObjectInfo(handle = handle)
        return try {
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val format = buffer.u16().toLong()
            val protection = buffer.u16()
            val size = buffer.u32()
            val thumbFormat = buffer.u16().toLong()
            val thumbBytes = buffer.u32()
            val pixelWidth = buffer.u32()
            val pixelHeight = buffer.u32()
            val parent = buffer.u32()
            val attributes = buffer.u16()
            val name = readPtpString(buffer)
            val captured = readPtpString(buffer)
            val modified = readPtpString(buffer)
            PtpObjectInfo(
                handle = handle, format = format, protectionStatus = protection,
                sizeBytes = size, thumbFormat = thumbFormat, thumbBytes = thumbBytes,
                pixelWidth = pixelWidth, pixelHeight = pixelHeight, parent = parent,
                name = name, capturedAt = parsePtpDate(captured), modifiedAt = parsePtpDate(modified),
            )
        } catch (_: RuntimeException) { PtpObjectInfo(handle = handle) }
    }

    private fun parsePtpDate(s: String): Long = runCatching {
        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
            .parse(s).let { java.time.Instant.from(it).toEpochMilli() }
    }.getOrDefault(0L)

    private fun readPtpString(buffer: ByteBuffer): String {
        val units = buffer.get().toInt() and 0xFF
        if (units <= 1 || buffer.remaining() < (units - 1) * 2) return ""
        val bytes = ByteArray((units - 1) * 2); buffer.get(bytes)
        return String(bytes, java.nio.charset.StandardCharsets.UTF_16LE).trimEnd('\u0000')
    }

    private fun ByteBuffer.u16(): Int = short.toInt() and 0xFFFF
    private fun ByteBuffer.u32(): Long = int.toLong() and 0xFFFFFFFFL
}
