package io.github.fotlab.fotlab.feature.camera.action

import io.github.fotlab.fotlab.feature.camera.model.PtpObjectInfo
import io.github.fotlab.fotlab.feature.camera.model.toPtpInt
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
            params = intArrayOf(handle.toPtpInt()), expectDataIn = true, skipStandard = skipStandard,
        )

        var info = decodeObjectInfo(result.data, handle)

        // 64-bit size repair — Nikon LARGE_OBJECT_LENGTH (0x9421) only
        if (info.sizeBytes == PtpStd.SIZE_32_SENTINEL && ctx.path == TransportPath.PTP_USB_NIKON_0X90XX) {
            val extended = PtpExecutor.executeNoRetry(
                ctx = ctx, standard = null, vendor = PtpNikon.LARGE_OBJECT_LENGTH,
                params = intArrayOf(handle.toPtpInt()), expectDataIn = true,
            )
            val raw = extended.data
            if (raw != null && raw.size >= 8) {
                info = info.copy(sizeBytes = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).long)
            }
        }
        return info
    }

    private fun decodeObjectInfo(raw: ByteArray?, handle: Long): PtpObjectInfo {
        // PIMA 15740 §5.1.3 ObjectInfo dataset — fixed part is 42 bytes through ParentObject,
        // then optionally 10 bytes for association fields (type + descriptor + sequence),
        // then four PTP-counted UTF-16LE strings (name, captured, modified, keywords),
        // optionally followed by an 8-byte ExtendedObjectSize (UINT64) suffix (v1.11).
        if (raw == null || raw.size < 42) return PtpObjectInfo(handle = handle)
        return try {
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val storageId = buffer.u32()                  // 0: StorageID
            val format = buffer.u16().toLong()             // 4: ObjectFormatCode
            val protection = buffer.u16()                  // 6: ProtectionStatus
            var size = buffer.u32()                        // 8: ObjectSize
            val thumbFormat = buffer.u16().toLong()        // 12: ThumbFormat
            val thumbBytes = buffer.u32()                  // 14: ThumbSize
            buffer.u32()                                   // 18: ThumbPixelWidth
            buffer.u32()                                   // 22: ThumbPixelHeight
            val pixelWidth = buffer.u32()                  // 26: ImagePixelWidth
            val pixelHeight = buffer.u32()                 // 30: ImagePixelHeight
            buffer.u32()                                   // 34: ImageBitDepth
            val parent = buffer.u32()                      // 38: ParentObject
            if (buffer.remaining() >= 10) {
                buffer.u16()                               // 42: AssociationType
                buffer.u32()                               // 44: AssociationDescriptor
                buffer.u32()                               // 48: SequenceNumber
            }
            val name = readPtpString(buffer)               // 52+: ObjectFileName
            val captured = readPtpString(buffer)           // CaptureDate
            val modified = readPtpString(buffer)            // ModificationDate
            readPtpString(buffer)                           // Keywords
            if (size == PtpStd.SIZE_32_SENTINEL && buffer.remaining() >= 8) {
                size = buffer.long
            }
            PtpObjectInfo(
                handle = handle, storageId = storageId,
                format = format, protectionStatus = protection,
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
