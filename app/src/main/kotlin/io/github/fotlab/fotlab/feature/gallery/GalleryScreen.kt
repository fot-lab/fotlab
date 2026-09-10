package io.github.fotlab.fotlab.feature.gallery

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DrawerSheet
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fotlab.fotlab.R
import kotlinx.coroutines.launch
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    // Node awaiting a rename from the single-selection edit action; null = dialog closed.
    var renameTarget by remember { mutableStateOf<FsNodeObject?>(null) }
    // Media list + start index for the full-screen viewer, captured from the folder's current
    // sort order the moment a tile is tapped (FOTLAB-IMGMGR viewer).
    var viewerItems by remember { mutableStateOf<List<FsNodeObject>?>(null) }
    var viewerStart by remember { mutableStateOf(0) }
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
                onCancelSelection = { GalleryCore.selection.clear() },
                onRename = {
                    // Exactly one node is selected (the edit icon only shows then): open its
                    // rename dialog with the current name prefilled.
                    val id = selectedIds.singleOrNull()
                    renameTarget = children.firstOrNull { it.fsNodeId == id }
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
                            when {
                                node.isCollection() -> currentDirectory = node
                                isMedia(node.typeMime) -> {
                                    // Open the full-screen viewer on the tapped media, paging
                                    // through the folder's media in its current sort order.
                                    val media = children.filter { isMedia(it.typeMime) }
                                    viewerStart = media.indexOfFirst { it.fsNodeId == id }.coerceAtLeast(0)
                                    viewerItems = media
                                }
                                else -> GalleryCore.selection.toggle(id)
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

            // Full-screen image / video viewer, opened by tapping a media tile.
            if (viewerItems != null) {
                GalleryViewerDialog(
                    items = viewerItems!!,
                    startIndex = viewerStart,
                    onDismiss = { viewerItems = null },
                )
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

    if (renameTarget != null) {
        GalleryRenameDialog(
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
    onCycleLayout: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDrawer: () -> Unit,
    onImport: () -> Unit,
    onCreateCollection: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onCancelSelection: () -> Unit,
    onRename: () -> Unit,
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
            // Leading cluster swaps on selection (`FOTLAB-UIXDES-000004`): with nothing selected
            // it is drawer + grid + sync; once anything is selected the drawer becomes a Close
            // (clear selection) and the grid slot becomes a rename pencil (single) or the plain
            // count (multiple) — sync is hidden while selecting.
            if (selectionSize == 0) {
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
                            contentDescription = stringResource(id = R.string.gallery_cd_layout_mode),
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Filled.Sync,
                            contentDescription = stringResource(id = R.string.gallery_cd_sync),
                        )
                    }
                }
            } else {
                Row {
                    IconButton(onClick = onCancelSelection) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(id = R.string.gallery_cd_clear_selection),
                        )
                    }
                    if (selectionSize == 1) {
                        IconButton(onClick = onRename) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = stringResource(id = R.string.gallery_cd_rename),
                            )
                        }
                    } else {
                        Text(
                            text = selectionSize.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun NodeCell(
    node: FsNodeObject,
    selected: Boolean,
    isGrid: Boolean,
    onNodeClick: (FsNodeObject) -> Unit,
    onToggleSelect: (FsNodeObject) -> Unit,
) {
    if (isGrid) {
        // M3 has no official grid cell, so it stays hand-written: a square thumbnail (like a
        // file manager) above the name, with the selected container colour and a long-press
        // to toggle selection.
        Column(
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
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onNodeClick(node) },
                    onLongClick = { onToggleSelect(node) },
                ),
        ) {
            NodeThumbnail(
                node = node,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            Text(
                text = node.nameDisplay,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp),
            )
        }
    } else {
        // Detail list: the official M3 [ListItem] carries the selected container colour and
        // the proper list-row metrics; a square thumbnail sits in the leading slot, the way a
        // file manager shows it. Long-press toggles selection like the grid tile does.
        ListItem(
            selected = selected,
            modifier = Modifier.combinedClickable(
                onClick = { onNodeClick(node) },
                onLongClick = { onToggleSelect(node) },
            ),
            leadingContent = { NodeThumbnail(node = node, modifier = Modifier.size(40.dp)) },
            headlineContent = { Text(text = node.nameDisplay) },
        )
    }
}

/**
 * Square thumbnail for a gallery node, in the spirit of Material Files: a real thumbnail for
 * image/video media (decoded from the node's `content://` URI), a folder glyph for
 * collections, and a MIME-type icon otherwise. The frame is always a square with a default
 * [MaterialTheme.colorScheme.surfaceVariant] background, and every thumbnail uses
 * [ContentScale.Fit] so the whole content stays visible inside it — for non-square images the
 * letterboxed bands show that background rather than the gallery behind it.
 */
@Composable
private fun NodeThumbnail(node: FsNodeObject, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        when {
            node.isCollection() -> {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxSize().padding(percent = 18),
                )
            }
            isMedia(node.typeMime) -> {
                val uri = node.uriStorage?.let(Uri::parse)
                if (uri != null) {
                    MediaThumbnail(uri = uri)
                } else {
                    MimeIcon(node.typeMime)
                }
            }
            else -> MimeIcon(node.typeMime)
        }
    }
}

/** MIME-type glyph shown when there is no real thumbnail to decode. */
@Composable
private fun MimeIcon(mimeType: String) {
    Icon(
        imageVector = mimeIcon(mimeType),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxSize().padding(percent = 22),
    )
}

/** Decodes and shows the real thumbnail for a media URI, fitting it inside the square. */
@Composable
private fun MediaThumbnail(uri: Uri) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { loadThumbnail(context.contentResolver, uri) }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Icon(
            imageVector = Icons.Filled.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize().padding(percent = 22),
        )
    }
}

/** True for image/* and video/* entries, which get a real decoded thumbnail. */
private fun isMedia(mime: String): Boolean = mime.startsWith("image/") || mime.startsWith("video/")

/** Pick the Material icon that represents a MIME type the way a file manager does. */
private fun mimeIcon(mime: String): ImageVector = when {
    mime.startsWith("image/") -> Icons.Filled.Image
    mime.startsWith("video/") -> Icons.Filled.Movie
    mime.startsWith("audio/") -> Icons.Filled.Audiotrack
    mime.startsWith("application/pdf") -> Icons.Filled.PictureAsPdf
    else -> Icons.Filled.InsertDriveFile
}

/**
 * Decode a thumbnail bitmap for [uri]. Image and video providers honour
 * [ContentResolver.loadThumbnail] (API 29+); older devices and non-media URIs fall back to a
 * MIME icon. Successful results are cached so scrolling does not re-decode them.
 */
private val thumbnailCache = object : LruCache<String, Bitmap?>(256) {}

private fun loadThumbnail(resolver: ContentResolver, uri: Uri): Bitmap? {
    val key = uri.toString()
    thumbnailCache.get(key)?.let { return it }
    val bitmap = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.loadThumbnail(uri, Size(256, 256), null)
        } else {
            resolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeStream(stream, null, opts)
            }
        }
    }.getOrNull()
    if (bitmap != null) thumbnailCache.put(key, bitmap)
    return bitmap
}
