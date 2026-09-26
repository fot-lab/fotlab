package io.github.fotlab.fotlab.feature.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.WbAuto
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
import io.github.fotlab.fotlab_rawler.DemosaicAlgorithm
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
 * sit as icon-only buttons right of the drawer menu — ordered Denoise (grain, strength input) → Dehaze (air, strength + percentile input) → Exposure (stops input) → Demosaic (dropdown) → White Balance (Kelvin input); the dropdowns anchor at the fun bar and therefore
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

    val zoomState = rememberZoomState()
    val renderResult by StudioEngine.renderResult.collectAsState()
    // Boost/LOG/LUT grade-fork state.
    val gradeSelection by StudioEngine.gradeSelection.collectAsState()
    val gradeError by StudioEngine.gradeError.collectAsState()
    // Grade (Boost/LOG/LUT) is a RAW-only fork: reGrade() is a safe no-op for non-RAW images, but
    // the former grade bar was gated on a resident RAW and we keep that contract for Tune/Style.
    val rawLoaded by StudioEngine.isRawLoaded.collectAsState()
    // The quarter-resolution develop switch (a preference: flipping it re-renders nothing) and
    // whether the resident RAW can actually honour it (`null` = nothing resident yet).
    val downsample by StudioEngine.downsample.collectAsState()
    val downsampleSupported by StudioEngine.downsampleSupported.collectAsState()
    // The log curve names are static per native library; read once for the LOG menu.
    val logSpaces = remember { StudioEngine.supportedLogSpaces() }
    var showUnsupported by remember { mutableStateOf(false) }
    LaunchedEffect(renderResult) {
        showUnsupported = renderResult is StudioRenderResult.Unsupported
        if (renderResult is StudioRenderResult.Ready) zoomState.reset()
    }
    // Zoom / pan live here so the overflow menu's "Reset view" can snap back to the default; the
    // shared state is what makes the canvas behave exactly like the Library viewer.

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { picked ->
        if (picked != null) {
            scope.launch {
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

    // LCA (chromatic-aberration correction) dialog state (opened by the DevelopFilm bar LCA icon,
    // the ClosedCaption glyph). Auto mode fits the residual-CA polynomial natively; otherwise the
    // manual radial red/blue strengths apply.
    var showCaDialog by remember { mutableStateOf(false) }
    var caEnabled by remember { mutableStateOf(false) }
    var caAuto by remember { mutableStateOf(true) }
    var caRedInput by remember { mutableStateOf("") }
    var caBlueInput by remember { mutableStateOf("") }

    // Per-stage enable toggles for the develop dialogs. The switch has priority over the numeric
    // value: OFF skips the stage regardless of the field (the engine writes `null`, the native stage
    // early-returns), ON enables it and passes the value. Each is prefilled from the engine state when
    // its dialog opens.
    var exposureEnabled by remember { mutableStateOf(false) }
    var denoiseEnabled by remember { mutableStateOf(false) }
    var denoiseBm3dEnabled by remember { mutableStateOf(false) }
    var dehazeEnabled by remember { mutableStateOf(false) }

    // Which HorizontalOperationBar is docked in the former grade-bar slot (above the fun bar).
    // Tapping the same fun-bar category icon again hides the bar; tapping another switches to it.
    var activeBar by remember { mutableStateOf<StudioOpBar?>(null) }

    // LUT picker: deliberately `*/*` — the interaction is not format-restricted; rawalchemy decides
    // whether the picked bytes are a usable .cube LUT (and an error dialog reports it if not).
    val lutPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { picked ->
        if (picked != null) StudioEngine.setGradeLut(picked)
    }

    // Share-as-PNG: while an image is resident on the canvas the fun bar's open-file slot becomes
    // a share action. CreateDocument hands the user the system file manager to choose the save
    // location and name (prefilled with the tap-time timestamp); the callback re-reads the canvas
    // state and writes it as a *compressed* PNG. The engine delivers an uncompressed PNG (rawler
    // path) or a source Uri (Coil path); both branches decode and re-encode through Android's
    // native PNG encoder so the exported file is actually zlib-compressed instead of huge.
    // (PNG quality is ignored by the platform, so this is the strongest lossless compression the
    // native API offers — there is no public Android API to force zlib level 9.)
    val shareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/png"),
    ) { target ->
        if (target != null) {
            val current = renderResult
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val bytes = when (val result = current) {
                        is StudioRenderResult.Ready -> when (val model = result.model) {
                            // rawler path: Rust PNG bytes are uncompressed — decode and re-encode
                            // through the native encoder to apply zlib compression.
                            is ByteBuffer -> BitmapFactory.decodeByteArray(model.array(), 0, model.array().size)
                                ?.let { bmp ->
                                    ByteArrayOutputStream().use { out ->
                                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                                        out.toByteArray()
                                    }
                                }
                            // Coil path: decode the source and re-encode with the native encoder.
                            is Uri -> context.contentResolver.openInputStream(model)?.use { input ->
                                BitmapFactory.decodeStream(input)?.let { bmp ->
                                    ByteArrayOutputStream().use { out ->
                                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                                        out.toByteArray()
                                    }
                                }
                            }
                            else -> null
                        }
                        else -> null
                    }
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
            StudioDrawer(
                downsample = downsample,
                // The switch is a preference, so it stays settable with nothing open; it is only
                // disabled when the RAW on the canvas reports it cannot downsample at all, which
                // the summary line then says out loud instead of leaving it silently inert.
                downsampleAvailable = downsampleSupported != false,
                onDownsampleChange = { StudioEngine.setDownsample(it) },
                onClose = { scope.launch { drawerState.close() } },
            )
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
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenFile = { importLauncher.launch(arrayOf("*/*")) },
                    onShareFile = {
                        // Prefill the system file manager with the tap-time timestamp.
                        val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
                        shareLauncher.launch("$stamp.png")
                    },
                    showShare = renderResult is StudioRenderResult.Ready,
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
                    when (val result = renderResult) {
                        is StudioRenderResult.Ready -> ZoomableAsyncImage(
                            // rawler path -> decoded PNG ByteBuffer; Coil path -> original Uri.
                            model = ImageRequest.Builder(context).data(result.model).build(),
                            contentDescription = null,
                            state = zoomState,
                            modifier = Modifier.fillMaxSize(),
                        )
                        is StudioRenderResult.Loading -> Text(
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

                // Active operation bar (the former grade-bar slot, directly above the fun bar).
                // The three develop/grade groups are HorizontalOperationBars selected by the
                // fun-bar category icons; only one (or none) is shown at a time. reGrade() is a
                // safe no-op for non-RAW images (loadedImage == null), so the bar is shown for any
                // rendered image and the buttons govern their own applicability.
                if (renderResult is StudioRenderResult.Ready) {
                    when (activeBar) {
                        // Develop tools (Demosaic/Exposure/WB) were always on the fun bar.
                        StudioOpBar.DevelopFilm -> StudioOperationBarDevelopFilm(
                            demosaicCandidates = StudioEngine.demosaicCandidates,
                            onAlgorithmPicked = { algo -> StudioEngine.develop(algo) },
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
                                showExposureDialog = true
                            },
                            onWhiteBalance = {
                                val kelvin = StudioEngine.currentWhiteBalanceKelvin()
                                whiteBalanceInput =
                                    if (kelvin > 0f) kelvin.roundToInt().toString() else ""
                                showWhiteBalanceDialog = true
                            },
                        )
                        // Grade tools (Contrast/Saturation/LOG/LUT) are RAW-only, like the
                        // former grade bar.
                        StudioOpBar.TuneImage -> if (rawLoaded) {
                            StudioOperationBarTuneImage(
                                contrast = gradeSelection.contrast,
                                saturation = gradeSelection.saturation,
                                onContrast = StudioEngine::setGradeContrast,
                                onSaturation = StudioEngine::setGradeSaturation,
                            )
                        }
                        StudioOpBar.StyleFilter -> if (rawLoaded) {
                            StudioOperationBarStyleFilter(
                                logSpace = gradeSelection.logSpace,
                                lutName = gradeSelection.lutName,
                                logSpaces = logSpaces,
                                onLogSpace = StudioEngine::setGradeLogSpace,
                                onPickLut = { lutPickerLauncher.launch(arrayOf("*/*")) },
                                onClearLut = StudioEngine::clearGradeLut,
                            )
                        }
                        null -> Unit
                    }
                }
            }
        }
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

    // Exposure dialog: the enable switch gates *application*, not editing — the value field is always
    // editable (so a metered value can be tweaked even while the stage is off). On OK, when the switch
    // is OFF the stage is skipped (exposureEv = null → native as-shot) regardless of the field; when ON
    // the parsed stops are applied. OK is disabled only when the switch is ON and the field is not a
    // parseable number.
    if (showExposureDialog) {
        AlertDialog(
            onDismissRequest = { showExposureDialog = false },
            confirmButton = {
                TextButton(
                    enabled = !exposureEnabled || exposureInput.toFloatOrNull() != null,
                    onClick = {
                        StudioEngine.setExposureEv(if (exposureEnabled) exposureInput.toFloatOrNull() else null)
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
                            )
                        } else {
                            StudioEngine.setDehaze(null, null, null, null)
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
 * Studio operation bars in the slot above — Theaters (DevelopFilm: Denoise / Dehaze / Exposure / Demosaic / White Balance),
 * Tune (TuneImage: Contrast / Saturation) and PhotoFilter (StyleFilter: LOG / LUT); a flexible
 * gap; the
 * file-open action and the overflow (three-dot) at the far right. The develop/grade tools
 * themselves no longer live here — they are `OperationalButton`s inside the operation bars, so
 * reordering them only touches the bar's list. The bar renders no title text
 * (`FOTLAB-UIXDES-000004` R6). Anchored at the bottom edge, every dropdown opens upward.
 */
@Composable
private fun StudioScreenFunBar(
    onOpenDrawer: () -> Unit,
    onOpenFile: () -> Unit,
    onShareFile: () -> Unit,
    showShare: Boolean,
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
                IconButton(onClick = onOpenDrawer) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = stringResource(id = R.string.studio_cd_drawer_open),
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

            if (showShare) {
                // An image is resident: the open-file slot becomes share-as-PNG.
                IconButton(onClick = onShareFile) {
                    Icon(
                        imageVector = Icons.Filled.IosShare,
                        contentDescription = stringResource(id = R.string.studio_cd_share),
                    )
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
 * The sheet's one setting today is the **quarter-resolution develop switch**: a persisted
 * preference, so flipping it deliberately does not re-render the canvas — the frame the user is
 * looking at stays put and the next develop picks the choice up. That is why the row says so in
 * its summary line (`rules/REVIEW/detail/OPTIMZ-PERFRM-000010.md`), and why
 * [downsampleAvailable] exists: a sensor that cannot use the superpixel debayer disables the
 * switch and states the reason rather than accepting a toggle it will ignore.
 *
 * TODO: drawer content — tool categories / recent edits. Module-private per `FOTLAB-UIXDES-000002` R5.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioDrawer(
    downsample: Boolean,
    downsampleAvailable: Boolean,
    onDownsampleChange: (Boolean) -> Unit,
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = R.string.studio_drawer_downsample),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (downsampleAvailable) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = stringResource(
                        id = if (downsampleAvailable) {
                            R.string.studio_drawer_downsample_summary
                        } else {
                            R.string.studio_drawer_downsample_unavailable
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = downsample,
                onCheckedChange = onDownsampleChange,
                enabled = downsampleAvailable,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

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
private enum class StudioOpBar { DevelopFilm, TuneImage, StyleFilter }

/**
 * Toggle helper: tapping the category icon for the already-active bar hides it; otherwise it
 * switches to that bar.
 */
private fun StudioOpBar?.toggle(target: StudioOpBar): StudioOpBar? =
    if (this == target) null else target

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
 * `rawtrp_demos` cannot show up in one without the other (`FOTLAB-NATIVE-000004` D5). See
 * [demosaicLabel] for how each entry's text is chosen.
 */
@Composable
private fun DemosaicButton(
    candidates: List<DemosaicCandidate>,
    onAlgorithmPicked: (DemosaicAlgorithm) -> Unit,
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
                    onClick = { open = false; onAlgorithmPicked(candidate.algorithm) },
                )
            }
        }
    }
}

/**
 * Display text for one demosaic candidate: the localised resource for the ids Studio shipped
 * before the menu became data-driven, and the catalogue's own label (`RAWTRP vng4`, `RAWLER Ppg`)
 * for anything else.
 *
 * The fallback is deliberate. The *list* is dynamic, but the four rawler entries keep their
 * translated names; for a kernel ported later only the translated string is missing, and the entry
 * degrades to its technical name rather than vanishing from the menu.
 */
@Composable
private fun demosaicLabel(candidate: DemosaicCandidate): String = when (candidate.id) {
    "rawler:default" -> stringResource(id = R.string.studio_demosaic_default)
    "rawler:ppg" -> stringResource(id = R.string.studio_demosaic_ppg)
    "rawler:bilinear4" -> stringResource(id = R.string.studio_demosaic_bilinear4)
    "rawler:xtrans_bilinear" -> stringResource(id = R.string.studio_demosaic_xtrans)
    else -> candidate.label
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
 * Contrast parameter of the boost group. Primary tint while configured. Opens
 * [BoostParameterDialog]; the boost switch itself is derived (either parameter configured).
 */
@Composable
private fun ContrastButton(
    contrast: Float?,
    onContrast: (Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.Contrast,
            contentDescription = stringResource(id = R.string.studio_cd_contrast),
            tint = if (contrast != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
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
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.Tonality,
            contentDescription = stringResource(id = R.string.studio_cd_saturation),
            tint = if (saturation != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
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
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val none = stringResource(id = R.string.studio_grade_none)
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = CustomMaterialStyleIcons.Filled.MovieEdit,
                contentDescription = stringResource(id = R.string.studio_cd_log),
                tint = if (logSpace != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
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
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.Filled.MovieFilter,
                contentDescription = stringResource(id = R.string.studio_cd_lut),
                tint = if (lutName != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
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
 * DevelopFilm bar — the develop tools that used to live directly on the fun bar, now ordered
 * Denoise → Dehaze → Exposure → Demosaic → White Balance: the mosaic-cleaning stages run before
 * demosaic, exposure is third, and white balance sits after demosaic. Reordering the list below
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
    onAlgorithmPicked: (DemosaicAlgorithm) -> Unit,
    onDenoise: () -> Unit,
    onDehaze: () -> Unit,
    onCa: () -> Unit,
    onExposure: () -> Unit,
    onWhiteBalance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalOperationBar(
        modifier = modifier,
        items = listOf(
            OperationalButton(
                id = "ca",
                label = stringResource(id = R.string.studio_label_lca),
            ) { CaButton(onCa) },
            OperationalButton(
                id = "denoise",
                label = stringResource(id = R.string.studio_label_denoise),
            ) { DenoiseButton(onDenoise) },
            OperationalButton(
                id = "dehaze",
                label = stringResource(id = R.string.studio_label_dehaze),
            ) { DehazeButton(onDehaze) },
            OperationalButton(
                id = "exposure",
                label = stringResource(id = R.string.studio_label_exposure),
            ) { ExposureButton(onExposure) },
            OperationalButton(
                id = "demosaic",
                label = stringResource(id = R.string.studio_label_demosaic),
            ) { DemosaicButton(demosaicCandidates, onAlgorithmPicked) },
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
                id = "contrast",
                label = stringResource(id = R.string.studio_cd_contrast),
            ) { ContrastButton(contrast = contrast, onContrast = onContrast) },
            OperationalButton(
                id = "saturation",
                label = stringResource(id = R.string.studio_cd_saturation),
            ) { SaturationButton(saturation = saturation, onSaturation = onSaturation) },
        ),
    )
}

/** StyleFilter bar — LOG and LUT. */
@Composable
private fun StudioOperationBarStyleFilter(
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
                LogButton(logSpace = logSpace, logSpaces = logSpaces, onLogSpace = onLogSpace)
            },
            OperationalButton(
                id = "lut",
                label = stringResource(id = R.string.studio_label_lut),
            ) {
                LutButton(lutName = lutName, onPick = onPickLut, onClear = onClearLut)
            },
        ),
    )
}
