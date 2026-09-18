package io.github.fotlab.fotlab.feature.studio

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.github.fotlab.fotlab.media.DEFAULT_SNIFF_TIMEOUT_MS
import io.github.fotlab.fotlab.media.FormatSniffer
import io.github.fotlab.fotlab.media.MediaPreference
import io.github.fotlab.fotlab.media.RawDecoder
import io.github.fotlab.fotlab.media.RawlerFotlabDecoder
import io.github.fotlab.fotlab.media.Route
import io.github.fotlab.fotlab.media.SniffResult
import io.github.fotlab.fotlab.media.StubRawDecoder
import io.github.fotlab.fotlab.media.route
import io.github.fotlab.fotlab_rawler.DemosaicAlgorithm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Lower layer of the Studio feature (`FOTLAB-STRUCT-000001`), analogous to `LibraryCore`.
 *
 * Studio is a Snapseed-style editor. The node it shows on its canvas is produced by the Studio top
 * bar's open action, which adds the picked file to the Library's current directory
 * (`LibraryCore.importUris`) and then surfaces it here through its virtual `uri_storage` path — the
 * physical file is never touched (`FOTLAB-IMGMGR-000001` R1/R3). Studio reads that path, runs it
 * through the first-party format-sniffing wrapper ([FormatSniffer], R8) and routes the result.
 *
 * Render pipeline (R8): every opened node goes through [FormatSniffer] -> [route]:
 *  1. rawler recognizes AND can decode -> decode to PNG via [rawDecoder], then render that raster;
 *  2. otherwise Coil can decode    -> render the original source with Coil;
 *  3. neither                     -> [StudioRenderResult.Unsupported] (the UI shows the dialog).
 * The rawler native decode bridge ([RawDecoder]) is wired to the native `rawler_fotlab` library
 * ([RawlerFotlabDecoder]); when `librawler_fotlab.so` is absent it returns null and the source falls
 * through to [StudioRenderResult.Unsupported] (FOTLAB-STUDIO-000001).
 */
object StudioEngine {

    /** Prepare process-wide state; call once from the application context. */
    fun prepare(context: Context) {
        appContext = context.applicationContext
        // Wire the real rawler/UniFFI decode bridge; falls back to null (-> Unsupported) when the
        // librawler_fotlab.so artifact is absent, so a non-native build still runs (FOTLAB-STUDIO-000001).
        rawDecoder = RawlerFotlabDecoder()
        // The sniff timeout is a user preference (R8 / Q6). Reading it here keeps the sniffer free of
        // preference plumbing and lets the future settings screen change the bound with no code change.
        mediaPreference = MediaPreference(appContext)
    }

    /**
     * The virtual `uri_storage` of the node currently shown on the Studio canvas, or `null` when
     * nothing has been opened yet. Process-scoped; never persisted.
     */
    private val currentNodeUriState = MutableStateFlow<String?>(null)
    val currentNodeUri: StateFlow<String?> = currentNodeUriState.asStateFlow()

    /** Live result of running the render pipeline over the current node. */
    private val renderResultState = MutableStateFlow<StudioRenderResult>(StudioRenderResult.Idle)
    val renderResult: StateFlow<StudioRenderResult> = renderResultState.asStateFlow()

    /** The RAW->PNG decoder. Wired to the native rawler/UniFFI bridge by [prepare]. */
    var rawDecoder: RawDecoder = StubRawDecoder

    private lateinit var appContext: Context

    /** Media-layer user preferences; the sniff timeout is read from it per open (R8 / Q6). */
    private lateinit var mediaPreference: MediaPreference

    /** The node currently on the canvas, retained so a develop re-render can re-open the source. */
    private var currentUri: Uri? = null

