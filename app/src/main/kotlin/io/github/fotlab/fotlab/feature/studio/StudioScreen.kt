package io.github.fotlab.fotlab.feature.studio

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import coil3.request.ImageRequest
import io.github.fotlab.fotlab.R
import io.github.fotlab.fotlab.feature.library.LibraryCore
import io.github.fotlab.fotlab.feature.studio.StudioRenderResult
import io.github.fotlab.fotlab.ui.ZoomableAsyncImage
import io.github.fotlab.fotlab.ui.icons.CustomMaterialStyleIcons
import io.github.fotlab.fotlab.ui.icons.WhiteBalanceLiteral
import io.github.fotlab.fotlab.ui.rememberZoomState
import io.github.fotlab.fotlab_rawler.DemosaicAlgorithm
import kotlinx.coroutines.launch
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
 * file-open action just left of the overflow (`FOTLAB-UIXDES-000002`). The three develop tools
 * sit as icon-only buttons right of the drawer menu — gradient (demosaic dropdown), exposure
 * (stops input) and wb-auto (Kelvin input); the dropdowns anchor at the fun bar and therefore
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
    // Boost/LOG/LUT grade-fork state; the grade bar exists only while a routed RAW is resident.
    val gradeSelection by StudioEngine.gradeSelection.collectAsState()
    val rawLoaded by StudioEngine.isRawLoaded.collectAsState()
    val gradeError by StudioEngine.gradeError.collectAsState()
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

    var showExposureDialog by remember { mutableStateOf(false) }
    var exposureInput by remember { mutableStateOf("") }

    var showWhiteBalanceDialog by remember { mutableStateOf(false) }
    var whiteBalanceInput by remember { mutableStateOf("") }

    // LUT picker: deliberately `*/*` — the interaction is not format-restricted; rawalchemy decides
    // whether the picked bytes are a usable .cube LUT (and an error dialog reports it if not).
    val lutPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { picked ->
        if (picked != null) StudioEngine.setGradeLut(picked)
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
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenFile = { importLauncher.launch(arrayOf("*/*")) },
                    onResetView = { zoomState.reset() },
                    onAlgorithmPicked = { algo -> StudioEngine.develop(algo) },
                    onExposure = {
                        exposureInput = StudioEngine.currentExposureEv().toString()
                        showExposureDialog = true
                    },
                    onWhiteBalance = {
                        // Prefill the current override, else the as-shot estimate decoded from the RAW.
                        val kelvin = StudioEngine.currentWhiteBalanceKelvin()
                        whiteBalanceInput = if (kelvin > 0f) kelvin.roundToInt().toString() else ""
                        showWhiteBalanceDialog = true
                    },
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

                // Grade bar (Boost / LOG / LUT) — the rawalchemy fork, shown only for a resident RAW.
                // The three chips start at "none"; selecting one re-grades the resident decode and the
                // graded PNG replaces the canvas (FOTLAB-RAWLER-000006). It sits directly above the
                // screen's fun bar; its content is unchanged by the layout move.
                if (rawLoaded) {
                    StudioGradeBar(
                        selection = gradeSelection,
                        logSpaces = logSpaces,
                        onBoost = StudioEngine::setGradeBoost,
                        onLogSpace = StudioEngine::setGradeLogSpace,
                        onPickLut = { lutPickerLauncher.launch(arrayOf("*/*")) },
                        onClearLut = StudioEngine::clearGradeLut,
                    )
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

    // Exposure input dialog: opened by the top-bar Exposure icon. The entered stops value is
    // written into the develop params and re-develops + re-renders the canvas (Rust applies 2^ev in
    // the linear domain before the cam->sRGB matrix).
    if (showExposureDialog) {
        AlertDialog(
            onDismissRequest = { showExposureDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val ev = exposureInput.toFloatOrNull()
                    if (ev != null) {
                        StudioEngine.setExposureEv(ev)
                        showExposureDialog = false
                    }
                }) {
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
                TextField(
                    value = exposureInput,
                    onValueChange = { exposureInput = it },
                    singleLine = true,
                    placeholder = { Text(text = stringResource(id = R.string.studio_exposure_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
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
 * The Studio fun bar: the shared skeleton of `FOTLAB-UIXDES-000002`, currently pinned to the
 * screen's bottom edge — drawer menu at the far left (bottom-left), immediately followed by the
 * three icon-only develop tools (gradient → demosaic algorithm dropdown, exposure → stops input
 * dialog, wb-auto → Kelvin input dialog); a flexible gap; the file-open action and the
 * overflow (three-dot) sit at the far right. The bar renders no title text
 * (`FOTLAB-UIXDES-000004` R6). Anchored at the bottom edge, every dropdown opens upward
 * (drop-up).
 */
@Composable
private fun StudioScreenFunBar(
    onOpenDrawer: () -> Unit,
    onOpenFile: () -> Unit,
    onResetView: () -> Unit,
    onAlgorithmPicked: (DemosaicAlgorithm) -> Unit,
    onExposure: () -> Unit,
    onWhiteBalance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var overflowOpen by remember { mutableStateOf(false) }
    var demosaicMenuOpen by remember { mutableStateOf(false) }

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
                // Demosaic: the gradient icon anchors the algorithm dropdown (opens upward here).
                Box {
                    IconButton(onClick = { demosaicMenuOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.Gradient,
                            contentDescription = stringResource(id = R.string.studio_cd_demosaic),
                        )
                    }
                    DropdownMenu(
                        expanded = demosaicMenuOpen,
                        onDismissRequest = { demosaicMenuOpen = false },
                    ) {
                        val algorithms = listOf(
                            DemosaicAlgorithm.DEFAULT to R.string.studio_demosaic_default,
                            DemosaicAlgorithm.PPG to R.string.studio_demosaic_ppg,
                            DemosaicAlgorithm.BILINEAR4_CHANNEL to R.string.studio_demosaic_bilinear4,
                            DemosaicAlgorithm.X_TRANS_BILINEAR to R.string.studio_demosaic_xtrans,
                        )
                        for ((algo, labelRes) in algorithms) {
                            DropdownMenuItem(
                                text = { Text(text = stringResource(id = labelRes)) },
                                onClick = {
                                    demosaicMenuOpen = false
                                    onAlgorithmPicked(algo)
                                },
                            )
                        }
                    }
                }
                IconButton(onClick = onExposure) {
                    Icon(
                        imageVector = Icons.Filled.Exposure,
                        contentDescription = stringResource(id = R.string.studio_cd_exposure),
                    )
                }
                IconButton(onClick = onWhiteBalance) {
                    Icon(
                        imageVector = CustomMaterialStyleIcons.Filled.WhiteBalanceLiteral,
                        contentDescription = stringResource(id = R.string.studio_cd_whitebalance),
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            IconButton(onClick = onOpenFile) {
                Icon(
                    imageVector = Icons.Filled.AddPhotoAlternate,
                    contentDescription = stringResource(id = R.string.studio_cd_open_file),
                )
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

/**
 * The Studio grade bar: the rawalchemy fork's three chips sitting directly under the canvas,
 * above Studio's own fun bar — `Boost: none`, `LOG: none`, `LUT: none` at the all-"none"
 * initial state. Each chip is a text button anchoring its own dropdown:
 *
 *  * **Boost** — two-state: none (boost explicitly OFF) / Boost (upstream's default enhancement:
 *    saturation 1.25 / contrast 1.10). Note the UI's "none" means off, NOT upstream's engine
 *    default (which is on) — the engine maps it to `enableBoost = false`.
 *  * **LOG** — none (skip gamut + log stages) plus every log curve rawalchemy accepts; the name
 *    list is enumerated natively from upstream's `LOG_SPACES`, not mirrored here.
 *  * **LUT** — "Choose file…" launches the unrestricted SAF picker (wildcard MIME filter, any
 *    file type selectable); the picked file is copied to a native-readable cache path by
 *    [StudioEngine]. none removes it. The selected file's name is shown on the chip.
 *
 * Any non-none selection re-renders the grade fork (resident RAW re-developed with the retained
 * demosaic/exposure/WB, then graded); back to all-none returns the canvas to the sRGB develop fork.
 */
@Composable
private fun StudioGradeBar(
    selection: StudioEngine.GradeSelection,
    logSpaces: List<String>,
    onBoost: (Boolean) -> Unit,
    onLogSpace: (String?) -> Unit,
    onPickLut: () -> Unit,
    onClearLut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val none = stringResource(id = R.string.studio_grade_none)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            GradeChip(
                label = stringResource(
                    id = R.string.studio_grade_bar_boost,
                    if (selection.boost) stringResource(id = R.string.studio_grade_boost_on) else none,
                ),
            ) { dismiss ->
                DropdownMenuItem(
                    text = { Text(text = none) },
                    onClick = { dismiss(); onBoost(false) },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(id = R.string.studio_grade_boost_on)) },
                    onClick = { dismiss(); onBoost(true) },
                )
            }

            GradeChip(
                label = stringResource(
                    id = R.string.studio_grade_bar_log,
                    selection.logSpace ?: none,
                ),
            ) { dismiss ->
                DropdownMenuItem(
                    text = { Text(text = none) },
                    onClick = { dismiss(); onLogSpace(null) },
                )
                for (name in logSpaces) {
                    DropdownMenuItem(
                        text = { Text(text = name) },
                        onClick = { dismiss(); onLogSpace(name) },
                    )
                }
            }

            GradeChip(
                label = stringResource(
                    id = R.string.studio_grade_bar_lut,
                    selection.lutName ?: none,
                ),
                // A picked LUT name can be long; take the remaining row width and ellipsize.
                modifier = Modifier.weight(1f),
            ) { dismiss ->
                DropdownMenuItem(
                    text = { Text(text = stringResource(id = R.string.studio_grade_lut_pick)) },
                    onClick = { dismiss(); onPickLut() },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(id = R.string.studio_grade_lut_clear)) },
                    onClick = { dismiss(); onClearLut() },
                )
            }
        }
    }
}

/**
 * A compact text chip ("Label: value") anchoring a dropdown [menu]. The menu content receives a
 * `dismiss` callback so every item can close the menu itself; the LUT chip passes a [modifier]
 * (weight) so long file names shrink and ellipsize instead of pushing the other chips off-row.
 */
@Composable
private fun GradeChip(
    label: String,
    modifier: Modifier = Modifier,
    menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        TextButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            menu { open = false }
        }
    }
}
