package io.github.fotlab.fotlab.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fotlab.fotlab.R
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recycle Bin screen (`FOTLAB-DATABS-000002`, per-batch soft deletion).
 *
 * Deletion never drops rows — it stamps every removed node (and the edges that leave with it)
 * with one shared `time_deleted`, so a whole delete operation is one timestamp. The bin shows
 * that grouping directly:
 *
 * - **Root** — one virtual folder per distinct `time_deleted` value, named by the timestamp. These
 *   are presentation-only (`MimeFolderDeleted`); they are **not** `fs_node` rows, so they never
 *   mix with the live library tree.
 * - **Inside a batch** — the batch's own directory structure, rebuilt from the edges stamped with
 *   the same `time_deleted`. Because every node in a batch was deleted as a flat set of siblings
 *   under one directory, the batch root is flat; if a batch ever contains a nested collection its
 *   children (also stamped in the batch) still render under it through the same edges.
 *
 * The screen owns its own two-level navigation ([RecycleLocation]) and its own **isolated** selection
 * (never touching [LibraryCore]'s live-library selection, keeping the two views apart —
 * `FOTLAB-UIXDES-000004`). It renders its own top bar ([RecycleTopBar]) whose action icons differ
 * from the library's: nothing when idle, `restore from bin` + `delete forever` when selecting; the
 * trailing overflow keeps the shared select-all / invert / deselect trio.
 */

/** Key under which a batch's root nodes are grouped (no in-batch parent). */
private const val ROOT_KEY = -1L

/** Timestamp format shown as the batch folder name (date + time, so distinct minutes sort apart). */
private val recycleBatchFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

/** Where the recycle navigation currently is. */
private sealed interface RecycleLocation {
    /** The batch listing at the bin root. */
    data object Root : RecycleLocation

    /** Inside a delete batch: [batchTime] selects the timestamp, [nodeId] the folder drilled into. */
    data class Inside(val batchTime: Long, val nodeId: Long? = null) : RecycleLocation
}

