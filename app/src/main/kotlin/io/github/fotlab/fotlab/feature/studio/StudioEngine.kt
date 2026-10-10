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
import io.github.fotlab.fotlab_rawler.DemosaicCandidate
import io.github.fotlab.fotlab_rawler.DehazeMergeMode
import io.github.fotlab.fotlab_rawler.CaSettings
import io.github.fotlab.fotlab_rawler.UnpurpleSettings
import io.github.fotlab.fotlab_rawler.CameraProfileParams
import io.github.fotlab.fotlab_rawler.DevelopParams
import io.github.fotlab.fotlab_rawler.DemosaicedCameraImage
import io.github.fotlab.fotlab_rawler.GradeParams
import io.github.fotlab.fotlab_rawler.LensProfileParams
import io.github.fotlab.fotlab_rawler.OutputTransfer
import io.github.fotlab.fotlab_rawler.PipelineStages
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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
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
 *
 * Develop parameters that are **preferences** rather than render state are held here — today the
 * chosen demosaic algorithm ([develop]). Selecting one stores the choice and re-develops; the
 * canvas keeps the frame it has until something else develops, and every later develop (a new
 * file, an exposure/WB change, a grade change) reads it and passes it to the native pipeline
 * (`rules/REVIEW/detail/OPTIMZ-PERFRM-000010.md`).
 */
object StudioEngine {

    /**
     * The demosaic a fresh install develops with, as a catalogue id: rawler's own CFA-driven choice.
     *
     * The single source of that default — [StudioDevelopPreference] deliberately returns `null`
     * rather than a literal, so this cannot drift into a second copy. It is an *id* rather than the
     * enum because the persisted value is an id too, and [defaultAlgorithm] resolves both through the
     * one catalogue lookup.
     */
    const val DEFAULT_DEMOSAIC_ID = "rawler:default"

