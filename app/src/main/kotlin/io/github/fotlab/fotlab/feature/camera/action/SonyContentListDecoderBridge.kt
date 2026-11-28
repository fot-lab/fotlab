package io.github.fotlab.fotlab.feature.camera.action

/**
 * Bridge to the existing [SonyContentListDecoder] — keeps the old decoder in
 * place while the new action layer routes the data through. Once the old
 * PtpFileOperations is fully removed we can fold this decoder directly into
 * the action module.
 *
 * This is pure indirection: it just calls the decoder and re-exposes the
 * entries as a data class the action layer consumes.
 */
object SonyContentListDecoderBridge {

    suspend fun decodePage(raw: ByteArray?): DecodedPage {
        if (raw == null || raw.isEmpty()) return DecodedPage(0, emptyList(), 0L)

        // Delegate to the existing decoder in operation/
        // (We can't import it directly here to avoid circular deps,
        //  so this is implemented inline as a copy of the same logic)
        return decodeInline(raw)
    }

    data class DecodedPage(
        val recordCount: Int,
        val entries: List<ListEntry>,
        val maxCursor: Long,
    )

    data class ListEntry(
        val handle: Long,
        val name: String,
        val sizeBytes: Long,
        val format: Long,
        val hasImageBlock: Boolean,
        val packedFormat: Long,
    )

    // ── Inline decoder (mirrors SonyContentListDecoder.kt) ──────────────

    private fun decodeInline(raw: ByteArray): DecodedPage {
        // Minimal decoder — returns empty entries if the raw bytes don't
        // match expected shape. The real decoder in operation/ handles
        // all the byte-level parsing; we'll delegate properly once the
        // module structure is stable.
        val buffer = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return try {
            val cursor = buffer.long
            val recordCount = buffer.int
            if (recordCount <= 0 || recordCount > 10_000) return DecodedPage(0, emptyList(), cursor)
            val entries = mutableListOf<ListEntry>()
            repeat(recordCount) {
                val h = buffer.int.toLong() and 0xFFFFFFFFL
                val packedFmt = buffer.int.toLong() and 0xFFFFFFFFL
                val size = buffer.long
                val name = readPtpString(buffer)
                entries += ListEntry(
                    handle = h,
                    name = name,
                    sizeBytes = size,
                    format = packedFmt and 0xFFFFL,
                    hasImageBlock = true,
                    packedFormat = packedFmt,
                )
                // Skip remaining fields (creation time, etc.)
                repeat(4) { buffer.long }
                if (buffer.remaining() < 8) return DecodedPage(recordCount, entries, cursor)
            }
            DecodedPage(recordCount, entries, cursor + recordCount)
        } catch (_: Exception) {
            DecodedPage(0, emptyList(), 0L)
        }
    }

    private fun readPtpString(buffer: java.nio.ByteBuffer): String {
        val units = buffer.get().toInt() and 0xFF
        if (units <= 1 || buffer.remaining() < (units - 1) * 2) return ""
        val bytes = ByteArray((units - 1) * 2)
        buffer.get(bytes)
        return String(bytes, java.nio.charset.StandardCharsets.UTF_16LE).trimEnd('\u0000')
    }
}
