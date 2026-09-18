package io.github.fotlab.fotlab.feature.studio

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.TopAppBar
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
import io.github.fotlab.fotlab.ui.rememberZoomState
import io.github.fotlab.fotlab_rawler.DemosaicAlgorithm
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Studio screen (UI) — a Snapseed-style editor and the second independent screen, owned by the
 * `feature/studio` package alongside its lower layer [StudioEngine] (`FOTLAB-STRUCT-000001`).
 *
 * Like every screen it fills the whole region above the bottom navigation bar and splits it into two
 * sibling regions: its own top bar and the content region below it (`FOTLAB-UIXDES-000002` R3). The
 * top bar follows the shared skeleton — drawer toggle at the far left, overflow at the far right, and
 * a file-open action just left of the overflow (`FOTLAB-UIXDES-000002`). The three develop tools sit
 * as icon-only buttons right of the drawer menu — gradient (demosaic dropdown), exposure (stops
 * input) and wb-auto (Kelvin input). The module also owns its drawer; nothing here is shared with the
 * shell.
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

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            StudioDrawer(onClose = { scope.launch { drawerState.close() } })
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            StudioTopBar(
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

        }
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

    // White-balance input dialog: opened by the top-bar WbAuto icon. The title carries the as-shot
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
}

/**
 * The Studio top bar: the shared skeleton of `FOTLAB-UIXDES-000002` — drawer menu at the far left,
 * immediately followed by the three icon-only develop tools (gradient → demosaic algorithm dropdown,
 * exposure → stops input dialog, wb-auto → Kelvin input dialog); the file-open action and the
 * overflow (three-dot) sit at the far right. The bar renders no title text
 * (`FOTLAB-UIXDES-000004` R6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioTopBar(
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

    TopAppBar(
        title = {},
        modifier = modifier,
        navigationIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = stringResource(id = R.string.studio_cd_drawer_open),
                    )
                }
                // Demosaic: the gradient icon anchors the algorithm dropdown.
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
                        imageVector = Icons.Filled.WbAuto,
                        contentDescription = stringResource(id = R.string.studio_cd_whitebalance),
                    )
                }
            }
        },
        actions = {
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
        },
    )
}

/**
 * The Studio drawer sheet: the Material3 [ModalDrawerSheet] at 80% of the module width
 * (`FOTLAB-UIXDES-000002` R3). The close button sits in the sheet's own top-left corner, aligned with
 * the top bar's three-line icon, so opening the drawer replaces that icon in place (R6).
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
        IconButton(onClick = onClose, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(id = R.string.common_drawer_close),
            )
        }
        Text(
            text = stringResource(id = R.string.app_nav_studio_label),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}
