package io.github.fotlab.fotlab.feature.camera

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.fotlab.fotlab.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Height shared by the camera fun bar and the drawer's in-place close row. */
internal val CameraScreenFunBarHeight = 64.dp

/**
 * The camera fun bar: module-owned, pinned to the bottom of the screen's content region
 * (`FOTLAB-UIXDES-000002` R1).
 *
 * The leading cluster carries the three affordances that are always meaningful: the drawer
 * toggle (the three-line glyph opens the drawer, the close glyph closes it again), the layout
 * switch (list ↔ thumbnail grid) and the refresh that re-reads the folder currently on screen.
 *
 * The trailing cluster carries the overflow menu — the selection trio, shared with the
 * library — plus two slots whose glyph depends on whether anything is selected. With a
 * selection they offer download and delete; without one they offer upload and new folder.
 * The two pairs are mutually exclusive by construction: upload and download are the two ends
 * of one transfer, and a new folder and a delete are the two ends of one destructive action.
 */
@Composable
internal fun CameraScreenFunBar(
    drawerOpen: Boolean,
    gridMode: Boolean,
    gridEnabled: Boolean,
    refreshEnabled: Boolean,
    hasSelection: Boolean,
    deleteEnabled: Boolean,
    uploadEnabled: Boolean,
    downloadEnabled: Boolean,
    createFolderEnabled: Boolean,
    onToggleDrawer: () -> Unit,
    onToggleGrid: () -> Unit,
    // Suspending, not a plain callback: the icon owns the refresh's lifetime, so its rotation
    // can be scoped to it — the same contract the library's sweep icon keeps.
    onRefresh: suspend () -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onSelectNone: () -> Unit,
    onCreateFolder: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
    onUpload: () -> Unit,
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
                .height(CameraScreenFunBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The drawer toggle keeps the slot filled in both states: the three-line glyph opens
            // the drawer, the close glyph closes it again.
            IconButton(onClick = onToggleDrawer) {
                Icon(
                    imageVector = if (drawerOpen) Icons.Filled.Close else Icons.Filled.Menu,
                    contentDescription = stringResource(
                        id = if (drawerOpen) {
                            R.string.common_drawer_close
                        } else {
                            R.string.common_drawer_open
                        },
                    ),
                )
            }

            IconButton(onClick = onToggleGrid, enabled = gridEnabled) {
                Icon(
                    // The glyph names the layout the button switches to, the way the library's
                    // layout switch does.
                    imageVector = if (gridMode) Icons.Filled.ViewList else Icons.Filled.GridView,
                    contentDescription = stringResource(
                        id = if (gridMode) {
                            R.string.camera_cd_layout_list
                        } else {
                            R.string.camera_cd_layout_grid
                        },
                    ),
                )
            }

            CameraRefreshIcon(onRefresh = onRefresh, enabled = refreshEnabled)

            Spacer(modifier = Modifier.weight(1f))

            // Slot A: upload without a selection, download with one. The two are the two ends of
            // one transfer, so they share the slot.
            IconButton(
                onClick = if (hasSelection) onDownload else onUpload,
                enabled = if (hasSelection) downloadEnabled else uploadEnabled,
            ) {
                Icon(
                    imageVector = if (hasSelection) Icons.Filled.Download else Icons.Filled.Upload,
                    contentDescription = stringResource(
                        id = if (hasSelection) {
                            R.string.camera_cd_download
                        } else {
                            R.string.camera_cd_upload
                        },
                    ),
                )
            }

            // Slot B: a new folder without a selection, delete with one.
            IconButton(
                onClick = if (hasSelection) onDelete else onCreateFolder,
                enabled = if (hasSelection) deleteEnabled else createFolderEnabled,
            ) {
                Icon(
                    imageVector = if (hasSelection) Icons.Filled.Delete else Icons.Filled.CreateNewFolder,
                    contentDescription = stringResource(
                        id = if (hasSelection) {
                            R.string.camera_cd_delete
                        } else {
                            R.string.camera_cd_new_folder
                        },
                    ),
                )
            }

            Box {
                IconButton(onClick = { overflowOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(id = R.string.camera_cd_more_options),
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
                            onSelectAll()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(id = R.string.common_selection_invert)) },
                        leadingIcon = {
                            Icon(imageVector = Icons.Filled.FlipToBack, contentDescription = null)
                        },
                        onClick = {
                            overflowOpen = false
                            onInvert()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(id = R.string.common_selection_select_none)) },
                        leadingIcon = {
                            Icon(imageVector = Icons.Filled.Deselect, contentDescription = null)
                        },
                        onClick = {
                            overflowOpen = false
                            onSelectNone()
                        },
                    )
                }
            }
        }
    }
}

/**
 * The refresh glyph. It spins for the length of the refresh it starts and stops on whichever
 * comes first: the refresh returning, or a one-minute animation ceiling — the same contract
 * the library's sweep icon keeps.
 */