@Composable
fun LibraryRecycleScreen(
    onOpenDrawer: () -> Unit,
    onCycleLayout: () -> Unit,
    onRefresh: () -> Unit,
) {
    var location by remember { mutableStateOf<RecycleLocation>(RecycleLocation.Root) }
    // Media viewer, opened by tapping an image/video tile inside a batch.
    var viewerItems by remember { mutableStateOf<List<FsNodeObject>?>(null) }
    var viewerStart by remember { mutableStateOf(0) }

    // Isolated selection — separate from the live library's, per `FOTLAB-UIXDES-000004`.
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectionActive by remember { mutableStateOf(false) }
    // Currently visible, selectable (real) node ids — drives the overflow trio.
    var candidateIds by remember { mutableStateOf<List<Long>>(emptyList()) }

    var deleteForeverConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun enterSelection(id: Long) {
        selectionActive = true
        selectedIds = selectedIds + id
    }

    fun toggleSelect(id: Long) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun exitSelection() {
        selectionActive = false
        selectedIds = emptySet()
    }

    // The bin drives its own back stack: pop the drilled folder, then drop to the root listing.
    BackHandler(enabled = location != RecycleLocation.Root) {
        location = when (val loc = location) {
            RecycleLocation.Root -> RecycleLocation.Root
            is RecycleLocation.Inside -> if (loc.nodeId != null) {
                RecycleLocation.Inside(loc.batchTime, null)
            } else {
                RecycleLocation.Root
            }
        }
    }
    // With the mode on, back exits the selection first (the X close does the same).
    BackHandler(enabled = selectionActive) { exitSelection() }

    Column(modifier = Modifier.fillMaxSize()) {
        RecycleTopBar(
            selectionActive = selectionActive,
            selectionSize = selectedIds.size,
            candidateIds = candidateIds,
            onOpenDrawer = onOpenDrawer,
            onCycleLayout = onCycleLayout,
            onRefresh = onRefresh,
            onExitSelection = { exitSelection() },
            onRestore = {
                scope.launch { LibraryCore.restoreFromBin(selectedIds.toList()) }
                exitSelection()
            },
            onDeleteForever = { deleteForeverConfirm = true },
            onSelectAll = {
                selectedIds = candidateIds.toSet()
                selectionActive = true
            },
            onInvert = {
                selectedIds = candidateIds.filterNot { it in selectedIds }.toSet()
                selectionActive = true
            },
            onDeselectAll = { selectedIds = emptySet() },
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (val loc = location) {
                RecycleLocation.Root -> RecycleRoot(
                    selectedIds = selectedIds,
                    selectionActive = selectionActive,
                    onToggleSelect = { node -> node.fsNodeId?.let { toggleSelect(it) } },
                    onLongPress = { node -> node.fsNodeId?.let { enterSelection(it) } },
                    onOpenBatch = { time -> location = RecycleLocation.Inside(time) },
                    onVisibleIds = { candidateIds = it },
                )
                is RecycleLocation.Inside -> RecycleBatch(
                    batchTime = loc.batchTime,
                    nodeId = loc.nodeId,
                    selectedIds = selectedIds,
                    selectionActive = selectionActive,
                    onToggleSelect = { node -> node.fsNodeId?.let { toggleSelect(it) } },
                    onLongPress = { node -> node.fsNodeId?.let { enterSelection(it) } },
                    onOpenNode = { node -> location = RecycleLocation.Inside(loc.batchTime, node.fsNodeId) },
                    onOpenMedia = { items, index -> viewerItems = items; viewerStart = index },
                    onVisibleIds = { candidateIds = it },
                )
            }
        }
    }

    if (viewerItems != null) {
        LibraryViewerDialog(items = viewerItems!!, startIndex = viewerStart, onDismiss = { viewerItems = null })
    }

    if (deleteForeverConfirm) {
        AlertDialog(
            onDismissRequest = { deleteForeverConfirm = false },
            title = { Text(text = stringResource(id = R.string.library_delete_forever_title)) },
            text = { Text(text = stringResource(id = R.string.library_delete_forever_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteForeverConfirm = false
                        scope.launch { LibraryCore.deleteForever(selectedIds.toList()) }
                        exitSelection()
                    },
                ) { Text(text = stringResource(id = R.string.common_action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteForeverConfirm = false }) {
                    Text(text = stringResource(id = R.string.common_action_cancel))
                }
            },
        )
    }
}

/**
 * Top bar for the recycle bin (`FOTLAB-UIXDES-000004`).
 *
 * - Idle: no action icons (the trailing overflow still offers select-all / invert / deselect);
 *   the leading cluster is drawer + layout + sync, exactly like the library.
 * - Selecting: `restore from bin` + `delete forever`; the leading cluster becomes the Close
 *   (exit the mode) and the selection count, mirroring the library's selection action mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecycleTopBar(
    selectionActive: Boolean,
    selectionSize: Int,
    candidateIds: List<Long>,
    onOpenDrawer: () -> Unit,
    onCycleLayout: () -> Unit,
    onRefresh: () -> Unit,
    onExitSelection: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onDeselectAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var overflowOpen by remember { mutableStateOf(false) }

    TopAppBar(
        title = {},
        modifier = modifier,
        navigationIcon = {
            if (!selectionActive) {
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
                    Text(
                        text = selectionSize.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        },
        actions = {
            // Selecting swaps the slot to the bin actions; idle shows nothing here (the overflow
            // trio is always available on the right).
            if (selectionActive) {
                IconButton(onClick = onRestore) {
                    Icon(
                        imageVector = Icons.Filled.RestoreFromTrash,
                        contentDescription = stringResource(id = R.string.library_cd_restore_from_bin),
                    )
                }
                IconButton(onClick = onDeleteForever) {
                    Icon(
                        imageVector = Icons.Filled.DeleteForever,
                        contentDescription = stringResource(id = R.string.library_cd_delete_forever),
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
                        leadingIcon = { Icon(imageVector = Icons.Filled.SelectAll, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onSelectAll()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(id = R.string.common_selection_invert)) },
                        leadingIcon = { Icon(imageVector = Icons.Filled.FlipToBack, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onInvert()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(id = R.string.common_selection_deselect_all)) },
                        leadingIcon = { Icon(imageVector = Icons.Filled.Deselect, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onDeselectAll()
                        },
                    )
                }
            }
        },
    )
}

/** Bin root: one virtual folder per delete batch, named by its shared timestamp. */
@Composable
private fun RecycleRoot(
    selectedIds: Set<Long>,
    selectionActive: Boolean,
    onToggleSelect: (FsNodeObject) -> Unit,
    onLongPress: (FsNodeObject) -> Unit,
    onOpenBatch: (Long) -> Unit,
    onVisibleIds: (List<Long>) -> Unit,
) {
    val batches by LibraryCore.deletedBatchTimes().collectAsState(initial = emptyList())
    val layoutMode by LibraryCore.layoutMode.collectAsState()

    // Virtual folders are not real nodes (no fs_node_id), so nothing is selectable at the root.
    LaunchedEffect(Unit) { onVisibleIds(emptyList()) }

    if (batches.isEmpty()) {
        Text(
            text = stringResource(id = R.string.library_recycle_bin_empty),
            modifier = Modifier.padding(16.dp),
        )
        return
    }

    // Virtual folders: not fs nodes. The timestamp lives in `timeCreated` so the batch can be
    // recovered from the clicked node without overloading `fsNodeId`.
    val nodes = remember(batches) {
        batches.map { time ->
            FsNodeObject(
                fsNodeId = null,
                nameDisplay = recycleBatchFormat.format(Date(time)),
                typeMime = MimeFolderDeleted,
                timeCreated = time,
            )
        }
    }

    NodeList(
        nodes = nodes,
        selectedIds = selectedIds,
        layoutMode = layoutMode,
        selectionActive = selectionActive,
        onNodeClick = { node ->
            if (selectionActive) onToggleSelect(node) else onOpenBatch(node.timeCreated)
        },
        onToggleSelect = onToggleSelect,
        onLongPress = onLongPress,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Inside a delete batch: the batch's directory tree, rebuilt from the edges stamped with the same
 * `time_deleted`. Roots are the nodes whose in-batch parent lies outside the batch (the directory
 * they were deleted from); drilling into a collection shows its in-batch children.
 */
@Composable
private fun RecycleBatch(
    batchTime: Long,
    nodeId: Long?,
    selectedIds: Set<Long>,
    selectionActive: Boolean,
    onToggleSelect: (FsNodeObject) -> Unit,
    onLongPress: (FsNodeObject) -> Unit,
    onOpenNode: (FsNodeObject) -> Unit,
    onOpenMedia: (List<FsNodeObject>, Int) -> Unit,
    onVisibleIds: (List<Long>) -> Unit,
) {
    val layoutMode by LibraryCore.layoutMode.collectAsState()
    val nodes by LibraryCore.recycleBatchNodes(batchTime).collectAsState(initial = emptyList())
    val relations by LibraryCore.recycleBatchRelations(batchTime).collectAsState(initial = emptyList())

    val batchIds = remember(nodes) { nodes.mapNotNull { it.fsNodeId }.toSet() }
    // child id -> parent id, only for edges whose both ends belong to this batch.
    val parentOf = remember(relations, batchIds) {
        relations.mapNotNull { r ->
            val child = r.fsNodeIdChild
            val parent = r.fsNodeIdParent
            if (child in batchIds && parent != null && parent in batchIds) child to parent else null
        }.toMap()
    }
    val childrenByParent = remember(nodes, parentOf) {
        nodes.groupBy { parentOf[it.fsNodeId] ?: ROOT_KEY }
    }

    val currentChildren = if (nodeId == null) {
        childrenByParent[ROOT_KEY].orEmpty()
    } else {
        childrenByParent[nodeId].orEmpty()
    }

    val visibleIds = currentChildren.mapNotNull { it.fsNodeId }
    LaunchedEffect(visibleIds) { onVisibleIds(visibleIds) }

    if (currentChildren.isEmpty()) {
        Text(
            text = stringResource(id = R.string.library_empty_directory),
            modifier = Modifier.padding(16.dp),
        )
        return
    }

    NodeList(
        nodes = currentChildren,
        selectedIds = selectedIds,
        layoutMode = layoutMode,
        selectionActive = selectionActive,
        onNodeClick = { node ->
            if (selectionActive) {
                onToggleSelect(node)
            } else {
                when {
                    node.isCollection() -> onOpenNode(node)
                    isMedia(node.typeMime) -> {
                        val media = currentChildren.filter { isMedia(it.typeMime) }
                        val start = media.indexOfFirst { it.fsNodeId == node.fsNodeId }.coerceAtLeast(0)
                        onOpenMedia(media, start)
                    }
                    // Plain files in the bin have no opener yet; ignore the tap.
                }
            }
        },
        onToggleSelect = onToggleSelect,
        onLongPress = onLongPress,
        modifier = Modifier.fillMaxSize(),
    )
}
