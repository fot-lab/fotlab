package io.github.fotlab.fotlab.feature.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.dp
import io.github.fotlab.fotlab.R
import io.github.fotlab.fotlab.feature.library.FsNodeObject
import io.github.fotlab.fotlab.feature.library.LibraryScreenFunBarHeight
import io.github.fotlab.fotlab.feature.library.MimeFolderDeleted
import io.github.fotlab.fotlab.feature.library.NodeList
import io.github.fotlab.fotlab.feature.library.SweepSyncIcon
import io.github.fotlab.fotlab.feature.library.isMedia
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Key under which a batch's root nodes are grouped (no in-batch parent). */
private const val ROOT_KEY = -1L

private val recycleBatchFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private sealed interface RecycleLocation {
    data object Root : RecycleLocation
    data class Inside(val batchTime: Long, val nodeId: Long? = null) : RecycleLocation
}

@Composable
fun GalleryRecycleScreen(
    onOpenDrawer: () -> Unit,
    onCycleLayout: () -> Unit,
    onRefresh: suspend () -> Unit,
    onOpenViewer: () -> Unit,
) {
    var location by remember { mutableStateOf<RecycleLocation>(RecycleLocation.Root) }

    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectionActive by remember { mutableStateOf(false) }
    var candidateIds by remember { mutableStateOf<List<Long>>(emptyList()) }

    var deleteForeverConfirm by remember { mutableStateOf(false) }
    var restoreConfirm by remember { mutableStateOf(false) }
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
    BackHandler(enabled = selectionActive) { exitSelection() }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        bottomBar = {
            RecycleScreenFunBar(
                selectionActive = selectionActive,
                selectionSize = selectedIds.size,
                candidateIds = candidateIds,
                onOpenDrawer = onOpenDrawer,
                onCycleLayout = onCycleLayout,
                onRefresh = onRefresh,
                onExitSelection = { exitSelection() },
                onRestore = { restoreConfirm = true },
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
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            when (val loc = location) {
                RecycleLocation.Root -> GalleryRecycleRoot(
                    selectedIds = selectedIds,
                    selectionActive = selectionActive,
                    onToggleSelectBatch = { _, batchIds ->
                        val isAllSelected = batchIds.all { it in selectedIds }
                        selectedIds = if (isAllSelected) selectedIds - batchIds else selectedIds + batchIds
                        selectionActive = true
                    },
                    onLongPressBatch = { _, batchIds ->
                        selectedIds = selectedIds + batchIds
                        selectionActive = true
                    },
                    onOpenBatch = { time -> location = RecycleLocation.Inside(time) },
                    onVisibleIds = { candidateIds = it },
                )
                is RecycleLocation.Inside -> GalleryRecycleBatch(
                    batchTime = loc.batchTime,
                    nodeId = loc.nodeId,
                    selectedIds = selectedIds,
                    selectionActive = selectionActive,
                    onToggleSelect = { node -> node.fsNodeId?.let { toggleSelect(it) } },
                    onLongPress = { node -> node.fsNodeId?.let { enterSelection(it) } },
                    onOpenNode = { node -> location = RecycleLocation.Inside(loc.batchTime, node.fsNodeId) },
                    onOpenMedia = { items, index ->
                        GalleryCore.setViewerSession(items, index)
                        onOpenViewer()
                    },
                    onVisibleIds = { candidateIds = it },
                )
            }
        }
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
                        val ids = selectedIds.toList()
                        scope.launch { GalleryCore.deleteForever(ids) }
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

    if (restoreConfirm) {
        AlertDialog(
            onDismissRequest = { restoreConfirm = false },
            title = { Text(text = stringResource(id = R.string.library_restore_batch_title)) },
            text = { Text(text = stringResource(id = R.string.library_restore_batch_message, selectedIds.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        restoreConfirm = false
                        val ids = selectedIds.toList()
                        scope.launch { GalleryCore.restoreFromBin(ids) }
                        exitSelection()
                    },
                ) { Text(text = stringResource(id = R.string.common_action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { restoreConfirm = false }) {
                    Text(text = stringResource(id = R.string.common_action_cancel))
                }
            },
        )
    }
}

@Composable
private fun RecycleScreenFunBar(
    selectionActive: Boolean,
    selectionSize: Int,
    candidateIds: List<Long>,
    onOpenDrawer: () -> Unit,
    onCycleLayout: () -> Unit,
    onRefresh: suspend () -> Unit,
    onExitSelection: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onDeselectAll: () -> Unit,
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
                .height(LibraryScreenFunBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                    SweepSyncIcon(onSweep = onRefresh)
                }
            } else {
                Row {
                    IconButton(onClick = onExitSelection) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(id = R.string.library_cd_clear_selection),
                        )
                    }
                    Box(
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

            Spacer(modifier = Modifier.weight(1f))

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
                        text = { Text(text = stringResource(id = R.string.common_selection_select_none)) },
                        leadingIcon = { Icon(imageVector = Icons.Filled.Deselect, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onDeselectAll()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryRecycleRoot(
    selectedIds: Set<Long>,
    selectionActive: Boolean,
    onToggleSelectBatch: (batchTime: Long, batchIds: List<Long>) -> Unit,
    onLongPressBatch: (batchTime: Long, batchIds: List<Long>) -> Unit,
    onOpenBatch: (Long) -> Unit,
    onVisibleIds: (List<Long>) -> Unit,
) {
    val batches by GalleryCore.deletedBatchTimes().collectAsState(initial = emptyList())
    val layoutMode by GalleryCore.layoutMode.collectAsState()

    var batchNodeIdsCache by remember { mutableStateOf<Map<Long, List<Long>>>(emptyMap()) }

    LaunchedEffect(batches) {
        val cache = mutableMapOf<Long, List<Long>>()
        for (time in batches) {
            cache[time] = GalleryCore.recycleBatchNodeIds(time)
        }
        batchNodeIdsCache = cache
    }

    if (batches.isEmpty()) {
        Text(
            text = stringResource(id = R.string.library_recycle_bin_empty),
            modifier = Modifier.padding(16.dp),
        )
        return
    }

    val allNodeIds = batchNodeIdsCache.values.flatten()
    LaunchedEffect(allNodeIds) { onVisibleIds(allNodeIds) }

    val virtualSelectedKeys = remember(selectedIds, batchNodeIdsCache) {
        batchNodeIdsCache.mapNotNull { (time, ids) ->
            if (ids.isNotEmpty() && ids.all { it in selectedIds }) time else null
        }.toSet()
    }

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
        virtualSelectedKeys = virtualSelectedKeys,
        onNodeClick = { node ->
            if (selectionActive) {
                val time = node.timeCreated
                val ids = batchNodeIdsCache[time].orEmpty()
                if (ids.isNotEmpty()) onToggleSelectBatch(time, ids)
            } else {
                onOpenBatch(node.timeCreated)
            }
        },
        onToggleSelect = { node ->
            val time = node.timeCreated
            val ids = batchNodeIdsCache[time].orEmpty()
            if (ids.isNotEmpty()) onToggleSelectBatch(time, ids)
        },
        onLongPress = { node ->
            val time = node.timeCreated
            val ids = batchNodeIdsCache[time].orEmpty()
            if (ids.isNotEmpty()) onLongPressBatch(time, ids)
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun GalleryRecycleBatch(
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
    val layoutMode by GalleryCore.layoutMode.collectAsState()
    val nodes by GalleryCore.recycleBatchNodes(batchTime).collectAsState(initial = emptyList())
    val relations by GalleryCore.recycleBatchRelations(batchTime).collectAsState(initial = emptyList())

    val batchIds = remember(nodes) { nodes.mapNotNull { it.fsNodeId }.toSet() }
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
                    node.isGalleryCollection() -> onOpenNode(node)
                    isMedia(node.typeMime) -> {
                        val media = currentChildren.filter { isMedia(it.typeMime) }
                        val start = media.indexOfFirst { it.fsNodeId == node.fsNodeId }.coerceAtLeast(0)
                        onOpenMedia(media, start)
                    }
                }
            }
        },
        onToggleSelect = onToggleSelect,
        onLongPress = onLongPress,
        modifier = Modifier.fillMaxSize(),
    )
}
