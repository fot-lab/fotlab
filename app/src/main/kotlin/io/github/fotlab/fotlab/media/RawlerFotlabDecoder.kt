package io.github.fotlab.fotlab.media

import io.github.fotlab.fotlab_rawler.RawlerFotlabBridge
import java.io.InputStream

/**
 * RAW->PNG decoder backed by the rawler_fotlab native library (rawler/dnglab via UniFFI); this is the
 * implementation wired by `StudioEngine.prepare` (FOTLAB-STUDIO-000001).
 *
 * Call #2 of the raw path: the source is opened, the bytes are handed to the native bridge
 * (which already identified the format during [FormatSniffer]/[RawlerProbe], call #1), and the
 * returned PNG is rendered by Coil. Without the .so, [decodeToPng] returns null and the Studio
 * pipeline falls through to Unsupported.
 */
class RawlerFotlabDecoder : RawDecoder {
    override suspend fun decodeToPng(format: String, open: suspend () -> InputStream): ByteArray? {
        val bytes = runCatching { open().use { it.readBytes() } }.getOrNull() ?: return null
        return RawlerFotlabBridge.decodeRawToPng(bytes)
    }
}
