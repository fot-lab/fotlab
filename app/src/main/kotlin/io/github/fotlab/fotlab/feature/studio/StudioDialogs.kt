package io.github.fotlab.fotlab.feature.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.fotlab.fotlab.R
import kotlin.math.roundToInt
import io.github.fotlab.fotlab.feature.studio.StudioEngine
import io.github.fotlab.fotlab_rawler.CaSettings
import io.github.fotlab.fotlab_rawler.DehazeMergeMode
import io.github.fotlab.fotlab_rawler.LocaSettings

/**
 * LCP lens-profile user focal-length override dialog. The OK action is owned by the caller (it writes
 * the engine and closes the dialog); this composable only renders the field and reports its value.
 */
@Composable
internal fun LcpFocalDialog(
    value: String,
    onValueChange: (String) -> Unit,
    rawFocalLengthMm: Float?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(id = R.string.common_action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.common_action_cancel))
            }
        },
        title = { Text(text = stringResource(id = R.string.studio_lcp_focal_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(
                        id = R.string.studio_lcp_focal_hint,
                        rawFocalLengthMm?.let { "%.0f".format(it) }
                            ?: stringResource(id = R.string.studio_lcp_focal_unknown),
                        StudioEngine.defaultLcpFocalMm.toInt(),
                    ),
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(text = stringResource(id = R.string.studio_lcp_focal_unit)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
    )
}

/** Unsupported-format dialog — a single OK that just dismisses. */
@Composable
internal fun UnsupportedDialog(
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.common_action_ok))
            }
        },
        title = { Text(text = stringResource(id = R.string.studio_unsupported_title)) },
        text = { Text(text = stringResource(id = R.string.studio_unsupported_format)) },
    )
}

/**
 * LCA (chromatic-aberration correction) dialog. Auto mode fits the residual-CA polynomial natively;
 * otherwise the manual red/blue strengths apply. OK is always enabled: auto mode needs no numbers, and
 * an empty manual field parses to 0 (= no shift for that channel). The confirm action is the caller's.
 */
@Composable
internal fun CaDialog(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    auto: Boolean,
    onAutoChange: (Boolean) -> Unit,
    red: String,
    onRedChange: (String) -> Unit,
    blue: String,
    onBlueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(id = R.string.common_action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.common_action_cancel))
            }
        },
        title = { Text(text = stringResource(id = R.string.studio_ca_title)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(id = R.string.studio_enable_stage))
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(id = R.string.studio_ca_auto_label))
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = auto, onCheckedChange = onAutoChange, enabled = enabled)
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = red,
                    onValueChange = onRedChange,
                    enabled = enabled && !auto,
                    singleLine = true,
                    placeholder = { Text(text = stringResource(id = R.string.studio_ca_red_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = blue,
                    onValueChange = onBlueChange,
                    enabled = enabled && !auto,
                    singleLine = true,
                    placeholder = { Text(text = stringResource(id = R.string.studio_ca_blue_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
    )
}

/**
 * LoCA (longitudinal / axial CA) dialog. Only the two PEER switches (purple / green) are exposed; the
 * master switch is derived by the caller. Strength / luminance fields default to platform values as
 * placeholders and are editable only while their pair switch is on. OK is always enabled.
 */
@Composable
internal fun LocaDialog(
    purpleEnabled: Boolean,
    onPurpleEnabledChange: (Boolean) -> Unit,
    greenEnabled: Boolean,
    onGreenEnabledChange: (Boolean) -> Unit,
    purpleStrength: String,
    onPurpleStrengthChange: (String) -> Unit,
    greenStrength: String,
    onGreenStrengthChange: (String) -> Unit,
    purpleLum: String,
    onPurpleLumChange: (String) -> Unit,
    greenLum: String,
    onGreenLumChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(id = R.string.common_action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.common_action_cancel))
            }
        },
        title = { Text(text = stringResource(id = R.string.studio_loca_title)) },
        text = {
            Column {
                Text(text = stringResource(id = R.string.studio_loca_body))
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(id = R.string.studio_loca_purple_label))
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = purpleEnabled, onCheckedChange = onPurpleEnabledChange)
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = purpleStrength,
                    onValueChange = onPurpleStrengthChange,
                    enabled = purpleEnabled,
                    singleLine = true,
                    placeholder = { Text(text = stringResource(id = R.string.studio_loca_purple_strength_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = purpleLum,
                    onValueChange = onPurpleLumChange,
                    enabled = purpleEnabled,
                    singleLine = true,
                    placeholder = { Text(text = stringResource(id = R.string.studio_loca_purple_lum_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(id = R.string.studio_loca_green_label))
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = greenEnabled, onCheckedChange = onGreenEnabledChange)
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = greenStrength,
                    onValueChange = onGreenStrengthChange,
                    enabled = greenEnabled,
                    singleLine = true,
                    placeholder = { Text(text = stringResource(id = R.string.studio_loca_green_strength_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = greenLum,
                    onValueChange = onGreenLumChange,
                    enabled = greenEnabled,
                    singleLine = true,
                    placeholder = { Text(text = stringResource(id = R.string.studio_loca_green_lum_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
    )
}

/**
 * Clipping dialog — the switch IS the parameter; there is no numeric field, and OK is always enabled.
 */
@Composable
internal fun ClippingDialog(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(id = R.string.common_action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.common_action_cancel))
            }
        },
        title = { Text(text = stringResource(id = R.string.studio_clipping_title)) },
        text = {
            Column {
                Text(text = stringResource(id = R.string.studio_clipping_body))
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(id = R.string.studio_enable_stage))
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }
            }
        },
    )
}

/**
 * OKLab highlight-compression dialog — the switch IS the parameter; there is no numeric field, and OK
 * is always enabled.
 */
@Composable
internal fun OklabDialog(
    srgbEnabled: Boolean,
    onSrgbEnabledChange: (Boolean) -> Unit,
    prophotoEnabled: Boolean,
    onProphotoEnabledChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(id = R.string.common_action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.common_action_cancel))
            }
        },
        title = { Text(text = stringResource(id = R.string.studio_oklab_title)) },
        text = {
            Column {
                Text(text = stringResource(id = R.string.studio_oklab_body))
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(id = R.string.studio_oklab_srgb))
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = srgbEnabled, onCheckedChange = onSrgbEnabledChange)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(id = R.string.studio_oklab_prophoto))
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = prophotoEnabled, onCheckedChange = onProphotoEnabledChange)
                }
            }
        },
    )
}

