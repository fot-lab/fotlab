package io.github.fotlab.fotlab.media

import io.github.fotlab.fotlab_rawler.DemosaicAlgorithm
import io.github.fotlab.fotlab_rawler.DevelopParams
import io.github.fotlab.fotlab_rawler.RawlerFotlabBridge
import java.io.InputStream

/**
 * RAW->PNG decoder backed by the rawler_fotlab native library (rawler/dnglab via UniFFI); this is the
 * implementation wired by `StudioEngine.prepare` (FOTLAB-STUDIO-000001).
 *
 * Call #2 of the raw path: the source is opened, the bytes are handed to the native bridge
 * ([RawlerFotlabBridge]) — which already identified the format during [FormatSniffer]/[RawlerProbe],
 * call #1 — and the returned PNG is rendered by Coil. Without the .so, [decodeToPng] returns null and
 * the Studio pipeline falls through to Unsupported.
 *
 * [developToPng] is the same pattern routed through [RawlerFotlabBridge.developRawToPng]. Until the
 * Studio bottom bar exposes Exposure / White Balance pickers, every develop call uses the RAW's
 * **as-shot** values: `exposureEv = 0f` (no compensation — the sensor data as captured) and
 * `wb = null` (which the Rust side resolves to `RawImage.wb_coeffs`, the camera's as-shot white
 * balance). The demosaic algorithm is the only user-selectable parameter for now.
 */
class RawlerFotlabDecoder : RawDecoder {
    override suspend fun decodeToPng(format: String, open: suspend () -> InputStream): ByteArray? {
        val bytes = runCatching { open().use { it.readBytes() } }.getOrNull() ?: return null
        return RawlerFotlabBridge.decodeRawToPng(bytes)
    }

    override suspend fun developToPng(
        format: String,
        algorithm: DemosaicAlgorithm,
        open: suspend () -> InputStream,
    ): ByteArray? {
        val bytes = runCatching { open().use { it.readBytes() } }.getOrNull() ?: return null
        // As-shot exposure (0 EV = no compensation) + as-shot WB (null -> RawImage.wb_coeffs).
        val params = DevelopParams(demosaicAlgorithm = algorithm, exposureEv = 0.0f, wb = null)
        return RawlerFotlabBridge.developRawToPng(bytes, params)
    }
}
