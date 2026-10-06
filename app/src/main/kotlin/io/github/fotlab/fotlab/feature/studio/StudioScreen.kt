package io.github.fotlab.fotlab.feature.studio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllOut
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RawOff
import androidx.compose.material.icons.filled.RawOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionOff
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.WbAuto
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.runtime.collectAsState
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
import io.github.fotlab.fotlab.ui.icons.MovieEdit
import io.github.fotlab.fotlab.ui.operation.HorizontalOperationBar
import io.github.fotlab.fotlab.ui.operation.OperationalButton
import io.github.fotlab.fotlab.ui.rememberZoomState
import io.github.fotlab.fotlab_rawler.CaSettings
import io.github.fotlab.fotlab_rawler.LocaSettings
import io.github.fotlab.fotlab_rawler.DehazeMergeMode
import io.github.fotlab.fotlab_rawler.DemosaicCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
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
    // Grade (Boost/LOG/LUT) is a RAW-only fork: reGrade() is a safe no-op for non-RAW images, but
    // the former grade bar was gated on a resident RAW and we keep that contract for Tune/Style.
    val rawLoaded by StudioEngine.isRawLoaded.collectAsState()
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

    // LoCA (longitudinal-CA / axial fringe) dialog state (opened by the DevelopFilm bar LoCA icon,
    // the ClosedCaptionOff glyph). Only the two PEER switches are exposed to the user; the master
    // switch is derived by Kotlin: both off → loca = null (the stage is skipped), either/both on
    // → loca = Some(...). Strength / threshold fields keep the platform defaults as placeholders.
    var showLocaDialog by remember { mutableStateOf(false) }
    var locaPurpleEnabled by remember { mutableStateOf(false) }
    var locaGreenEnabled by remember { mutableStateOf(false) }
    var locaPurpleStrengthInput by remember { mutableStateOf("") }
    var locaGreenStrengthInput by remember { mutableStateOf("") }
    var locaPurpleLumInput by remember { mutableStateOf("") }
    var locaGreenLumInput by remember { mutableStateOf("") }

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
                        onLoca = {
                            StudioEngine.currentLoca()?.let { loca ->
                                locaPurpleEnabled = loca.purpleEnabled
                                locaGreenEnabled = loca.greenEnabled
                                locaPurpleStrengthInput = loca.purpleStrength.toString()
                                locaGreenStrengthInput = loca.greenStrength.toString()
                                locaPurpleLumInput = loca.purpleLumMin.toString()
                                locaGreenLumInput = loca.greenLumMin.toString()
                            } ?: run {
                                locaPurpleEnabled = false
                                locaGreenEnabled = false
                                locaPurpleStrengthInput = ""
                                locaGreenStrengthInput = ""
                                locaPurpleLumInput = ""
                                locaGreenLumInput = ""
                            }
                            showLocaDialog = true
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
        AlertDialog(
            onDismissRequest = { showLcpFocalDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Empty input → clear the override (fall back to decoded-RAW → LCP built-in → constant).
                        val mm = lcpFocalInput.toFloatOrNull()
                        StudioEngine.setLensProfileUserFocal(mm)
                        showLcpFocalDialog = false
                    },
                ) {
                    Text(text = stringResource(id = R.string.common_action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLcpFocalDialog = false }) {
                    Text(text = stringResource(id = R.string.common_action_cancel))
                }
            },
            title = { Text(text = stringResource(id = R.string.studio_lcp_focal_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(
                            id = R.string.studio_lcp_focal_hint,
                            rawFocalLengthMm?.let { "%.0f".format(it) }
                                ?: stringResource(id = R.string.studio_lcp_focal_unknown),
                            StudioEngine.defaultLcpFocalMm.toInt(),
                        ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = lcpFocalInput,
                        onValueChange = { lcpFocalInput = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text(text = stringResource(id = R.string.studio_lcp_focal_unit)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            },
        )
    }

    if (showUnsupported) {
        AlertDialog(
            onDismissRequest = { showUnsupported = false },
            confirmButton = {
                TextButton(onClick = { showUnsupported = false }) {
                    Text(text = stringResource(id = R.string.common_action_ok))
                }
            },
            title = { Text(text = stringResource(id = R.string.studio_unsupported_title)) },
            text = { Text(text = stringResource(id = R.string.studio_unsupported_format)) },
        )
    }

    // LCA dialog: the enable switch has priority over the parameters — when OFF the stage is
    // skipped (ca = null → native identity) regardless of the fields; when ON, auto mode fits the
    // residual-CA polynomial on the native side and the manual red/blue radial strengths are
    // ignored. OK is always enabled: auto mode needs no numbers, and an empty manual field parses
    // to 0 (= no shift for that channel).
    if (showCaDialog) {
        AlertDialog(
            onDismissRequest = { showCaDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
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
                ) {
                    Text(text = stringResource(id = R.string.common_action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCaDialog = false }) {
                    Text(text = stringResource(id = R.string.common_action_cancel))
                }
            },
            title = { Text(text = stringResource(id = R.string.studio_ca_title)) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(id = R.string.studio_enable_stage))
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(checked = caEnabled, onCheckedChange = { caEnabled = it })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(id = R.string.studio_ca_auto_label))
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(checked = caAuto, onCheckedChange = { caAuto = it }, enabled = caEnabled)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = caRedInput,
                        onValueChange = { caRedInput = it },
                        enabled = caEnabled && !caAuto,
                        singleLine = true,
                        placeholder = { Text(text = stringResource(id = R.string.studio_ca_red_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = caBlueInput,
                        onValueChange = { caBlueInput = it },
                        enabled = caEnabled && !caAuto,
                        singleLine = true,
                        placeholder = { Text(text = stringResource(id = R.string.studio_ca_blue_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            },
        )
    }

    // LoCA dialog: the two PEER switches (去紫边 / 去绿边) are the only user controls; strength and
    // luminance-threshold fields carry the platform defaults as placeholders and are editable only
    // while their pair switch is on. The master switch is derived, not shown: both off → loca = null
    // (identity; native short-circuit), either/both on → loca = Some(...) with `enabled` set to the
    // derived master (purpleOn || greenOn). The Rust side defaults `enabled = false` (short-circuit),
    // so a LoCA stage only ever runs when Kotlin explicitly opts in. OK is always enabled.
    if (showLocaDialog) {
        AlertDialog(
            onDismissRequest = { showLocaDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val purpleOn = locaPurpleEnabled
                        val greenOn = locaGreenEnabled
                        StudioEngine.setLoca(
                            if (purpleOn || greenOn) {
                                LocaSettings(
                                    enabled = purpleOn || greenOn,
                                    purpleEnabled = purpleOn,
                                    greenEnabled = greenOn,
                                    purpleStrength = locaPurpleStrengthInput.toFloatOrNull() ?: 1.0f,
                                    greenStrength = locaGreenStrengthInput.toFloatOrNull() ?: 1.0f,
                                    purpleLumMin = locaPurpleLumInput.toFloatOrNull() ?: 0.5f,
                                    greenLumMin = locaGreenLumInput.toFloatOrNull() ?: 0.5f,
                                )
                            } else {
                                null
                            },
                        )
                        showLocaDialog = false
                    },
                ) {
                    Text(text = stringResource(id = R.string.common_action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocaDialog = false }) {
                    Text(text = stringResource(id = R.string.common_action_cancel))
                }
            },
            title = { Text(text = stringResource(id = R.string.studio_loca_title)) },
            text = {
                Column {
                    Text(text = stringResource(id = R.string.studio_loca_body))
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(id = R.string.studio_loca_purple_label))
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(checked = locaPurpleEnabled, onCheckedChange = { locaPurpleEnabled = it })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = locaPurpleStrengthInput,
                        onValueChange = { locaPurpleStrengthInput = it },
                        enabled = locaPurpleEnabled,
                        singleLine = true,
                        placeholder = { Text(text = stringResource(id = R.string.studio_loca_purple_strength_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = locaPurpleLumInput,
                        onValueChange = { locaPurpleLumInput = it },
                        enabled = locaPurpleEnabled,
                        singleLine = true,
                        placeholder = { Text(text = stringResource(id = R.string.studio_loca_purple_lum_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(id = R.string.studio_loca_green_label))
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(checked = locaGreenEnabled, onCheckedChange = { locaGreenEnabled = it })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = locaGreenStrengthInput,
                        onValueChange = { locaGreenStrengthInput = it },
                        enabled = locaGreenEnabled,
                        singleLine = true,
                        placeholder = { Text(text = stringResource(id = R.string.studio_loca_green_strength_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = locaGreenLumInput,
                        onValueChange = { locaGreenLumInput = it },
                        enabled = locaGreenEnabled,
                        singleLine = true,
                        placeholder = { Text(text = stringResource(id = R.string.studio_loca_green_lum_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            },
        )
    }

    // Clipping dialog: the switch IS the parameter — there is no numeric field, and OK is always
    // enabled. The body states what the switch does in the engine's own terms (the boundary it
    // clips to is the D50 ProPhoto RGB cube, i.e. the working space of the editing fork), so the
    // user can tell that this is a *display/editing* clamp and not a raw-data change: the sRGB
    // presentation PNG is clipped the same way either way, and only the buffer rawalchemy grades
    // changes.
    if (showClippingDialog) {
        AlertDialog(
            onDismissRequest = { showClippingDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        StudioEngine.setClipToGamut(clipToGamutEnabled)
                        showClippingDialog = false
                    },
                ) {
                    Text(text = stringResource(id = R.string.common_action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClippingDialog = false }) {
                    Text(text = stringResource(id = R.string.common_action_cancel))
                }
            },
            title = { Text(text = stringResource(id = R.string.studio_clipping_title)) },
            text = {
                Column {
                    Text(text = stringResource(id = R.string.studio_clipping_body))
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(id = R.string.studio_enable_stage))
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(checked = clipToGamutEnabled, onCheckedChange = { clipToGamutEnabled = it })
                    }
                }
            },
        )
    }

    // OKLab highlight-compression dialog: the switch IS the parameter — there is no numeric field,
    // and OK is always enabled. The body states what the switch does in the engine's own terms: it
    // inserts a perceptual chroma roll-off on near-clipped sRGB highlights (in OKLab) so the
    // per-channel clamp no longer freezes a hue error. OFF is a pure identity for the rest of the
    // image, and the ProPhoto-D50 editing fork is untouched.
    if (showOklabDialog) {
        AlertDialog(
            onDismissRequest = { showOklabDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        StudioEngine.setOklabHighlightCompressSrgb(oklabSrgbEnabled)
                        StudioEngine.setOklabHighlightCompressProphoto(oklabProphotoEnabled)
                        showOklabDialog = false
                    },
                ) {
                    Text(text = stringResource(id = R.string.common_action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showOklabDialog = false }) {
                    Text(text = stringResource(id = R.string.common_action_cancel))
                }
            },
            title = { Text(text = stringResource(id = R.string.studio_oklab_title)) },
            text = {
                Column {
                    Text(text = stringResource(id = R.string.studio_oklab_body))
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(id = R.string.studio_oklab_srgb))
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(checked = oklabSrgbEnabled, onCheckedChange = { oklabSrgbEnabled = it })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(id = R.string.studio_oklab_prophoto))
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(checked = oklabProphotoEnabled, onCheckedChange = { oklabProphotoEnabled = it })
                    }
                }
            },
        )
    }

    // Exposure dialog: the enable switch gates *application*, not editing — the value field is always
    // editable (so a metered value can be tweaked even while the stage is off), while the clip-bound
    // row between the switch and the EV field shares the switch's enabled state. On OK, when the
    // switch is OFF the stage is skipped (exposureEv = null → native as-shot, clip included)
    // regardless of the fields; when ON the parsed stops and clip bounds are applied. OK is disabled
    // when the switch is ON and any of the three fields is not a parseable number.
    if (showExposureDialog) {
        AlertDialog(
            onDismissRequest = { showExposureDialog = false },
            confirmButton = {
                TextButton(
                    enabled = !exposureEnabled || (
                        exposureInput.toFloatOrNull() != null &&
                            exposureClipLowerInput.toFloatOrNull() != null &&
                            exposureClipUpperInput.toFloatOrNull() != null
                        ),
                    onClick = {
                        StudioEngine.setExposure(
                            ev = if (exposureEnabled) exposureInput.toFloatOrNull() else null,
                            clipLower = exposureClipLowerInput.toFloatOrNull() ?: 0f,
                            clipUpper = exposureClipUpperInput.toFloatOrNull() ?: 1f,
                        )
                        showExposureDialog = false
                    },
                ) {
                    Text(text = stringResource(id = R.string.common_action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExposureDialog = false }) {
                    Text(text = stringResource(id = R.string.common_action_cancel))
                }
            },
            title = { Text(text = stringResource(id = R.string.studio_exposure_title)) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(id = R.string.studio_enable_stage))
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(checked = exposureEnabled, onCheckedChange = { exposureEnabled = it })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // Clip-bounds row: between the enable switch and the EV field — left is the
                    // lower bound, right the upper. Both fields share the switch's enabled state;
                    // with the switch OFF the whole stage (clip included) is skipped on OK anyway.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextField(
                            value = exposureClipLowerInput,
                            onValueChange = { exposureClipLowerInput = it },
                            enabled = exposureEnabled,
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            label = { Text(text = stringResource(id = R.string.studio_exposure_clip_lower)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        TextField(
                            value = exposureClipUpperInput,
                            onValueChange = { exposureClipUpperInput = it },
                            enabled = exposureEnabled,
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            label = { Text(text = stringResource(id = R.string.studio_exposure_clip_upper)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = exposureInput,
                        onValueChange = { exposureInput = it },
                        singleLine = true,
                        placeholder = { Text(text = stringResource(id = R.string.studio_exposure_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    // Metering buttons: each meters the current image with one rawalchemy strategy and
                    // fills the returned EV into the field once (the user may still edit it). Metering
                    // only proposes a value — it works regardless of the enable switch and applies
                    // nothing; OK with the switch ON is what writes it into exposureEv.
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = stringResource(id = if (isMetering) R.string.studio_exposure_metering_calculating else R.string.studio_exposure_metering))
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        meteringModes.forEach { (mode, icon) ->
                            IconButton(
                                onClick = {
                                    // Metering develops + meters natively — off the main thread so the
                                    // dialog never blocks; the result lands in the field once it returns.
                                    isMetering = true
                                    scope.launch(Dispatchers.IO) {
                                        val ev = StudioEngine.meterAutoExposure(mode)
                                        isMetering = false
                                        ev?.let { exposureInput = it.toString() }
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = mode,
                                )
                            }
                        }
                    }
                }
            },
        )
    }

    // White-balance input dialog: opened by the top-bar WhiteBalance icon. The title carries the as-shot
    // CCT estimated from the decoded multipliers ("As-shot: xxxx K"; "–" when unavailable); the field
    // lets the user enter any target Kelvin, which is projected to camera multipliers natively and
    // re-develops the resident RAW via StudioEngine.setWhiteBalanceKelvin.
    if (showWhiteBalanceDialog) {
        val asShot = StudioEngine.asShotWhiteBalanceKelvin()
        val asShotLabel = if (asShot > 0f) asShot.roundToInt().toString() else "–"
        AlertDialog(
            onDismissRequest = { showWhiteBalanceDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val kelvin = whiteBalanceInput.toFloatOrNull()
                    if (kelvin != null && kelvin > 0f) {
                        StudioEngine.setWhiteBalanceKelvin(kelvin)
                        showWhiteBalanceDialog = false
                    }
                }) {
                    Text(text = stringResource(id = R.string.common_action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showWhiteBalanceDialog = false }) {
                    Text(text = stringResource(id = R.string.common_action_cancel))
                }
            },
            title = { Text(text = stringResource(id = R.string.studio_wb_title, asShotLabel)) },
            text = {
                TextField(
                    value = whiteBalanceInput,
                    onValueChange = { whiteBalanceInput = it },
                    singleLine = true,
                    placeholder = { Text(text = stringResource(id = R.string.studio_wb_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
        )
    }

    // Denoise dialog: the enable switch has priority over the strength value — when OFF the stage is
    // skipped (denoiseStrength = null → native identity) regardless of the field; when ON the parsed
    // sensitivity multiplier is applied. OK is disabled unless the switch is ON with a parseable number.
    if (showDenoiseDialog) {
        AlertDialog(
            onDismissRequest = { showDenoiseDialog = false },
            confirmButton = {
                TextButton(
                    enabled = (!denoiseEnabled || denoiseInput.toFloatOrNull() != null) &&
                        (!denoiseBm3dEnabled || denoiseBm3dInput.toFloatOrNull() != null),
                    onClick = {
                        StudioEngine.setDenoise(
                            if (denoiseEnabled) denoiseInput.toFloatOrNull() else null,
                            if (denoiseBm3dEnabled) denoiseBm3dInput.toFloatOrNull() else null,
                        )
                        showDenoiseDialog = false
                    },
                ) {
                    Text(text = stringResource(id = R.string.common_action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDenoiseDialog = false }) {
                    Text(text = stringResource(id = R.string.common_action_cancel))
                }
            },
            title = { Text(text = stringResource(id = R.string.studio_denoise_title)) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(id = R.string.studio_enable_stage))
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(checked = denoiseEnabled, onCheckedChange = { denoiseEnabled = it })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = denoiseInput,
                        onValueChange = { denoiseInput = it },
                        enabled = denoiseEnabled,
                        singleLine = true,
                        placeholder = { Text(text = stringResource(id = R.string.studio_denoise_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(id = R.string.studio_denoise_bm3d_label))
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(checked = denoiseBm3dEnabled, onCheckedChange = { denoiseBm3dEnabled = it })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = denoiseBm3dInput,
                        onValueChange = { denoiseBm3dInput = it },
                        enabled = denoiseBm3dEnabled,
                        singleLine = true,
                        placeholder = { Text(text = stringResource(id = R.string.studio_denoise_bm3d_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            },
        )
    }

    // Dehaze dialog: the enable switch has priority over the strength / percentile values — when OFF
    // the stage is skipped (dehazeStrength = null → native identity) regardless of the fields; when ON
    // both the blend and the haze-floor percentile are applied. OK is disabled unless the switch is ON
    // with both fields parseable.
    if (showDehazeDialog) {
        AlertDialog(
            onDismissRequest = { showDehazeDialog = false },
            confirmButton = {
                TextButton(
                    enabled = !dehazeEnabled ||
                        (dehazeStrengthInput.toFloatOrNull() != null && dehazePercentileInput.toFloatOrNull() != null),
                    onClick = {
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
                ) {
                    Text(text = stringResource(id = R.string.common_action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDehazeDialog = false }) {
                    Text(text = stringResource(id = R.string.common_action_cancel))
                }
            },
            title = { Text(text = stringResource(id = R.string.studio_dehaze_title)) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(id = R.string.studio_enable_stage))
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(checked = dehazeEnabled, onCheckedChange = { dehazeEnabled = it })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = dehazeStrengthInput,
                        onValueChange = { dehazeStrengthInput = it },
                        enabled = dehazeEnabled,
                        singleLine = true,
                        placeholder = { Text(text = stringResource(id = R.string.studio_dehaze_strength_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = dehazePercentileInput,
                        onValueChange = { dehazePercentileInput = it },
                        enabled = dehazeEnabled,
                        singleLine = true,
                        placeholder = { Text(text = stringResource(id = R.string.studio_dehaze_percentile_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = dehazeRadiusDarkInput,
                        onValueChange = { dehazeRadiusDarkInput = it },
                        enabled = dehazeEnabled,
                        singleLine = true,
                        placeholder = { Text(text = stringResource(id = R.string.studio_dehaze_radius_dark_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = dehazeRadiusGuideInput,
                        onValueChange = { dehazeRadiusGuideInput = it },
                        enabled = dehazeEnabled,
                        singleLine = true,
                        placeholder = { Text(text = stringResource(id = R.string.studio_dehaze_radius_guide_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Dehaze merge mode — a clickable read-only field that expands a DropdownMenu
                    // of the four modes (Each / Blue / Min / Avg). The selection is held in
                    // `dehazeMergeModeInput` and passed to the engine on OK; default is Min.
                    Box {
                        TextField(
                            value = when (dehazeMergeModeInput) {
                                DehazeMergeMode.EACH -> stringResource(id = R.string.studio_dehaze_merge_each)
                                DehazeMergeMode.BLUE -> stringResource(id = R.string.studio_dehaze_merge_blue)
                                DehazeMergeMode.MIN -> stringResource(id = R.string.studio_dehaze_merge_min)
                                DehazeMergeMode.AVG -> stringResource(id = R.string.studio_dehaze_merge_avg)
                            },
                            onValueChange = { },
                            readOnly = true,
                            enabled = dehazeEnabled,
                            singleLine = true,
                            label = { Text(text = stringResource(id = R.string.studio_dehaze_merge_label)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = dehazeEnabled) { dehazeMergeModeMenuOpen = true },
                        )
                        DropdownMenu(
                            expanded = dehazeMergeModeMenuOpen,
                            onDismissRequest = { dehazeMergeModeMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(text = stringResource(id = R.string.studio_dehaze_merge_each)) },
                                onClick = {
                                    dehazeMergeModeInput = DehazeMergeMode.EACH
                                    dehazeMergeModeMenuOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(text = stringResource(id = R.string.studio_dehaze_merge_blue)) },
                                onClick = {
                                    dehazeMergeModeInput = DehazeMergeMode.BLUE
                                    dehazeMergeModeMenuOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(text = stringResource(id = R.string.studio_dehaze_merge_min)) },
                                onClick = {
                                    dehazeMergeModeInput = DehazeMergeMode.MIN
                                    dehazeMergeModeMenuOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(text = stringResource(id = R.string.studio_dehaze_merge_avg)) },
                                onClick = {
                                    dehazeMergeModeInput = DehazeMergeMode.AVG
                                    dehazeMergeModeMenuOpen = false
                                },
                            )
                        }
                    }
                }
            },
        )
    }

    // Grade-fork error (a picked file that is not a readable .cube LUT, or a grader failure):
    // StudioEngine already fell back to the sRGB develop presentation, so this only explains why.
    gradeError?.let { message ->
        AlertDialog(
            onDismissRequest = { StudioEngine.clearGradeError() },
            confirmButton = {
                TextButton(onClick = { StudioEngine.clearGradeError() }) {
                    Text(text = stringResource(id = R.string.common_action_ok))
                }
            },
            title = { Text(text = stringResource(id = R.string.studio_grade_error_title)) },
            text = { Text(text = message) },
        )
    }
}

/** Height of the Studio fun bar — the former M3 top app bar's 64.dp. */
private val StudioScreenFunBarHeight = 64.dp

/**
 * The Studio fun bar: the shared skeleton of `FOTLAB-UIXDES-000002`, pinned to the screen's
 * bottom edge. Left-to-right: drawer menu, then the three *category* icons that dock one of the
 * Studio operation bars in the slot above — Theaters (DevelopFilm: Exposure / Denoise / Dehaze / Demosaic / White Balance),
 * Tune (TuneImage: Contrast / Saturation) and PhotoFilter (StyleFilter: LOG / LUT); a flexible
 * gap; the
 * file-open action and the overflow (three-dot) at the far right. The develop/grade tools
 * themselves no longer live here — they are `OperationalButton`s inside the operation bars, so
 * reordering them only touches the bar's list. The bar renders no title text
 * (`FOTLAB-UIXDES-000004` R6). Anchored at the bottom edge, every dropdown opens upward — including
 * the share action's format menu, which lists the two [StudioExportFormat] branches.
 */
@Composable
private fun StudioScreenFunBar(
    barIsOpen: Boolean,
    onOpenDrawer: () -> Unit,
    onExitBar: () -> Unit,
    onOpenFile: () -> Unit,
    onShareFile: (StudioExportFormat) -> Unit,
    hasImage: Boolean,
    isPipelineRunning: Boolean,
    onStopPipeline: () -> Unit,
    onResetView: () -> Unit,
    onDevelopFilm: () -> Unit,
    onTuneImage: () -> Unit,
    onStyleFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var overflowOpen by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(StudioScreenFunBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = if (barIsOpen) onExitBar else onOpenDrawer) {
                    Icon(
                        imageVector = if (barIsOpen) Icons.Filled.Close else Icons.Filled.Menu,
                        contentDescription = stringResource(
                            id = if (barIsOpen) R.string.studio_cd_close_bar else R.string.studio_cd_drawer_open,
                        ),
                    )
                }
                IconButton(onClick = onDevelopFilm) {
                    Icon(
                        imageVector = Icons.Filled.Theaters,
                        contentDescription = stringResource(id = R.string.studio_cd_develop_film),
                    )
                }
                IconButton(onClick = onTuneImage) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = stringResource(id = R.string.studio_cd_tune_image),
                    )
                }
                IconButton(onClick = onStyleFilter) {
                    Icon(
                        imageVector = Icons.Filled.PhotoFilter,
                        contentDescription = stringResource(id = R.string.studio_cd_style_filter),
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (isPipelineRunning) {
                // A render is in flight: the slot becomes a square Stop button that cancels it.
                IconButton(onClick = onStopPipeline) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = stringResource(id = R.string.studio_cd_stop_pipeline),
                    )
                }
            } else if (hasImage) {
                // An image is resident: the open-file slot becomes the share action. Its drop-up menu
                // picks the format (PNG / JPG); the two branches are parallel — the bar hands the
                // choice back and the launcher encodes exactly that one.
                var shareMenuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { shareMenuOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.IosShare,
                            contentDescription = stringResource(id = R.string.studio_cd_share),
                        )
                    }
                    DropdownMenu(
                        expanded = shareMenuOpen,
                        onDismissRequest = { shareMenuOpen = false },
                    ) {
                        StudioExportFormat.entries.forEach { format ->
                            DropdownMenuItem(
                                text = { Text(text = stringResource(id = format.labelRes)) },
                                onClick = {
                                    shareMenuOpen = false
                                    onShareFile(format)
                                },
                            )
                        }
                    }
                }
            } else {
                IconButton(onClick = onOpenFile) {
                    Icon(
                        imageVector = Icons.Filled.AddPhotoAlternate,
                        contentDescription = stringResource(id = R.string.studio_cd_open_file),
                    )
                }
            }
            Box {
                IconButton(onClick = { overflowOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(id = R.string.studio_cd_more_options),
                    )
                }
                DropdownMenu(
                    expanded = overflowOpen,
                    onDismissRequest = { overflowOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(id = R.string.studio_reset_view)) },
                        leadingIcon = { Icon(imageVector = Icons.Filled.Refresh, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onResetView()
                        },
                    )
                }
            }
        }
    }
}

/**
 * The Studio drawer sheet: the Material3 [ModalDrawerSheet] at 80% of the module width
 * (`FOTLAB-UIXDES-000002` R3). The close button sits in the sheet's own bottom-left corner,
 * level with the fun bar's menu icon, so opening the drawer replaces that icon in place
 * (R6); the close row shares the fun bar's 64.dp height and the navigation-bar inset.
 *
 * It has no settings today. The quarter-resolution switch that used to live here is gone: rawler's
 * superpixel is a demosaic like any other, so it is an entry in the DevelopFilm bar's demosaic menu
 * next to `RAWTRP vng4` rather than a second, independent knob that could contradict the algorithm
 * choice (`rules/REVIEW/detail/OPTIMZ-PERFRM-000010.md`). The sheet stays because the fun bar's menu
 * icon opens it and R6 fixes the close affordance's position.
 *
 * TODO: drawer content — tool categories / recent edits. Module-private per `FOTLAB-UIXDES-000002` R5.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioDrawer(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth(0.8f),
    ) {
        Text(
            text = stringResource(id = R.string.app_nav_studio_label),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )

        // Push the close affordance to the bottom-left, level with the fun bar's menu icon.
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(StudioScreenFunBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(id = R.string.common_drawer_close),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Operation-bar categories and the buttons that populate them
// ---------------------------------------------------------------------------

/** The three Studio operation bars docked in the former grade-bar slot. */
private enum class StudioOpBar { DevelopFilm, TuneImage, StyleFilter, Basic }

/**
 * Toggle helper: tapping the category icon for the already-active bar closes it (falls back to the
 * Basic bar); tapping a different bar switches to it; Basic is the floor and never toggles off.
 */
private fun StudioOpBar.toggle(target: StudioOpBar): StudioOpBar =
    if (this == target) StudioOpBar.Basic else target

/**
 * Max height of the scrolling picker menus (Demosaic / LOG): five 48dp menu rows plus Material3's
 * 8dp top/bottom menu padding = 256dp.
 *
 * The cap MUST be applied through [DropdownMenu]'s own `modifier`, never by wrapping the items in
 * another scrolling `Column`: Material3 already hosts the menu content in a vertically scrolling
 * Column, and a scrollable child nested inside it is measured with unbounded height constraints,
 * crashing during layout ("Vertically scrollable component was measured with an infinity maximum
 * height constraints") before the popup is ever drawn.
 */
private val PickerMenuMaxHeight = 256.dp

/**
 * Demosaic algorithm picker (the gradient icon anchors an upward-opening dropdown).
 *
 * The entries come from the native catalogue ([StudioEngine.demosaicCandidates]), not from a list
 * written here: the menu and the pipeline read the same catalogue, so a kernel ported in
 * `rawtrp_demosaic` cannot show up in one without the other (`FOTLAB-NATIVE-000004` D5). See
 * [demosaicLabel] for how each entry's text is chosen.
 *
 * [superpixelSupported] `false` greys out the superpixel entry rather than letting the pick resolve
 * to something else: that entry is the quarter-resolution demosaic, and on a sensor that cannot run
 * it (X-Trans, Fuji-rotated) picking it would silently give the CFA default instead. `null` (no RAW
 * resident) leaves it selectable — the capability is about the image, and there is no image to
 * contradict yet.
 */
@Composable
private fun DemosaicButton(
    candidates: List<DemosaicCandidate>,
    superpixelSupported: Boolean?,
    onAlgorithmPicked: (DemosaicCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.Filled.Gradient,
                contentDescription = stringResource(id = R.string.studio_cd_demosaic),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = PickerMenuMaxHeight),
        ) {
            for (candidate in candidates) {
                DropdownMenuItem(
                    text = { Text(text = demosaicLabel(candidate)) },
                    onClick = { open = false; onAlgorithmPicked(candidate) },
                    enabled = candidate.id != SUPERPIXEL_ID || superpixelSupported != false,
                )
            }
        }
    }
}

/** The superpixel entry's catalogue id — the one pick whose applicability is sensor-dependent. */
private const val SUPERPIXEL_ID = "rawler:superpixel"

/**
 * Display text for one demosaic candidate, **always naming the source library** except for the
 * default entry.
 *
 * The menu lists two independent implementations under similar names — `amaze` and `fast` exist on
 * both sides — so an entry that did not say where it came from would be ambiguous. The native
 * catalogue already prefixes every label with `RAWLER` / `RAWTRP` (`rawtrp_demosaic::algo`); this
 * keeps that prefix while still showing the *translated* algorithm name for the entries Studio has
 * localised. The default is the one exception: it is not an algorithm but "whatever the sensor's CFA
 * calls for", so it carries no source.
 *
 * The prefix is a parameter rather than part of each translated string so the source name stays a
 * single fact: translating "RAWLER" or "RAWTRP" is not a thing, and duplicating it across five
 * strings is how the two halves would drift apart.
 */
@Composable
private fun demosaicLabel(candidate: DemosaicCandidate): String {
    val localized = when (candidate.id) {
        "rawler:default" -> return stringResource(id = R.string.studio_demosaic_default)
        "rawler:ppg" -> stringResource(id = R.string.studio_demosaic_ppg)
        "rawler:bilinear4" -> stringResource(id = R.string.studio_demosaic_bilinear4)
        "rawler:xtrans_bilinear" -> stringResource(id = R.string.studio_demosaic_xtrans)
        SUPERPIXEL_ID -> stringResource(id = R.string.studio_demosaic_superpixel)
        // A kernel ported later: no translated string exists yet, so it keeps the catalogue's own
        // already-prefixed label rather than vanishing from the menu.
        else -> return candidate.label
    }
    return stringResource(id = R.string.studio_demosaic_source_prefix, candidate.label.substringBefore(' ')) +
        " " + localized
}

/**
 * Read-only RAW status indicator for the Basic bar's first slot.
 *
 * Shows the Material `RawOn` glyph only when an image is actually held AND the
 * format sniffer routed it to the rawler RAW path ([StudioEngine.isRawLoaded]);
 * every other case — no image, or a sniffed jpeg/png handled by Coil — shows
 * `RawOff`. It is a pure status readout, so it renders a bare [Icon] (no
 * IconButton / no click handling).
 */
@Composable
private fun RawStatusButton(
    isOn: Boolean,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = if (isOn) Icons.Filled.RawOn else Icons.Filled.RawOff,
        contentDescription = stringResource(
            id = if (isOn) R.string.studio_cd_raw_on else R.string.studio_cd_raw_off,
        ),
        modifier = modifier,
    )
}

/** Exposure stops input (opens the EV dialog owned by StudioScreen). */
@Composable
private fun ExposureButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.Exposure,
            contentDescription = stringResource(id = R.string.studio_cd_exposure),
        )
    }
}

/** White-balance Kelvin input (opens the WB dialog owned by StudioScreen). */
@Composable
private fun WhiteBalanceButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.WbAuto,
            contentDescription = stringResource(id = R.string.studio_cd_whitebalance),
        )
    }
}

/** Denoise strength input (opens the Denoise dialog owned by StudioScreen). */
@Composable
private fun DenoiseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.Grain,
            contentDescription = stringResource(id = R.string.studio_cd_denoise),
        )
    }
}

/** Dehaze input (opens the Dehaze dialog owned by StudioScreen). */
@Composable
private fun DehazeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.Air,
            contentDescription = stringResource(id = R.string.studio_cd_dehaze),
        )
    }
}

/**
 * LCA (chromatic-aberration correction) parameter entry of the develop bar.
 * The ClosedCaption glyph stands for Color Correction here; the caption reads
 * LCA in every locale.
 */
@Composable
private fun CaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.ClosedCaption,
            contentDescription = stringResource(id = R.string.studio_cd_lca),
        )
    }
}

/**
 * LoCA (longitudinal / axial chromatic-aberration correction) parameter entry of the develop bar.
 * The ClosedCaptionOff glyph is the "CC disabled" mark repurposed here as the axial-fringe tool; the
 * caption reads LoCA in every locale. It opens the LoCA dialog, which exposes only the two peer
 * switches (去紫边 / 去绿边) — the master switch is derived by Kotlin from them.
 */
@Composable
private fun LocaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.ClosedCaptionOff,
            contentDescription = stringResource(id = R.string.studio_cd_loca),
        )
    }
}

/**
 * Out-of-gamut clipping switch (opens the Clipping dialog owned by StudioScreen).
 *
 * Material's *all out* glyph is the deliberate choice here: it is the "pull everything inside
 * the boundary" mark, which is exactly what the tool does to a ProPhoto buffer whose channels
 * left the 0..1 cube. Like the other DevelopFilm-bar tools it carries no state of its own — the
 * dialog's switch is the only control (`FOTLAB-UIXDES-000002`: the screen owns the dialogs).
 */
@Composable
private fun ClippingButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.AllOut,
            contentDescription = stringResource(id = R.string.studio_cd_clipping),
            tint = operationIconTint(enabled = enabled, active = false),
        )
    }
}

/**
 * OKLab highlight-compression switch (opens the OKLab dialog owned by StudioScreen).
 *
 * Material's *flare* glyph marks the perceptual highlight glow this tool tames: it inserts a
 * lightness-driven chroma roll-off in OKLab on the sRGB presentation fork's near-clipped
 * highlights, so the per-channel sRGB clamp no longer freezes a hue error. Like the other
 * tools it carries no state of its own — the dialog's switch is the only control
 * (`FOTLAB-UIXDES-000002`: the screen owns the dialogs).
 */

/**
 * Shared icon tint for operation buttons. [active] (the tool is currently applied) tints primary,
 * otherwise the default onSurfaceVariant; [enabled = false] — the tool's precondition (e.g. a
 * prior RAW decode) is not met — forces the standard Material disabled alpha so the button reads
 * as unavailable rather than merely inactive, instead of the whole bar hiding it.
 */
@Composable
private fun operationIconTint(enabled: Boolean, active: Boolean) =
    if (!enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    } else if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
private fun OklabHighlightButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.Flare,
            contentDescription = stringResource(id = R.string.studio_cd_oklab),
            tint = operationIconTint(enabled = enabled, active = false),
        )
    }
}

/**
 * Contrast parameter of the boost group. Primary tint while configured. Opens
 * [BoostParameterDialog]; the boost switch itself is derived (either parameter configured).
 */
@Composable
private fun ContrastButton(
    contrast: Float?,
    onContrast: (Float?) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, enabled = enabled, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.Contrast,
            contentDescription = stringResource(id = R.string.studio_cd_contrast),
            tint = operationIconTint(enabled = enabled, active = contrast != null),
        )
    }
    if (open) {
        BoostParameterDialog(
            titleRes = R.string.studio_contrast_title,
            currentValue = contrast,
            onApply = { onContrast(it); open = false },
            onDismiss = { open = false },
        )
    }
}

/**
 * Saturation parameter of the boost group — same shape as [ContrastButton]'s, Tonality icon.
 */
@Composable
private fun SaturationButton(
    saturation: Float?,
    onSaturation: (Float?) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {

    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, enabled = enabled, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.Tonality,
            contentDescription = stringResource(id = R.string.studio_cd_saturation),
            tint = operationIconTint(enabled = enabled, active = saturation != null),
        )
    }
    if (open) {
        BoostParameterDialog(
            titleRes = R.string.studio_saturation_title,
            currentValue = saturation,
            onApply = { onSaturation(it); open = false },
            onDismiss = { open = false },
        )
    }
}

