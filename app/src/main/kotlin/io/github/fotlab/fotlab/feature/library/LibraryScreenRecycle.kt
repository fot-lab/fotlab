package io.github.fotlab.fotlab.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.fotlab.fotlab.R
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
 * The screen owns its own two-level navigation ([RecycleLocation]); it reads the library's layout
 * mode and reuses the generic [NodeList]/[NodeCell] for rendering but never touches the live
 * library selection or tree, keeping the two views isolated (`FOTLAB-UIXDES-000004`).
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
fun LibraryRecycleScreen() {
    var location by remember { mutableStateOf<RecycleLocation>(RecycleLocation.Root) }
    // Media viewer, opened by tapping an image/video tile inside a batch.
    var viewerItems by remember { mutableStateOf<List<FsNodeObject>?>(null) }
    var viewerStart by remember { mutableStateOf(0) }

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

    when (val loc = location) {
        RecycleLocation.Root -> RecycleRoot(
            onOpenBatch = { time -> location = RecycleLocation.Inside(time) },
        )
        is RecycleLocation.Inside -> RecycleBatch(
            batchTime = loc.batchTime,
            nodeId = loc.nodeId,
            onOpenNode = { node -> location = RecycleLocation.Inside(loc.batchTime, node.fsNodeId) },
            onOpenMedia = { items, index -> viewerItems = items; viewerStart = index },
        )
    }

    if (viewerItems != null) {
        LibraryViewerDialog(items = viewerItems!!, startIndex = viewerStart, onDismiss = { viewerItems = null })
    }
}

/** Bin root: one virtual folder per delete batch, named by its shared timestamp. */
@Composable
private fun RecycleRoot(onOpenBatch: (Long) -> Unit) {
    val batches by LibraryCore.deletedBatchTimes().collectAsState(initial = emptyList())
    val layoutMode by LibraryCore.layoutMode.collectAsState()

    if (batches.isEmpty()) {
        Text(
            text = stringResource(id = R.string.library_recycle_bin_empty),
            modifier = Modifier.padding(16.dp),
        )
        return
    }

    // Virtual folders: not fs nodes. The timestamp lives in `timeCreated` so the batch can be
    // recovered from the clicked node without overloading `fsNodeId`.
    val nodes = batches.map { time ->
        FsNodeObject(
            fsNodeId = null,
            nameDisplay = recycleBatchFormat.format(Date(time)),
            typeMime = MimeFolderDeleted,
            timeCreated = time,
        )
    }

    NodeList(
        nodes = nodes,
        selectedIds = emptySet(),
        layoutMode = layoutMode,
        selectionActive = false,
        onNodeClick = { onOpenBatch(it.timeCreated) },
        onToggleSelect = {},
        onLongPress = {},
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
    onOpenNode: (FsNodeObject) -> Unit,
    onOpenMedia: (List<FsNodeObject>, Int) -> Unit,
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

    if (currentChildren.isEmpty()) {
        Text(
            text = stringResource(id = R.string.library_empty_directory),
            modifier = Modifier.padding(16.dp),
        )
        return
    }

    NodeList(
        nodes = currentChildren,
        selectedIds = emptySet(),
        layoutMode = layoutMode,
        selectionActive = false,
        onNodeClick = { node ->
            when {
                node.isCollection() -> onOpenNode(node)
                isMedia(node.typeMime) -> {
                    val media = currentChildren.filter { isMedia(it.typeMime) }
                    val start = media.indexOfFirst { it.fsNodeId == node.fsNodeId }.coerceAtLeast(0)
                    onOpenMedia(media, start)
                }
                // Plain files in the bin have no opener yet; ignore the tap.
            }
        },
        onToggleSelect = {},
        onLongPress = {},
        modifier = Modifier.fillMaxSize(),
    )
}
