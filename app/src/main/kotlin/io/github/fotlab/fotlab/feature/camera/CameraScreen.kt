package io.github.fotlab.fotlab.feature.camera

import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fotlab.fotlab.R
import io.github.fotlab.fotlab.feature.camera.discovery.CameraDevice
import io.github.fotlab.fotlab.feature.camera.model.CameraEventCode
import io.github.fotlab.fotlab.feature.camera.model.CameraLink
import io.github.fotlab.fotlab.feature.camera.model.PTP_PARENT_ROOT
import io.github.fotlab.fotlab.feature.camera.model.PtpMediaFilter
import io.github.fotlab.fotlab.feature.camera.model.PtpObject
import io.github.fotlab.fotlab.feature.camera.model.PtpStorage
import io.github.fotlab.fotlab.feature.camera.model.TransferProgress
import io.github.fotlab.fotlab.feature.camera.model.TransferState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** One level of the folder path the user descended into. */
private data class CameraFolder(
    val handle: Long,
    val name: String,
)

/**
 * Camera screen (UI) — the third top-level destination, owned by `feature/camera` alongside
 * [CameraCore]. It fills the region below the shell's nav bar and follows the module chrome
 * contract: a `ModalNavigationDrawer` wrapping a module-level `Scaffold`, whose `bottomBar` hosts
 * the fun bar (`FOTLAB-UIXDES-000002` R1–R6). The shell zeroed its own content insets; this
 * Scaffold consumes the navigation-bar inset its fun bar does not, exactly once.
 *
 * The content region has two states. While idle it lists discovered devices (USB and network)
 * and accepts a manually entered address; the primary action there is Connect. Once connected it
 * browses the camera's storages as folders, lets the user select objects, and offers Download as
 * the primary action while Upload and the destructive Delete sit in the fun bar's drop-up
 * overflow. Downloads run through [CameraTransferService] so they survive leaving the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val devices by CameraCore.devices.collectAsState()
    val connectionState = CameraCore.state.collectAsState()
    val connection = connectionState.value

    var hostInput by remember { mutableStateOf("") }
    var manualDevices by remember { mutableStateOf<List<CameraDevice.Ip>>(emptyList()) }
    var storages by remember { mutableStateOf<List<PtpStorage>>(emptyList()) }
    var selectedStorage by remember { mutableStateOf<Long?>(null) }
    var path by remember { mutableStateOf<List<CameraFolder>>(emptyList()) }
    var filter by remember { mutableStateOf(PtpMediaFilter.ALL) }
    var objects by remember { mutableStateOf<List<PtpObject>>(emptyList()) }
    var selection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<TransferProgress?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    suspend fun reloadObjects() {
        val storage = selectedStorage ?: return
        val parent = path.lastOrNull()?.handle ?: PTP_PARENT_ROOT
        objects = CameraCore.objects(storage, parent, filter)
        selection = emptySet()
    }

    suspend fun reloadFiles() {
        val loaded = CameraCore.storages()
        storages = loaded
        val storage = selectedStorage?.takeIf { id -> loaded.any { it.id == id } }
            ?: loaded.firstOrNull()?.id
        selectedStorage = storage
        objects = if (storage != null) {
            CameraCore.objects(storage, path.lastOrNull()?.handle ?: PTP_PARENT_ROOT, filter)
        } else {
            emptyList()
        }
        selection = emptySet()
    }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val storage = selectedStorage ?: 0L
            val parent = path.lastOrNull()?.handle ?: PTP_PARENT_ROOT
            scope.launch {
                busy = true
                errorMessage = null
                try {
                    CameraCore.upload(uri, storage, parent)
                    reloadObjects()
                } catch (error: Throwable) {
                    errorMessage = error.message ?: error.toString()
                }
                busy = false
            }
        }
    }

    fun startDownload() {
        val handles = selection.toLongArray()
        if (handles.isEmpty()) return
        val intent = Intent(context, CameraTransferService::class.java).apply {
            action = CameraTransferService.ACTION_DOWNLOAD
            putExtra(CameraTransferService.EXTRA_HANDLES, handles)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            @Suppress("DEPRECATION")
            context.startService(intent)
        }
    }

    BackHandler(enabled = drawerState.isOpen || path.isNotEmpty()) {
        scope.launch {
            when {
                drawerState.isOpen -> drawerState.close()
                else -> {
                    path = path.dropLast(1)
                    reloadObjects()
                }
            }
        }
    }

    LaunchedEffect(connection) {
        if (connection is CameraConnectionState.Connected) {
            errorMessage = null
            runCatching { reloadFiles() }.onFailure { error ->
                errorMessage = error.message ?: error.toString()
            }
        } else {
            storages = emptyList()
            selectedStorage = null
            path = emptyList()
            objects = emptyList()
            selection = emptySet()
            progress = null
        }
    }

    LaunchedEffect(connection is CameraConnectionState.Connected) {
        if (connection is CameraConnectionState.Connected) {
            // The repository belongs to the session; if the link drops between the
            // state check and this call, the session is gone and there is nothing
            // left to observe.
            runCatching { CameraCore.repository() }.getOrNull()?.progress
                ?.collect { value -> progress = value }
        }
    }

    // A camera that changes on its own — a new capture, a deleted object — refreshes
    // the listing so the screen never shows a stale directory.
    LaunchedEffect(connection is CameraConnectionState.Connected) {
        if (connection is CameraConnectionState.Connected) {
            CameraCore.events.collect { event ->
                when (event.code) {
                    CameraEventCode.OBJECT_ADDED,
                    CameraEventCode.OBJECT_REMOVED,
                    CameraEventCode.OBJECT_INFO_CHANGED,
                    -> runCatching { reloadObjects() }

                    else -> Unit
                }
            }
        }
    }

    val visibleDevices = (devices + manualDevices).distinctBy { it.id }
    val connected = connection is CameraConnectionState.Connected
    val canDelete = connected && selection.isNotEmpty()
    val canDownload = connected && selection.isNotEmpty()

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            CameraDrawer(onClose = { scope.launch { drawerState.close() } })
        },
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.navigationBars,
            bottomBar = {
                CameraScreenFunBar(
                    canUpload = connected,
                    canDelete = canDelete,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onUpload = { uploadLauncher.launch(arrayOf("*/*")) },
                    onDelete = { confirmDelete = true },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .padding(horizontal = 16.dp),
            ) {
                val status = when (connection) {
                    is CameraConnectionState.Idle -> stringResource(id = R.string.camera_status_idle)
                    is CameraConnectionState.Connecting -> stringResource(
                        id = R.string.camera_status_connecting,
                        connection.device.displayName,
                    )
                    is CameraConnectionState.Connected -> stringResource(
                        id = R.string.camera_status_connected,
                        connection.device.displayName,
                    )
                    is CameraConnectionState.Failed -> stringResource(
                        id = R.string.camera_status_failed,
                        connection.device.displayName,
                    )
                }
                Text(text = status, style = MaterialTheme.typography.titleMedium)

                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                errorMessage?.let { message ->
                    Text(
                        text = stringResource(id = R.string.camera_error, message),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                progress?.let { value ->
                    val label = when (value.state) {
                        TransferState.RUNNING -> stringResource(
                            id = R.string.camera_transfer_progress,
                            value.objectName,
                            (value.fraction * 100).toInt(),
                        )
                        TransferState.COMPLETED -> stringResource(id = R.string.camera_transfer_completed)
                        TransferState.FAILED -> stringResource(id = R.string.camera_transfer_failed)
                        TransferState.CANCELLED -> stringResource(id = R.string.camera_transfer_cancelled)
                    }
                    Text(text = label, style = MaterialTheme.typography.bodySmall)
                }

                if (connection is CameraConnectionState.Connected) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = { startDownload() }, enabled = canDownload) {
                            Text(text = stringResource(id = R.string.camera_action_download))
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { CameraCore.disconnect() } }) {
                            Text(text = stringResource(id = R.string.camera_action_disconnect))
                        }
                    }

                    if (storages.size > 1) {
                        LazyRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            items(storages, key = { it.id }) { storage ->
                                FilterChip(
                                    selected = storage.id == selectedStorage,
                                    onClick = {
                                        selectedStorage = storage.id
                                        path = emptyList()
                                        scope.launch {
                                            busy = true
                                            runCatching { reloadObjects() }
                                                .onFailure { error ->
                                                    errorMessage = error.message ?: error.toString()
                                                }
                                            busy = false
                                        }
                                    },
                                    label = { Text(text = storageLabel(storage)) },
                                )
                            }
                        }
                    }

                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(PtpMediaFilter.entries.toList(), key = { it.name }) { entry ->
                            FilterChip(
                                selected = filter == entry,
                                onClick = {
                                    filter = entry
                                    scope.launch {
                                        busy = true
                                        runCatching { reloadObjects() }
                                            .onFailure { error ->
                                                errorMessage = error.message ?: error.toString()
                                            }
                                        busy = false
                                    }
                                },
                                label = { Text(text = stringResource(id = filterLabel(entry))) },
                            )
                        }
                    }

                    if (path.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = {
                                path = path.dropLast(1)
                                scope.launch {
                                    busy = true
                                    runCatching { reloadObjects() }
                                        .onFailure { error ->
                                            errorMessage = error.message ?: error.toString()
                                        }
                                    busy = false
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowBack,
                                    contentDescription = stringResource(id = R.string.camera_action_up),
                                )
                            }
                            Text(
                                text = path.last().name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                        if (objects.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(id = R.string.camera_empty_files),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        } else {
                            items(objects, key = { it.handle }) { item ->
                                if (item.isFolder) {
                                    FolderRow(item.name) {
                                        path = path + CameraFolder(item.handle, item.name)
                                        scope.launch {
                                            busy = true
                                            runCatching { reloadObjects() }
                                                .onFailure { error ->
                                                    errorMessage = error.message ?: error.toString()
                                                }
                                            busy = false
                                        }
                                    }
                                } else {
                                    ObjectRow(
                                        item = item,
                                        checked = item.handle in selection,
                                        onCheckedChange = { checked ->
                                            selection = if (checked) {
                                                selection + item.handle
                                            } else {
                                                selection - item.handle
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = stringResource(id = R.string.camera_section_devices),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = hostInput,
                            onValueChange = { hostInput = it },
                            label = { Text(text = stringResource(id = R.string.camera_hint_host)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        TextButton(onClick = {
                            val host = hostInput.trim()
                            if (host.isNotEmpty()) {
                                manualDevices = manualDevices + CameraDevice.Ip(host = host)
                                hostInput = ""
                            }
                        }) {
                            Text(text = stringResource(id = R.string.camera_action_add))
                        }
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                errorMessage = null
                                runCatching { CameraCore.discover() }.onFailure { error ->
                                    errorMessage = error.message ?: error.toString()
                                }
                                busy = false
                            }
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(text = stringResource(id = R.string.camera_action_refresh))
                    }

                    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                        if (visibleDevices.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(id = R.string.camera_empty_devices),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        } else {
                            items(visibleDevices, key = { it.id }) { device ->
                                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = device.displayName,
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                            Text(
                                                text = stringResource(
                                                    id = if (device.link == CameraLink.USB) {
                                                        R.string.camera_label_usb
                                                    } else {
                                                        R.string.camera_label_network
                                                    },
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        Button(onClick = {
                                            scope.launch {
                                                busy = true
                                                errorMessage = null
                                                runCatching { CameraCore.connect(device) }.onFailure { error ->
                                                    errorMessage = error.message ?: error.toString()
                                                }
                                                busy = false
                                            }
                                        }) {
                                            Text(text = stringResource(id = R.string.camera_action_connect))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(text = stringResource(id = R.string.camera_delete_title)) },
            text = {
                Text(text = stringResource(id = R.string.camera_delete_message, selection.size))
            },
            confirmButton = {
                TextButton(onClick = {
                    val handles = selection.toList()
                    confirmDelete = false
                    scope.launch {
                        busy = true
                        runCatching {
                            CameraCore.delete(handles)
                            reloadFiles()
                        }.onFailure { error ->
                            errorMessage = error.message ?: error.toString()
                        }
                        busy = false
                    }
                }) {
                    Text(text = stringResource(id = R.string.camera_action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(text = stringResource(id = R.string.camera_action_cancel))
                }
            },
        )
    }
}

/** A folder row; tapping it descends one level. */
@Composable
private fun FolderRow(name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = Icons.Filled.Folder, contentDescription = null)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A media row with its selection box. */
@Composable
private fun ObjectRow(
    item: PtpObject,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(id = R.string.camera_label_size, item.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Storage label: its description when the device reports one, otherwise its id. */
private fun storageLabel(storage: PtpStorage): String =
    storage.description.ifBlank { storage.id.toString() }

/** Copy for one media filter chip. */
private fun filterLabel(filter: PtpMediaFilter): Int = when (filter) {
    PtpMediaFilter.ALL -> R.string.camera_filter_all
    PtpMediaFilter.IMAGE -> R.string.camera_filter_image
    PtpMediaFilter.VIDEO -> R.string.camera_filter_video
}