@Composable
private fun CameraRefreshIcon(onRefresh: suspend () -> Unit, enabled: Boolean) {
    var spinning by remember { mutableStateOf(false) }
    val rotation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(spinning) {
        if (spinning) {
            while (true) {
                rotation.snapTo(0f)
                rotation.animateTo(
                    targetValue = 360f,
                    animationSpec = tween(durationMillis = 1_000, easing = LinearEasing),
                )
            }
        } else {
            val nextWholeTurn = kotlin.math.ceil(rotation.value / 360f) * 360f
            val remaining = nextWholeTurn - rotation.value
            if (remaining > 0f) {
                rotation.animateTo(
                    targetValue = nextWholeTurn,
                    animationSpec = tween(
                        durationMillis = (remaining / 360f * 1_000f).toInt().coerceAtLeast(1),
                        easing = LinearEasing,
                    ),
                )
            }
            rotation.snapTo(0f)
        }
    }

    IconButton(
        onClick = {
            if (spinning || !enabled) return@IconButton
            spinning = true
            scope.launch {
                try { onRefresh() } finally { spinning = false }
            }
            // The ceiling, deliberately not wrapping `onRefresh`.
            scope.launch {
                delay(REFRESH_SPIN_CEILING_MS)
                spinning = false
            }
        },
        enabled = enabled,
    ) {
        Icon(
            imageVector = Icons.Filled.Sync,
            contentDescription = stringResource(id = R.string.camera_cd_refresh),
            modifier = Modifier.graphicsLayer { rotationZ = rotation.value % 360f },
        )
    }
}

/**
 * The camera drawer, at 80% of the module width (`FOTLAB-UIXDES-000002` R3). Its bottom-left
 * close button shares the fun bar's height and inset so opening the drawer replaces the menu
 * icon in place (R6). Content is module-private (R5): only the view tabs, discovery/transfer
 * help and this module's own discovery settings, no app-level entries.
 *
 * The tabs sit at the very top of the drawer and switch the **content region**, the way the
 * library's drawer switches between its views (`FOTLAB-UIXDES-000004`): picking a tab closes
 * the drawer, so the user lands on the view they chose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CameraDrawer(
    viewMode: CameraViewMode,
    onSelectView: (CameraViewMode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth(0.8f),
    ) {
        TabRow(selectedTabIndex = viewMode.ordinal) {
            CameraViewMode.entries.forEach { entry ->
                Tab(
                    selected = viewMode == entry,
                    onClick = {
                        onSelectView(entry)
                        onClose()
                    },
                    text = { Text(text = stringResource(id = entry.labelRes)) },
                )
            }
        }

        Text(
            text = stringResource(id = R.string.app_nav_camera_label),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        Text(
            text = stringResource(id = R.string.camera_drawer_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        // The module's own settings: the two discovery knobs, both configurable with
        // an unset (first install) value meaning "use the default".
        val settings by CameraCore.settings.collectAsState()
        Text(
            text = stringResource(id = R.string.camera_drawer_settings_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
        )

        StepperSetting(
            title = stringResource(id = R.string.camera_setting_mdns_timeout),
            supporting = stringResource(id = R.string.camera_setting_mdns_timeout_supporting),
            value = settings.mdnsTimeoutSeconds,
            step = 1,
            minimum = CameraSettings.MIN_MDNS_TIMEOUT_SECONDS,
            maximum = CameraSettings.MAX_MDNS_TIMEOUT_SECONDS,
            onChange = { CameraCore.setMdnsTimeoutSeconds(it) },
        )

        StepperSetting(
            title = stringResource(id = R.string.camera_setting_keepalive),
            supporting = stringResource(id = R.string.camera_setting_keepalive_supporting),
            value = settings.keepaliveIntervalSeconds,
            step = 5,
            minimum = CameraSettings.MIN_KEEPALIVE_INTERVAL_SECONDS,
            maximum = CameraSettings.MAX_KEEPALIVE_INTERVAL_SECONDS,
            onChange = { CameraCore.setKeepaliveIntervalSeconds(it) },
        )

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(CameraScreenFunBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(id = R.string.common_drawer_close),
                )
            }
        }
    }
}

/**
 * One numeric setting row: a title, a supporting line, the current value and a pair of
 * step buttons.
 *
 * A stepper rather than a free-text field on purpose — the two values are bounded and
 * coarse (seconds), and a stepper cannot produce an out-of-range or unparsable value.
 * The value is clamped to [minimum]..[maximum] here, so the buttons stop at the ends
 * rather than silently storing something the clamp would undo.
 */
@Composable
private fun StepperSetting(
    title: String,
    supporting: String,
    value: Int,
    step: Int,
    minimum: Int,
    maximum: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = { onChange((value - step).coerceAtLeast(minimum)) },
            enabled = value > minimum,
        ) {
            Icon(
                imageVector = Icons.Filled.Remove,
                contentDescription = stringResource(id = R.string.camera_setting_decrease, title),
            )
        }
        Text(
            text = stringResource(id = R.string.camera_setting_seconds, value),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(48.dp),
            textAlign = TextAlign.Center,
        )
        IconButton(
            onClick = { onChange((value + step).coerceAtMost(maximum)) },
            enabled = value < maximum,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(id = R.string.camera_setting_increase, title),
            )
        }
    }
}

/** The animation ceiling of the refresh glyph: one minute, then the spin stops on its own. */
private const val REFRESH_SPIN_CEILING_MS: Long = 60_000L
