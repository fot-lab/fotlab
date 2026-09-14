package io.github.fotlab.fotlab.media

import android.graphics.BitmapFactory
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream

/**
 * First-party format-sniffing wrapper (R8, FOTLAB-STUDIO-000001).
 *
 * Every image byte stream entering the app MUST pass through [sniff] before any
 * routing decision. It **classifies only** — it never decodes pixels or renders.
 * The route decision is a separate pure function driven by the emitted
 * [SniffResult] dictionary (rules TBD — Open Question Q6).
 *
 * Flow: parallel Coil-side + rawler-side sniffers, bounded by a timeout (default
 * [DEFAULT_SNIFF_TIMEOUT_MS], a user preference — see [MediaPreference]); timeout =>
 * [SniffResult.Timeout] (hard error, never a silent fallback).
 */
object FormatSniffer {

    /**
     * Bounded time for ALL sniffers to settle. Defaults to [DEFAULT_SNIFF_TIMEOUT_MS] (5 s), a user
     * preference ([MediaPreference]) the settings UI will expose later (R8 / Q6). Callers may pass a live
     * value from [MediaPreference.sniffTimeoutMs]; until that UI is wired, the default is used.
     */
    suspend fun sniff(
        header: ByteArray,
        timeoutMillis: Long = DEFAULT_SNIFF_TIMEOUT_MS,
    ): SniffResult = coroutineScope {
        try {
            withTimeout(timeoutMillis) {
                val coil = async { CoilSideSniffer.sniff(header) }
                val rawler = async { RawlerProbe.sniff(header) }
                val results = awaitAll(coil, rawler)
                SniffResult.Ok(
                    verdicts = mapOf(
                        Sniffer.COIL to results[0],
                        Sniffer.RAWLER to results[1],
                    ),
                )
            }
        } catch (_: TimeoutCancellationException) {
            SniffResult.Timeout
        }
    }
}

/** Identifies a registered sniffer. The dictionary is keyed by these; add values to extend. */
enum class Sniffer { COIL, RAWLER }

/**
 * Per-sniffer verdict. [format] is the identified format label (MIME or RAW name);
 * null means this sniffer did not recognize the input. [canDecode] is whether this
 * sniffer's backend can actually decode/render it.
 */
data class Verdict(
    val format: String? = null,
    val canDecode: Boolean = false,
)

typealias SniffDict = Map<Sniffer, Verdict>

/** Result of [FormatSniffer.sniff]: the sniff dictionary, or a timeout. */
sealed interface SniffResult {
    val verdicts: SniffDict
    data class Ok(override val verdicts: SniffDict) : SniffResult
    data object Timeout : SniffResult {
        override val verdicts: SniffDict = emptyMap()
    }
}

/**
 * Coil-side sniffer. Reuses the **platform/Coil native** format detector — Android
 * `BitmapFactory` with `inJustDecodeBounds` — which is exactly the decoder Coil wraps
 * for rasters, so its verdict equals Coil's raster-decode capability. **No hand-rolled
 * magic bytes.** A non-null `outMimeType` means Coil can render it; otherwise this
 * sniffer reports unrecognized (the RAW path is owned by [RawlerProbe]).
 *
 * SVG is Coil's vector path that `BitmapFactory` cannot see, so a minimal content
 * check is kept as the only non-platform exception.
 */
internal object CoilSideSniffer {
    fun sniff(header: ByteArray): Verdict {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ByteArrayInputStream(header).use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        val mime = options.outMimeType
        if (mime != null) return Verdict(format = mime, canDecode = true)
        if (looksLikeSvg(header)) return Verdict(format = "image/svg+xml", canDecode = true)
        return Verdict()
    }

    private fun looksLikeSvg(h: ByteArray): Boolean {
        if (h.isEmpty()) return false
        var i = 0
        while (i < h.size && (
                h[i] == ' '.code.toByte() || h[i] == '\n'.code.toByte() ||
                    h[i] == '\r'.code.toByte() || h[i] == '\t'.code.toByte())
        ) i++
        if (i >= h.size || h[i] != '<'.code.toByte()) return false
        val tail = String(h.sliceArray(i until minOf(i + 64, h.size)), Charsets.UTF_8).lowercase()
        return tail.contains("<svg") || tail.contains("<?xml")
    }
}

/**
 * RAW-center sniffer backed by rawler/dnglab. The actual probe crosses the native
 * boundary (`rawler::get_decoder`, rawler/src/decoders/mod.rs:909) via JNI/UniFFI —
 * that bridge is NOT wired yet (TODO). Map `RawlerError::Unsupported` (CLI exit code 7
 * / `AppError::UnsupportedFile`) to `Verdict()` (unrecognized).
 */
internal object RawlerProbe {
    suspend fun sniff(header: ByteArray): Verdict {
        // TODO: call native bridge; on RawlerError::Unsupported -> Verdict().
        return Verdict()
    }
}

/*
 * Routing decision (R8 / Open Question Q6) — PENDING USER SPECIFICATION.
 * Implement as a pure function of [SniffResult.Ok.verdicts], e.g.:
 *   val coil = verdicts[Sniffer.COIL]; val rawler = verdicts[Sniffer.RAWLER]
 *   when {
 *     coil.canDecode && !rawler.canDecode -> Route.ToCoil(coil.format)
 *     rawler.canDecode && !coil.canDecode -> Route.RawToRaster(rawler.format)
 *     !coil.canDecode && !rawler.canDecode -> Route.Unsupported
 *     else -> TODO("both decodable — precedence TBD")
 *   }
 */
