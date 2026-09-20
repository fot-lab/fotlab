package io.github.fotlab.fotlab.feature.studio

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
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

/**
 * Studio screen (UI) — a Snapseed-style editor and the second independent screen, owned by the
 * `feature/studio` package alongside its lower layer [StudioEngine] (`FOTLAB-STRUCT-000001`).
 *
 * Like every screen it fills the whole region above the bottom navigation bar and splits it into two
 * sibling regions: its own top bar and the content region below it (`FOTLAB-UIXDES-000002` R3). The
 * top bar follows the shared skeleton — drawer toggle at the far left, overflow at the far right, and
 * a file-open action just left of the overflow (`FOTLAB-UIXDES-000002`). The module also owns its
 * drawer and its bottom action bar, none of which is shared with the shell.
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

    var showDemosaicSheet by remember { mutableStateOf(false) }
    val demosaicSheetState = rememberModalBottomSheetState()

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            StudioDrawer(onClose = { scope.launch { drawerState.close() } })
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            StudioTopBar(
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onOpenFile = { importLauncher.launch(arrayOf("*/*")) },
                onResetView = { zoomState.reset() },
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

            StudioBottomBar(onLooks = { showDemosaicSheet = true })
        }
    }

    // Demosaic pull-up menu: replacing the original "Looks" action. The first Studio render is a
    // grayscale raw preview; picking an algorithm here triggers `StudioEngine.develop`, which re-runs
    // the develop pipeline (demosaic + calibrate) and pushes the resulting linear PNG to the canvas.
    if (showDemosaicSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDemosaicSheet = false },
            sheetState = demosaicSheetState,
        ) {
            Text(
                text = stringResource(id = R.string.studio_demosaic_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            val algorithms = listOf(
                DemosaicAlgorithm.Default to stringResource(id = R.string.studio_demosaic_default),
                DemosaicAlgorithm.Ppg to stringResource(id = R.string.studio_demosaic_ppg),
                DemosaicAlgorithm.Bilinear4Channel to stringResource(id = R.string.studio_demosaic_bilinear4),
                DemosaicAlgorithm.XTransBilinear to stringResource(id = R.string.studio_demosaic_xtrans),
            )
            for ((algo, label) in algorithms) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showDemosaicSheet = false
                            scope.launch { StudioEngine.develop(algo) }
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

/**
 * The Studio top bar: the shared skeleton of `FOTLAB-UIXDES-000002` — drawer toggle at the far left,
 * overflow (three-dot) at the far right, and the file-open action just left of the overflow. The bar
 * renders no title text (`FOTLAB-UIXDES-000004` R6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioTopBar(
    onOpenDrawer: () -> Unit,
    onOpenFile: () -> Unit,
    onResetView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var overflowOpen by remember { mutableStateOf(false) }

    TopAppBar(
        title = {},
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = stringResource(id = R.string.studio_cd_drawer_open),
                )
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

/**
 * Snapseed-style bottom action bar: Looks / Tools / Export. Editing itself is not built yet — these
 * are the home for those actions, kept here so the layout matches the reference app.
 *
 * The "Looks" entry is the demosaic trigger: it opens the pull-up menu of demosaic algorithms
 * (`onLooks`). The other two remain placeholders for now.
 */
@Composable
private fun StudioBottomBar(
    onLooks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Text(
                text = stringResource(id = R.string.studio_tools_looks),
                modifier = Modifier.clickable(onClick = onLooks),
            )
            Text(text = stringResource(id = R.string.studio_tools))
            Text(text = stringResource(id = R.string.studio_export))
        }
    }
}