    /** Prepare process-wide state; call once from the application context. */
    fun prepare(context: Context) {
        appContext = context.applicationContext
        // Wire the real rawler/UniFFI decode bridge; falls back to null (-> Unsupported) when the
        // librawler_fotlab.so artifact is absent, so a non-native build still runs (FOTLAB-STUDIO-000001).
        rawDecoder = RawlerFotlabDecoder()
        // The sniff timeout is a user preference (R8 / Q6). Reading it here keeps the sniffer free of
        // preference plumbing and lets the future settings screen change the bound with no code change.
        mediaPreference = MediaPreference(appContext)
        // The develop pipeline's demosaic choice, loaded once so the very first open already
        // develops with the user's last pick (`OPTIMZ-PERFRM-000010`). A broken store must not
        // block Studio, hence `runCatching` + the `null` (nothing stored) fallback.
        developPreference = StudioDevelopPreference(appContext)
        scope.launch {
            val persisted = runCatching { developPreference.demosaicId.first() }.getOrNull()
            // A pick that landed while the store was still being read wins: the menu is a user
            // action and must never be silently reverted by a slower disk read.
            if (!demosaicTouched) applyPersistedDemosaic(persisted)
        }
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

    /**
     * The last successfully rendered frame, retained across re-render Loading states so the canvas
     * keeps showing the previous image until a new one arrives. Holds a [StudioRenderResult.Ready]
     * or null; it is cleared only when the node is switched/closed ([setCurrentNode]) — never on
     * Loading — which is exactly the "don't release the old bitmap until the new one lands" contract.
     */
    private val displayedResultState = MutableStateFlow<StudioRenderResult?>(null)
    val displayedResult: StateFlow<StudioRenderResult?> = displayedResultState.asStateFlow()

    /**
     * Whether a render pipeline is currently in flight (open-file decode, or a develop/grade
     * re-render). Drives the fun-bar slot: while running it shows a Stop button instead of
     * share/add. Derived from the in-flight job count, not from the transient Loading state, so it
     * stays accurate across the cooperative-discard stop.
     */
    private val pipelineRunningState = MutableStateFlow(false)
    val pipelineRunning: StateFlow<Boolean> = pipelineRunningState.asStateFlow()

    /** Number of render jobs currently in flight; gates [pipelineRunning] so overlapping re-renders
     * (e.g. a slider dragging exposure) don't flip the flag off prematurely. */
    private val inFlight = AtomicInteger(0)

    /** The most recently launched render job; the stop button cancels it (best-effort). */
    private var runningJob: Job? = null

    /** The RAW->PNG decoder. Wired to the native rawler/UniFFI bridge by [prepare]. */
    var rawDecoder: RawDecoder = StubRawDecoder

    private lateinit var appContext: Context

    /** Media-layer user preferences; the sniff timeout is read from it per open (R8 / Q6). */
    private lateinit var mediaPreference: MediaPreference

    /** Studio develop preferences (the quarter-resolution switch); owned here, written per toggle. */
    private lateinit var developPreference: StudioDevelopPreference

    /** The node currently on the canvas, retained so a develop re-render can re-open the source. */
    private var currentUri: Uri? = null

    /** The raw format label from the sniff step, retained for the develop call. */
    private var currentFormat: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Point the canvas at [uri] (the virtual node's `uri_storage`) and run the render pipeline. */
    fun setCurrentNode(uri: String?) {
        // Release any previously-held decoded RAW and invalidate in-flight work before switching
        // (FOTLAB-RAWLER-000004 §lifecycle: exactly one handle per loaded file, at most).
        loadedImage = null
        // …and with it the demosaiced camera buffer, which is the other half of the same memory
        // budget and belongs to the file that was open before. Dropping both together is what
        // keeps "at most one" true across a file switch.
        clearRenderCache()
        // The held frame belongs to the previous node: drop it so we never show a different
        // file's image while the new one decodes; the new Ready replaces it on arrival.
        displayedResultState.value = null
        // The Boost/LOG/LUT selection belongs to the previous file's grade fork — every new node
        // starts at all-"none" (the regular sRGB develop presentation).
        gradeSelectionState.value = GradeSelection()
        outputTransferState.value = OutputTransfer.GAMMA
        gradeErrorState.value = null
        rawLoadedState.value = false
        // The LCP focal sources belong to the previous file: the decoded-RAW focal is re-read for the
        // new file, and the user override is dropped so it never leaks across files.
        rawFocalLengthMmState.value = null
        userLcpFocalLengthMmState.value = null
        // The quarter-resolution capability is a property of the decode, so it is re-answered for
        // the new file (null = nothing resident yet). The chosen algorithm is a preference and
        // deliberately survives the file switch.
        superpixelSupportedState.value = null
        val token = loadNonce.incrementAndGet()
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
        currentWhiteBalanceKelvin = null
        renderResultState.value = StudioRenderResult.Loading
        inFlight.incrementAndGet()
        pipelineRunningState.value = true
        val job = scope.launch {
            val result = runPipeline(appContext.contentResolver, parsed, token)
            if (loadNonce.get() == token) {
                renderResultState.value = result
                if (result is StudioRenderResult.Ready) displayedResultState.value = result
            }
        }
        runningJob = job
        job.invokeOnCompletion { if (inFlight.decrementAndGet() == 0) pipelineRunningState.value = false }
    }

    private suspend fun runPipeline(resolver: ContentResolver, uri: Uri, token: Long): StudioRenderResult {
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
                // rawler path — decode exactly ONCE into a resident `RawlerImageLoaded`, then develop it
                // with as-shot params so the as-shot rendered image is shown. Every later develop
                // (algorithm / exposure change) reuses the same object (no re-decode, no re-cross of the
                // pixel buffer; FOTLAB-RAWLER-000004). `decode_to_png` (grayscale preview) is retained in
                // the bridge/Rust but is no longer called here. `currentFormat` is kept for the stateless fallback.
                val path = runCatching { copySourceToCache(uri) }.getOrNull()
                    ?: return StudioRenderResult.Unsupported
                val loaded = RawlerFotlabBridge.loadRawlerImageFromFile(path)
                    ?: return StudioRenderResult.Unsupported
                // A newer node was opened while we decoded: discard so we never clobber the new file's state.
                if (loadNonce.get() != token) return StudioRenderResult.Unsupported
                loadedImage = loaded
                // Ask the decode itself whether quarter resolution is available for it, so the menu
                // can disable the superpixel entry instead of leaving it inert on a sensor that
                // cannot run it (`OPTIMZ-PERFRM-000010`).
                superpixelSupportedState.value = RawlerFotlabBridge.supportsDownsample(loaded)
                // Capture focal length (mm) decoded from the RAW EXIF — 2nd LCP priority (tier-2). UniFFI
                // maps Option<f64> to a nullable Double?, so null means the file carried no focal.
                rawFocalLengthMmState.value = loaded.focalLengthMm()?.toFloat()
                // A new file invalidates every render cache: the resident handle and the demosaiced
                // camera buffer both belong to the file that was open before, and the buffer carries
                // no notion of which image produced it.
                clearRenderCache()
                // Develop once with as-shot params: pass `null` for both `exposureEv` and `wb` so the
                // pipeline adopts the decoded as-shot values (rawler's `RawDevelop::default()`, which
                // dnglab uses for its DNG thumbnail and applies no exposure step — FOTLAB-RAWLER-000004
                // §as-shot). Nothing is configured yet, so the stage dictionary is the neutral one:
                // develop everything, grade nothing — which is also what the gamma/linear readout
                // starts out reporting. `develop = true` is not merely the default here, it is
                // required: there is no cache to reuse on a freshly opened file.
                // The demosaic choice does apply here: opening the file is a develop, so the frame it
                // lands on already honours the user's pick (or the default when they never picked one).
                val transfer = OutputTransfer.GAMMA
                outputTransferState.value = transfer
                val params = assembleDevelopParams(exposureEv = null, outputTransfer = transfer)
                val png = RawlerFotlabBridge.renderRawlerImage(
                    loaded,
                    params,
                    PipelineStages(develop = true, oklab = oklabForFreshOpen(), grade = false),
                    GradeParams(),
                    null,
                    0f,
                ) ?: return StudioRenderResult.Unsupported
                // Take the demosaiced buffer this render just built, so the first grade-only edit can
                // start from it instead of re-running decode and demosaic.
                demosaicedCache = RawlerFotlabBridge.takeDemosaicedCameraImage(loaded)
                demosaicedKey = params.copy(
                    outputTransfer = OutputTransfer.GAMMA,
                    oklabHighlightCompressSrgb = false,
                    oklabHighlightCompressProphoto = false,
                )
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

        /**
         * App-private cache subdirectory holding SAF-picked DCP camera-profile files copied off their
         * `content://` URIs so the native deprofile stage can open a real path.
         */
        const val DCP_CACHE_DIR = "camera-profiles"

        /**
         * App-private cache subdirectory holding SAF-picked LCP lens-profile files copied off their
         * `content://` URIs so the native deprofile stage can open a real path.
         */
        const val LCP_CACHE_DIR = "lens-profiles"

        /**
         * App-private cache subdirectory holding opened source documents copied off their
         * `content://` URIs, so the native decoder can memory-map a real path instead of
         * receiving a whole-file `ByteArray` (`OPTIMZ-PERFRM-000002`).
         */
        const val SOURCE_CACHE_DIR = "source-cache"

        /**
         * Upper bound on how many source copies [StudioEngine] keeps in the private cache.
         *
         * RAW files are tens of megabytes each and `cacheDir` is only reclaimed by the system
         * under storage pressure, so an unbounded content-addressed copy would quietly grow
         * into the user's storage. Past this count the least-recently-modified copies are
         * deleted; re-opening such a file simply copies it again.
         */
        const val MAX_SOURCE_CACHE_FILES = 8
    }

    /**
     * Set once the user has picked from the demosaic menu, so the one-shot store read in [prepare]
     * can never revert a choice the user made while it was still reading.
     */
    @Volatile private var demosaicTouched = false

    /**
     * Whether the resident RAW can be developed at quarter resolution, decided by the native
     * sensor/CFA guard; `null` while no routed RAW is resident. The menu disables the superpixel
     * entry on `false`, so a sensor that cannot run it (X-Trans, Fuji-rotated) is *stated* instead
     * of the pick silently resolving to something else.
     */
    private val superpixelSupportedState = MutableStateFlow<Boolean?>(null)
    val superpixelSupported: StateFlow<Boolean?> = superpixelSupportedState.asStateFlow()

    /**
     * The demosaic algorithm retained for the next develop re-render (set when the user picks one).
     *
     * Starts at [DemosaicAlgorithm.DEFAULT] — rawler's CFA-driven choice, the same algorithm
     * [DEFAULT_DEMOSAIC_ID] names, which is what a fresh install has always developed with.
     * [applyPersistedDemosaic] replaces it with the last pick once the store has been read.
     *
     * It is spelled as the enum constant rather than as `defaultAlgorithm()` on purpose. A field
     * initialiser runs inside the object's `<clinit>`, in *declaration order*, and
     * [demosaicCandidatesCache] is a `by lazy` delegate declared further down — so reading it from
     * here dereferences a field that has not been assigned yet and throws
     * `NullPointerException: … kotlin.Lazy.getValue() on a null object reference`, which surfaces as
     * a process-wide `ExceptionInInitializerError` from `MainApplication.onCreate`. The id → algorithm
     * lookup therefore happens in [prepare], long after `<clinit>` has finished. Do not "simplify"
     * this back into a call.
     */
    private var currentAlgorithm: DemosaicAlgorithm = DemosaicAlgorithm.DEFAULT

    /**
     * Record the user's pick from the demosaic menu, persist it, and re-develop.
     *
     * The id is persisted rather than the enum so the stored value stays meaningful across
     * renames and locales; an id the catalogue no longer knows (a kernel withdrawn in a later
     * version) simply does not resolve and the pick falls back to the default.
     */
    fun develop(candidate: DemosaicCandidate) {
        demosaicTouched = true
        currentAlgorithm = candidate.algorithm
        scope.launch { runCatching { developPreference.setDemosaicId(candidate.id) } }
        requestRender()
    }

    /**
     * Resolve a persisted id onto the algorithm to develop with, falling back to [defaultAlgorithm]
     * when the id is absent or unknown.
     *
     * An unknown id is not an error worth surfacing: it means the catalogue moved on since the
     * value was written, and the honest outcome is the default the user would have got anyway.
     */
    private fun applyPersistedDemosaic(id: String?) {
        currentAlgorithm = id
            ?.let { wanted -> demosaicCandidates.firstOrNull { it.id == wanted } }
            ?.algorithm
            ?: defaultAlgorithm()
    }

    /**
     * [DEFAULT_DEMOSAIC_ID] resolved through the catalogue, so the default is named in exactly one
     * place and still travels the same id → algorithm path a persisted pick does.
     *
     * Must only be called once `<clinit>` has finished (i.e. from [prepare] and later), because it
     * reads the [demosaicCandidatesCache] lazy delegate — see [currentAlgorithm].
     *
     * Falls back to [DemosaicAlgorithm.DEFAULT] when the catalogue has no such entry, which is the
     * case on a build without `librawler_fotlab.so` ([RawlerFotlabBridge.demosaicAlgorithms] returns
     * an empty list there). That is the same algorithm the id names, so nothing is lost.
     */
    private fun defaultAlgorithm(): DemosaicAlgorithm =
        demosaicCandidates.firstOrNull { it.id == DEFAULT_DEMOSAIC_ID }?.algorithm
            ?: DemosaicAlgorithm.DEFAULT

    /**
     * The demosaic algorithms the DevelopFilm bar offers, in catalogue order.
     *
     * Read from the native catalogue instead of a list written in the Composable, so the day a
     * kernel lands in `rawtrp_demosaic` the menu already offers it (`FOTLAB-NATIVE-000004` D5).
     * Each entry carries both its display label and the [DemosaicAlgorithm] to send back, which
     * is what keeps the UI from needing an id→algorithm table of its own — the list and the
     * dispatch come from the same place and cannot drift apart.
     *
     * Lazy and read once: it enumerates two upstream dictionaries, and the answer cannot change
     * while the app runs.
     */
    private val demosaicCandidatesCache: List<DemosaicCandidate> by lazy { RawlerFotlabBridge.demosaicAlgorithms() }

    /** The list the DevelopFilm bar renders; see [demosaicCandidatesCache]. */
    val demosaicCandidates: List<DemosaicCandidate> get() = demosaicCandidatesCache

    /** The white-balance color temperature (Kelvin) retained for the next develop re-render; null = as-shot. */
    private var currentWhiteBalanceKelvin: Float? = null

    /**
     * The exposure compensation (in stops) retained for the next develop re-render. `null` means the
     * exposure stage is skipped entirely (as-shot, unity gain) — this is how the Exposure dialog's
     * enable switch turns the stage off; the native `apply_exposure` also early-returns on `None`.
     */
    private var currentExposureEv: Float? = null

    /**
     * Exposure-stage clip bounds (0..1) retained for the next develop re-render, fused into the
     * native exposure pass (clamp → `2^ev` gain). Gated by the same Exposure-dialog enable switch
     * as [currentExposureEv]: a stage-off render (`ev = null`) never clips either. Defaults are
     * the no-op `[0, 1]` on the already-normalised 0..1 mosaic; kept ordered (lower ≤ upper).
     */
    private var currentExposureClipLower: Float = 0f
    private var currentExposureClipUpper: Float = 1f

    /** The denoise strength (sensitivity multiplier) retained for the next develop re-render; null = off. */
    private var currentDenoiseStrength: Float? = null

    /** The BM3D-CFA denoise strength (collaborative-filter sensitivity) retained for the next develop re-render; null = off. */
    private var currentDenoiseBm3dStrength: Float? = null

    /** The dehaze strength (0..1 blend) retained for the next develop re-render; null = off. */
    private var currentDehazeStrength: Float? = null

    /** The dehaze haze-floor percentile (0..1) retained for the next develop re-render; null = default (0.01). */
    private var currentDehazePercentile: Float? = null

    /** The dehaze guided-filter dark-channel box radius (sub-lattice px); null = engine default (8). */
    private var currentDehazeRadiusDark: Int? = null

    /** The dehaze guided-filter window radius (sub-lattice px); null = engine default (8). */
    private var currentDehazeRadiusGuide: Int? = null

    /** The dehaze merge mode (Each/Blue/Min/Avg) retained for the next develop re-render; null = Min. */
    private var currentDehazeMergeMode: DehazeMergeMode = DehazeMergeMode.MIN

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
     * The three grade-bar selections. The boost group is the [contrast] / [saturation] pair: both
     * null = boost OFF; either configured = boost ON, and the unconfigured sibling falls back to
     * 1.0 at assembly time in [runGrade] (upstream receives both parameters explicitly). Both
     * floats are free-form — no range limiting. [logSpace] null = no log curve (skip gamut + log
     * stages); [lutPath] null = no LUT. [lutName] is only the picked file's display name.
     */
    data class GradeSelection(
        val contrast: Float? = null,
        val saturation: Float? = null,
        val logSpace: String? = null,
        val lutName: String? = null,
        val lutPath: String? = null,
    ) {
        /** Upstream's boost switch — on as soon as either boost parameter is configured. */
        val boostEnabled: Boolean get() = contrast != null || saturation != null

        /** At least one grading stage switched on; all-"none" keeps the sRGB develop fork. */
        val isActive: Boolean get() = boostEnabled || logSpace != null || lutPath != null
    }

    /**
     * The log curves rawalchemy accepts — static per loaded .so, queried once and cached. These are
     * UI display names (vendor spelled out, e.g. "FUJIFILM F-Log2 C") and are exactly what
     * [setGradeLogSpace] / [GradeSelection.logSpace] carry; the display↔engine mapping lives in the
     * cxx shim, so this layer never sees upstream's keys.
     */
    @Volatile private var logSpacesCache: List<String>? = null
    fun supportedLogSpaces(): List<String> =
        logSpacesCache ?: RawlerFotlabBridge.supportedGradeLogSpaces().also { logSpacesCache = it }

    /** Configure the boost-group contrast parameter; null = unconfigured (clears the value). */
    fun setGradeContrast(value: Float?) {
        gradeSelectionState.update { it.copy(contrast = value) }
        requestRender()
    }

    /** Configure the boost-group saturation parameter; null = unconfigured (clears the value). */
    fun setGradeSaturation(value: Float?) {
        gradeSelectionState.update { it.copy(saturation = value) }
        requestRender()
    }

    /** Pick a log curve by name (one of [supportedLogSpaces]); null = the "none" chip. */
    fun setGradeLogSpace(name: String?) {
        gradeSelectionState.update { it.copy(logSpace = name) }
        requestRender()
    }

    /** Remove the picked LUT (the "none" item in the LUT menu). */
    fun clearGradeLut() {
        gradeSelectionState.update { it.copy(lutName = null, lutPath = null) }
        requestRender()
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
            requestRender()
        }
    }

    // ---- the unified render -----------------------------------------------------------------
    //
    // There is one native trunk and one call any more. `reDevelop` and `reGrade` used to be two
    // entry points that each ran a whole pipeline of their own — a develop-parameter change
    // stopped at the sRGB presentation PNG and a grade-parameter change re-ran everything up to
    // rawalchemy. Both now funnel into [requestRender], which assembles the `develop`/`grade`
    // dictionary and calls `renderRawlerImage` once. Consequences worth knowing:
    //
    //  * a develop change while grading is enabled finishes the grade in the *same* pass;
    //  * a grade-only change does not re-run decode→demosaic, because the demosaiced
    //    camera-space buffer is cached (see [demosaicedCache]);
    //  * turning every grade stage off returns the canvas to the sRGB+gamma presentation render,
    //    which is now just another dictionary value rather than a separate code path.

    /**
     * The demosaiced camera-space buffer, held here on purpose.
     *
     * Native publishes the most recent one after every render that re-developed and hands it over
     * exactly once (see [RawlerFotlabBridge.takeDemosaicedCameraImage]); from then on Kotlin owns
     * it and passes it back as the render's `cache`. At most one is alive: the previous handle is
     * dropped *before* a new one is taken, and both are dropped on a file switch.
     */
    @Volatile private var demosaicedCache: DemosaicedCameraImage? = null

    /**
     * The [DevelopParams] the cached [demosaicedCache] was built from, with the output-transfer
     * field normalised away — that field is applied by the encoder, downstream of the cache
     * boundary, so flipping it must not invalidate anything. Any other difference means the cache
     * describes different pixels and the next render has to re-develop.
     */
    @Volatile private var demosaicedKey: DevelopParams? = null

    /**
     * Assemble the full develop parameter set for the next render.
     *
     * One function, so the cache-validity comparison in [requestRender] can never drift from
     * what is actually sent: both read the same record.
     *
     * [outputTransfer] is the only field the develop half does not act on — it is read by the PNG
     * encoder — and [requestRender] derives it from the grade state rather than from a user
     * choice (see [outputTransfer]).
     */
    private fun assembleDevelopParams(exposureEv: Float?, outputTransfer: OutputTransfer): DevelopParams =
        DevelopParams(
            demosaicAlgorithm = currentAlgorithm,
            exposureEv = exposureEv,
            exposureClipLower = currentExposureClipLower,
            exposureClipUpper = currentExposureClipUpper,
            wb = null,
            denoiseStrength = currentDenoiseStrength,
            denoiseBm3dStrength = currentDenoiseBm3dStrength,
            dehazeStrength = currentDehazeStrength,
            dehazePercentile = currentDehazePercentile,
            dehazeCeiling = currentDehazePercentile,
            dehazeRadiusDark = currentDehazeRadiusDark,
            dehazeRadiusGuide = currentDehazeRadiusGuide,
            dehazeMergeMode = currentDehazeMergeMode,
            ca = currentCa,
            defringeProphoto = currentUnpurple,
            clipToGamut = currentClipToGamut,
            cameraProfile = cameraProfileState.value,
            lensProfile = lensProfileState.value,
            rawFocalLengthMm = rawFocalLengthMmState.value,
            oklabHighlightCompressSrgb = currentOklabHighlightCompressSrgb,
            oklabHighlightCompressProphoto = currentOklabHighlightCompressProphoto,
            outputTransfer = outputTransfer,
        )

    /**
     * Re-render after any parameter change, on whichever output the current state calls for.
     *
     * Every develop control *and* every grade control lands here — they no longer differ in kind,
     * only in which dictionary value they imply. The decision rule is short and entirely
     * Kotlin-side, because Kotlin is the layer that knows what changed:
     *
     *  * no cache held → `develop = true` (there is nothing to reuse);
     *  * cache held and the develop-stage parameters are unchanged → `develop = false`, reuse it;
     *  * otherwise → `develop = true`, replacing the cache.
     *
     * `grade` is simply "is any grade stage active", and it is what selects the output: graded →
     * ProPhoto D50 + the graded PNG + `OutputTransfer.LINEAR`; not graded → sRGB D65 + the
     * presentation PNG + `OutputTransfer.GAMMA`.
     *
     * Falls back to the stateless decoder when no resident image is held (a non-RAW source), which
     * is the one case with no cache to speak of.
     *
     * [gradeOverride] exists for exactly one caller: the recovery path after a grading failure. It
     * forces the presentation output for that one render instead of re-reading the selection,
     * which is still active and would otherwise send the render straight back into the grader that
     * just rejected it - an endless retry loop with no way out.
     */
    private fun requestRender(gradeOverride: Boolean? = null) {
        val loaded = loadedImage
        if (loaded == null) {
            reDevelopStateless()
            return
        }
        val selection = gradeSelectionState.value
        // ACA counts as engaging the grade: the unpurple core runs on the linear ProPhoto-D50
        // editing buffer, which only the graded fork builds (`working_space()` picks ProPhotoD50
        // exactly when `grade` is true). Without this, enabling ACA on the sRGB presentation path
        // would silently do nothing.
        val gradeActive = gradeOverride ?: (selection.isActive || currentUnpurple != null)
        val transfer = if (gradeActive) OutputTransfer.LINEAR else OutputTransfer.GAMMA
        outputTransferState.value = transfer

        val params = assembleDevelopParams(currentExposureEv, transfer)
        // Normalise out everything the develop half does not act on. The transfer function is an
        // encoder concern, and the OKLab sub-switches belong to the stage ABOVE the cache, so
        // neither changes the cached pixels; letting either into the key would re-run a whole
        // decode for a render that costs one matrix multiply.
        val key = params.copy(
            outputTransfer = OutputTransfer.GAMMA,
            oklabHighlightCompressSrgb = false,
            oklabHighlightCompressProphoto = false,
        )
        val cache = demosaicedCache
        val develop = cache == null || demosaicedKey != key
        // The OKLab stage master gate, derived the same way `grade` is: with both sub-switches off
        // the stage is a guaranteed identity for every output, so there is nothing to run.
        val oklab = currentOklabHighlightCompressSrgb || currentOklabHighlightCompressProphoto
        val stages = PipelineStages(develop = develop, oklab = oklab, grade = gradeActive)

        val token = loadNonce.get()
        renderResultState.value = StudioRenderResult.Loading
        inFlight.incrementAndGet()
        pipelineRunningState.value = true
        val job = scope.launch {
            val png = runRender(token, loaded, params, stages, selection, if (develop) null else cache)
            if (loadNonce.get() != token) return@launch
            if (png != null) {
                if (stages.develop) {
                    // Replace, never accumulate: dropping first is what keeps exactly one buffer
                    // alive across an arbitrarily long editing session.
                    demosaicedCache = null
                    demosaicedCache = RawlerFotlabBridge.takeDemosaicedCameraImage(loaded)
                    demosaicedKey = key
                }
                val ready = StudioRenderResult.Ready(ByteBuffer.wrap(png))
                renderResultState.value = ready
                displayedResultState.value = ready
            } else if (stages.grade) {
                // The grader rejected the inputs (almost always a bad LUT): surface the reason once
                // and keep the canvas usable by falling back to the develop presentation.
                gradeErrorState.value = appContext.getString(R.string.studio_grade_error_message)
                requestRender(gradeOverride = false)
            } else {
                // Not a grading problem - the develop render itself failed. Retrying would only
                // fail the same way, so say so instead of spinning.
                renderResultState.value = StudioRenderResult.Unsupported
            }
        }
        runningJob = job
        job.invokeOnCompletion { if (inFlight.decrementAndGet() == 0) pipelineRunningState.value = false }
    }

    /** One native call: run the trunk (develop half cached or not) and encode the PNG. */
    private fun runRender(
        token: Long,
        loaded: RawlerImageLoaded,
        params: DevelopParams,
        stages: PipelineStages,
        selection: GradeSelection,
        cache: DemosaicedCameraImage?,
    ): ByteArray? {
        if (loadNonce.get() != token) return null
        // Only the three grade-bar controls are wired. The boost group assembles here: the switch
        // is derived (either parameter configured), and an unconfigured sibling falls back to 1.0
        // so upstream always receives both parameters explicitly. Every other rawalchemy parameter
        // stays null ("the engine decides") — this layer pins no upstream default of its own.
        // Ignored entirely when `stages.grade` is false, which is the "all stages off" case.
        val boostOn = selection.boostEnabled
        val grade = GradeParams(
            logSpace = selection.logSpace,
            lutPath = selection.lutPath,
            meteringMode = null,
            gain = null,
            targetGray = null,
            enableBoost = boostOn,
            saturation = if (boostOn) (selection.saturation ?: 1.0f) else null,
            contrast = if (boostOn) (selection.contrast ?: 1.0f) else null,
            pivot = null,
        )
        return RawlerFotlabBridge.renderRawlerImage(
            loaded,
            params,
            stages,
            grade,
            cache,
            currentWhiteBalanceKelvin ?: 0f,
        )
    }

    /**
     * The OKLab stage master gate for the very first render of a freshly opened file.
     *
     * Split out only so [runPipeline] and [requestRender] cannot drift: a fresh file develops with
     * nothing configured except whatever the OKLab dialog already holds, and that is exactly the
     * same derivation.
     */
    private fun oklabForFreshOpen(): Boolean =
        currentOklabHighlightCompressSrgb || currentOklabHighlightCompressProphoto

    /**
     * Drop both halves of the render cache. Called on a file switch so the next file cannot be
     * handed the previous file's demosaiced buffer — the single most important invariant here,
     * since the handle alone carries no notion of which image it belongs to.
     */
    private fun clearRenderCache() {
        demosaicedCache = null
        demosaicedKey = null
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

    /**
     * Copy the SAF document behind [uri] into `cacheDir/[subdir]/<sha-256><ext>` and return
     * `(displayName, absolutePath)`. Shared by the DCP / LCP pickers; content-addressed so re-picking
     * the same file reuses the copy, and the extension comes from the display name so a real `.dcp` /
     * `.lcp` keeps its suffix while an arbitrary pick is still accepted (format validation is the
     * native deprofile stage's job). Identical mechanics to [copyLutToCache].
     */
    private fun copyProfileToCache(uri: Uri, subdir: String): Pair<String, String> {
        val resolver = appContext.contentResolver
        val displayName = resolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
            ?: "profile"
        val dir = File(appContext.cacheDir, subdir).apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
        val tmp = File.createTempFile("prof-", ".part", dir)
        try {
            (resolver.openInputStream(uri) ?: error("cannot open picked profile")).use { input ->
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

    /**
     * Copy the opened source document behind [uri] to `cacheDir/source-cache/<key>` and return its
     * absolute path, so the native decoder can memory-map it instead of being handed the whole file
     * as a `ByteArray` (`rules/REVIEW/detail/OPTIMZ-PERFRM-000002.md`).
     *
     * Why copy at all: rawler's `RawSource::new` needs a real filesystem path, and a `content://`
     * URI cannot be mapped — the alternative is `readBytes()` followed by `new_from_slice`, i.e.
     * two complete copies of a file that routinely exceeds 40 MB, plus the Java-heap pressure of
     * holding it whole.
     *
     * The cache key is derived from the source identity (URI + provider-reported size), not from a
     * SHA-256 of the bytes: hashing a 40 MB file would cost a full extra read just to pick a name.
     * An existing copy is reused as-is, so re-opening the same node costs nothing.
     */
    private fun copySourceToCache(uri: Uri): String {
        val resolver = appContext.contentResolver
        val dir = File(appContext.cacheDir, Constants.SOURCE_CACHE_DIR).apply { mkdirs() }
        val dest = File(dir, sourceCacheKey(uri))
        if (!dest.exists()) {
            val tmp = File.createTempFile("src-", ".part", dir)
            try {
                (resolver.openInputStream(uri) ?: error("cannot open source")).use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                        out.flush()
                    }
                }
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
        } else {
            // Touch so the LRU trim below evicts genuinely old sources first.
            dest.setLastModified(System.currentTimeMillis())
        }
        trimSourceCache(dir)
        return dest.absolutePath
    }

    /** Stable cache key for [uri]: its own string plus the provider-reported size (both cheap). */
    private fun sourceCacheKey(uri: Uri): String {
        val size = appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L }
            ?: -1L
        return MessageDigest.getInstance("SHA-256")
            .digest("$uri|$size".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /** Evict least-recently-modified copies past [Constants.MAX_SOURCE_CACHE_FILES]. Best-effort. */
    private fun trimSourceCache(dir: File) {
        val files = runCatching { dir.listFiles()?.toList() }.getOrNull() ?: return
        if (files.size <= Constants.MAX_SOURCE_CACHE_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - Constants.MAX_SOURCE_CACHE_FILES)
            .forEach { runCatching { it.delete() } }
    }

    /** The current exposure compensation in stops; `null` means the stage is skipped (as-shot). The UI prefills the Exposure dialog from this. */
    fun currentExposureEv(): Float? = currentExposureEv

    /**
     * The current exposure clip bounds as `(lower, upper)` (0..1); the UI prefills the Exposure
     * dialog's clip row from this. Only effective while the exposure stage is enabled.
     */
    fun currentExposureClip(): Pair<Float, Float> = currentExposureClipLower to currentExposureClipUpper

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
        requestRender()
    }

    /**
     * Re-develop the current RAW with a new exposure-stage configuration from the Studio Exposure
     * dialog: [ev] in stops (applied as the `2^ev` linear gain) plus the clip bounds [clipLower] /
     * [clipUpper] (0..1) applied to the scaled mosaic immediately *before* the gain and fused into
     * the same native rayon pass. `null` [ev] skips the stage entirely (as-shot, clip included) —
     * how the dialog's enable switch turns it off. The bounds are coerced to 0..1 and, defensively,
     * ordered so lower ≤ upper even if the UI ever hands them swapped.
     */
    fun setExposure(ev: Float?, clipLower: Float, clipUpper: Float) {
        currentExposureEv = ev
        val lo = clipLower.coerceIn(0f, 1f)
        val hi = clipUpper.coerceIn(0f, 1f)
        if (lo <= hi) {
            currentExposureClipLower = lo
            currentExposureClipUpper = hi
        } else {
            currentExposureClipLower = hi
            currentExposureClipUpper = lo
        }
        requestRender()
    }

    /**
     * Auto-exposure metering with rawalchemy's 5-strategy meter ([mode]), returning the **absolute**
     * exposure EV the Exposure dialog should show, or `null` when no RAW is resident or the native
     * meter failed.
     *
     * The meter runs on the resident decode developed with the *current* develop params — including
     * the exposure already applied ([currentExposureEv]) — so the rawalchemy result is an offset
     * *relative to the image as it stands now*. This method therefore adds the **recorded,
     * already-applied** exposure ([currentExposureEv], this engine's state — deliberately NOT the
     * value the user has just typed into the dialog field) to obtain the absolute stop value.
     *
     * Metering only *proposes* a value: it applies nothing and is independent of the Exposure stage
     * switch. The user may still edit the field, and only confirming with the switch ON writes the
     * value into [DevelopParams.exposureEv] (see [setExposure]).
     */
    fun meterAutoExposure(mode: String): Float? {
        val loaded = loadedImage ?: return null
        // Metering runs the same trunk into the ProPhoto editing space, so it assembles from the
        // one shared parameter set. The transfer field is irrelevant to it (it never encodes a
        // PNG), but passing the current value keeps the record identical to what a render would
        // send — and it is excluded from the cache key anyway.
        val params = assembleDevelopParams(currentExposureEv, outputTransferState.value)
        // `metered` is the offset relative to the current image; add the recorded applied exposure
        // so the dialog's field receives an absolute value on the same scale.
        val metered = RawlerFotlabBridge.meterAutoExposure(loaded, params, mode, null) ?: return null
        return metered + (currentExposureEv ?: 0f)
    }

    /** The current denoise strength; the UI prefills the Denoise dialog from this. */
    fun currentDenoiseStrength(): Float? = currentDenoiseStrength

    /** The current BM3D-CFA denoise strength; the UI prefills the Denoise dialog from this. */
    fun currentDenoiseBm3dStrength(): Float? = currentDenoiseBm3dStrength

    /** The current dehaze strength; the UI prefills the Dehaze dialog from this. */
    fun currentDehazeStrength(): Float? = currentDehazeStrength

    /** The current dehaze percentile; the UI prefills the Dehaze dialog from this. */
    fun currentDehazePercentile(): Float? = currentDehazePercentile

    /** The current dehaze dark-channel radius; the UI prefills the Dehaze dialog from this. */
    fun currentDehazeRadiusDark(): Int? = currentDehazeRadiusDark

    /** The current dehaze guided-filter radius; the UI prefills the Dehaze dialog from this. */
    fun currentDehazeRadiusGuide(): Int? = currentDehazeRadiusGuide

    /** The current dehaze merge mode; the UI prefills the Dehaze dialog from this. */
    fun currentDehazeMergeMode(): DehazeMergeMode = currentDehazeMergeMode

    /** The CA-correction settings retained for the next develop re-render; null = off. */
    private var currentCa: CaSettings? = null

    /** The current CA settings; the UI prefills the LCA dialog from this. */
    fun currentCa(): CaSettings? = currentCa

    /**
     * The ACA (purple-fringe) settings retained for the next render; `null` = off.
     *
     * A non-null value also **engages the grade** (see [requestRender]): the unpurple core runs on
     * the linear ProPhoto-D50 editing buffer, which only exists on the graded fork.
     */
    private var currentUnpurple: UnpurpleSettings? = null

    /** The current ACA settings; the UI prefills the ACA dialog from this. */
    fun currentUnpurple(): UnpurpleSettings? = currentUnpurple

    /**
     * Whether out-of-gamut clipping is retained for the next render (the Studio Clipping dialog's
     * switch). Clipping only affects the **editing** fork — the linear ProPhoto-D50 buffer that is
     * handed to rawalchemy — so the switch is carried in the develop params but is a no-op for the
     * sRGB presentation PNG.
     */
    private var currentClipToGamut: Boolean = true

    /** The current out-of-gamut clipping switch; the UI prefills the Clipping dialog from this. */
    fun currentClipToGamut(): Boolean = currentClipToGamut

    /**
     * Whether the OKLab highlight roll-off runs for the **sRGB presentation** output (the Studio
     * OKLab dialog's first switch). A lightness-driven chroma roll-off in OKLab desaturates
     * near-clipped highlights so the encoder's per-channel clamp no longer freezes a hue error.
     * Default **on**. OFF is a bit-for-bit identity for the rest of the image.
     */
    private var currentOklabHighlightCompressSrgb: Boolean = true

    /**
     * Whether the OKLab highlight roll-off runs for the **ProPhoto grade** output (the dialog's
     * second switch). Same D65-anchored camera-space round trip as the sRGB twin, so rawalchemy
     * receives a desaturated (not clamped) near-clipped-highlight buffer. Default **off** — the
     * graded output leaves its highlights untouched unless the UI enables it.
     *
     * Both switches sit **above** the demosaic cache, so flipping either re-renders without
     * re-developing: the stage is cheap and the cached buffer is pre-roll-off by design.
     */
    private var currentOklabHighlightCompressProphoto: Boolean = false

    /** Current sRGB OKLab highlight-roll-off switch; the UI prefills the OKLab dialog from this. */
    fun currentOklabHighlightCompressSrgb(): Boolean = currentOklabHighlightCompressSrgb

    /** Current ProPhoto OKLab highlight-roll-off switch; the UI prefills the OKLab dialog from this. */
    fun currentOklabHighlightCompressProphoto(): Boolean = currentOklabHighlightCompressProphoto

    /**
     * Re-render with the OKLab highlight roll-off [enabled] for the sRGB presentation output
     * (Studio OKLab dialog).
     */
    fun setOklabHighlightCompressSrgb(enabled: Boolean) {
        currentOklabHighlightCompressSrgb = enabled
        requestRender()
    }

    /**
     * Re-render with the OKLab highlight roll-off [enabled] for the ProPhoto grade output
     * (Studio OKLab dialog).
     */
    fun setOklabHighlightCompressProphoto(enabled: Boolean) {
        currentOklabHighlightCompressProphoto = enabled
        requestRender()
    }

    /**
     * The output transfer the next render asks the encoder for — `Gamma` for the develop
     * presentation PNG, `Linear` for a graded one.
     *
     * This is an **indicator, not a user choice**: there is no switch to flip it, because the
     * right answer is decided by what the render is. A graded buffer has already been
     * log-encoded by rawalchemy, so running the sRGB OETF over it would double-encode it;
     * the presentation render is the one that wants the transfer function. So the value is
     * derived from [currentGradeSelection] and merely *reported* — to the native encoder
     * through `DevelopParams.outputTransfer`, and to the user through the Basic bar's readout.
     *
     * Exposed as a `StateFlow` because the bar's label follows it.
     */
    private val outputTransferState = MutableStateFlow(OutputTransfer.GAMMA)
    val outputTransfer: StateFlow<OutputTransfer> = outputTransferState.asStateFlow()

    /**
     * The DCP camera profile retained for the next develop re-render; null = off (no camera
     * corrections). Only the CFA-stage BaselineExposure scalar is applied today (the colour
     * matrix / HSD / Tone / Look belong to the RGB calibration stage and are out of scope here).
     */
    private val cameraProfileState = MutableStateFlow<CameraProfileParams?>(null)
    val cameraProfile: StateFlow<CameraProfileParams?> = cameraProfileState.asStateFlow()

    /**
     * The LCP lens profile retained for the next develop re-render; null = off (no lens
     * corrections). Vignette + distortion are colour-independent and applied directly to the
     * single-channel CFA mosaic. `focalLength` is required by the LCP model but rawler's decoded
     * `RawImage` does not surface it, so a fixed fallback (mm) is used until an EXIF accessor lands.
     */
    private val lensProfileState = MutableStateFlow<LensProfileParams?>(null)
    val lensProfile: StateFlow<LensProfileParams?> = lensProfileState.asStateFlow()

    /** User-specified LCP focal length (mm) — top priority in the effective-focal chain. `null` = not specified. */
    private val userLcpFocalLengthMmState = MutableStateFlow<Float?>(null)
    val userLcpFocalLengthMm: StateFlow<Float?> = userLcpFocalLengthMmState.asStateFlow()

    /** Capture focal length (mm) decoded from the RAW EXIF — second priority. `null` = decoder surfaced none. */
    private val rawFocalLengthMmState = MutableStateFlow<Float?>(null)
    val rawFocalLengthMm: StateFlow<Float?> = rawFocalLengthMmState.asStateFlow()

    /** Fallback focal length (mm) — mirrors the native `DEFAULT_LCP_FOCAL_MM` (rawler_fotlab::develop). Used as the
     * last-resort LCP focal and as the Studio "指定焦距" dialog hint. */
    private const val DEFAULT_LCP_FOCAL_MM = 50.0f
    val defaultLcpFocalMm: Float = DEFAULT_LCP_FOCAL_MM

    /**
     * Re-render with out-of-gamut clipping [enabled] (Studio Clipping dialog). Every component of
     * the linear ProPhoto-D50 buffer is clamped into 0..1 as the last native step, i.e. before it
     * reaches rawalchemy, so the graded output can no longer show the >1 excursions that the
     * unclipped editing branch carried.
     *
     * The render goes through the resident trunk because clipping is a property of the *working-space*
     * projection, which sits below the cache boundary: with grading off it is simply not applied, so
     * toggling the switch is a visually identical re-render 鈥?and with grading on it changes the
     * buffer rawalchemy receives, which is exactly the intent.
     */
    fun setClipToGamut(enabled: Boolean) {
        currentClipToGamut = enabled
        requestRender()
    }

    /**
     * Re-develop the current RAW with a denoise [strength] (sensitivity multiplier on the detection
     * threshold) entered from the Studio Denoise dialog, keeping the current demosaic algorithm,
     * exposure, white balance and dehaze. `null` (or 0) is the identity — [DevelopParams.denoiseStrength]
     * is `None`/0, so the stage is skipped; the canvas is re-rendered from the re-developed PNG.
     */
    fun setDenoise(impulse: Float?, bm3d: Float?) {
        currentDenoiseStrength = impulse
        currentDenoiseBm3dStrength = bm3d
        requestRender()
    }

    /**
     * Re-develop the current RAW with a dehaze [strength] (0..1 blend) and [percentile] (0..1 haze-floor
     * quantile) entered from the Studio Dehaze dialog, keeping the current demosaic algorithm, exposure,
     * white balance and denoise. Both are required for the stage to take effect: [DevelopParams.dehazeStrength]
     * `None`/0 makes the whole dehaze an identity, so the blend must be set alongside the percentile.
     * The canvas is re-rendered from the re-developed PNG.
     */
    fun setDehaze(strength: Float?, percentile: Float?, radiusDark: Int?, radiusGuide: Int?, mergeMode: DehazeMergeMode) {
        currentDehazeStrength = strength
        currentDehazePercentile = percentile
        currentDehazeRadiusDark = radiusDark
        currentDehazeRadiusGuide = radiusGuide
        currentDehazeMergeMode = mergeMode
        requestRender()
    }

    /**
     * Re-develop the current RAW with [ca] chromatic-aberration settings entered from the Studio
     * LCA dialog, keeping the current demosaic algorithm, exposure, white balance, denoise and
     * dehaze. `null` (the dialog switch OFF) is the identity — [DevelopParams.ca] is `None`, so the
     * stage is skipped. The canvas is re-rendered from the re-developed PNG.
     */
    fun setCa(ca: CaSettings?) {
        currentCa = ca
        requestRender()
    }

    /**
     * Re-render with [unpurple] purple-fringe (ACA) settings entered from the Studio ACA dialog.
     * `null` (the dialog switch OFF) is the identity — [DevelopParams.defringeProphoto] is `None`,
     * so the stage is skipped. A non-null value **engages the grade**, because the core runs on the
     * ProPhoto-D50 editing buffer that only the graded fork produces; the canvas is re-rendered from
     * the re-developed PNG.
     */
    fun setUnpurple(unpurple: UnpurpleSettings?) {
        currentUnpurple = unpurple
        requestRender()
    }

    /**
     * Re-develop with a DCP camera profile [profile] (picked from the Studio DCP dropdown), keeping
     * the current demosaic algorithm, exposure, white balance, denoise, dehaze and lens profile.
     * `null` (the dropdown's "none" item) is the identity — [DevelopParams.cameraProfile] is `None`,
     * so the deprofile stage is skipped. The canvas is re-rendered from the re-developed PNG.
     */
    fun setCameraProfile(profile: CameraProfileParams?) {
        cameraProfileState.value = profile
        requestRender()
    }

    /** Remove the DCP camera profile (the "none" item in the DCP dropdown). */
    fun clearCameraProfile() {
        setCameraProfile(null)
    }

    /**
     * Accept a SAF-picked DCP [uri] (no format restriction — rawtherapee_fotlab validates the bytes as
     * a `.dcp`). The native deprofile stage only reads real filesystem paths, never `content://` URIs,
     * so the bytes are copied into an app-private cache file (content-addressed) and that path is what
     * crosses the FFI. A copy failure is reported via [gradeError] without changing the selection; a
     * bad DCP surfaces when the native develop runs.
     */
    fun setCameraProfileUri(uri: Uri) {
        val token = loadNonce.get()
        scope.launch {
            val copied = runCatching { copyProfileToCache(uri, Constants.DCP_CACHE_DIR) }.getOrNull()
            if (copied == null) {
                gradeErrorState.value = appContext.getString(R.string.studio_dcp_error_copy)
                return@launch
            }
            if (loadNonce.get() != token) return@launch
            cameraProfileState.value = CameraProfileParams(copied.second, true)
            requestRender()
        }
    }

    /**
     * Re-develop with an LCP lens profile [profile] (picked from the Studio LCP dropdown), keeping the
     * current demosaic algorithm, exposure, white balance, denoise, dehaze and camera profile. `null`
     * (the dropdown's "none" item) is the identity — [DevelopParams.lensProfile] is `None`, so the
     * deprofile stage is skipped.
     */
    fun setLensProfile(profile: LensProfileParams?) {
        lensProfileState.value = profile
        requestRender()
    }

    /** Remove the LCP lens profile (the "none" item in the LCP dropdown). Also drops any user-specified focal override. */
    fun clearLensProfile() {
        userLcpFocalLengthMmState.value = null
        setLensProfile(null)
    }

    /**
     * Set the user-specified LCP focal length (mm) from the Studio "指定焦距" dialog. This is the
     * **top** priority in the LCP effective-focal chain: it wins over the decoded-RAW focal, the LCP
     * built-in focal, and [DEFAULT_LCP_FOCAL_MM]. `null` clears the override so the effective focal
     * falls back to the next source. Re-develops only when an LCP is active so the new focal takes
     * effect on the canvas.
     */
    fun setLensProfileUserFocal(mm: Float?) {
        userLcpFocalLengthMmState.value = mm
        if (lensProfileState.value != null) requestRender()
    }

    /**
     * Accept a SAF-picked LCP [uri] (no format restriction — rawtherapee_fotlab validates the bytes as
     * a `.lcp`). Copied into an app-private cache like the DCP/LUT pickers; vignette + distortion are
     * both enabled so the single-channel correction actually runs. The effective focal length fed to
     * the LCP model resolves across a priority chain: a user-specified focal (Studio "指定焦距"
     * dialog) wins, then the focal decoded from the RAW EXIF, then the focal the LCP file itself
     * carries, and finally [DEFAULT_LCP_FOCAL_MM] (see [lensProfileState] / the native `develop` docs).
     */
    fun setLensProfileUri(uri: Uri) {
        val token = loadNonce.get()
        scope.launch {
            val copied = runCatching { copyProfileToCache(uri, Constants.LCP_CACHE_DIR) }.getOrNull()
            if (copied == null) {
                gradeErrorState.value = appContext.getString(R.string.studio_lcp_error_copy)
                return@launch
            }
            if (loadNonce.get() != token) return@launch
            lensProfileState.value = LensProfileParams(
                path = copied.second,
                applyVignette = true,
                applyDistortion = true,
                focalLength = userLcpFocalLengthMmState.value,
            )
            requestRender()
        }
    }

    /** The no-resident-image fallback for [requestRender]: re-decodes from scratch. */
    private fun reDevelopStateless() {
        val uri = currentUri ?: return
        val token = loadNonce.get()
        renderResultState.value = StudioRenderResult.Loading
        inFlight.incrementAndGet()
        pipelineRunningState.value = true
        val job = scope.launch {
            val result = runDevelop(appContext.contentResolver, uri, token, currentAlgorithm, currentExposureEv)
            if (loadNonce.get() == token) {
                renderResultState.value = result
                if (result is StudioRenderResult.Ready) displayedResultState.value = result
            }
        }
        runningJob = job
        job.invokeOnCompletion { if (inFlight.decrementAndGet() == 0) pipelineRunningState.value = false }
    }

    /**
     * Cooperatively stop the in-flight render. The native decode is a blocking call that cannot be
     * interrupted mid-execution, so this does not abort the native compute — it invalidates the
     * in-flight token (via [loadNonce]) so the finished result is never applied, cancels the
     * coroutine, and drops the running flag. The canvas keeps showing [displayedResult], i.e. the
     * last good frame that was not replaced, which is exactly the pre-stop image.
     */
    fun stopPipeline() {
        if (inFlight.get() == 0) return
        loadNonce.incrementAndGet()
        runningJob?.cancel()
        // The cancelled / invalidated jobs' invokeOnCompletion drives inFlight back to 0 and clears
        // pipelineRunning; the held frame is intentionally kept so the stop restores the last image.
        // If nothing was held (a stop pressed mid initial decode), fall back to Idle so the canvas
        // shows the open prompt instead of a stuck "decoding" state.
        if (displayedResultState.value == null) renderResultState.value = StudioRenderResult.Idle
    }

    /**
     * The stateless develop fallback, used only when no resident image is held.
     *
     * A routed RAW always goes through [requestRender] and its resident handle, so reaching here
     * means the source is not a resident RAW (a build without the `.so`, or a route that never
     * produced one) and the only thing available is [RawDecoder.developToPng], which re-reads and
     * re-decodes the bytes. There is no cache to consult on this path — and no develop-stage state
     * beyond the algorithm and exposure, which is exactly what that older interface carries.
     */
    private suspend fun runDevelop(
        resolver: ContentResolver,
        uri: Uri,
        token: Long,
        algorithm: DemosaicAlgorithm,
        exposureEv: Float?,
    ): StudioRenderResult {
        // If the file was switched while we were about to develop, bail — never develop a different
        // file's pixels (FOTLAB-RAWLER-000004 搂lifecycle: exactly one handle per current file).
        if (loadNonce.get() != token) return StudioRenderResult.Unsupported
        val png = rawDecoder.developToPng(currentFormat ?: "", algorithm, exposureEv) {
            resolver.openInputStream(uri) ?: error("cannot open source")
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
