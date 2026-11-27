package io.github.fotlab.fotlab.feature.camera.operation

import io.github.fotlab.fotlab.feature.camera.model.PtpObjectInfo
import io.github.fotlab.fotlab.feature.camera.protocol.PtpContainer
import io.github.fotlab.fotlab.feature.camera.protocol.PtpOpcode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

/** Decodes the standard PTP object-info dataset, including the 64-bit size suffix. */
object ObjectInfoDecoder {
    fun decode(raw: ByteArray?, handle: Long): PtpObjectInfo {
        if (raw == null || raw.size < 50) return PtpObjectInfo(handle = handle)
        return try {
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val storageId = buffer.u32()
            val format = buffer.u16().toLong()
            val protection = buffer.u16()
            var size = buffer.u32()
            val thumbFormat = buffer.u16().toLong()
            val thumbBytes = buffer.u32()
            buffer.u32() // thumbnail pixel width
            buffer.u32() // thumbnail pixel height
            val pixelWidth = buffer.u32() // image pixel width
            val pixelHeight = buffer.u32() // image pixel height
            buffer.u32() // image bit depth
            val parent = buffer.u32() // parent object: association handle, 0 at root
            buffer.u16() // association type
            buffer.u32() // association descriptor
            buffer.u32() // sequence number
            val name = readString(buffer)
            val captured = parseDate(readString(buffer))
            val modified = parseDate(readString(buffer))
            readString(buffer) // keywords
            if (size == PtpOpcode.SIZE_32_SENTINEL && buffer.remaining() >= 8) {
                size = buffer.long
            }
            PtpObjectInfo(
                handle = handle,
                storageId = storageId,
                format = format,
                protectionStatus = protection,
                sizeBytes = size,
                thumbFormat = thumbFormat,
                thumbBytes = thumbBytes,
                pixelWidth = pixelWidth,
                pixelHeight = pixelHeight,
                parent = parent,
                name = name,
                capturedAt = captured,
                modifiedAt = modified,
            )
        } catch (_: RuntimeException) {
            PtpObjectInfo(handle = handle)
        }
    }

    /** Dataset sent with SendObjectInfo; the four trailing strings are PTP-counted. */
    fun encodeForUpload(
        storageId: Long,
        parent: Long,
        format: Long,
        size: Long,
        name: String,
    ): ByteArray {
        val out = ByteBuffer.allocate(128 + name.length * 2 + 80)
            .order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(storageId.toInt())
        out.putShort(format.toShort())
        out.putShort(0) // no protection
        out.putInt(if (size <= 0xFFFFFFFFL) size.toInt() else -1)
        out.putShort(0) // thumbnail format
        out.putInt(0) // thumbnail size
        repeat(4) { out.putInt(0) } // thumb width/height, image width/height
        out.putInt(0) // image bit depth
        out.putInt(parent.toInt()) // parent object
        out.putShort(0) // association type
        out.putInt(0) // association descriptor
        out.putInt(0) // sequence number
        writeString(out, name)
        writeString(out, "")
        writeString(out, "")
        writeString(out, "")
        out.flip()
        return ByteArray(out.remaining()).also { out.get(it) }
    }

    private fun ByteBuffer.u16(): Int = short.toInt() and 0xFFFF

    private fun ByteBuffer.u32(): Long = int.toLong() and 0xFFFFFFFFL

    private fun readString(buffer: ByteBuffer): String {
        val units = buffer.get().toInt() and 0xFF
        if (units <= 1 || buffer.remaining() < (units - 1) * 2) return ""
        val bytes = ByteArray((units - 1) * 2)
        buffer.get(bytes)
        return PtpContainer.decodeUtf16Le(bytes, units - 1)
    }

    private fun writeString(buffer: ByteBuffer, value: String) {
        val units = if (value.isEmpty()) 1 else value.length + 1
        buffer.put(units.toByte())
        value.forEach { ch ->
            buffer.put(ch.code.toByte())
            buffer.put((ch.code ushr 8).toByte())
        }
        buffer.putShort(0)
    }

    private fun parseDate(value: String): Long {
        if (value.length < 15) return 0L
        val pattern = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
        pattern.isLenient = true
        return pattern.parse(value, ParsePosition(0))?.time ?: 0L
    }
}
