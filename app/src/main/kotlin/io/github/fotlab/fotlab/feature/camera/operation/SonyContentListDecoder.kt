package io.github.fotlab.fotlab.feature.camera.operation

import io.github.fotlab.fotlab.feature.camera.model.PtpObjectInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decoder for Sony's paged content-list dataset (private command `0x923C`).
 *
 * The dataset is not a plain handle array: it is a 148-byte header followed by
 * fixed-shape records, each of which groups child entries that already carry
 * everything a listing needs — handle, format, size, file name, capture date
 * and protection. Devices in this family do not answer the standard
 * per-object info command for these handles, so the listing dataset is the
 * single source of object information: it is decoded once per page and cached
 * by [PtpFileOperations].
 *
 * Layout notes (per record, after the 148-byte header):
 * - 72-byte prologue: cursor/timestamp u64 at +28, index base u32 at +4, and
 *   at +72 a u32 whose value skips one block before the child array.
 * - The child array is length-prefixed with a u32 count.
 * - Each child: u16 format, u32 name byte length, name bytes (NUL-padded),
 *   u16 protection, u64 size, then a chain of optional fixed-size blocks
 *   guarded by u32 presence flags (the first carries image dimensions).
 *
 * Every read is bounds-guarded and yields 0 on underflow, so a truncated page
 * degrades instead of throwing mid-enumeration.
 */
object SonyContentListDecoder {

    /** Fixed header size in bytes, before the first record. */
    private const val HEADER_BYTES = 148

    /** Minimum bytes a record must occupy for its child block to be trusted. */
    private const val RECORD_MIN_BYTES = 80

    /** Page size the listing command is issued with. */
    const val PAGE_SIZE = 100

    /** One decoded page. */
    data class Page(
        /** Records the device answered with (0 when the page was empty or truncated). */
        val recordCount: Int,
        /** Highest cursor value seen in this page; the next page continues from here. */
        val maxCursor: Long,
        /** Child entries decoded from the page. */
        val entries: List<Entry>,
    )

    /** One child entry. */
    data class Entry(
        val handle: Long,
        /** The plain 16-bit object format code (JPEG 0x3801, RAW 0xB10B, ...). */
        val format: Long,
        /** The class byte rides in the top byte for the transfer commands. */
        val packedFormat: Long,
        val sizeBytes: Long,
        val name: String,
        val capturedAt: Long,
        val protection: Int,
        val hasImageBlock: Boolean,
    ) {
        fun toInfo() = PtpObjectInfo(
            handle = handle,
            format = format,
            sizeBytes = sizeBytes,
            thumbBytes = if (hasImageBlock) 1L else 0L,
            protectionStatus = protection,
            name = name,
            capturedAt = capturedAt,
        )
    }

    /** Decodes one page into child entries. */
    fun decodePage(raw: ByteArray?): Page {
        if (raw == null || raw.size <= HEADER_BYTES) return Page(0, 0L, emptyList())
        return try {
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            // Page-level fields: the format class byte every entry shares, and
            // the number of records on this page.
            val formatBase = u32(buffer, 140).toInt()
            val recordCount = u32(buffer, 144).toInt()
            val entries = ArrayList<Entry>(PAGE_SIZE)
            var maxCursor = 0L
            var offset = HEADER_BYTES
            var record = 0
            while (record < recordCount && offset + RECORD_MIN_BYTES <= raw.size) {
                // The record's timestamp doubles as the paging cursor.
                val recordTime = u64(buffer, offset + 28)
                maxCursor = maxOf(maxCursor, recordTime)
                val indexBase = u32(buffer, offset + 4).toInt()
                // 72-byte prologue, then one length-skipped block, then the children.
                val skip = u32(buffer, offset + 72).toInt()
                var childOffset = offset + 72 + 4 + skip
                if (childOffset + 4 > raw.size) break
                val childCount = u32(buffer, childOffset).toInt()
                childOffset += 4
                var child = 0
                while (child < childCount && childOffset + 8 <= raw.size) {
                    val located = decodeLocated(buffer, childOffset, indexBase, formatBase, recordTime, raw.size)
                        ?: break
                    entries += located.entry
                    childOffset = located.nextOffset
                    child++
                }
                offset = childOffset
                record++
            }
            Page(recordCount = recordCount, maxCursor = maxCursor, entries = entries)
        } catch (_: RuntimeException) {
            Page(0, 0L, emptyList())
        }
    }

    /** A child entry plus where the next one begins. */
    private data class Located(
        val entry: Entry,
        val nextOffset: Int,
    )

    /** Reads one child entry starting at [start]. */
    private fun decodeLocated(
        buffer: ByteBuffer,
        start: Int,
        indexBase: Int,
        formatBase: Int,
        capturedAt: Long,
        limit: Int,
    ): Located? {
        val formatLow = u32(buffer, start, 2).toInt()
        val handle = (indexBase or (formatBase shl 28) or (formatLow shl 24)) and 0xFFFFFFFF.toInt()
        val packedFormat = ((formatBase shl 24) or formatLow).toLong() and 0xFFFFFFFFL
        val nameLength = u32(buffer, start + 4).toInt()
        val nameStart = start + 8
        if (nameLength < 0 || nameStart + nameLength > limit) return null
        val name = readName(buffer, nameStart, nameLength)
        val body = nameStart + nameLength
        if (body + 48 > limit) return null
        val protection = u32(buffer, body, 2).toInt()
        val size = u64(buffer, body + 4)
        // Optional-block chain after the fixed body, each guarded by a u32 flag.
        var hasImageBlock = false
        var cursor = body + 44
        if (u32(buffer, cursor) != 0L) {
            hasImageBlock = true
            cursor += 4 + 8 // dimensions pair
        } else {
            cursor += 4
        }
        if (u32(buffer, cursor) != 0L) {
            hasImageBlock = true
            cursor += 80 // block whose dimensions sit at +24/+28
        } else {
            cursor += 4
        }
        if (u32(buffer, cursor) != 0L) {
            cursor += 4 + 16
        } else {
            cursor += 4
        }
        val entry = Entry(
            handle = handle.toLong() and 0xFFFFFFFFL,
            format = formatLow.toLong(),
            packedFormat = packedFormat,
            sizeBytes = size,
            name = name,
            capturedAt = capturedAt,
            protection = protection,
            hasImageBlock = hasImageBlock,
        )
        return Located(entry, cursor)
    }

    /** Bytes up to the first NUL, decoded as characters (ASCII-safe subset). */
    private fun readName(buffer: ByteBuffer, start: Int, length: Int): String {
        val chars = StringBuilder(length)
        for (index in 0 until length) {
            val value = buffer.get(start + index).toInt() and 0xFF
            if (value == 0) break
            chars.append(value.toChar())
        }
        val name = chars.toString()
        // Entries may arrive as full paths; a listing shows the bare file name.
        val slash = name.lastIndexOf('/')
        return if (slash >= 0) name.substring(slash + 1) else name
    }

    private fun u32(buffer: ByteBuffer, offset: Int, width: Int = 4): Long {
        if (offset < 0 || offset + width > buffer.limit()) return 0L
        var value = 0L
        for (index in 0 until width) {
            value = value or ((buffer.get(offset + index).toInt() and 0xFF).toLong() shl (8 * index))
        }
        return value
    }

    private fun u64(buffer: ByteBuffer, offset: Int): Long {
        if (offset < 0 || offset + 8 > buffer.limit()) return 0L
        var value = 0L
        for (index in 0 until 8) {
            value = value or ((buffer.get(offset + index).toInt() and 0xFF).toLong() shl (8 * index))
        }
        return value
    }
}