/**
 * Boost-parameter input dialog shared by contrast and saturation: an enable switch plus one free-form
 * float field, no range limiting. The switch has priority over the value — when OFF the parameter is
 * cleared (unconfigured; when the sibling is unconfigured too the whole boost switch turns off) and
 * the field is ignored; when ON the parsed value is applied. OK is disabled while ON with a
 * non-parseable field. The two boost parameters are coupled by the engine (rawalchemy applies both
 * together, the unconfigured sibling falling back to 1.0), so each dialog only toggles its own.
 */
@Composable
private fun BoostParameterDialog(
    titleRes: Int,
    currentValue: Float?,
    onApply: (Float?) -> Unit,
    onDismiss: () -> Unit,
) {
    var enabled by remember(currentValue) { mutableStateOf(currentValue != null) }
    var input by remember(currentValue) { mutableStateOf(currentValue?.toString() ?: "") }
    val parsed = input.toFloatOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = !enabled || parsed != null,
                onClick = { onApply(if (enabled) parsed else null) },
            ) {
                Text(text = stringResource(id = R.string.common_action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.common_action_cancel))
            }
        },
        title = { Text(text = stringResource(id = titleRes)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(id = R.string.studio_enable_stage))
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    enabled = enabled,
                    singleLine = true,
                    placeholder = { Text(text = stringResource(id = R.string.studio_boost_param_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
    )
}

/**
 * LOG curve picker — none plus every curve rawalchemy enumerates. Primary tint while a curve is
 * selected. The icon is the official Material "movie_edit" glyph, reproduced first-party in
 * [CustomMaterialStyleIcons] (the frozen material-icons-extended artifact never generated it).
 */
@Composable
private fun LogButton(
    logSpace: String?,
    logSpaces: List<String>,
    onLogSpace: (String?) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val none = stringResource(id = R.string.studio_grade_none)
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(
                imageVector = CustomMaterialStyleIcons.Filled.MovieEdit,
                contentDescription = stringResource(id = R.string.studio_cd_log),
                tint = operationIconTint(enabled = enabled, active = logSpace != null),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = PickerMenuMaxHeight),
        ) {
            DropdownMenuItem(
                text = { Text(text = none) },
                onClick = { open = false; onLogSpace(null) },
            )
            for (name in logSpaces) {
                DropdownMenuItem(
                    text = { Text(text = name) },
                    onClick = { open = false; onLogSpace(name) },
                )
            }
        }
    }
}

