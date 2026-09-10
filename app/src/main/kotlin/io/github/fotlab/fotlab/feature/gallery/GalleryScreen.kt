package io.github.fotlab.fotlab.feature.gallery

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DrawerSheet
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fotlab.fotlab.R
import kotlinx.coroutines.launch

/** Drawer width: 80% of the module region (`FOTLAB-UIXDES-000002` R3). */
private const val DrawerWidthFraction = 0.8f

/**
 * Gallery screen (UI) — the first independent screen, owned by the `feature/gallery`
 * package alongside its lower layer [GalleryCore] (`FOTLAB-STRUCT-000001`).
 *
 * The screen fills the whole region above the bottom navigation bar and splits it into two
 * sibling regions: the top bar, and the content region below it. Everything else the screen
 * adds belongs to one of them — the screen itself never stacks a second `Scaffold` on top of
 * the shell's.
 *
 * The drawer is the native Material3 [ModalNavigationDrawer] wrapped around both regions: it
 * slides over the top bar the way the platform does (`FOTLAB-UIXDES-000002` R3) and it can
 * never reach the bottom navigation region, which lies outside the module region. Its sheet
 * is the Material3 [DrawerSheet] at 80% of the module width, and it carries the close button
 * in its own top-left corner (`FOTLAB-UIXDES-000002` R6). The open/close is the M3 standard
 * motion — the sheet slides from the start edge while the scrim fades, both on the standard
 * easing — which [ModalNavigationDrawer] provides.
 *
 * The top bar follows `FOTLAB-UIXDES-000002` (drawer icon left, overflow right) and fills
 * its leading cluster and two action slots as `FOTLAB-UIXDES-000004` prescribes: a
 * layout-toggle icon (grid / 田字) and a refresh icon sit right of the drawer icon and cycle
 * / reconcile the content; import + new collection show when nothing is selected, export +
 * delete when something is.
 *
 * The selection itself lives in [GalleryCore.selection] — a process-scoped object. This
 * screen only reads it with plain `remember`; it is never saved with `rememberSaveable`
 * and never restored (`FOTLAB-UIXDES-000004` R3/C6). The display mode is likewise owned by
 * the core and read here (`FOTLAB-UIXDES-000004` R9); the refresh fires a core reconcile
 * (`FOTLAB-UIXDES-000004` R10).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen() {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var currentDirectory by remember { mutableStateOf<FsNodeObject?>(null) }
    var deleteConfirmation by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val newCollectionName = stringResource(id = R.string.gallery_new_collection_name)

    // Process-scoped state owned by the core: read here, never stored here.
    val selectedIds by GalleryCore.selection.selected.collectAsState()
    val layoutMode by GalleryCore.layoutMode.collectAsState()

    val children by remember(currentDirectory) {
        val parentId = currentDirectory?.fsNodeId
        if (parentId == null) GalleryCore.rootChildren() else GalleryCore.childrenOf(parentId)
    }.collectAsState(initial = emptyList())

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch { GalleryCore.importUris(currentDirectory?.fsNodeId, uris) }
        }
    }

    // Drawer first, then the directory: back closes the drawer before leaving a folder.
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = !drawerState.isOpen && currentDirectory != null) { currentDirectory = null }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            GalleryDrawer(onClose = { scope.launch { drawerState.close() } })
        },
    ) {
        // Two sibling regions: the top bar, and the content region below it.
        Column(modifier = Modifier.fillMaxSize()) {
            GalleryTopBar(
                directoryName = currentDirectory?.nameDisplay
                    ?: stringResource(id = GalleryCore.titleRes),
                selectionSize = selectedIds.size,
                candidateIds = children.mapNotNull { it.fsNodeId },
                layoutMode = layoutMode,
                onCycleLayout = { scope.launch { GalleryCore.cycleLayoutMode() } },
                onRefresh = { scope.launch { GalleryCore.refresh() } },
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onImport = { importLauncher.launch(arrayOf("*/*")) },
                onCreateCollection = {
                    scope.launch {
                        GalleryCore.createCollection(
                            parentId = currentDirectory?.fsNodeId,
                            name = newCollectionName,
                        )
                    }
                },
                // Export shape is undecided (`FOTLAB-UIXDES-000004` Q6): the slot is
                // present as required by R4, the behaviour is added when Q6 is settled.
                onExport = { /* TODO: export, pending Q6 */ },
                onDelete = { deleteConfirmation = true },
            )

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                NodeList(
                    nodes = children,
                    selectedIds = selectedIds,
                    layoutMode = layoutMode,
                    onNodeClick = { node ->
                        node.fsNodeId?.let { id ->
                            if (node.isCollection()) {
                                currentDirectory = node
                            } else {
                                GalleryCore.selection.toggle(id)
                            }
                        }
                    },
                    // Long press selects a collection too: it can be deleted like any
                    // other node, and the delete walks its subtree (`FOTLAB-DATABS-000002`
                    // R12; `FOTLAB-UIXDES-000004` Q4).
                    onToggleSelect = { node ->
                        node.fsNodeId?.let { id ->
                            GalleryCore.selection.toggle(id)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                if (children.isEmpty()) {
                    Text(
                        text = stringResource(id = R.string.gallery_empty_directory),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    if (deleteConfirmation) {
        AlertDialog(
            onDismissRequest = { deleteConfirmation = false },
            title = { Text(text = stringResource(id = R.string.gallery_delete_title)) },
            text = { Text(text = stringResource(id = R.string.gallery_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirmation = false
                        scope.launch { GalleryCore.deleteSelected() }
                    },
                ) {
                    Text(text = stringResource(id = R.string.common_action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmation = false }) {
                    Text(text = stringResource(id = R.string.common_action_cancel))
                }
            },
        )
    }
}

/**
 * The gallery drawer sheet: the Material3 [DrawerSheet] at 80% of the module width
 * (`FOTLAB-UIXDES-000002` R3), holding module-private content only (R5).
 *
 * [DrawerSheet] supplies the M3 container treatment — surface colour, the rounded trailing
 * edge and the tonal elevation — so the sheet reads as a proper M3 modal drawer while it
 * slides. The slide and the scrim fade follow the M3 standard motion (standard easing,
 * `FastOutSlowInEasing`), which [ModalNavigationDrawer] provides out of the box.
 *
 * The close button sits in the sheet's own top-left corner, at the position the top bar's
 * three-line icon occupies while the drawer is closed: the affordance the user pressed is
 * replaced in place by its counterpart (`FOTLAB-UIXDES-000002` R6). The padding matches the
 * top bar's leading slot so the two icons land on exactly the same spot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryDrawer(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DrawerSheet(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth(DrawerWidthFraction),
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(id = R.string.common_drawer_close),
            )
        }

        // Feature-private drawer content (R5): no app-level entries here.
        Text(
            text = stringResource(id = R.string.common_drawer_empty),
            modifier = Modifier.padding(16.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryTopBar(
    directoryName: String,
    selectionSize: Int,
    candidateIds: List<Long>,
    layoutMode: GalleryLayoutMode,
    onCycleLayout: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDrawer: () -> Unit,
    onImport: () -> Unit,
    onCreateCollection: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var overflowOpen by remember { mutableStateOf(false) }
    val selection = GalleryCore.selection

    TopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = if (selectionSize == 0) {
                    directoryName
                } else {
                    pluralStringResource(
                        id = R.plurals.common_selection_count,
                        count = selectionSize,
                        selectionSize,
                    )
                },
                maxLines = 1,
            )
        },
        navigationIcon = {
            // Drawer icon, then the layout toggle (grid / 田字) and the refresh icon (R9/R10).
            Row {
                IconButton(onClick = onOpenDrawer) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = stringResource(id = R.string.common_drawer_open),
                    )
                }
                IconButton(onClick = onCycleLayout) {
                    Icon(
                        imageVector = if (layoutMode.isGrid) Icons.Filled.GridView else Icons.Filled.ViewList,
                        contentDescription = stringResource(id = R.string.gallery_cd_layout_mode),
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(id = R.string.gallery_cd_refresh),
                    )
                }
            }
        },
        actions = {
            // Slot A then slot B, then the overflow icon (`FOTLAB-UIXDES-000004` R1).
            if (selectionSize == 0) {
                IconButton(onClick = onImport) {
                    Icon(
                        // Import: the arrow coming from outside down into the tray — data enters
                        // the app (`FOTLAB-UIXDES-000005` R2).
                        imageVector = Icons.Filled.SaveAlt,
                        contentDescription = stringResource(id = R.string.common_action_import),
                    )
                }
                IconButton(onClick = onCreateCollection) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(id = R.string.common_action_new_folder),
                    )
                }
            } else {
                IconButton(onClick = onExport) {
                    Icon(
                        // Export: the arrow rising out of the box — data leaves the app
                        // (`FOTLAB-UIXDES-000005` R2).
                        imageVector = Icons.Filled.IosShare,
                        contentDescription = stringResource(id = R.string.common_action_export),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(id = R.string.common_action_delete_selection),
                    )
                }
            }

            Box {
                IconButton(onClick = { overflowOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(id = R.string.common_action_more_options),
                    )
                }
                DropdownMenu(
                    expanded = overflowOpen,
                    onDismissRequest = { overflowOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(id = R.string.common_selection_select_all)) },
                        leadingIcon = {
                            Icon(imageVector = Icons.Filled.SelectAll, contentDescription = null)
                        },
                        onClick = {
                            overflowOpen = false
                            selection.selectAll(candidateIds)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(id = R.string.common_selection_invert)) },
                        leadingIcon = {
                            Icon(imageVector = Icons.Filled.FlipToBack, contentDescription = null)
                        },
                        onClick = {
                            overflowOpen = false
                            selection.invert(candidateIds)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(id = R.string.common_selection_deselect_all)) },
                        leadingIcon = {
                            Icon(imageVector = Icons.Filled.Deselect, contentDescription = null)
                        },
                        onClick = {
                            overflowOpen = false
                            selection.clear()
                        },
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeList(
    nodes: List<FsNodeObject>,
    selectedIds: Set<Long>,
    layoutMode: GalleryLayoutMode,
    onNodeClick: (FsNodeObject) -> Unit,
    onToggleSelect: (FsNodeObject) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cell: @Composable (FsNodeObject) -> Unit = { node ->
        NodeCell(
            node = node,
            selected = node.fsNodeId != null && node.fsNodeId in selectedIds,
            isGrid = layoutMode.isGrid,
            onNodeClick = onNodeClick,
            onToggleSelect = onToggleSelect,
        )
    }
    when (layoutMode) {
        GalleryLayoutMode.DetailList -> LazyColumn(modifier = modifier) {
            items(nodes, key = { it.fsNodeId ?: it.nameDisplay }) { cell(it) }
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(layoutMode.columns),
            modifier = modifier,
        ) {
            items(nodes, key = { it.fsNodeId ?: it.nameDisplay }) { cell(it) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeCell(
    node: FsNodeObject,
    selected: Boolean,
    isGrid: Boolean,
    onNodeClick: (FsNodeObject) -> Unit,
    onToggleSelect: (FsNodeObject) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = node.nameDisplay,
        maxLines = if (isGrid) 1 else Int.MAX_VALUE,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onNodeClick(node) },
                onLongClick = { onToggleSelect(node) },
            )
            .padding(if (isGrid) 8.dp else 16.dp),
    )
}
