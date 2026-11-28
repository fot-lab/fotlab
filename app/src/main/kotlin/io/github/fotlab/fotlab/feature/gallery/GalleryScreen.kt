package io.github.fotlab.fotlab.feature.gallery

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import io.github.fotlab.fotlab.R
import io.github.fotlab.fotlab.data.common.FsNodeObject
import io.github.fotlab.fotlab.data.common.MimeFolderDeleted
import io.github.fotlab.fotlab.data.common.isCollection
import io.github.fotlab.fotlab.feature.library.LibraryRenameDialog
import io.github.fotlab.fotlab.feature.library.LibraryScreenFunBarHeight
import io.github.fotlab.fotlab.feature.library.NodeList
import io.github.fotlab.fotlab.feature.library.SweepSyncIcon
import io.github.fotlab.fotlab.feature.library.isMedia
import kotlinx.coroutines.launch

/** Drawer width: 80% of the module region. */
private const val DrawerWidthFraction = 0.8f

/**
 * The two top-level views the drawer switches between. Gallery is the main view.
 * RecycleBin shows removed nodes.
 */
private enum class GalleryViewMode { Gallery, RecycleBin }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onOpenViewer: () -> Unit,
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var currentDirectoryId by remember { mutableStateOf(GalleryRoot.ID) }
    var viewMode by remember { mutableStateOf(GalleryViewMode.Gallery) }
    var deleteConfirmation by remember { mutableStateOf(false) }
    var deleteInvalid by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FsNodeObject?>(null) }
    val scope = rememberCoroutineScope()
    val newCollectionName = stringResource(id = R.string.library_new_collection_name)

    val selectedIds by GalleryCore.selection.selected.collectAsState()
    val selectionModeActive by GalleryCore.selectionModeActive.collectAsState()
    val layoutMode by GalleryCore.layoutMode.collectAsState()

    LaunchedEffect(currentDirectoryId) {
        GalleryCore.setCurrentDirectory(currentDirectoryId)
    }

    val children by remember(currentDirectoryId) {
        GalleryCore.childrenOf(currentDirectoryId)
    }.collectAsState(initial = emptyList())

    // Drawer first, then the directory: back closes the drawer before leaving a folder.
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = !drawerState.isOpen && viewMode == GalleryViewMode.Gallery && currentDirectoryId != GalleryRoot.ID) { currentDirectoryId = GalleryRoot.ID }
    BackHandler(enabled = !drawerState.isOpen && viewMode == GalleryViewMode.RecycleBin) {
        viewMode = GalleryViewMode.Gallery
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            GalleryDrawer(
                viewMode = viewMode,
                onSelectView = { viewMode = it },
                onClose = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        val isGalleryView = viewMode == GalleryViewMode.Gallery
        Scaffold(
            contentWindowInsets = if (isGalleryView) androidx.compose.foundation.layout.WindowInsets.navigationBars else androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (isGalleryView) {
                    GalleryScreenFunBar(
                        selectionModeActive = selectionModeActive,
                        selectionSize = selectedIds.size,
                        candidateIds = children.mapNotNull { it.fsNodeId },
                        onCycleLayout = { scope.launch { GalleryCore.cycleLayoutMode() } },
                        onRefresh = { GalleryCore.refresh() },
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onCreateCollection = {
                            scope.launch {
                                GalleryCore.createCollection(
                                    parentId = currentDirectoryId,
                                    name = newCollectionName,
                                )
                            }
                        },
                        onExitSelection = { GalleryCore.exitSelectionMode() },
                        onRename = {
                            val id = selectedIds.singleOrNull()
                            renameTarget = children.firstOrNull { it.fsNodeId == id }
                        },
                        onExport = { /* TODO */ },
                        onDelete = {
                            scope.launch {
                                if (GalleryCore.selectionDirectlyUnder(currentDirectoryId)) {
                                    deleteConfirmation = true
                                } else {
                                    deleteInvalid = true
                                }
                            }
                        },
                    )
                }
            },
        ) { innerPadding ->
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
            ) {
                when (viewMode) {
                    GalleryViewMode.Gallery -> {
                        NodeList(
                            nodes = children,
                            selectedIds = selectedIds,
                            layoutMode = layoutMode,
                            selectionActive = selectionModeActive,
                            onNodeClick = { node ->
                                node.fsNodeId?.let { id ->
                                    when {
                                        node.isCollection() -> node.fsNodeId?.let { currentDirectoryId = it }
                                        isMedia(node.typeMime) -> {
                                            val media = children.filter { isMedia(it.typeMime) }
                                            val start = media.indexOfFirst { it.fsNodeId == id }.coerceAtLeast(0)
                                            GalleryCore.setViewerSession(media, start)
                                            onOpenViewer()
                                        }
                                        else -> GalleryCore.selection.toggle(id)
                                    }
                                }
                            },
                            onLongPress = { node ->
                                node.fsNodeId?.let { id -> GalleryCore.enterSelectionMode(id) }
                            },
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
                    GalleryViewMode.RecycleBin -> {
                        GalleryRecycleScreen(
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onCycleLayout = { scope.launch { GalleryCore.cycleLayoutMode() } },
                            onRefresh = { GalleryCore.refresh() },
                            onOpenViewer = onOpenViewer,
                        )
                    }
                }
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
                    renameTarget!!.fsNodeId?.let { GalleryCore.renameNode(it, newName) }
                    renameTarget = null
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryDrawer(
    viewMode: GalleryViewMode,
    onSelectView: (GalleryViewMode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth(DrawerWidthFraction),
    ) {
        DrawerNavItem(
            icon = Icons.Filled.Delete,
            label = stringResource(id = R.string.library_recycle_bin),
            contentDescription = stringResource(id = R.string.library_cd_recycle_bin),
            selected = viewMode == GalleryViewMode.RecycleBin,
            onClick = {
                onSelectView(GalleryViewMode.RecycleBin)
                onClose()
            },
        )
        DrawerNavItem(
            icon = Icons.Filled.Source,
            label = stringResource(id = R.string.gallery_name),
            contentDescription = stringResource(id = R.string.library_cd_library),
            selected = viewMode == GalleryViewMode.Gallery,
            onClick = {
                onSelectView(GalleryViewMode.Gallery)
                onClose()
            },
        )

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.navigationBars)
                .height(LibraryScreenFunBarHeight),
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
    androidx.compose.foundation.layout.Row(
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
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(16.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun GalleryScreenFunBar(
    selectionModeActive: Boolean,
    selectionSize: Int,
    candidateIds: List<Long>,
    onCycleLayout: () -> Unit,
    onRefresh: suspend () -> Unit,
    onOpenDrawer: () -> Unit,
    onCreateCollection: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onExitSelection: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var overflowOpen by remember { mutableStateOf(false) }
    val selection = GalleryCore.selection

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.navigationBars)
                .height(LibraryScreenFunBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!selectionModeActive) {
                androidx.compose.foundation.layout.Row {
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
                    SweepSyncIcon(onSweep = onRefresh)
                }
            } else {
                androidx.compose.foundation.layout.Row {
                    IconButton(onClick = onExitSelection) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(id = R.string.library_cd_clear_selection),
                        )
                    }
                    if (selectionSize == 1) {
                        IconButton(onClick = onRename) {
                            Icon(
                                imageVector = Icons.Filled.DriveFileRenameOutline,
                                contentDescription = stringResource(id = R.string.library_cd_rename),
                            )
                        }
                    } else {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .height(48.dp)
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = selectionSize.toString(),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                ),
                            )
                        }
                    }
                }
            }

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))

            if (!selectionModeActive) {
                IconButton(onClick = onCreateCollection) {
                    Icon(
                        imageVector = Icons.Filled.CreateNewFolder,
                        contentDescription = stringResource(id = R.string.common_action_new_folder),
                    )
                }
            } else {
                IconButton(onClick = onExport) {
                    Icon(
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

            androidx.compose.foundation.layout.Box {
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
                        text = { Text(text = stringResource(id = R.string.common_selection_select_none)) },
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
        }
    }
}
