package io.github.fotlab.fotlab.feature.studio

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import io.github.fotlab.fotlab.R
import io.github.fotlab.fotlab.feature.library.LibraryCore
import io.github.fotlab.fotlab.feature.studio.StudioRenderResult
import io.github.fotlab.fotlab.media.MediaPreference
import io.github.fotlab.fotlab.ui.ZoomableAsyncImage
import io.github.fotlab.fotlab.ui.icons.CustomMaterialStyleIcons
import io.github.fotlab.fotlab.ui.icons.MeteringCenterAsterisk
import io.github.fotlab.fotlab.ui.icons.MeteringCenterAsteriskMatrix
import io.github.fotlab.fotlab.ui.icons.MeteringCenterWeighted
import io.github.fotlab.fotlab.ui.icons.MeteringMatrixAverage
import io.github.fotlab.fotlab.ui.icons.MeteringMatrixSpot
import io.github.fotlab.fotlab.ui.operation.HorizontalOperationBar
import io.github.fotlab.fotlab.ui.operation.OperationalButton
import io.github.fotlab.fotlab.ui.rememberZoomState
import io.github.fotlab.fotlab_rawler.CaSettings
import io.github.fotlab.fotlab_rawler.DehazeMergeMode
import io.github.fotlab.fotlab_rawler.UnpurpleSettings
import io.github.fotlab.fotlab_rawler.OutputTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Studio screen (UI) — a Snapseed-style editor and the second independent screen, owned by the
 * `feature/studio` package alongside its lower layer [StudioEngine] (`FOTLAB-STRUCT-000001`).
 *
 * Like every screen it fills the whole region below the shell's nav bar and splits
 * into sibling regions: the canvas, the grade bar, and the fun bar. The regions are laid out
 * by a module-level Material3 `Scaffold` nested inside the shell's root `Scaffold` (canvas +
 * grade bar as content, fun bar in the `bottomBar` slot) — permitted by `FOTLAB-UIXDES-000002`
 * R3 under conditions (a)–(c): the drawer wraps the Scaffold, the navigation-bar inset is
 * consumed exactly once, and the bar stays module-owned. The fun bar follows the shared
 * skeleton — drawer menu at the far left (bottom-left), overflow at the far right, and a
 * file-open action just left of the overflow (`FOTLAB-UIXDES-000002`). The develop tools (Denoise / Dehaze / Exposure / Demosaic / White Balance)
 * sit as icon-only buttons right of the drawer menu — ordered Exposure (stops input) → Denoise (grain, strength input) → Dehaze (air, strength + percentile input) → Demosaic (dropdown) → White Balance (Kelvin input); the dropdowns anchor at the fun bar and therefore
 * open upward. Directly above the fun bar (RAW files only) sits the grade bar — the rawalchemy
 * fork's Boost / LOG / LUT chips (`StudioGradeBar`), its content unchanged by the layout move.
 * The module also owns its drawer; nothing here is shared with the shell.
 *
 * The open action lands the picked file in the Library directory the user is currently viewing
 * (shared app state, never the Recycle view — `LibraryCore.currentDirectoryId`) and renders it on the
 * canvas through its virtual `uri_storage` path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen() {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Per-SAF-call "last document URI" (LUT / PNG export / import). The system picker only
    // remembers one global last directory, so we persist each call's own and feed it back as
    // EXTRA_INITIAL_URI so LUT and export no longer fight over the same starting folder.
    val mediaPref = remember { MediaPreference(context) }
    val lastLutUri by mediaPref.lastLutUri.collectAsState(initial = null)
    val lastExportUri by mediaPref.lastExportUri.collectAsState(initial = null)
    val lastImportUri by mediaPref.lastImportUri.collectAsState(initial = null)
    // DCP / LCP camera & lens profile selections (drive the develop-bar icon tint) and their own
    // "last opened document" URIs, so each picker opens where the previous pick landed.
    val cameraProfile by StudioEngine.cameraProfile.collectAsState()
    val lensProfile by StudioEngine.lensProfile.collectAsState()
    val userLcpFocalLengthMm by StudioEngine.userLcpFocalLengthMm.collectAsState()
    val rawFocalLengthMm by StudioEngine.rawFocalLengthMm.collectAsState()
    val lastDcpUri by mediaPref.lastDcpUri.collectAsState(initial = null)
    val lastLcpUri by mediaPref.lastLcpUri.collectAsState(initial = null)

    val zoomState = rememberZoomState()
    val renderResult by StudioEngine.renderResult.collectAsState()
    val displayedResult by StudioEngine.displayedResult.collectAsState()
    val isPipelineRunning by StudioEngine.pipelineRunning.collectAsState()
    // Boost/LOG/LUT grade-fork state.
    val gradeSelection by StudioEngine.gradeSelection.collectAsState()
    val gradeError by StudioEngine.gradeError.collectAsState()
    // Grade (Boost/LOG/LUT) is a RAW-only fork: requestRender() is a safe no-op for non-RAW images, but
    // the former grade bar was gated on a resident RAW and we keep that contract for Tune/Style.
    val rawLoaded by StudioEngine.isRawLoaded.collectAsState()
    // Whether the PNG on the canvas went through the sRGB transfer function. Derived by the engine
    // from what the render actually is (a graded render is already log-encoded, so no gamma) and
    // shown in the Basic bar as a readout - there is nothing here for the user to choose.
    val outputTransfer by StudioEngine.outputTransfer.collectAsState()
    // Whether the resident RAW can be developed at quarter resolution, i.e. whether the
    // superpixel entry in the demosaic menu applies to it (`null` = nothing resident yet).
    val superpixelSupported by StudioEngine.superpixelSupported.collectAsState()
    // The log curve names are static per native library; read once for the LOG menu.
    val logSpaces = remember { StudioEngine.supportedLogSpaces() }
    var showUnsupported by remember { mutableStateOf(false) }
    LaunchedEffect(renderResult) {
        showUnsupported = renderResult is StudioRenderResult.Unsupported
    }
    LaunchedEffect(displayedResult) {
        if (displayedResult != null) zoomState.reset()
    }
    // Zoom / pan live here so the overflow menu's "Reset view" can snap back to the default; the
    // shared state is what makes the canvas behave exactly like the Library viewer.

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val picked = result.takeIf { it.resultCode == Activity.RESULT_OK }?.data?.data
        if (picked != null) {
            // Remember read access across process death so the next import opens here.
            persistUriPermission(context, picked, write = false)
            scope.launch {
                mediaPref.setLastImportUri(picked.toString())
                // Land the picked file in the Library directory currently on screen (shared state,
                // never Recycle), then surface it on the Studio canvas.
                LibraryCore.importUris(LibraryCore.currentDirectoryId.value, listOf(picked))
                StudioEngine.setCurrentNode(picked.toString())
            }
        }
    }

    // The 5 rawalchemy metering strategies, shown as icon buttons in the Exposure dialog. Each
    // string IS the algorithm name and is exactly the mode key rawalchemy's computeAutoGain
    // accepts, so the icon and the native meter cannot drift apart. The icon is the brand-new
    // CustomMaterialStyleIcons glyph for that strategy; the mode key drives metering, the icon is
    // only its visual label.
    val meteringModes = listOf(
        "average" to CustomMaterialStyleIcons.Filled.MeteringMatrixAverage,
        "center-weighted" to CustomMaterialStyleIcons.Filled.MeteringCenterWeighted,
        "highlight-safe" to CustomMaterialStyleIcons.Filled.MeteringCenterAsterisk,
        "hybrid" to CustomMaterialStyleIcons.Filled.MeteringCenterAsteriskMatrix,
        "matrix" to CustomMaterialStyleIcons.Filled.MeteringMatrixSpot,
    )

    var showExposureDialog by remember { mutableStateOf(false) }
    var exposureInput by remember { mutableStateOf("") }
    // True while a metering button's native auto-exposure pass is in flight; the label above the
    // metering buttons swaps to "Calculating…" for this duration and reverts once it returns.
    var isMetering by remember { mutableStateOf(false) }

    var showWhiteBalanceDialog by remember { mutableStateOf(false) }
    var whiteBalanceInput by remember { mutableStateOf("") }

    // Denoise strength dialog state (opened by the DevelopFilm bar Denoise icon). The entered
    // sensitivity multiplier is written into the develop params and re-develops the canvas.
    var showDenoiseDialog by remember { mutableStateOf(false) }
    var denoiseInput by remember { mutableStateOf("") }
    var denoiseBm3dInput by remember { mutableStateOf("") }

    // Dehaze dialog state (opened by the DevelopFilm bar Dehaze icon). The stage needs both the
    // strength (0..1 blend) and the haze-floor percentile (0..1) to take effect, so both are entered
    // and written into the develop params together.
    var showDehazeDialog by remember { mutableStateOf(false) }
    var dehazeStrengthInput by remember { mutableStateOf("") }
    var dehazePercentileInput by remember { mutableStateOf("") }
    var dehazeRadiusDarkInput by remember { mutableStateOf("") }
    var dehazeRadiusGuideInput by remember { mutableStateOf("") }
    // Dehaze merge mode (Each/Blue/Min/Avg); default Min. Carried through to the engine on OK.
    var dehazeMergeModeInput by remember { mutableStateOf(DehazeMergeMode.MIN) }
    var dehazeMergeModeMenuOpen by remember { mutableStateOf(false) }

    // LCA (chromatic-aberration correction) dialog state (opened by the DevelopFilm bar LCA icon,
    // the ClosedCaption glyph). Auto mode fits the residual-CA polynomial natively; otherwise the
    // manual radial red/blue strengths apply.
    var showCaDialog by remember { mutableStateOf(false) }
    var caEnabled by remember { mutableStateOf(false) }
    var caAuto by remember { mutableStateOf(true) }
    var caRedInput by remember { mutableStateOf("") }
    var caBlueInput by remember { mutableStateOf("") }

    // ACA (purple-fringe / unpurple) dialog state (opened by the adjustment bar's ACA icon, the
    // ClosedCaptionOff glyph, between Clipping and Contrast). The switch IS the tool: OFF hands the
    // engine null (the stage is skipped); ON builds an UnpurpleSettings from the five fields, each
    // falling back to its unpurple.ml default when blank or unparseable. Enabling it engages the
    // grade, because the core runs on the ProPhoto-D50 editing buffer.
    var showAcaDialog by remember { mutableStateOf(false) }
    var acaEnabled by remember { mutableStateOf(false) }
    var acaRadiusInput by remember { mutableStateOf("") }
    var acaIntensityInput by remember { mutableStateOf("") }
    var acaMinBrightnessInput by remember { mutableStateOf("") }
    var acaMinRatioInput by remember { mutableStateOf("") }
    var acaMaxRatioInput by remember { mutableStateOf("") }

    // Clipping dialog state (opened by the Clipping icon, the AllOut glyph).
    // The switch IS the tool — there is no numeric parameter: ON clamps every component of the
    // linear ProPhoto-D50 buffer into 0..1 as the last native step (before rawalchemy), OFF
    // leaves the editing branch wide-gamut and unclamped. The sRGB presentation PNG is
    // unaffected either way, so the switch only changes what the grade fork receives.
    var showClippingDialog by remember { mutableStateOf(false) }
    var showLcpFocalDialog by remember { mutableStateOf(false) }
    var lcpFocalInput by remember { mutableStateOf("") }
    var clipToGamutEnabled by remember { mutableStateOf(true) }

    // OKLab highlight-compression dialog state (opened by the Flare icon, the last button of the
    // DevelopFilm operation bar). The switch IS the parameter — ON inserts a lightness-driven chroma
    // roll-off in OKLab on the sRGB presentation fork's near-clipped highlights (desaturating the
    // frozen sRGB-clamp hue error); OFF leaves the pipeline untouched (bit-for-bit identity for the
    // rest of the image). Mirrors the Clipping dialog's switch-only pattern.
    var showOklabDialog by remember { mutableStateOf(false) }
    var oklabSrgbEnabled by remember { mutableStateOf(true) }
    var oklabProphotoEnabled by remember { mutableStateOf(false) }

    // Per-stage enable toggles for the develop dialogs. The switch has priority over the numeric
    // value: OFF skips the stage regardless of the field (the engine writes `null`, the native stage
    // early-returns), ON enables it and passes the value. Each is prefilled from the engine state when
    // its dialog opens.
    var exposureEnabled by remember { mutableStateOf(false) }
    // Exposure clip bounds (0..1) field text, shown as one left/right row in the Exposure
    // dialog between the enable switch and the EV field. Both fields share the switch's
    // enabled state; the pair is coerced to 0..1 and ordered (lower ≤ upper) engine-side
    // on OK. Defaults are the no-op [0, 1].
    var exposureClipLowerInput by remember { mutableStateOf("0.0") }
    var exposureClipUpperInput by remember { mutableStateOf("1.0") }
    var denoiseEnabled by remember { mutableStateOf(false) }
    var denoiseBm3dEnabled by remember { mutableStateOf(false) }
    var dehazeEnabled by remember { mutableStateOf(false) }

    // Which HorizontalOperationBar is docked in the former grade-bar slot (above the fun bar).
    // Tapping the same fun-bar category icon again hides the bar; tapping another switches to it.
    var activeBar by remember { mutableStateOf(StudioOpBar.Basic) }

    // LUT picker: deliberately `*/*` — the interaction is not format-restricted; rawalchemy decides
    // whether the picked bytes are a usable .cube LUT (and an error dialog reports it if not).
    val lutPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val picked = result.takeIf { it.resultCode == Activity.RESULT_OK }?.data?.data
        if (picked != null) {
            // Remember read access across process death so the next LUT pick opens here.
            persistUriPermission(context, picked, write = false)
            scope.launch { mediaPref.setLastLutUri(picked.toString()) }
            StudioEngine.setGradeLut(picked)
        }
    }

    // DCP camera-profile picker — same SAF "remember my last folder" mechanics as the LUT picker.
    val dcpPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val picked = result.takeIf { it.resultCode == Activity.RESULT_OK }?.data?.data
        if (picked != null) {
            persistUriPermission(context, picked, write = false)
            scope.launch { mediaPref.setLastDcpUri(picked.toString()) }
            StudioEngine.setCameraProfileUri(picked)
        }
    }

    // LCP lens-profile picker — same SAF "remember my last folder" mechanics as the LUT picker.
    val lcpPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val picked = result.takeIf { it.resultCode == Activity.RESULT_OK }?.data?.data
        if (picked != null) {
            persistUriPermission(context, picked, write = false)
            scope.launch { mediaPref.setLastLcpUri(picked.toString()) }
            StudioEngine.setLensProfileUri(picked)
        }
    }

    // Share: while an image is resident on the canvas the fun bar's open-file slot becomes a share
    // action whose drop-up menu picks the export format. CreateDocument hands the user the system
    // file manager to choose the save location and name (prefilled with the tap-time timestamp plus
    // the format's extension); the callback re-reads the canvas state and writes it through
    // Android's native bitmap encoder. The engine delivers an uncompressed PNG (rawler path) or a
    // source Uri (Coil path); both branches decode and re-encode via `Bitmap.compress`, which is the
    // platform's compression + container step. The SAF round-trip is asynchronous, so the picked
    // format is parked here and read back when the document returns.
    var pendingExportFormat by remember { mutableStateOf(StudioExportFormat.Png) }
    val shareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val target = result.takeIf { it.resultCode == Activity.RESULT_OK }?.data?.data
        if (target != null) {
            val format = pendingExportFormat
            // Remember write access across process death so the next export opens here.
            persistUriPermission(context, target, write = true)
            scope.launch { mediaPref.setLastExportUri(target.toString()) }
            val current = displayedResult
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val bytes = encodeExport(context, current, format)
                    if (bytes != null) {
                        context.contentResolver.openOutputStream(target)?.use { it.write(bytes) }
                    }
                }
            }
        }
    }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            StudioDrawer(onClose = { scope.launch { drawerState.close() } })
        },
    ) {
        // Module-level Scaffold nested inside the shell's root Scaffold (allowed by
        // FOTLAB-UIXDES-000002 R3, conditions a–c): it lives inside the drawer content subtree
        // so the drawer covers the fun bar; it consumes only the navigation-bar inset the shell
        // does not (the shell zeroed its own contentWindowInsets); the bar stays module-owned.
        Scaffold(
            contentWindowInsets = WindowInsets.navigationBars,
            bottomBar = {
                // The screen's own fun bar, menu at the bottom-left.
                StudioScreenFunBar(
                    barIsOpen = activeBar != StudioOpBar.Basic,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onExitBar = { activeBar = StudioOpBar.Basic },
                    onOpenFile = {
                        importLauncher.launch(
                            openDocumentIntent(arrayOf("*/*"), lastImportUri?.let { Uri.parse(it) }),
                        )
                    },
                    onShareFile = { format ->
                        // Prefill the system file manager with the tap-time timestamp, and start it
                        // in the folder the last export landed in (rather than the global SAF one).
                        val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
                        pendingExportFormat = format
                        shareLauncher.launch(
                            createDocumentIntent(
                                format.mimeType,
                                "$stamp.${format.extension}",
                                lastExportUri?.let { Uri.parse(it) },
                            ),
                        )
                    },
                    hasImage = displayedResult != null,
                    isPipelineRunning = isPipelineRunning,
                    onStopPipeline = { StudioEngine.stopPipeline() },
                    onResetView = { zoomState.reset() },
                    onDevelopFilm = { activeBar = activeBar.toggle(StudioOpBar.DevelopFilm) },
                    onTuneImage = { activeBar = activeBar.toggle(StudioOpBar.TuneImage) },
                    onStyleFilter = { activeBar = activeBar.toggle(StudioOpBar.StyleFilter) },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    val displayed = displayedResult
                    if (displayed != null) {
                        ZoomableAsyncImage(
                            // rawler path -> decoded PNG ByteBuffer; Coil path -> original Uri.
                            model = ImageRequest.Builder(context).data((displayed as StudioRenderResult.Ready).model).build(),
                            contentDescription = null,
                            state = zoomState,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (!isPipelineRunning) {
                        // No held frame and not running: show the idle / first-decode prompt.
                        // (While running with no frame yet, the processing overlay below covers it.)
                        when {
                            renderResult is StudioRenderResult.Loading -> Text(
                                text = stringResource(id = R.string.studio_decoding),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            else -> Text(
                                text = stringResource(id = R.string.studio_open_prompt),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // A separate overlay layer (NOT the canvas) signals an in-flight render: a
                    // translucent scrim frosts the held frame and a "Processing" text sits on top.
                    // It is removed the instant a new frame lands or the user stops the pipeline,
                    // restoring the held image. A uniform translucent mask is used on every API
                    // level (no RenderEffect gaussian blur).
                    if (isPipelineRunning) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    color = MaterialTheme.colorScheme.surface
                                        .copy(alpha = 0.5f),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(id = R.string.studio_processing),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                // Active operation bar (the former grade-bar slot, directly above the fun bar).
                // The develop / adjustment / style groups are HorizontalOperationBars selected by
                // the fun-bar category icons; exactly one is shown at a time. The bar is purely a
                // container — it is docked whenever its category is active and an image is loaded;
                // it is NEVER gated by whether a feature applies. Applicability is each tool's own
                // concern: every grade control (Contrast / Saturation / LOG / LUT / clipping /
                // OKLab) depends on a prior RAW decode, so each one disables itself via the shared
                // `rawLoaded` precondition rather than hiding the whole bar. Basic is the floor
                // only when no image is loaded (displayedResult == null) so the fun-bar menu keeps
                // a stable close target.
                val dockedBar = if (displayedResult != null) {
                    when (activeBar) {
                        StudioOpBar.Basic -> StudioOpBar.Basic
                        StudioOpBar.DevelopFilm -> StudioOpBar.DevelopFilm
                        StudioOpBar.TuneImage -> StudioOpBar.TuneImage
                        StudioOpBar.StyleFilter -> StudioOpBar.StyleFilter
                    }
                } else {
                    StudioOpBar.Basic
                }
                when (dockedBar) {
                    // Develop tools (Demosaic/Exposure/WB) were always on the fun bar.
                    StudioOpBar.DevelopFilm -> StudioOperationBarDevelopFilm(
                        demosaicCandidates = StudioEngine.demosaicCandidates,
                        superpixelSupported = superpixelSupported,
                        onAlgorithmPicked = { candidate -> StudioEngine.develop(candidate) },
                        onDenoise = {
                            denoiseEnabled = StudioEngine.currentDenoiseStrength() != null
                            denoiseInput = StudioEngine.currentDenoiseStrength()?.toString() ?: ""
                            denoiseBm3dEnabled = StudioEngine.currentDenoiseBm3dStrength() != null
                            denoiseBm3dInput = StudioEngine.currentDenoiseBm3dStrength()?.toString() ?: ""
                            showDenoiseDialog = true
                        },
                        onDehaze = {
                            dehazeEnabled = StudioEngine.currentDehazeStrength() != null
                            dehazeStrengthInput = StudioEngine.currentDehazeStrength()?.toString() ?: ""
                            dehazePercentileInput = StudioEngine.currentDehazePercentile()?.toString() ?: ""
                            dehazeRadiusDarkInput = StudioEngine.currentDehazeRadiusDark()?.toString() ?: ""
                            dehazeRadiusGuideInput = StudioEngine.currentDehazeRadiusGuide()?.toString() ?: ""
                            dehazeMergeModeInput = StudioEngine.currentDehazeMergeMode()
                            showDehazeDialog = true
                        },
                        onCa = {
                            StudioEngine.currentCa()?.let { ca ->
                                caEnabled = true
                                caAuto = ca.auto
                                caRedInput = ca.red.toString()
                                caBlueInput = ca.blue.toString()
                            } ?: run {
                                caEnabled = false
                                caAuto = true
                                caRedInput = ""
                                caBlueInput = ""
                            }
                            showCaDialog = true
                        },
                        onExposure = {
                            exposureEnabled = StudioEngine.currentExposureEv() != null
                            exposureInput = StudioEngine.currentExposureEv()?.toString() ?: ""
                            val (clipLower, clipUpper) = StudioEngine.currentExposureClip()
                            exposureClipLowerInput = clipLower.toString()
                            exposureClipUpperInput = clipUpper.toString()
                            showExposureDialog = true
                        },
                        onWhiteBalance = {
                            val kelvin = StudioEngine.currentWhiteBalanceKelvin()
                            whiteBalanceInput =
                                if (kelvin > 0f) kelvin.roundToInt().toString() else ""
                            showWhiteBalanceDialog = true
                        },
                    )
                    // Grade tools (Contrast/Saturation/LOG/LUT) depend on a prior RAW decode; each
                    // button self-disables via rawLoaded, so the bar stays docked and the tools
                    // govern their own applicability.
                    StudioOpBar.TuneImage -> StudioOperationBarTuneImage(
                        rawLoaded = rawLoaded,
                        onOklabHighlight = {
                            oklabSrgbEnabled = StudioEngine.currentOklabHighlightCompressSrgb()
                            oklabProphotoEnabled = StudioEngine.currentOklabHighlightCompressProphoto()
                            showOklabDialog = true
                        },
                        onClipping = {
                            clipToGamutEnabled = StudioEngine.currentClipToGamut()
                            showClippingDialog = true
                        },
                        onLoca = {
                            StudioEngine.currentUnpurple()?.let { unpurple ->
                                acaEnabled = true
                                acaRadiusInput = unpurple.radius.toString()
                                acaIntensityInput = unpurple.intensity.toString()
                                acaMinBrightnessInput = unpurple.minBrightness.toString()
                                acaMinRatioInput = unpurple.minRedToBlueRatio.toString()
                                acaMaxRatioInput = unpurple.maxRedToBlueRatio.toString()
                            } ?: run {
                                acaEnabled = false
                                acaRadiusInput = ""
                                acaIntensityInput = ""
                                acaMinBrightnessInput = ""
                                acaMinRatioInput = ""
                                acaMaxRatioInput = ""
                            }
                            showAcaDialog = true
                        },
                        contrast = gradeSelection.contrast,
                        saturation = gradeSelection.saturation,
                        onContrast = StudioEngine::setGradeContrast,
                        onSaturation = StudioEngine::setGradeSaturation,
                    )
                    StudioOpBar.StyleFilter -> StudioOperationBarStyleFilter(
                        rawLoaded = rawLoaded,
                        logSpace = gradeSelection.logSpace,
                        lutName = gradeSelection.lutName,
                        logSpaces = logSpaces,
                        onLogSpace = StudioEngine::setGradeLogSpace,
                        onPickLut = {
                            lutPickerLauncher.launch(
                                openDocumentIntent(arrayOf("*/*"), lastLutUri?.let { Uri.parse(it) }),
                            )
                        },
                        onClearLut = StudioEngine::clearGradeLut,
                    )
                    // Basic bar: the floor — a read-only RAW status indicator followed by the
                    // camera/lens profile controls (DCP/LCP), which are global to any loaded image.
                    StudioOpBar.Basic -> HorizontalOperationBar(
                        items = listOf(
                            OperationalButton(
                                id = "raw_status",
                                label = stringResource(id = R.string.studio_label_format),
                            ) { slotModifier ->
                                RawStatusButton(
                                    isOn = displayedResult != null && rawLoaded,
                                    modifier = slotModifier,
                                )
                            },
                            OperationalButton(
                                id = "lcp",
                                label = stringResource(id = R.string.studio_label_lcp),
                            ) {
                                LcpButton(
                                    active = lensProfile != null,
                                    onPick = {
                                        lcpPickerLauncher.launch(
                                            openDocumentIntent(arrayOf("*/*"), lastLcpUri?.let { Uri.parse(it) }),
                                        )
                                    },
                                    onClear = StudioEngine::clearLensProfile,
                                    onFocal = {
                                        lcpFocalInput = userLcpFocalLengthMm?.let { "%.0f".format(it) } ?: ""
                                        showLcpFocalDialog = true
                                    },
                                    currentUserFocal = userLcpFocalLengthMm,
                                )
                            },
                            OperationalButton(
                                id = "transfer",
                                label = stringResource(
                                    id = if (outputTransfer == OutputTransfer.GAMMA) {
                                        R.string.studio_transfer_gamma
                                    } else {
                                        R.string.studio_transfer_linear
                                    },
                                ),
                            ) { slotModifier ->
                                OutputTransferStatusButton(
                                    isGamma = outputTransfer == OutputTransfer.GAMMA,
                                    modifier = slotModifier,
                                )
                            },
                            OperationalButton(
                                id = "dcp",
                                label = stringResource(id = R.string.studio_label_dcp),
                            ) {
                                DcpButton(
                                    active = cameraProfile != null,
                                    onPick = {
                                        dcpPickerLauncher.launch(
                                            openDocumentIntent(arrayOf("*/*"), lastDcpUri?.let { Uri.parse(it) }),
                                        )
                                    },
                                    onClear = StudioEngine::clearCameraProfile,
                                )
                            },
                        ),
                    )
                }
            }
        }
    }

    if (showLcpFocalDialog) {
        LcpFocalDialog(
            value = lcpFocalInput,
            onValueChange = { lcpFocalInput = it.filter { ch -> ch.isDigit() || ch == '.' } },
            rawFocalLengthMm = rawFocalLengthMm,
            onConfirm = {
                StudioEngine.setLensProfileUserFocal(lcpFocalInput.toFloatOrNull())
                showLcpFocalDialog = false
            },
            onDismiss = { showLcpFocalDialog = false },
        )
    }

    if (showUnsupported) {
        UnsupportedDialog(onDismiss = { showUnsupported = false })
    }

    if (showCaDialog) {
        CaDialog(
            enabled = caEnabled,
            onEnabledChange = { caEnabled = it },
            auto = caAuto,
            onAutoChange = { caAuto = it },
            red = caRedInput,
            onRedChange = { caRedInput = it },
            blue = caBlueInput,
            onBlueChange = { caBlueInput = it },
            onConfirm = {
                if (caEnabled) {
                    StudioEngine.setCa(
                        CaSettings(
                            enabled = caEnabled,
                            auto = caAuto,
                            red = caRedInput.toFloatOrNull() ?: 0f,
                            blue = caBlueInput.toFloatOrNull() ?: 0f,
                            avoidColourshift = false,
                        ),
                    )
                } else {
                    StudioEngine.setCa(null)
                }
                showCaDialog = false
            },
            onDismiss = { showCaDialog = false },
        )
    }

    if (showAcaDialog) {
        AcaDialog(
            enabled = acaEnabled,
            onEnabledChange = { acaEnabled = it },
            radius = acaRadiusInput,
            onRadiusChange = { acaRadiusInput = it },
            intensity = acaIntensityInput,
            onIntensityChange = { acaIntensityInput = it },
            minBrightness = acaMinBrightnessInput,
            onMinBrightnessChange = { acaMinBrightnessInput = it },
            minRedToBlueRatio = acaMinRatioInput,
            onMinRedToBlueRatioChange = { acaMinRatioInput = it },
            maxRedToBlueRatio = acaMaxRatioInput,
            onMaxRedToBlueRatioChange = { acaMaxRatioInput = it },
            onConfirm = {
                // The switch is the tool: OFF is the identity (null = stage skipped). Each blank or
                // unparseable field falls back to unpurple.ml's own default rather than blocking OK.
                StudioEngine.setUnpurple(
                    if (acaEnabled) {
                        UnpurpleSettings(
                            radius = acaRadiusInput.toDoubleOrNull() ?: 5.0,
                            intensity = acaIntensityInput.toDoubleOrNull() ?: 1.0,
                            minBrightness = acaMinBrightnessInput.toDoubleOrNull() ?: 0.0,
                            minRedToBlueRatio = acaMinRatioInput.toDoubleOrNull() ?: 0.0,
                            maxRedToBlueRatio = acaMaxRatioInput.toDoubleOrNull() ?: 0.33,
                        )
                    } else {
                        null
                    },
                )
                showAcaDialog = false
            },
            onDismiss = { showAcaDialog = false },
        )
    }

    if (showClippingDialog) {
        ClippingDialog(
            enabled = clipToGamutEnabled,
            onEnabledChange = { clipToGamutEnabled = it },
            onConfirm = {
                StudioEngine.setClipToGamut(clipToGamutEnabled)
                showClippingDialog = false
            },
            onDismiss = { showClippingDialog = false },
        )
    }

    if (showOklabDialog) {
        OklabDialog(
            srgbEnabled = oklabSrgbEnabled,
            onSrgbEnabledChange = { oklabSrgbEnabled = it },
            prophotoEnabled = oklabProphotoEnabled,
            onProphotoEnabledChange = { oklabProphotoEnabled = it },
            onConfirm = {
                StudioEngine.setOklabHighlightCompressSrgb(oklabSrgbEnabled)
                StudioEngine.setOklabHighlightCompressProphoto(oklabProphotoEnabled)
                showOklabDialog = false
            },
            onDismiss = { showOklabDialog = false },
        )
    }

    if (showExposureDialog) {
        ExposureDialog(
            enabled = exposureEnabled,
            onEnabledChange = { exposureEnabled = it },
            ev = exposureInput,
            onEvChange = { exposureInput = it },
            clipLower = exposureClipLowerInput,
            onClipLowerChange = { exposureClipLowerInput = it },
            clipUpper = exposureClipUpperInput,
            onClipUpperChange = { exposureClipUpperInput = it },
            isMetering = isMetering,
            meteringModes = meteringModes,
            onMeter = { mode ->
                isMetering = true
                scope.launch(Dispatchers.IO) {
                    val ev = StudioEngine.meterAutoExposure(mode)
                    isMetering = false
                    ev?.let { exposureInput = it.toString() }
                }
            },
            onConfirm = {
                StudioEngine.setExposure(
                    ev = if (exposureEnabled) exposureInput.toFloatOrNull() else null,
                    clipLower = exposureClipLowerInput.toFloatOrNull() ?: 0f,
                    clipUpper = exposureClipUpperInput.toFloatOrNull() ?: 1f,
                )
                showExposureDialog = false
            },
            onDismiss = { showExposureDialog = false },
        )
    }

    if (showWhiteBalanceDialog) {
        WhiteBalanceDialog(
            value = whiteBalanceInput,
            onValueChange = { whiteBalanceInput = it },
            asShotKelvin = StudioEngine.asShotWhiteBalanceKelvin(),
            onConfirm = {
                val kelvin = whiteBalanceInput.toFloatOrNull()
                if (kelvin != null && kelvin > 0f) {
                    StudioEngine.setWhiteBalanceKelvin(kelvin)
                    showWhiteBalanceDialog = false
                }
            },
            onDismiss = { showWhiteBalanceDialog = false },
        )
    }

    if (showDenoiseDialog) {
        DenoiseDialog(
            enabled = denoiseEnabled,
            onEnabledChange = { denoiseEnabled = it },
            strength = denoiseInput,
            onStrengthChange = { denoiseInput = it },
            bm3dEnabled = denoiseBm3dEnabled,
            onBm3dEnabledChange = { denoiseBm3dEnabled = it },
            bm3dStrength = denoiseBm3dInput,
            onBm3dStrengthChange = { denoiseBm3dInput = it },
            onConfirm = {
                StudioEngine.setDenoise(
                    if (denoiseEnabled) denoiseInput.toFloatOrNull() else null,
                    if (denoiseBm3dEnabled) denoiseBm3dInput.toFloatOrNull() else null,
                )
                showDenoiseDialog = false
            },
            onDismiss = { showDenoiseDialog = false },
        )
    }

    if (showDehazeDialog) {
        DehazeDialog(
            enabled = dehazeEnabled,
            onEnabledChange = { dehazeEnabled = it },
            strength = dehazeStrengthInput,
            onStrengthChange = { dehazeStrengthInput = it },
            percentile = dehazePercentileInput,
            onPercentileChange = { dehazePercentileInput = it },
            radiusDark = dehazeRadiusDarkInput,
            onRadiusDarkChange = { dehazeRadiusDarkInput = it },
            radiusGuide = dehazeRadiusGuideInput,
            onRadiusGuideChange = { dehazeRadiusGuideInput = it },
            mergeMode = dehazeMergeModeInput,
            onMergeModeChange = { dehazeMergeModeInput = it },
            mergeMenuOpen = dehazeMergeModeMenuOpen,
            onMergeMenuOpenChange = { dehazeMergeModeMenuOpen = it },
            onConfirm = {
                if (dehazeEnabled) {
                    StudioEngine.setDehaze(
                        dehazeStrengthInput.toFloatOrNull(),
                        dehazePercentileInput.toFloatOrNull(),
                        dehazeRadiusDarkInput.toIntOrNull(),
                        dehazeRadiusGuideInput.toIntOrNull(),
                        dehazeMergeModeInput,
                    )
                } else {
                    StudioEngine.setDehaze(null, null, null, null, DehazeMergeMode.MIN)
                }
                showDehazeDialog = false
            },
            onDismiss = { showDehazeDialog = false },
        )
    }

    gradeError?.let { message ->
        GradeErrorDialog(
            message = message,
            onDismiss = { StudioEngine.clearGradeError() },
        )
    }
}










