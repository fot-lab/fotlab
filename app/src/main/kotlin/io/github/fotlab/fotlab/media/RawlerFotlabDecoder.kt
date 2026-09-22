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
 * [developToPng] is the same pattern routed through [RawlerFotlabBridge.developRawToPng]. The
 * Studio fun bar passes the user's chosen demosaic [algorithm] and exposure compensation
 * [exposureEv] (in stops; `wb` stays `null` so the Rust side resolves to `RawImage.wb_coeffs`, the
 * camera's as-shot white balance). `exposureEv` defaults to `0f` (no compensation) for callers that
 * do not override it.
 */
class RawlerFotlabDecoder : RawDecoder {
    override suspend fun decodeToPng(format: String, open: suspend () -> InputStream): ByteArray? {
        val bytes = runCatching { open().use { it.readBytes() } }.getOrNull() ?: return null
        return RawlerFotlabBridge.decodeRawToPng(bytes)
    }

    override suspend fun developToPng(
        format: String,
        algorithm: DemosaicAlgorithm,
        exposureEv: Float,
        downsample: Boolean,
        open: suspend () -> InputStream,
    ): ByteArray? {
        val bytes = runCatching { open().use { it.readBytes() } }.getOrNull() ?: return null
        // exposureEv carries the user's exposure compensation (2^ev linear gain in the Rust
        // calibrate step); wb = null keeps the camera's as-shot white balance; downsample carries
        // the Studio drawer's quarter-resolution preference (superpixel debayer on the native side).
        val params = DevelopParams(
            demosaicAlgorithm = algorithm,
            exposureEv = exposureEv,
            wb = null,
            downsample = downsample,
        )
        return RawlerFotlabBridge.developRawToPng(bytes, params)
    }
}