/**
 * LUT picker — "Choose file…" (SAF) / "None (remove LUT)". Primary tint while a LUT is loaded.
 */
@Composable
private fun LutButton(
    lutName: String?,
    onPick: () -> Unit,
    onClear: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(
                imageVector = Icons.Filled.MovieFilter,
                contentDescription = stringResource(id = R.string.studio_cd_lut),
                tint = operationIconTint(enabled = enabled, active = lutName != null),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(id = R.string.studio_grade_lut_pick)) },
                onClick = { open = false; onPick() },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(id = R.string.studio_grade_lut_clear)) },
                onClick = { open = false; onClear() },
            )
        }
    }
}

/**
 * DCP camera-profile picker — "Choose file…" (SAF) / "None (remove camera correction)". Primary
 * tint while a profile is loaded (the [active] flag).
 */
@Composable
private fun DcpButton(
    active: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.Filled.PhotoCamera,
                contentDescription = stringResource(id = R.string.studio_cd_dcp),
                tint = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(id = R.string.studio_dcp_pick)) },
                onClick = { open = false; onPick() },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(id = R.string.studio_dcp_clear)) },
                onClick = { open = false; onClear() },
            )
        }
    }
}

/**
 * LCP lens-profile picker — "Choose file…" (SAF) / "None (remove lens correction)". Primary tint
 * while a profile is loaded (the [active] flag). The bar sits just above the fun bar, so Material3
 * opens this dropdown upward automatically.
 */
