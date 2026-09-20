package io.github.fotlab.fotlab.feature.studio

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.fotlab.fotlab.R
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
import io.github.fotlab.fotlab_rawler.DevelopParams
import io.github.fotlab.fotlab_rawler.GradeParams
import io.github.fotlab.fotlab_rawler.RawlerImageLoaded
import io.github.fotlab.fotlab_rawler.RawlerFotlabBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.jvm.Volatile

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
        val header = runCatching { resolver.openInputStream(uri)?.use { it.readHeader(HEADER_BYTES) } }
    /** Point the canvas at [uri] (the virtual node's `uri_storage`) and run the render pipeline. */
    fun setCurrentNode(uri: String?) {
        // Release any previously-held decoded RAW and invalidate in-flight work before switching
        // (FOTLAB-RAWLER-000004 §lifecycle: exactly one handle per loaded file, at most).
        loadedImage = null
        // The Boost/LOG/LUT selection belongs to the previous file's grade fork — every new node
        // starts at all-"none" (the regular sRGB develop presentation).
        gradeSelectionState.value = GradeSelection()
        val verdicts = when (val sniff = FormatSniffer.sniff(header, timeout)) {
            is SniffResult.Ok -> sniff.verdicts
            is SniffResult.Timeout -> return StudioRenderResult.Unsupported
        }
        }
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
        if (parsed == null) {
            renderResultState.value = StudioRenderResult.Unsupported
            return
        }
        currentUri = parsed
        currentWhiteBalanceKelvin = null
        renderResultState.value = StudioRenderResult.Loading
        scope.launch {
            val result = runPipeline(appContext.contentResolver, parsed, token)
            if (loadNonce.get() == token) renderResultState.value = result
        }
    }

    private suspend fun runPipeline(resolver: ContentResolver, uri: Uri, token: Long): StudioRenderResult {
        val header = runCatching { resolver.openInputStream(uri)?.use { it.readHeader(Constants.HEADER_BYTES) } }
            .getOrNull()
    private companion object {
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
                // rawler path — decode exactly ONCE into a resident `RawlerImageLoaded`, then develop it
                // with as-shot params so the as-shot rendered image is shown. Every later develop
                // (algorithm / exposure change) reuses the same object (no re-decode, no re-cross of the
                // pixel buffer; FOTLAB-RAWLER-000004). `decode_to_png` (grayscale preview) is retained in
                // the bridge/Rust but is no longer called here. `currentFormat` is kept for the stateless fallback.
                val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }
                    .getOrNull() ?: return StudioRenderResult.Unsupported
                val loaded = RawlerFotlabBridge.loadRawlerImage(bytes) ?: return StudioRenderResult.Unsupported
                // A newer node was opened while we decoded: discard so we never clobber the new file's state.
                if (loadNonce.get() != token) return StudioRenderResult.Unsupported
                loadedImage = loaded
                // Develop once with as-shot params: pass `null` for both `exposureEv` and `wb` so the
                // pipeline adopts the decoded as-shot values (rawler's `RawDevelop::default()`, which
                // dnglab uses for its DNG thumbnail and applies no exposure step — FOTLAB-RAWLER-000004
                // §as-shot). Later develops reuse this same object.
                val png = RawlerFotlabBridge.developRawlerImage(
                    loaded,
                    DevelopParams(demosaicAlgorithm = DemosaicAlgorithm.DEFAULT, exposureEv = null, wb = null),
                ) ?: return StudioRenderResult.Unsupported
                currentFormat = r.format
                rawLoadedState.value = true
                StudioRenderResult.Ready(ByteBuffer.wrap(png))
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

        /**
         * App-private cache subdirectory holding SAF-picked LUT files copied off their
         * `content://` URIs so the native grader can open a real path.
         */
        const val LUT_CACHE_DIR = "grading-luts"
    }

    /** The demosaic algorithm retained for the next develop re-render (set when the user picks one). */
    private var currentAlgorithm: DemosaicAlgorithm = DemosaicAlgorithm.DEFAULT

    /** The exposure compensation (in stops) retained for the next develop re-render. */
    private var currentExposureEv: Float = 0.0f

    /** The white-balance color temperature (Kelvin) retained for the next develop re-render; null = as-shot. */
    private var currentWhiteBalanceKelvin: Float? = null

    /**
     * The RAW decoded once and held resident as a UniFFI handle; null when no raw file is loaded.
     * Tied to [currentUri] — there is exactly one at a time (`FOTLAB-RAWLER-000004` §lifecycle).
     * Releasing it (on file switch) drops the Kotlin reference and lets GC free the native decode.
     */
    @Volatile private var loadedImage: RawlerImageLoaded? = null

    /**
     * Monotonic token bumped on every [setCurrentNode]; in-flight develop/preview coroutines bail if
     * it changes, so a stale result never paints a different file's canvas (`FOTLAB-RAWLER-000004` §lifecycle).
     */
    private val loadNonce = AtomicLong(0)

    // ---- rawalchemy grade fork: the Boost / LOG / LUT grade bar ----
    //
    // Two rendering forks share the same resident decode (FOTLAB-RAWLER-000006):
    //  * develop fork — a demosaic / exposure / WB change re-renders the sRGB PNG (gamma-applied);
    //  * grade fork   — a Boost / LOG / LUT change re-develops with the retained develop params,
    //    hands the linear ProPhoto buffer to rawalchemy, and shows the graded PNG (direct
    //    quantization, no extra transfer function).
    // The grade bar only exists while a routed RAW is resident; all-"none" means the grade fork is
    // inactive and the canvas keeps/returns to the develop presentation.

    /** Current Boost/LOG/LUT selection; reset to all-"none" on every file switch. */
    private val gradeSelectionState = MutableStateFlow(GradeSelection())
    val gradeSelection: StateFlow<GradeSelection> = gradeSelectionState.asStateFlow()

    /** True only while a routed RAW is resident — the grade bar is a RAW-only surface. */
    private val rawLoadedState = MutableStateFlow(false)
    val isRawLoaded: StateFlow<Boolean> = rawLoadedState.asStateFlow()

    /** Last grade-fork failure message (typically an unreadable/unsupported LUT); null when clear. */
    private val gradeErrorState = MutableStateFlow<String?>(null)
    val gradeError: StateFlow<String?> = gradeErrorState.asStateFlow()

    /** Dismiss the one-shot grade error dialog. */
    fun clearGradeError() {
        gradeErrorState.value = null
    }

    /**
     * The three grade-bar selections. [boost] is the explicit enable flag — the UI's "none" chip
     * means boost OFF (it is not upstream's engine default, which is on); [logSpace] null = no log
     * curve (skip gamut + log stages); [lutPath] null = no LUT. [lutName] is only the picked
     * file's display name for the chip.
     */
    data class GradeSelection(
        val boost: Boolean = false,
        val logSpace: String? = null,
        val lutName: String? = null,
        val lutPath: String? = null,
    ) {
        /** At least one grading stage switched on; all-"none" keeps the sRGB develop fork. */
        val isActive: Boolean get() = boost || logSpace != null || lutPath != null
    }

    /** The log curves rawalchemy accepts — static per loaded .so, queried once and cached. */
    @Volatile private var logSpacesCache: List<String>? = null
    fun supportedLogSpaces(): List<String> =
        logSpacesCache ?: RawlerFotlabBridge.supportedGradeLogSpaces().also { logSpacesCache = it }

    /** Toggle the default enhancement (upstream saturation/contrast boost); false = the "none" chip. */
    fun setGradeBoost(enabled: Boolean) {
        gradeSelectionState.update { it.copy(boost = enabled) }
        reGrade()
    }

    /** Pick a log curve by name (one of [supportedLogSpaces]); null = the "none" chip. */
    fun setGradeLogSpace(name: String?) {
        gradeSelectionState.update { it.copy(logSpace = name) }
        reGrade()
    }

    /** Remove the picked LUT (the "none" item in the LUT menu). */
    fun clearGradeLut() {
        gradeSelectionState.update { it.copy(lutName = null, lutPath = null) }
        reGrade()
    }

    /**
     * Accept a SAF-picked LUT [uri] with no format restriction (the picker launches with the
     * wildcard MIME filter — every file type is selectable; rawalchemy validates the contents as a
     * `.cube` 3D LUT). The native grader only reads real filesystem paths, never `content://` URIs,
     * so the bytes are copied into an app-private cache file (content-addressed by SHA-256, display
     * extension retained) and that path is what crosses the FFI. A copy failure is reported via
     * [gradeError] without changing the selection; a bad LUT file surfaces when the native grade
     * runs.
     */
    fun setGradeLut(uri: Uri) {
        val token = loadNonce.get()
        scope.launch {
            val copied = runCatching { copyLutToCache(uri) }.getOrNull()
            if (copied == null) {
                gradeErrorState.value = appContext.getString(R.string.studio_grade_error_lut_copy)
                return@launch
            }
            if (loadNonce.get() != token) return@launch
            gradeSelectionState.update { it.copy(lutName = copied.first, lutPath = copied.second) }
            reGrade()
        }
    }

    /** Shared grade-fork render: re-develop (resident decode, retained algo/EV/WB) → grade → PNG. */
    private fun reGrade() {
        val selection = gradeSelectionState.value
        if (!selection.isActive) {
            // All three back to "none": nothing to grade — return the canvas to the sRGB develop
            // presentation rather than showing a dark, linear, unencoded ProPhoto buffer.
            reDevelop()
            return
        }
        if (loadedImage == null) return
        val token = loadNonce.get()
        renderResultState.value = StudioRenderResult.Loading
        scope.launch {
            val png = runGrade(token, selection)
            if (loadNonce.get() != token) return@launch
            if (png != null) {
                renderResultState.value = StudioRenderResult.Ready(ByteBuffer.wrap(png))
            } else {
                // The grader rejected the inputs (almost always a bad LUT): surface the reason once
                // and keep the canvas usable by falling back to the sRGB develop presentation.
                gradeErrorState.value = appContext.getString(R.string.studio_grade_error_message)
                reDevelop()
            }
        }
    }

    private fun runGrade(token: Long, selection: GradeSelection): ByteArray? {
        if (loadNonce.get() != token) return null
        val loaded = loadedImage ?: return null
        val params = DevelopParams(
            demosaicAlgorithm = currentAlgorithm,
            exposureEv = currentExposureEv,
            wb = null,
        )
        // Only the three grade-bar controls are wired. Every other rawalchemy parameter stays null
        // ("the engine decides") — this crate pins no upstream default of its own.
        val grade = GradeParams(
            logSpace = selection.logSpace,
            lutPath = selection.lutPath,
            meteringMode = null,
            gain = null,
            targetGray = null,
            enableBoost = selection.boost,
            saturation = null,
            contrast = null,
            pivot = null,
        )
        val kelvin = currentWhiteBalanceKelvin
        return if (kelvin != null) {
            RawlerFotlabBridge.gradeRawlerImageToPngAtKelvin(loaded, params, kelvin, grade)
        } else {
            RawlerFotlabBridge.gradeRawlerImageToPng(loaded, params, grade)
        }
    }

    /**
     * Copy the SAF document behind [uri] to `cacheDir/grading-luts/<sha-256><ext>` and return
     * `(displayName, absolutePath)`. Content-addressed so re-picking the same file reuses the copy;
     * the extension comes from the display name so a real `.cube` keeps its suffix, while an
     * extensionless/arbitrary pick is still accepted (format validation is rawalchemy's job).
     */
    private fun copyLutToCache(uri: Uri): Pair<String, String> {
        val resolver = appContext.contentResolver
        val displayName = resolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
            ?: "lut"
        val dir = File(appContext.cacheDir, Constants.LUT_CACHE_DIR).apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
        val tmp = File.createTempFile("lut-", ".part", dir)
        try {
            (resolver.openInputStream(uri) ?: error("cannot open picked LUT")).use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        val ext = displayName.substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() }
            ?.let { "." + it.take(8) }
            .orEmpty()
        val dest = File(dir, digest.digest().joinToString("") { "%02x".format(it) } + ext)
        if (dest.exists()) {
            tmp.delete()
        } else if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        return displayName to dest.absolutePath
    }

    /** The current exposure compensation in stops; the UI prefills the Exposure dialog from this. */
    fun currentExposureEv(): Float = currentExposureEv

    /** The as-shot color temperature (Kelvin) decoded from the current RAW, or 0f when unavailable. */
    fun asShotWhiteBalanceKelvin(): Float = loadedImage?.asShotColorTempKelvin() ?: 0f

    /**
     * The Kelvin the white-balance dialog prefills: the user override while one is set, otherwise
     * the as-shot estimate (0f when no RAW is loaded or the estimate is unavailable).
     */
    fun currentWhiteBalanceKelvin(): Float = currentWhiteBalanceKelvin ?: asShotWhiteBalanceKelvin()

    /**
     * Re-develop the current RAW with a white-balance color temperature [kelvin] (Kelvin) entered
     * from the Studio white-balance dialog, keeping the current demosaic algorithm and exposure. The
     * Kelvin is projected to camera multipliers on the native side and written into the develop
     * params; the canvas is re-rendered from the re-developed PNG.
     */
    fun setWhiteBalanceKelvin(kelvin: Float) {
        currentWhiteBalanceKelvin = kelvin
        reDevelop()
    }

    /**
     * Re-develop the current RAW with the demosaic [algorithm] the user picked from the Studio
     * top-bar gradient dropdown, and push the resulting **linear** PNG to the canvas. It keeps the
     * currently set exposure compensation, then re-runs the full develop pipeline through the
     * native bridge (the decode is reused from the resident handle). No-op if nothing is open or
     * the source is not a routed raw.
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
    private fun reDevelop(wbKelvin: Float? = currentWhiteBalanceKelvin) {
        val uri = currentUri ?: return
        val token = loadNonce.get()
        renderResultState.value = StudioRenderResult.Loading
        scope.launch {
            val result = runDevelop(appContext.contentResolver, uri, token, currentAlgorithm, currentExposureEv, wbKelvin)
            if (loadNonce.get() == token) renderResultState.value = result
        }
    }

    private suspend fun runDevelop(
        resolver: ContentResolver,
        uri: Uri,
        token: Long,
        algorithm: DemosaicAlgorithm,
        exposureEv: Float,
        wbKelvin: Float? = null,
    ): StudioRenderResult {
        // If the file was switched while we were about to develop, bail — never develop a different
        // file's pixels (FOTLAB-RAWLER-000004 §lifecycle: exactly one handle per current file).
        if (loadNonce.get() != token) return StudioRenderResult.Unsupported
        // Reuse the resident decoded image; fall back to a stateless re-decode only if it is absent.
        val loaded = loadedImage
        val png = if (loaded != null) {
            if (wbKelvin != null) {
                RawlerFotlabBridge.developRawlerImageAtKelvin(
                    loaded,
                    DevelopParams(demosaicAlgorithm = algorithm, exposureEv = exposureEv, wb = null),
                    wbKelvin,
                )
            } else {
                RawlerFotlabBridge.developRawlerImage(
                    loaded,
                    DevelopParams(demosaicAlgorithm = algorithm, exposureEv = exposureEv, wb = null),
                )
            }
        } else {
            rawDecoder.developToPng(currentFormat ?: "", algorithm, exposureEv) {
                resolver.openInputStream(uri) ?: error("cannot open source")
            }
        }
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
