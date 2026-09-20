package io.github.fotlab.fotlab.media

import android.graphics.BitmapFactory
import io.github.fotlab.fotlab.binding.dnglab.rawler_fotlab.RawlerFotlabBridge
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * First-party format-sniffing wrapper (R8, FOTLAB-STUDIO-000001).
 *
 * Every image byte stream entering the app MUST pass through [sniff] before any
 * routing decision. It **classifies only** — it never decodes pixels or renders.
 * The route decision is a separate pure function driven by the emitted
 * [SniffResult] dictionary via [route] (R8 / Q6, resolved).
 *
 * Flow: parallel Coil-side + rawler-side sniffers, bounded by a timeout (default
 * [DEFAULT_SNIFF_TIMEOUT_MS], a user preference — see [MediaPreference]); timeout =>
 * [SniffResult.Timeout] (hard error, never a silent fallback).
 *
 * **A timeout must not depend on the sniffers cooperating.** Neither the platform
 * `BitmapFactory` call nor the native rawler call can be cancelled — `Thread.interrupt()`
 * only raises a flag such code never reads — so each sniffer runs on its own daemon worker
 * thread ([SniffThreads]) and, when the deadline passes, the caller *gives up on the thread*
 * instead of waiting for it: the worker is interrupted and abandoned. See [SniffThreads].
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
    ): SniffResult {
        val coil = SniffThreads.submit { CoilSideSniffer.sniff(header) }
        val rawler = SniffThreads.submit { RawlerProbe.sniff(header) }
        return try {
            withTimeout(timeoutMillis) {
                SniffResult.Ok(
                    verdicts = mapOf(
                        Sniffer.COIL to coil.await(),
                        Sniffer.RAWLER to rawler.await(),
                    ),
                )
            }
        } catch (_: TimeoutCancellationException) {
            // Hard timeout. The in-flight calls cannot be asked to stop, so interrupt their worker
            // threads and abandon them — the abandoned work is discarded because nothing awaits
            // these futures any more, and being daemon threads they can never hold the process open.
            coil.cancel(true)
            rawler.cancel(true)
            SniffResult.Timeout
        }
    }
}

/**
 * Worker pool for the blocking sniffers (R8).
 *
 * Each sniffer call is a black box that may block in platform or native code for an unbounded
 * time. Running it on a dedicated **daemon** thread is what makes the sniff timeout authoritative:
 * once the deadline passes we can stop waiting immediately and simply orphan the thread, rather
 * than block the caller on a call that may never return. A sniffer that wedges therefore costs one
 * abandoned daemon thread, never a hang.
 */
private object SniffThreads {

    private val pool: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "fotlab-sniff").apply { isDaemon = true }
    }

    /** Runs [call] on a worker thread; the returned future may be cancelled (interrupt-and-orphan). */
    fun <T> submit(call: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        pool.execute {
            try {
                future.complete(call())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return future
    }
}

/**
 * Awaits this future, interrupting the worker thread if the awaiting coroutine is cancelled
 * (which is how `withTimeout` in [FormatSniffer.sniff] reaches it).
 *
 * `tryResume`/`completeResume` are used instead of `resume` so a result that arrives *after*
 * cancellation is silently dropped instead of throwing inside the completion callback.
 */
private suspend fun <T> CompletableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    whenComplete { value, error ->
        val token = if (error != null) cont.tryResumeWithException(error) else cont.tryResume(value)
        if (token != null) cont.completeResume(token)
    }
    cont.invokeOnCancellation { cancel(true) }
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
 * RAW-center sniffer backed by rawler/dnglab. This is the **first** of the two raw-path calls:
 * format *identification* only — it answers "is this a RAW rawler recognizes, and can it decode?" and
 * returns a [Verdict] (`format` + `canDecode`). It does **not** decode pixels. The decode itself is
 * the **second** call, made later by [io.github.fotlab.fotlab.media.RawDecoder.decodeToPng] once the
 * route resolves to [Route.RawToRaster].
 *
 * The probe crosses the native boundary through the first-party `rawler_fotlab` library
 * ([RawlerFotlabBridge.identifyFormat] -> UniFFI -> `rawler::decode_dummy`), which reports the
 * camera make/model for bytes rawler recognizes and `null` otherwise. `RawlerError::Unsupported`
 * (CLI exit code 7 / `AppError::UnsupportedFile`) surfaces as `null`, i.e. `Verdict()`.
 */
internal object RawlerProbe {
    // Call #1 of the raw path: identification only. It returns the camera make/model when rawler
    // recognizes the bytes; null -> Verdict() (unrecognized). The actual decode (call #2) is a
    // separate native call made later by RawDecoder, after routing to RawToRaster. A missing
    // librawler_fotlab.so degrades to Verdict() (graceful, no crash).
    //
    // Blocking by design and always invoked from a [SniffThreads] worker: this call runs inside
    // `librawler_fotlab.so` and, once started, cannot be cancelled.
    fun sniff(header: ByteArray): Verdict {
        val format = RawlerFotlabBridge.identifyFormat(header)
        return if (format != null) Verdict(format = format, canDecode = true) else Verdict()
    }
}

/**
 * Studio routing decision over the sniff dictionary (R8 / Q6, resolved).
 *
 * Precedence (user-specified):
 *  1. rawler recognizes AND can decode -> [Route.RawToRaster]: the rawler path decodes the source to a
 *     PNG and the frontend renders that raster.
 *  2. otherwise Coil can decode    -> [Route.ToCoil]: hand the original source to Coil.
 *  3. neither can decode           -> [Route.Unsupported]: the UI shows "Unsupported Format".
 *
 * [Route.RawToRaster] wins when both report `canDecode` (rawler's raster is authoritative for RAW, and
 * Coil cannot decode RAW anyway, so this only ever triggers for formats rawler also handles).
 */
sealed interface Route {
    /** The sniff dictionary this route was derived from. */
    val verdicts: SniffDict
    /** rawler recognized and can decode -> decode to PNG, then render the raster. */
    data class RawToRaster(override val verdicts: SniffDict, val format: String) : Route
    /** Coil can decode the original source. */
    data class ToCoil(override val verdicts: SniffDict, val format: String) : Route
    /** Neither sniffer can decode. */
    data object Unsupported : Route
}

/** Pure routing function: classify the sniff dictionary into a [Route] (R8 / Q6). */
fun route(verdicts: SniffDict): Route {
    val coil = verdicts[Sniffer.COIL]
    val rawler = verdicts[Sniffer.RAWLER]
    return when {
        rawler != null && rawler.canDecode ->
            Route.RawToRaster(verdicts, rawler.format ?: "raw")
        coil != null && coil.canDecode ->
            Route.ToCoil(verdicts, coil.format ?: "application/octet-stream")
        else -> Route.Unsupported
    }
}