/**
 * Exposure dialog. The enable switch gates *application*, not editing — the value field is always
 * editable and the clip-bound row shares the switch's enabled state. OK is disabled when the switch is
 * ON and any of the three fields is not a parseable number. The metering pass is delegated to the
 * caller via [onMeter]; [isMetering] drives the "Calculating…" label.
 */
@Composable
internal fun ExposureDialog(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    ev: String,
    onEvChange: (String) -> Unit,
    clipLower: String,
    onClipLowerChange: (String) -> Unit,
    clipUpper: String,
    onClipUpperChange: (String) -> Unit,
    isMetering: Boolean,
    meteringModes: List<Pair<String, ImageVector>>,
    onMeter: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val confirmEnabled = !enabled || (
        ev.toFloatOrNull() != null &&
            clipLower.toFloatOrNull() != null &&
            clipUpper.toFloatOrNull() != null
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = confirmEnabled, onClick = onConfirm) {
                Text(text = stringResource(id = R.string.common_action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.common_action_cancel))
            }
        },
        title = { Text(text = stringResource(id = R.string.studio_exposure_title)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(id = R.string.studio_enable_stage))
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextField(
                        value = clipLower,
                        onValueChange = onClipLowerChange,
                        enabled = enabled,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        label = { Text(text = stringResource(id = R.string.studio_exposure_clip_lower)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    TextField(
                        value = clipUpper,
                        onValueChange = onClipUpperChange,
                        enabled = enabled,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        label = { Text(text = stringResource(id = R.string.studio_exposure_clip_upper)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = ev,
                    onValueChange = onEvChange,
                    singleLine = true,
                    placeholder = { Text(text = stringResource(id = R.string.studio_exposure_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = stringResource(id = if (isMetering) R.string.studio_exposure_metering_calculating else R.string.studio_exposure_metering))
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    meteringModes.forEach { (mode, icon) ->
                        IconButton(onClick = { onMeter(mode) }) {
                            Icon(imageVector = icon, contentDescription = mode)
                        }
                    }
                }
            }
        },
    )
}

/**
 * White-balance input dialog. The title carries the as-shot CCT estimated from the decoded
 * multipliers; the field lets the user enter any target Kelvin, projected to camera multipliers by
 * the caller. OK only writes when the value is a positive number.
 */
@Composable
internal fun WhiteBalanceDialog(
    value: String,
    onValueChange: (String) -> Unit,
    asShotKelvin: Float,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val asShotLabel = if (asShotKelvin > 0f) asShotKelvin.roundToInt().toString() else "–"
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(id = R.string.common_action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.common_action_cancel))
            }
        },
        title = { Text(text = stringResource(id = R.string.studio_wb_title, asShotLabel)) },
        text = {
            TextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                placeholder = { Text(text = stringResource(id = R.string.studio_wb_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
    )
}

/**
 * Denoise dialog. The enable switch has priority over the strength value — when OFF the stage is
 * skipped regardless of the field; when ON the parsed sensitivity multiplier is applied. OK is
 * disabled unless the switch is ON with a parseable number.
 */
@Composable
internal fun DenoiseDialog(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    strength: String,
    onStrengthChange: (String) -> Unit,
    bm3dEnabled: Boolean,
    onBm3dEnabledChange: (Boolean) -> Unit,
    bm3dStrength: String,
    onBm3dStrengthChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val confirmEnabled = (!enabled || strength.toFloatOrNull() != null) &&
        (!bm3dEnabled || bm3dStrength.toFloatOrNull() != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = confirmEnabled, onClick = onConfirm) {
                Text(text = stringResource(id = R.string.common_action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.common_action_cancel))
            }
        },
        title = { Text(text = stringResource(id = R.string.studio_denoise_title)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(id = R.string.studio_enable_stage))
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = strength,
                    onValueChange = onStrengthChange,
                    enabled = enabled,
                    singleLine = true,
                    placeholder = { Text(text = stringResource(id = R.string.studio_denoise_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(id = R.string.studio_denoise_bm3d_label))
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = bm3dEnabled, onCheckedChange = onBm3dEnabledChange)
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = bm3dStrength,
                    onValueChange = onBm3dStrengthChange,
                    enabled = bm3dEnabled,
                    singleLine = true,
                    placeholder = { Text(text = stringResource(id = R.string.studio_denoise_bm3d_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
    )
}

/**
 * Dehaze dialog. The enable switch has priority over the strength / percentile values; when OFF the
 * stage is skipped regardless of the fields; when ON both the blend and the haze-floor percentile are
 * applied. OK is disabled unless the switch is ON with both fields parseable. The merge mode is a
 * read-only field opening a DropdownMenu of the four modes; the selection is held in [mergeMode].
 */
@Composable
internal fun DehazeDialog(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    strength: String,
    onStrengthChange: (String) -> Unit,
    percentile: String,
    onPercentileChange: (String) -> Unit,
    radiusDark: String,
    onRadiusDarkChange: (String) -> Unit,
    radiusGuide: String,
    onRadiusGuideChange: (String) -> Unit,
    mergeMode: DehazeMergeMode,
    onMergeModeChange: (DehazeMergeMode) -> Unit,
    mergeMenuOpen: Boolean,
    onMergeMenuOpenChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val confirmEnabled = !enabled ||
        (strength.toFloatOrNull() != null && percentile.toFloatOrNull() != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = confirmEnabled, onClick = onConfirm) {
                Text(text = stringResource(id = R.string.common_action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.common_action_cancel))
            }
        },
        title = { Text(text = stringResource(id = R.string.studio_dehaze_title)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(id = R.string.studio_enable_stage))
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = strength,
                    onValueChange = onStrengthChange,
                    enabled = enabled,
                    singleLine = true,
                    placeholder = { Text(text = stringResource(id = R.string.studio_dehaze_strength_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = percentile,
                    onValueChange = onPercentileChange,
                    enabled = enabled,
                    singleLine = true,
                    placeholder = { Text(text = stringResource(id = R.string.studio_dehaze_percentile_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = radiusDark,
                    onValueChange = onRadiusDarkChange,
                    enabled = enabled,
                    singleLine = true,
                    placeholder = { Text(text = stringResource(id = R.string.studio_dehaze_radius_dark_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = radiusGuide,
                    onValueChange = onRadiusGuideChange,
                    enabled = enabled,
                    singleLine = true,
                    placeholder = { Text(text = stringResource(id = R.string.studio_dehaze_radius_guide_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box {
                    TextField(
                        value = when (mergeMode) {
                            DehazeMergeMode.EACH -> stringResource(id = R.string.studio_dehaze_merge_each)
                            DehazeMergeMode.BLUE -> stringResource(id = R.string.studio_dehaze_merge_blue)
                            DehazeMergeMode.MIN -> stringResource(id = R.string.studio_dehaze_merge_min)
                            DehazeMergeMode.AVG -> stringResource(id = R.string.studio_dehaze_merge_avg)
                        },
                        onValueChange = { },
                        readOnly = true,
                        enabled = enabled,
                        singleLine = true,
                        label = { Text(text = stringResource(id = R.string.studio_dehaze_merge_label)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) { onMergeMenuOpenChange(true) },
                    )
                    DropdownMenu(
                        expanded = mergeMenuOpen,
                        onDismissRequest = { onMergeMenuOpenChange(false) },
                    ) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(id = R.string.studio_dehaze_merge_each)) },
                            onClick = { onMergeModeChange(DehazeMergeMode.EACH); onMergeMenuOpenChange(false) },
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(id = R.string.studio_dehaze_merge_blue)) },
                            onClick = { onMergeModeChange(DehazeMergeMode.BLUE); onMergeMenuOpenChange(false) },
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(id = R.string.studio_dehaze_merge_min)) },
                            onClick = { onMergeModeChange(DehazeMergeMode.MIN); onMergeMenuOpenChange(false) },
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(id = R.string.studio_dehaze_merge_avg)) },
                            onClick = { onMergeModeChange(DehazeMergeMode.AVG); onMergeMenuOpenChange(false) },
                        )
                    }
                }
            }
        },
    )
}

/** Grade-fork error dialog (an unreadable .cube LUT, or a grader failure). */
@Composable
internal fun GradeErrorDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.common_action_ok))
            }
        },
        title = { Text(text = stringResource(id = R.string.studio_grade_error_title)) },
        text = { Text(text = message) },
    )
}
