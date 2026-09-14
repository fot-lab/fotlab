package io.github.fotlab.fotlab.media

import java.io.InputStream

/**
 * The **second** of the two raw-path calls: actual pixel decode (R8 / FOTLAB-STUDIO-000001).
 *
 * The raw route is deliberately split into two native calls and is **not** solved in one step:
 *  1. **Sniff / identify** — [RawlerProbe.sniff] (run inside [FormatSniffer.sniff]) answers "is this a
 *     RAW rawler recognizes, and can it decode?" and emits a [Verdict] with `format` + `canDecode`.
 *  2. **Decode** — *this* call, made only after the route resolves to [Route.RawToRaster]. It takes the
 *     `format` produced by step 1 so the native side decodes the already-identified RAW instead of
 *     re-identifying it, and returns PNG-encoded bytes for the frontend to render.
 *
 * The concrete implementation is [RawlerFotlabDecoder]: the bytes are handed to the first-party
 * `rawler_fotlab` native library (rawler/dnglab over UniFFI), which decodes them and returns PNG.
 * [StubRawDecoder] stays as the no-op default for builds without the native artifact.
 */
interface RawDecoder {

    /**
     * Decode the RAW identified as [format] (from the sniff step) to PNG-encoded bytes, or `null` if it
     * cannot be decoded. [open] is a suspend provider so the bridge can stream bytes off the main thread
     * / off a `ContentResolver`. [format] must come from the [Verdict] produced by [RawlerProbe], never
     * be re-derived here.
     */
    suspend fun decodeToPng(format: String, open: suspend () -> InputStream): ByteArray?
}

/**
 * No-op [RawDecoder]: always reports "cannot decode", so the Studio pipeline falls through to
 * `Unsupported`. Only the default until the native bridge is wired, and the fallback for a build
 * without `librawler_fotlab.so`.
 */
object StubRawDecoder : RawDecoder {
    override suspend fun decodeToPng(format: String, open: suspend () -> InputStream): ByteArray? = null
}