    /** The raw format label from the sniff step, retained for the develop call. */
    private var currentFormat: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Point the canvas at [uri] (the virtual node's `uri_storage`) and run the render pipeline. */
    fun setCurrentNode(uri: String?) {
        currentNodeUriState.value = uri
        if (uri == null) {
            renderResultState.value = StudioRenderResult.Idle
            return
        }
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
        if (parsed == null) {
            renderResultState.value = StudioRenderResult.Unsupported
            return
        }
        currentUri = parsed
        renderResultState.value = StudioRenderResult.Loading
        scope.launch { renderResultState.value = runPipeline(appContext.contentResolver, parsed) }
    }

    private suspend fun runPipeline(resolver: ContentResolver, uri: Uri): StudioRenderResult {
        val header = runCatching { resolver.openInputStream(uri)?.use { it.readHeader(Constants.HEADER_BYTES) } }
            .getOrNull()
        if (header == null) return StudioRenderResult.Unsupported

        // 1) Sniff: every input passes through the first-party wrapper (R8). Timeout is a hard error.
        // The bound is the user preference (R8 / Q6); a failed read falls back to the default so a
        // broken preference store can never block opening a file.
        val timeout = runCatching { mediaPreference.sniffTimeoutMs.first() }
            .getOrDefault(DEFAULT_SNIFF_TIMEOUT_MS)
        // Last line of defence: the sniff coroutine escaping with anything (including a timeout
        // result or an unexpected throwable from the wrapper machinery) degrades to Unsupported
        // here. It runs on Dispatchers.IO inside a SupervisorJob, where an uncaught throwable
        // would otherwise kill the whole process — the file must fail closed, the app must not.
        val sniff = runCatching { FormatSniffer.sniff(header, timeout) }.getOrNull()
        val verdicts = (sniff as? SniffResult.Ok)?.verdicts
            ?: return StudioRenderResult.Unsupported

        // 2) Route, then execute (R8 / Q6, resolved).
        return when (val r = route(verdicts)) {
            is Route.RawToRaster -> {
                // rawler path — TWO separate calls:
                //   call #1 (identify) already happened above in FormatSniffer.sniff via RawlerProbe,
                //   which produced `r.format` + `canDecode`. This is call #2 (decode): hand that format
                //   to the native bridge so it decodes the already-identified RAW into a grayscale raw
                //   preview, then render the PNG. `currentFormat` is retained for the later develop call.
                val png = runCatching {
                    rawDecoder.decodeToPng(r.format) { resolver.openInputStream(uri) ?: error("cannot open source") }
                }.getOrNull()
                if (png != null) {
                    currentFormat = r.format
                    StudioRenderResult.Ready(ByteBuffer.wrap(png))
                } else {
                    StudioRenderResult.Unsupported
                }
            }
            is Route.ToCoil -> StudioRenderResult.Ready(uri)
            is Route.Unsupported -> StudioRenderResult.Unsupported
        }
    }

    // A nested object (not a companion): a standalone `object` cannot itself host a companion.
    private object Constants {
        /**
         * Bytes handed to [FormatSniffer]. RAW containers are TIFF/BMFF based, so the identification
         * tags (and any embedded-preview IFD entries) can sit far into the file; 1 MiB keeps the
         * sniff content-based instead of guessing from the first block. It is a bounded, single
         * read, so the cost stays flat regardless of file size.
         */
        const val HEADER_BYTES = 1024 * 1024
    }

    /** The demosaic algorithm retained for the next develop re-render (set when the user picks one). */
    private var currentAlgorithm: DemosaicAlgorithm = DemosaicAlgorithm.DEFAULT

    /** The exposure compensation (in stops) retained for the next develop re-render. */
    private var currentExposureEv: Float = 0.0f

    /** The current exposure compensation in stops; the UI prefills the Exposure dialog from this. */
    fun currentExposureEv(): Float = currentExposureEv

    /**
     * Re-develop the current RAW with the demosaic [algorithm] the user picked from the Studio
     * bottom-bar menu, and push the resulting **linear** PNG to the canvas. It keeps the currently
     * set exposure compensation, then re-runs the full develop pipeline (decode included) through the
     * native bridge. No-op if nothing is open or the source is not a routed raw.
     */
    fun develop(algorithm: DemosaicAlgorithm) {
        currentAlgorithm = algorithm
        reDevelop()
    }

    /**
     * Re-develop the current RAW with a new exposure compensation [ev] (in stops) entered from the
     * Studio Exposure dialog, keeping the current demosaic algorithm. The value is written into
     * [DevelopParams.exposureEv] so the native calibrate step applies the `2^ev` linear gain before
     * the cam→sRGB matrix; the canvas is re-rendered from the re-developed PNG.
     */
    fun setExposureEv(ev: Float) {
        currentExposureEv = ev
        reDevelop()
    }

    /** Shared re-develop path: re-runs the develop pipeline with the retained algorithm + exposure. */
    private fun reDevelop() {
        val uri = currentUri ?: return
        val format = currentFormat ?: return
        renderResultState.value = StudioRenderResult.Loading
        scope.launch {
            renderResultState.value = runDevelop(appContext.contentResolver, uri, format, currentAlgorithm, currentExposureEv)
        }
    }

    private suspend fun runDevelop(
        resolver: ContentResolver,
        uri: Uri,
        format: String,
        algorithm: DemosaicAlgorithm,
        exposureEv: Float,
    ): StudioRenderResult {
        val png = runCatching {
            rawDecoder.developToPng(format, algorithm, exposureEv) { resolver.openInputStream(uri) ?: error("cannot open source") }
        }.getOrNull()
        return if (png != null) StudioRenderResult.Ready(ByteBuffer.wrap(png)) else StudioRenderResult.Unsupported
    }
}

/**
 * Read up to [max] bytes, stopping early at end-of-stream.
 *
 * `InputStream.readNBytes` only exists from API 33, so it must not be used with `minSdk = 26`;
 * this is the API-safe equivalent for the sniff header.
 */
private fun InputStream.readHeader(max: Int): ByteArray {
    val buffer = ByteArray(max)
    var filled = 0
    while (filled < max) {
        val read = read(buffer, filled, max - filled)
        if (read < 0) break
        filled += read
    }
    return if (filled == max) buffer else buffer.copyOf(filled)
}

/** Result of running the studio render pipeline over the current node (R8). */
sealed interface StudioRenderResult {
    /** Nothing opened yet. */
    data object Idle : StudioRenderResult
    /** Sniffing / decoding in progress. */
    data object Loading : StudioRenderResult
    /** A model Coil can render: the original [Uri] (Coil path) or decoded PNG [ByteBuffer] (rawler path). */
    data class Ready(val model: Any) : StudioRenderResult
    /** Neither sniffer can decode the source. */
    data object Unsupported : StudioRenderResult
}