@Composable
private fun LcpButton(
    active: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
    onFocal: () -> Unit,
    currentUserFocal: Float? = null,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.Filled.Camera,
                contentDescription = stringResource(id = R.string.studio_cd_lcp),
                tint = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(id = R.string.studio_lcp_pick)) },
                onClick = { open = false; onPick() },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (currentUserFocal != null) {
                            stringResource(id = R.string.studio_lcp_focal_with_value, "%.0f".format(currentUserFocal))
                        } else {
                            stringResource(id = R.string.studio_lcp_focal)
                        },
                    )
                },
                onClick = { open = false; onFocal() },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(id = R.string.studio_lcp_clear)) },
                onClick = { open = false; onClear() },
            )
        }
    }
}

/**
 * DevelopFilm bar — the develop tools that used to live directly on the fun bar, now ordered
 * Exposure → Denoise → Dehaze → Demosaic → White Balance: exposure is first, the mosaic-cleaning
 * stages run before demosaic, and white balance sits after demosaic. Reordering the list below
 * reorders the bar.
 *
 * [demosaicCandidates] is the native catalogue, passed in rather than read here so the bar stays a
 * pure renderer of state the engine owns. Each tool is an icon-only `OperationalButton`; the dialogs
 * they open are owned by `StudioScreen`, so the bar itself carries no parameter UI (the function /
 * layout decoupling the screen keeps — `FOTLAB-UIXDES-000002`).
 */
