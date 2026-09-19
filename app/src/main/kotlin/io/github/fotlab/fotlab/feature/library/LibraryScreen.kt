package io.github.fotlab.fotlab.feature.library

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import io.github.fotlab.fotlab.R
import io.github.fotlab.fotlab.feature.studio.StudioEngine
import kotlinx.coroutines.launch
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Drawer width: 80% of the module region (`FOTLAB-UIXDES-000002` R3). */
private const val DrawerWidthFraction = 0.8f

/**
 * The two top-level views the drawer switches between. `Library` is the Source Library — the
 * whole library feature built so far. `RecycleBin` shows removed nodes, whose content is not
 * implemented yet and is left empty for now.
 */
private enum class LibraryViewMode { Library, RecycleBin }

/**
 * Library screen (UI) — the first independent screen, owned by the `feature/library`
 * package alongside its lower layer [LibraryCore] (`FOTLAB-STRUCT-000001`).
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
 * in its own top-left corner (`FOTLAB-UIXDES-000002` R6). Edge-swipe gestures are disabled
 * (`gesturesEnabled = false`): the drawer opens only via the top-bar menu icon and closes via
 * its own X button (plus back / scrim tap); the standard M3 slide motion is kept for both.
 *
 * The top bar follows `FOTLAB-UIXDES-000002` (drawer icon left, overflow right) and fills
 * its leading cluster and two action slots as `FOTLAB-UIXDES-000004` prescribes: a
 * layout-toggle icon (grid / 田字) and a refresh icon sit right of the drawer icon and cycle
 * / reconcile the content; import + new collection show when nothing is selected, export +
 * delete when something is.
 *
 * The selection itself lives in [LibraryCore.selection] — a process-scoped object. This
 * screen only reads it with plain `remember`; it is never saved with `rememberSaveable`
 * and never restored (`FOTLAB-UIXDES-000004` R3/C6). The display mode is likewise owned by
 * the core and read here (`FOTLAB-UIXDES-000004` R9); the refresh fires a core reconcile
 * (`FOTLAB-UIXDES-000004` R10).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onNavigateToStudio: () -> Unit,
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var currentDirectory by remember { mutableStateOf<FsNodeObject?>(null) }
    // Which top-level view the drawer selected; defaults to the Source Library (built so far).
    var viewMode by remember { mutableStateOf(LibraryViewMode.Library) }
    var deleteConfirmation by remember { mutableStateOf(false) }
    // Set when a delete press is blocked because a selected node is not a direct child of the
    // directory on screen; drives the "cannot delete" dialog.
    var deleteInvalid by remember { mutableStateOf(false) }
    // Node awaiting a rename from the single-selection edit action; null = dialog closed.
    var renameTarget by remember { mutableStateOf<FsNodeObject?>(null) }
    // Media list + start index for the full-screen viewer, captured from the folder's current
    // sort order the moment a tile is tapped (FOTLAB-IMGMGR viewer).
    var viewerItems by remember { mutableStateOf<List<FsNodeObject>?>(null) }
    var viewerStart by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val newCollectionName = stringResource(id = R.string.library_new_collection_name)

    // Process-scoped state owned by the core: read here, never stored here.
    val selectedIds by LibraryCore.selection.selected.collectAsState()
    val selectionModeActive by LibraryCore.selectionModeActive.collectAsState()
    val layoutMode by LibraryCore.layoutMode.collectAsState()

    // Publish the directory the user is currently viewing (normal Library view, never Recycle) so
    // other screens can import into it (`FOTLAB-UIXDES-000004`).
    LaunchedEffect(currentDirectory) {
        LibraryCore.setCurrentDirectory(currentDirectory?.fsNodeId)
    }

    val children by remember(currentDirectory) {
        val parentId = currentDirectory?.fsNodeId
        if (parentId == null) LibraryCore.rootChildren() else LibraryCore.childrenOf(parentId)
    }.collectAsState(initial = emptyList())

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch { LibraryCore.importUris(currentDirectory?.fsNodeId, uris) }
        }
    }

    // Drawer first, then the directory: back closes the drawer before leaving a folder.
    // The directory back is only for the Library view; the Recycle Bin drives its own back stack.
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = !drawerState.isOpen && viewMode == LibraryViewMode.Library && currentDirectory != null) { currentDirectory = null }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            LibraryDrawer(
                viewMode = viewMode,
                onSelectView = { viewMode = it },
                onClose = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        // Two sibling regions: the top bar, and the content region below it.
        Column(modifier = Modifier.fillMaxSize()) {
            if (viewMode == LibraryViewMode.Library) {
                LibraryTopBar(
                selectionModeActive = selectionModeActive,
                selectionSize = selectedIds.size,
                candidateIds = children.mapNotNull { it.fsNodeId },
                onCycleLayout = { scope.launch { LibraryCore.cycleLayoutMode() } },
                onRefresh = { scope.launch { LibraryCore.refresh() } },
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onImport = { importLauncher.launch(arrayOf("*/*")) },
                onCreateCollection = {
                    scope.launch {
                        LibraryCore.createCollection(
                            parentId = currentDirectory?.fsNodeId,
                            name = newCollectionName,
                        )
                    }
                },
                onExitSelection = { LibraryCore.exitSelectionMode() },
                onRename = {
                    // Exactly one node is selected (the edit icon only shows then): open its
                    // rename dialog with the current name prefilled.
                    val id = selectedIds.singleOrNull()
                    renameTarget = children.firstOrNull { it.fsNodeId == id }
                },
                // Same wiring as the viewer's "open in Studio": resolve the single selected node,
                // hand its uri to the studio engine, and switch to Studio. Lets files the viewer
                // cannot preview reach Studio's decoder in one tap from the bar.
                onOpenInStudio = {
                    val id = selectedIds.singleOrNull()
                    children.firstOrNull { it.fsNodeId == id }?.uriStorage
                        ?.let { StudioEngine.setCurrentNode(it) }
                    onNavigateToStudio()
                },
                // Export shape is undecided (`FOTLAB-UIXDES-000004` Q6): the slot is
                // present as required by R4, the behaviour is added when Q6 is settled.
                onExport = { /* TODO: export, pending Q6 */ },
                onDelete = {
                    scope.launch {
                        // Gate: only nodes directly under the directory on screen may be deleted
                        // from here; recursion into subfolders is not re-checked (`FOTLAB-UIXDES-000004`).
                        if (LibraryCore.selectionDirectlyUnder(currentDirectory?.fsNodeId)) {
                            deleteConfirmation = true
                        } else {
                            deleteInvalid = true
                        }
                    }
                },
            )
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (viewMode) {
                    LibraryViewMode.Library -> {
                        NodeList(
                            nodes = children,
                            selectedIds = selectedIds,
                            layoutMode = layoutMode,
                            selectionActive = selectionModeActive,
                            onNodeClick = { node ->
                                node.fsNodeId?.let { id ->
                                    when {
                                        node.isCollection() -> currentDirectory = node
                                        isMedia(node.typeMime) -> {
                                            // Open the full-screen viewer on the tapped media, paging
                                            // through the folder's media in its current sort order.
                                            val media = children.filter { isMedia(it.typeMime) }
                                            viewerStart = media.indexOfFirst { it.fsNodeId == id }.coerceAtLeast(0)
                                            viewerItems = media
                                        }
                                        else -> LibraryCore.selection.toggle(id)
                                    }
                                }
                            },
                            // Long press enters the selection action mode and selects the node as
                            // its first pick (`FOTLAB-UIXDES-000004` selection action mode).
                            onLongPress = { node ->
                                node.fsNodeId?.let { id -> LibraryCore.enterSelectionMode(id) }
                            },
                            // Plain tap while the action mode is active toggles the node; the
                            // checkbox also routes here. A long press selects a collection too: it
                            // can be deleted like any other node, and the delete walks its subtree
                            // (`FOTLAB-DATABS-000002` R12; `FOTLAB-UIXDES-000004` Q4).
                            onToggleSelect = { node ->
                                node.fsNodeId?.let { id ->
                                    LibraryCore.selection.toggle(id)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )

                        if (children.isEmpty()) {
                            Text(
                                text = stringResource(id = R.string.library_empty_directory),
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    // Recycle Bin: removed nodes, grouped by their shared delete-batch timestamp.
                    LibraryViewMode.RecycleBin -> {
                        LibraryRecycleScreen(
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onCycleLayout = { scope.launch { LibraryCore.cycleLayoutMode() } },
                            onRefresh = { scope.launch { LibraryCore.refresh() } },
                            onNavigateToStudio = onNavigateToStudio,
                        )
                    }
                }
            }

            // Full-screen image / video viewer, opened by tapping a media tile.
            if (viewerItems != null) {
                LibraryViewerDialog(
                    items = viewerItems!!,
                    startIndex = viewerStart,
                    onDismiss = { viewerItems = null },
                    onOpenInStudio = { node ->
                        // Equivalent to: close the dialog, switch to Studio (nav bar), and open the
                        // tapped image there (`FOTLAB-UIXDES`, viewer layout).
                        node.uriStorage?.let { StudioEngine.setCurrentNode(it) }
                        onNavigateToStudio()
                        viewerItems = null
                    },
                )
            }
        }
    }

    if (deleteConfirmation) {
        AlertDialog(
            onDismissRequest = { deleteConfirmation = false },
            title = { Text(text = stringResource(id = R.string.library_delete_title)) },
            text = { Text(text = stringResource(id = R.string.library_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirmation = false
                        scope.launch { LibraryCore.deleteSelected() }
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

    if (deleteInvalid) {
        AlertDialog(
            onDismissRequest = { deleteInvalid = false },
            title = { Text(text = stringResource(id = R.string.library_delete_invalid_title)) },
            text = { Text(text = stringResource(id = R.string.library_delete_invalid_message)) },
            confirmButton = {
                TextButton(onClick = { deleteInvalid = false }) {
                    Text(text = stringResource(id = R.string.common_action_ok))
                }
            },
        )
    }

    if (renameTarget != null) {
        LibraryRenameDialog(
            initialName = renameTarget!!.nameDisplay,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                scope.launch {
                    renameTarget!!.fsNodeId?.let { LibraryCore.renameNode(it, newName) }
                    renameTarget = null
                }
            },
        )
    }
}

/**
 * The library drawer sheet: the Material3 [DrawerSheet] at 80% of the module width
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
private fun LibraryDrawer(
    viewMode: LibraryViewMode,
    onSelectView: (LibraryViewMode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth(DrawerWidthFraction),
    ) {
        // Row 1: the close affordance that replaces the drawer icon while closed (R6).
        IconButton(
            onClick = onClose,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(id = R.string.common_drawer_close),
            )
        }

        // Row 2: Recycle Bin — shows removed nodes (content not built yet, left empty).
        DrawerNavItem(
            icon = Icons.Filled.Delete,
            label = stringResource(id = R.string.library_recycle_bin),
            contentDescription = stringResource(id = R.string.library_cd_recycle_bin),
            selected = viewMode == LibraryViewMode.RecycleBin,
            onClick = {
                onSelectView(LibraryViewMode.RecycleBin)
                onClose()
            },
        )
        // Row 3: Library (Source Library) — the library feature built so far.
        DrawerNavItem(
            icon = Icons.Filled.Source,
            label = stringResource(id = R.string.library_library),
            contentDescription = stringResource(id = R.string.library_cd_library),
            selected = viewMode == LibraryViewMode.Library,
            onClick = {
                onSelectView(LibraryViewMode.Library)
                onClose()
            },
        )
    }
}

/**
 * A single drawer navigation row: a leading icon plus its label, with the selected row
 * tinted by the M3 secondary container. Tapping switches the top-level view and closes the drawer.
 */
@Composable
private fun DrawerNavItem(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTopBar(
    selectionModeActive: Boolean,
    selectionSize: Int,
    candidateIds: List<Long>,
    onCycleLayout: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDrawer: () -> Unit,
    onImport: () -> Unit,
    onCreateCollection: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onExitSelection: () -> Unit,
    onRename: () -> Unit,
    onOpenInStudio: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var overflowOpen by remember { mutableStateOf(false) }
    val selection = LibraryCore.selection

    TopAppBar(
        // The title slot stays an empty composable — Material3 makes `title` a required
        // parameter, so it cannot simply be dropped. The bar never renders text: the directory
        // name is not surfaced here, and while selecting the count lives in the leading cluster.
        title = {},
        modifier = modifier,
        navigationIcon = {
            // Leading cluster swaps on the selection action mode (`FOTLAB-UIXDES-000004`): with
            // the mode off it is drawer + grid + sync; once the mode is on the drawer becomes a
            // Close (exit the mode) and the grid slot becomes a rename pencil (single) or the bare
            // count (multiple) — sync is hidden while selecting. The bar has no title, so the
            // count is shown only here. The mode is independent of the selection count: it stays
            // on after everything is deselected, so the Close is the only way out.
            if (!selectionModeActive) {
                Row {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            imageVector = Icons.Filled.Menu,
                            contentDescription = stringResource(id = R.string.common_drawer_open),
                        )
                    }
                    IconButton(onClick = onCycleLayout) {
                        Icon(
                            imageVector = Icons.Filled.GridView,
                            contentDescription = stringResource(id = R.string.library_cd_layout_mode),
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Filled.Sync,
                            contentDescription = stringResource(id = R.string.library_cd_sync),
                        )
                    }
                }
            } else {
                Row {
                    IconButton(onClick = onExitSelection) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(id = R.string.library_cd_clear_selection),
                        )
                    }
                    // The grid slot: a rename pencil for a single selection, the bare
                    // count for several — set in the title's own type scale.
                    if (selectionSize == 1) {
                        IconButton(onClick = onRename) {
                            Icon(
                                imageVector = Icons.Filled.DriveFileRenameOutline,
                                contentDescription = stringResource(id = R.string.library_cd_rename),
                            )
                        }
                        // Open the single selected node in Studio straight from the bar. This is the
                        // quick path for files the viewer cannot preview: it routes them into
                        // Studio's decoder the same way the viewer's "open in Studio" does
                        // (`FOTLAB-UIXDES`, selection bar).
                        IconButton(onClick = onOpenInStudio) {
                            Icon(
                                imageVector = Icons.Filled.AddPhotoAlternate,
                                contentDescription = stringResource(id = R.string.library_viewer_cd_open_in_studio),
                            )
                        }
                    } else {
                        // The count lives in the same 48.dp vertical slot as the icon buttons so it
                        // shares their centre line; the box centres the glyph and the text drops
                        // Android's default `includeFontPadding`. That padding reserves descender
                        // space below the baseline, and digits have no descenders, so leaving it on
                        // pushes the numerals above the bar's centre by about half a character —
                        // which is exactly the offset being fixed here.
                        Box(
                            modifier = Modifier
                                .height(48.dp)
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = selectionSize.toString(),
                                // platformStyle used to be a Text() parameter; current Compose
                                // carries it on TextStyle instead.
                                style = MaterialTheme.typography.titleLarge.copy(
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                ),
                            )
                        }
                    }
                }
            }
        },
        actions = {
            // Slot A then slot B, then the overflow icon (`FOTLAB-UIXDES-000004` R1). The slots
            // swap on the selection action mode, not on the count: import + new collection when
            // the mode is off, export + delete when it is on.
            if (!selectionModeActive) {
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
                        imageVector = Icons.Filled.CreateNewFolder,
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

private val nodeDateformat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

/** Secondary line under a node name: last-modified (falling back to created) as a short date. */
private fun nodeSubtitle(node: FsNodeObject): String {
    val t = node.timeModified ?: node.timeCreated
    return nodeDateformat.format(Date(t))
}

/**
 * Single-line text that truncates the **middle** (`head…tail`) so both the start of the string
 * and its trailing suffix (file extension) stay fully visible (`FOTLAB-UIXDES-000004`). The
 * standard [TextOverflow.Ellipsis] only keeps the head and cuts the tail, which would hide the
 * extension; for file names we must keep the extension, so we measure and split manually.
 *
 * The displayed string is recomputed in [androidx.compose.foundation.text.onTextLayout] against
 * the real available width, so it tracks container resizes (grows back to the full name when it
 * fits, shrinks again when it does not).
 */
@Composable
private fun MiddleEllipsisText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    ellipsis: String = "…",
) {
    val resolvedStyle = LocalTextStyle.current.merge(style)
    val textMeasurer = rememberTextMeasurer()
    var displayText by remember(text) { mutableStateOf(text) }

    val measure: (String) -> Int = { str ->
        textMeasurer.measure(
            text = str,
            style = resolvedStyle,
            constraints = Constraints(maxWidth = Int.MAX_VALUE),
        ).size.width
    }

    Text(
        text = displayText,
        modifier = modifier,
        color = color,
        style = resolvedStyle,
        softWrap = false,
        overflow = TextOverflow.Visible,
        maxLines = 1,
        onTextLayout = { layoutResult ->
            val maxWidthPx = layoutResult.layoutInput.constraints.maxWidth
            if (maxWidthPx == Constraints.Infinity) return@Text

            if (!layoutResult.didOverflowWidth) {
                // Current text fits; prefer the full name when it would now fit (resize).
                if (displayText != text && measure(text) <= maxWidthPx) displayText = text
                return@Text
            }
            val fitted = middleTruncated(text, maxWidthPx, ellipsis, measure)
            if (fitted != displayText) displayText = fitted
        },
    )
}

/**
 * Produce a middle-truncated copy of [text] that fits [maxWidthPx]: the trailing suffix (file
 * extension, or a short tail when there is none) is always kept whole, and as much of the leading
 * head as fits is shown before the [ellipsis].
 */
private fun middleTruncated(
    text: String,
    maxWidthPx: Int,
    ellipsis: String,
    measure: (String) -> Int,
): String {
    if (measure(text) <= maxWidthPx) return text
    val ellipsisWidth = measure(ellipsis)
    val (head, tail) = splitFileTail(text)
    val tailWidth = measure(tail)
    val availableForHead = (maxWidthPx - ellipsisWidth - tailWidth).coerceAtLeast(0)

    // Binary-search the longest head that fits beside the ellipsis + tail.
    var lo = 0
    var hi = head.length
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (measure(head.take(mid)) <= availableForHead) lo = mid else hi = mid - 1
    }
    return if (lo == 0) {
        // Head does not fit at all: fall back to the ellipsis plus as much tail as fits.
        val fallback = ellipsis + tail
        if (measure(fallback) <= maxWidthPx) fallback else ellipsis
    } else {
        head.take(lo) + ellipsis + tail
    }
}

/** Split [text] into (head, tail) where tail is the file extension, or a short trailing chunk. */
private fun splitFileTail(text: String): Pair<String, String> {
    val dot = text.lastIndexOf('.')
    return if (dot in 1 until text.length - 1) {
        text.substring(0, dot) to text.substring(dot)
    } else {
        val tailLen = minOf(4, text.length)
        text.dropLast(tailLen) to text.takeLast(tailLen)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NodeList(
    nodes: List<FsNodeObject>,
    selectedIds: Set<Long>,
    layoutMode: LibraryLayoutMode,
    selectionActive: Boolean,
    onNodeClick: (FsNodeObject) -> Unit,
    onToggleSelect: (FsNodeObject) -> Unit,
    onLongPress: (FsNodeObject) -> Unit,
    modifier: Modifier = Modifier,
) {
    // [selectionActive] is the selection action mode flag (owned by the core), not the bare
    // selection count: a plain tap toggles while the mode is on, opens while it is off
    // (`FOTLAB-UIXDES-000004`). The flag survives an empty selection.
    val cell: @Composable (FsNodeObject) -> Unit = { node ->
        NodeCell(
            node = node,
            selected = node.fsNodeId != null && node.fsNodeId in selectedIds,
            isGrid = layoutMode.isGrid,
            selectionActive = selectionActive,
            onNodeClick = onNodeClick,
            onToggleSelect = onToggleSelect,
            onLongPress = onLongPress,
        )
    }
    when (layoutMode) {
        LibraryLayoutMode.DetailList -> LazyColumn(modifier = modifier) {
            items(nodes, key = { it.fsNodeId ?: it.nameDisplay }) { cell(it) }
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(layoutMode.columns),
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(8.dp),
        ) {
            items(nodes, key = { it.fsNodeId ?: it.nameDisplay }) { cell(it) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun NodeCell(
    node: FsNodeObject,
    selected: Boolean,
    isGrid: Boolean,
    selectionActive: Boolean,
    onNodeClick: (FsNodeObject) -> Unit,
    onToggleSelect: (FsNodeObject) -> Unit,
    onLongPress: (FsNodeObject) -> Unit,
) {
    if (isGrid) {
        // M3 has no official grid cell, so it stays hand-written but framed like a file manager:
        // a square, rounded thumbnail tile inside a Card, the node name + a short date below, and
        // a Checkbox shown during selection. Long-press enters the selection action mode (and
        // selects this node); a plain tap toggles in selection mode, otherwise it opens
        // (`FOTLAB-UIXDES-000004`).
        Card(
            modifier = Modifier.combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { if (selectionActive) onToggleSelect(node) else onNodeClick(node) },
                onLongClick = { onLongPress(node) },
            ),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Box(contentAlignment = Alignment.TopStart, modifier = Modifier.padding(8.dp)) {
                NodeThumbnail(
                    node = node,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp)),
                )
                if (selectionActive) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelect(node) },
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
            MiddleEllipsisText(
                text = node.nameDisplay,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
            )
            Text(
                text = nodeSubtitle(node),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            )
        }
    } else {
        // Detail list: the official M3 [ListItem] carries the selected container colour and the
        // proper list-row metrics; a rounded thumbnail sits in the leading slot, the node name is
        // the headline, a short date the supporting line, and a Checkbox appears during selection.
        // Tapping follows the grid tile's rules (`FOTLAB-UIXDES-000004`): long-press enters the
        // selection action mode, a plain tap toggles in it, otherwise it opens.
        ListItem(
            modifier = Modifier.combinedClickable(
                onClick = { if (selectionActive) onToggleSelect(node) else onNodeClick(node) },
                onLongClick = { onLongPress(node) },
            ),
            // M3's ListItem has no `selected` parameter — the selected tint is
            // expressed through its colours instead.
            colors = ListItemDefaults.colors(
                containerColor = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    Color.Transparent
                },
            ),
            leadingContent = {
                NodeThumbnail(node = node, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
            },
            headlineContent = {
                MiddleEllipsisText(text = node.nameDisplay)
            },
            supportingContent = {
                Text(
                    text = nodeSubtitle(node),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                if (selectionActive) {
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelect(node) })
                }
            },
        )
    }
}


