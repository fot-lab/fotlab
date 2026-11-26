package io.github.fotlab.fotlab.feature.camera

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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.fotlab.fotlab.R

/** Height shared by the camera fun bar and the drawer's in-place close row. */
internal val CameraScreenFunBarHeight = 64.dp

/**
 * The camera fun bar: module-owned, pinned to the bottom of the screen's content region
 * (`FOTLAB-UIXDES-000002` R1). The drawer icon is always the leftmost element (R2), the overflow
 * is always the rightmost one (R4); the file actions live in the overflow because they are
 * secondary to the connect/browse flow in the content region. Popups anchored here open upward.
 */
@Composable
internal fun CameraScreenFunBar(
    canUpload: Boolean,
    canDelete: Boolean,
    onOpenDrawer: () -> Unit,
    onUpload: () -> Unit,
    onDelete: () -> Unit,
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
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = stringResource(id = R.string.common_drawer_open),
                )
            }

            Spacer(modifier = Modifier.weight(1f))

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
                        text = { Text(text = stringResource(id = R.string.camera_action_upload)) },
                        leadingIcon = {
                            Icon(
                            imageVector = Icons.Filled.Upload,
                            contentDescription = stringResource(id = R.string.camera_cd_upload),
                        )
                        },
                        enabled = canUpload,
                        onClick = {
                            overflowOpen = false
                            onUpload()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(id = R.string.camera_action_delete)) },
                        leadingIcon = {
                            Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(id = R.string.camera_cd_delete),
                        )
                        },
                        enabled = canDelete,
                        onClick = {
                            overflowOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/**
 * The camera drawer, at 80% of the module width (`FOTLAB-UIXDES-000002` R3). Its bottom-left close
 * button shares the fun bar's height and inset so opening the drawer replaces the menu icon in
 * place (R6). Content is module-private (R5): only discovery/transfer help and this module's own
 * discovery settings, no app-level entries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CameraDrawer(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth(0.8f),
    ) {
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