@Composable
private fun StudioOperationBarDevelopFilm(
    demosaicCandidates: List<DemosaicCandidate>,
    superpixelSupported: Boolean?,
    onAlgorithmPicked: (DemosaicCandidate) -> Unit,
    onDenoise: () -> Unit,
    onDehaze: () -> Unit,
    onCa: () -> Unit,
    onLoca: () -> Unit,
    onExposure: () -> Unit,
    onWhiteBalance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalOperationBar(
        modifier = modifier,
        items = listOf(
            OperationalButton(
                id = "exposure",
                label = stringResource(id = R.string.studio_label_exposure),
            ) { ExposureButton(onExposure) },
            OperationalButton(
                id = "ca",
                label = stringResource(id = R.string.studio_label_lca),
            ) { CaButton(onCa) },
            OperationalButton(
                id = "loca",
                label = stringResource(id = R.string.studio_label_loca),
            ) { LocaButton(onLoca) },
            OperationalButton(
                id = "denoise",
                label = stringResource(id = R.string.studio_label_denoise),
            ) { DenoiseButton(onDenoise) },
            OperationalButton(
                id = "dehaze",
                label = stringResource(id = R.string.studio_label_dehaze),
            ) { DehazeButton(onDehaze) },
            OperationalButton(
                id = "demosaic",
                label = stringResource(id = R.string.studio_label_demosaic),
            ) { DemosaicButton(demosaicCandidates, superpixelSupported, onAlgorithmPicked) },
            OperationalButton(
                id = "wb",
                label = stringResource(id = R.string.studio_label_whitebalance),
            ) { WhiteBalanceButton(onWhiteBalance) },
        ),
    )
}

/** TuneImage bar — the boost group: Contrast and Saturation parameter inputs. */
@Composable
private fun StudioOperationBarTuneImage(
    rawLoaded: Boolean,
    onOklabHighlight: () -> Unit,
    onClipping: () -> Unit,
    contrast: Float?,
    saturation: Float?,
    onContrast: (Float?) -> Unit,
    onSaturation: (Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalOperationBar(
        modifier = modifier,
        items = listOf(
            OperationalButton(
                id = "oklab",
                label = stringResource(id = R.string.studio_label_oklab),
            ) { OklabHighlightButton(onOklabHighlight, enabled = rawLoaded) },
            OperationalButton(
                id = "clipping",
                label = stringResource(id = R.string.studio_label_clipping),
            ) { ClippingButton(onClipping, enabled = rawLoaded) },
            OperationalButton(
                id = "contrast",
                label = stringResource(id = R.string.studio_cd_contrast),
            ) { ContrastButton(contrast = contrast, onContrast = onContrast, enabled = rawLoaded) },
            OperationalButton(
                id = "saturation",
                label = stringResource(id = R.string.studio_cd_saturation),
            ) { SaturationButton(saturation = saturation, onSaturation = onSaturation, enabled = rawLoaded) },
        ),
    )
}

/** StyleFilter bar — LOG and LUT. */
@Composable
private fun StudioOperationBarStyleFilter(
    rawLoaded: Boolean,
    logSpace: String?,
    lutName: String?,
    logSpaces: List<String>,
    onLogSpace: (String?) -> Unit,
    onPickLut: () -> Unit,
    onClearLut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalOperationBar(
        modifier = modifier,
        items = listOf(
            OperationalButton(
                id = "log",
                label = stringResource(id = R.string.studio_label_log),
            ) {
                LogButton(
                    logSpace = logSpace,
                    logSpaces = logSpaces,
                    onLogSpace = onLogSpace,
                    enabled = rawLoaded,
                )
            },
            OperationalButton(
                id = "lut",
                label = stringResource(id = R.string.studio_label_lut),
            ) {
                LutButton(
                    lutName = lutName,
                    onPick = onPickLut,
                    onClear = onClearLut,
                    enabled = rawLoaded,
                )
            },
        ),
    )
}

// ---------------------------------------------------------------------------
// Export — the share action's format choices
// ---------------------------------------------------------------------------

/**
 * The two formats the fun bar's share menu offers. They are parallel branches, not a
 * quality/format axis: the user picks one, and the export runs exactly that encoder.
 *
 * Both go through `Bitmap.compress`, the platform's own compression + container step, so the two
 * differ only in the [Bitmap.CompressFormat] handed to it and the file's extension / MIME type.
 * PNG is lossless (the platform ignores `quality` there — 100 is the strongest deflate the public
 * API offers, as there is no API to force a zlib level). JPG is lossy: [JPG_QUALITY] is the
 * quantization hint, and Android's JPEG encoder keeps its own chroma subsampling — `compress`
 * exposes no sampling-factor control, so 4:4:4 vs 4:2:0 is the platform's call, not ours.
 */
/** `quality` for the JPEG branch (the platform ignores it for PNG). 95 is the conventional
 * "visually near-lossless" JPEG setting; the chroma subsampling is the encoder's own choice. */
private const val JPG_QUALITY: Int = 95

private enum class StudioExportFormat(
    val labelRes: Int,
    val mimeType: String,
    val extension: String,
    val compressFormat: Bitmap.CompressFormat,
    val quality: Int,
) {
    Png(R.string.studio_export_png, "image/png", "png", Bitmap.CompressFormat.PNG, 100),
    Jpg(R.string.studio_export_jpg, "image/jpeg", "jpg", Bitmap.CompressFormat.JPEG, JPG_QUALITY),
    ;
}

/**
 * Decode whatever the canvas is currently showing and re-encode it as [format], returning the
 * file bytes, or null when nothing decodable is resident.
 *
 * The engine hands back either uncompressed PNG bytes (rawler path) or the source [Uri] (Coil
 * path); both are decoded to a [Bitmap] first, then handed to `Bitmap.compress` so the file the
 * user gets is a real compressed image rather than raw samples.
 */
private fun encodeExport(
    context: Context,
    result: StudioRenderResult?,
    format: StudioExportFormat,
): ByteArray? {
    result ?: return null
    val bitmap = when (val r = result) {
        is StudioRenderResult.Ready -> when (val model = r.model) {
            is ByteBuffer -> BitmapFactory.decodeByteArray(model.array(), 0, model.array().size)
            is Uri -> context.contentResolver.openInputStream(model)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
            else -> null
        }
        else -> null
    } ?: return null
    return ByteArrayOutputStream().use { out ->
        bitmap.compress(format.compressFormat, format.quality, out)
        out.toByteArray()
    }
}

// ---------------------------------------------------------------------------
// SAF helpers — per-call "remember my last folder"
// ---------------------------------------------------------------------------

/**
 * Build an `ACTION_OPEN_DOCUMENT` intent that, when [initialUri] is non-null, starts the system
 * picker in that document's parent folder via [DocumentsContract.EXTRA_INITIAL_URI]. Passing the
 * *document* URI (not a tree) is exactly what makes the picker open where the previous pick landed,
 * which is how LUT / import keep their own independent "recent directory" instead of sharing the
 * single global SAF one.
 */
private fun openDocumentIntent(mimeTypes: Array<String>, initialUri: Uri?): Intent =
    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mimeTypes.firstOrNull() ?: "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        if (initialUri != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
    }

/**
 * Build an `ACTION_CREATE_DOCUMENT` intent (the share export) that prefills the file name via
 * [Intent.EXTRA_TITLE] and, when [initialUri] is non-null, opens the picker in that export's parent
 * folder so repeated exports stay put. [mimeType] and the title's extension both come from the
 * chosen [StudioExportFormat].
 */
private fun createDocumentIntent(mimeType: String, title: String, initialUri: Uri?): Intent =
    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mimeType
        putExtra(Intent.EXTRA_TITLE, title)
        if (initialUri != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
    }

/**
 * Take a persistable URI permission on a document the system picker just granted us, so the
 * [DocumentsContract.EXTRA_INITIAL_URI] hint survives process death. Some providers grant only
 * transient permission and throw on the persistable call — that is non-fatal, so we swallow it.
 */
private fun persistUriPermission(context: Context, uri: Uri, write: Boolean) {
    runCatching {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        context.contentResolver.takePersistableUriPermission(uri, flags)
    }
}
