package io.github.fotlab.fotlab.feature.studio

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllOut
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.RawOff
import androidx.compose.material.icons.filled.RawOn
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionOff
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.WbAuto
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import coil3.request.ImageRequest
import io.github.fotlab.fotlab.R
import io.github.fotlab.fotlab.feature.library.LibraryCore
import io.github.fotlab.fotlab.feature.studio.StudioRenderResult
import io.github.fotlab.fotlab.media.MediaPreference
import io.github.fotlab.fotlab.ui.ZoomableAsyncImage
import io.github.fotlab.fotlab.ui.icons.CustomMaterialStyleIcons
import io.github.fotlab.fotlab.ui.icons.MeteringCenterAsterisk
import io.github.fotlab.fotlab.ui.icons.MeteringCenterAsteriskMatrix
import io.github.fotlab.fotlab.ui.icons.MeteringCenterWeighted
import io.github.fotlab.fotlab.ui.icons.MeteringMatrixAverage
import io.github.fotlab.fotlab.ui.icons.MeteringMatrixSpot
import io.github.fotlab.fotlab.ui.icons.MovieEdit
import io.github.fotlab.fotlab.ui.operation.HorizontalOperationBar
import io.github.fotlab.fotlab.ui.operation.OperationalButton
import io.github.fotlab.fotlab.ui.rememberZoomState
import io.github.fotlab.fotlab_rawler.CaSettings
import io.github.fotlab.fotlab_rawler.LocaSettings
import io.github.fotlab.fotlab_rawler.DehazeMergeMode
import io.github.fotlab.fotlab_rawler.DemosaicCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Demosaic algorithm picker (the gradient icon anchors an upward-opening dropdown).
 *
 * The entries come from the native catalogue ([StudioEngine.demosaicCandidates]), not from a list
 * written here: the menu and the pipeline read the same catalogue, so a kernel ported in
 * `rawtrp_demosaic` cannot show up in one without the other (`FOTLAB-NATIVE-000004` D5). See
 * [demosaicLabel] for how each entry's text is chosen.
 *
 * [superpixelSupported] `false` greys out the superpixel entry rather than letting the pick resolve
 * to something else: that entry is the quarter-resolution demosaic, and on a sensor that cannot run
 * it (X-Trans, Fuji-rotated) picking it would silently give the CFA default instead. `null` (no RAW
 * resident) leaves it selectable — the capability is about the image, and there is no image to
 * contradict yet.
 */
@Composable
internal fun DemosaicButton(
    candidates: List<DemosaicCandidate>,
    superpixelSupported: Boolean?,
    onAlgorithmPicked: (DemosaicCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.Filled.Gradient,
                contentDescription = stringResource(id = R.string.studio_cd_demosaic),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = PickerMenuMaxHeight),
        ) {
            for (candidate in candidates) {
                DropdownMenuItem(
                    text = { Text(text = demosaicLabel(candidate)) },
                    onClick = { open = false; onAlgorithmPicked(candidate) },
                    enabled = candidate.id != SUPERPIXEL_ID || superpixelSupported != false,
                )
            }
        }
    }
}

/** The superpixel entry's catalogue id — the one pick whose applicability is sensor-dependent. */
internal const val SUPERPIXEL_ID = "rawler:superpixel"

/**
 * Display text for one demosaic candidate, **always naming the source library** except for the
 * default entry.
 *
 * The menu lists two independent implementations under similar names — `amaze` and `fast` exist on
 * both sides — so an entry that did not say where it came from would be ambiguous. The native
 * catalogue already prefixes every label with `RAWLER` / `RAWTRP` (`rawtrp_demosaic::algo`); this
 * keeps that prefix while still showing the *translated* algorithm name for the entries Studio has
 * localised. The default is the one exception: it is not an algorithm but "whatever the sensor's CFA
 * calls for", so it carries no source.
 *
 * The prefix is a parameter rather than part of each translated string so the source name stays a
 * single fact: translating "RAWLER" or "RAWTRP" is not a thing, and duplicating it across five
 * strings is how the two halves would drift apart.
 */
@Composable
internal fun demosaicLabel(candidate: DemosaicCandidate): String {
    val localized = when (candidate.id) {
        "rawler:default" -> return stringResource(id = R.string.studio_demosaic_default)
        "rawler:ppg" -> stringResource(id = R.string.studio_demosaic_ppg)
        "rawler:bilinear4" -> stringResource(id = R.string.studio_demosaic_bilinear4)
        "rawler:xtrans_bilinear" -> stringResource(id = R.string.studio_demosaic_xtrans)
        SUPERPIXEL_ID -> stringResource(id = R.string.studio_demosaic_superpixel)
        // A kernel ported later: no translated string exists yet, so it keeps the catalogue's own
        // already-prefixed label rather than vanishing from the menu.
        else -> return candidate.label
    }
    return stringResource(id = R.string.studio_demosaic_source_prefix, candidate.label.substringBefore(' ')) +
        " " + localized
}

/**
 * Read-only RAW status indicator for the Basic bar's first slot.
 *
 * Shows the Material `RawOn` glyph only when an image is actually held AND the
 * format sniffer routed it to the rawler RAW path ([StudioEngine.isRawLoaded]);
 * every other case — no image, or a sniffed jpeg/png handled by Coil — shows
 * `RawOff`. It is a pure status readout, so it renders a bare [Icon] (no
 * IconButton / no click handling).
 */
@Composable
internal fun RawStatusButton(
    isOn: Boolean,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = if (isOn) Icons.Filled.RawOn else Icons.Filled.RawOff,
        contentDescription = stringResource(
            id = if (isOn) R.string.studio_cd_raw_on else R.string.studio_cd_raw_off,
        ),
        modifier = modifier,
    )
}

/**
 * Read-only output-transfer indicator for the Basic bar, in the same spirit as the raw-status
 * readout: it *reports* whether the PNG the canvas is showing was written through the sRGB transfer
 * function or straight from linear, and offers nothing to change.
 *
 * The value is derived by `StudioEngine` from what the render actually is — a graded render is
 * already log-encoded by rawalchemy and must not be gamma-encoded again, while the develop
 * presentation is the one that wants the curve — so surfacing it as a switch would only let the
 * user ask for a combination that cannot happen. It is an indicator, not a control: a bare icon
 * with no click handling, whose primary tint means "gamma is being applied".
 */
@Composable
internal fun OutputTransferStatusButton(
    isGamma: Boolean,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.Filled.SdCard,
        contentDescription = stringResource(
            id = if (isGamma) R.string.studio_cd_transfer_gamma else R.string.studio_cd_transfer_linear,
        ),
        tint = if (isGamma) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier,
    )
}

/** Exposure stops input (opens the EV dialog owned by StudioScreen). */
@Composable
internal fun ExposureButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.Exposure,
            contentDescription = stringResource(id = R.string.studio_cd_exposure),
        )
    }
}

/** White-balance Kelvin input (opens the WB dialog owned by StudioScreen). */
@Composable
internal fun WhiteBalanceButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.WbAuto,
            contentDescription = stringResource(id = R.string.studio_cd_whitebalance),
        )
    }
}

/** Denoise strength input (opens the Denoise dialog owned by StudioScreen). */
@Composable
internal fun DenoiseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.Grain,
            contentDescription = stringResource(id = R.string.studio_cd_denoise),
        )
    }
}

/** Dehaze input (opens the Dehaze dialog owned by StudioScreen). */
@Composable
internal fun DehazeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.Air,
            contentDescription = stringResource(id = R.string.studio_cd_dehaze),
        )
    }
}

/**
 * LCA (chromatic-aberration correction) parameter entry of the develop bar.
 * The ClosedCaption glyph stands for Color Correction here; the caption reads
 * LCA in every locale.
 */
@Composable
internal fun CaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.ClosedCaption,
            contentDescription = stringResource(id = R.string.studio_cd_lca),
        )
    }
}

/**
 * LoCA (longitudinal / axial chromatic-aberration correction) parameter entry of the develop bar.
 * The ClosedCaptionOff glyph is the "CC disabled" mark repurposed here as the axial-fringe tool; the
 * caption reads LoCA in every locale. It opens the LoCA dialog, which exposes only the two peer
 * switches (去紫边 / 去绿边) — the master switch is derived by Kotlin from them.
 */
@Composable
internal fun LocaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.ClosedCaptionOff,
            contentDescription = stringResource(id = R.string.studio_cd_loca),
        )
    }
}

/**
 * Out-of-gamut clipping switch (opens the Clipping dialog owned by StudioScreen).
 *
 * Material's *all out* glyph is the deliberate choice here: it is the "pull everything inside
 * the boundary" mark, which is exactly what the tool does to a ProPhoto buffer whose channels
 * left the 0..1 cube. Like the other DevelopFilm-bar tools it carries no state of its own — the
 * dialog's switch is the only control (`FOTLAB-UIXDES-000002`: the screen owns the dialogs).
 */
@Composable
internal fun ClippingButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.AllOut,
            contentDescription = stringResource(id = R.string.studio_cd_clipping),
            tint = operationIconTint(enabled = enabled, active = false),
        )
    }
}

/**
 * OKLab highlight-compression switch (opens the OKLab dialog owned by StudioScreen).
 *
 * Material's *flare* glyph marks the perceptual highlight glow this tool tames: it inserts a
 * lightness-driven chroma roll-off in OKLab on the sRGB presentation fork's near-clipped
 * highlights, so the per-channel sRGB clamp no longer freezes a hue error. Like the other
 * tools it carries no state of its own — the dialog's switch is the only control
 * (`FOTLAB-UIXDES-000002`: the screen owns the dialogs).
 */

/**
 * Shared icon tint for operation buttons. [active] (the tool is currently applied) tints primary,
 * otherwise the default onSurfaceVariant; [enabled = false] — the tool's precondition (e.g. a
 * prior RAW decode) is not met — forces the standard Material disabled alpha so the button reads
 * as unavailable rather than merely inactive, instead of the whole bar hiding it.
 */
@Composable
internal fun operationIconTint(enabled: Boolean, active: Boolean) =
    if (!enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    } else if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
internal fun OklabHighlightButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.Flare,
            contentDescription = stringResource(id = R.string.studio_cd_oklab),
            tint = operationIconTint(enabled = enabled, active = false),
        )
    }
}

/**
 * Contrast parameter of the boost group. Primary tint while configured. Opens
 * [BoostParameterDialog]; the boost switch itself is derived (either parameter configured).
 */
@Composable
internal fun ContrastButton(
    contrast: Float?,
    onContrast: (Float?) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, enabled = enabled, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.Contrast,
            contentDescription = stringResource(id = R.string.studio_cd_contrast),
            tint = operationIconTint(enabled = enabled, active = contrast != null),
        )
    }
    if (open) {
        BoostParameterDialog(
            titleRes = R.string.studio_contrast_title,
            currentValue = contrast,
            onApply = { onContrast(it); open = false },
            onDismiss = { open = false },
        )
    }
}

/**
 * Saturation parameter of the boost group — same shape as [ContrastButton]'s, Tonality icon.
 */
@Composable
internal fun SaturationButton(
    saturation: Float?,
    onSaturation: (Float?) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {

    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, enabled = enabled, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.Tonality,
            contentDescription = stringResource(id = R.string.studio_cd_saturation),
            tint = operationIconTint(enabled = enabled, active = saturation != null),
        )
    }
    if (open) {
        BoostParameterDialog(
            titleRes = R.string.studio_saturation_title,
            currentValue = saturation,
            onApply = { onSaturation(it); open = false },
            onDismiss = { open = false },
        )
    }
}

/**
 * Boost-parameter input dialog shared by contrast and saturation: an enable switch plus one free-form
 * float field, no range limiting. The switch has priority over the value — when OFF the parameter is
 * cleared (unconfigured; when the sibling is unconfigured too the whole boost switch turns off) and
 * the field is ignored; when ON the parsed value is applied. OK is disabled while ON with a
 * non-parseable field. The two boost parameters are coupled by the engine (rawalchemy applies both
 * together, the unconfigured sibling falling back to 1.0), so each dialog only toggles its own.
 */
@Composable
internal fun BoostParameterDialog(
    titleRes: Int,
    currentValue: Float?,
    onApply: (Float?) -> Unit,
    onDismiss: () -> Unit,
) {
    var enabled by remember(currentValue) { mutableStateOf(currentValue != null) }
    var input by remember(currentValue) { mutableStateOf(currentValue?.toString() ?: "") }
    val parsed = input.toFloatOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = !enabled || parsed != null,
                onClick = { onApply(if (enabled) parsed else null) },
            ) {
                Text(text = stringResource(id = R.string.common_action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.common_action_cancel))
            }
        },
        title = { Text(text = stringResource(id = titleRes)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(id = R.string.studio_enable_stage))
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    enabled = enabled,
                    singleLine = true,
                    placeholder = { Text(text = stringResource(id = R.string.studio_boost_param_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
    )
}

/**
 * LOG curve picker — none plus every curve rawalchemy enumerates. Primary tint while a curve is
 * selected. The icon is the official Material "movie_edit" glyph, reproduced first-party in
 * [CustomMaterialStyleIcons] (the frozen material-icons-extended artifact never generated it).
 */
@Composable
internal fun LogButton(
    logSpace: String?,
    logSpaces: List<String>,
    onLogSpace: (String?) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val none = stringResource(id = R.string.studio_grade_none)
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(
                imageVector = CustomMaterialStyleIcons.Filled.MovieEdit,
                contentDescription = stringResource(id = R.string.studio_cd_log),
                tint = operationIconTint(enabled = enabled, active = logSpace != null),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = PickerMenuMaxHeight),
        ) {
            DropdownMenuItem(
                text = { Text(text = none) },
                onClick = { open = false; onLogSpace(null) },
            )
            for (name in logSpaces) {
                DropdownMenuItem(
                    text = { Text(text = name) },
                    onClick = { open = false; onLogSpace(name) },
                )
            }
        }
    }
}

/**
 * LUT picker — "Choose file…" (SAF) / "None (remove LUT)". Primary tint while a LUT is loaded.
 */
@Composable
internal fun LutButton(
    lutName: String?,
    onPick: () -> Unit,
    onClear: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(
                imageVector = Icons.Filled.MovieFilter,
                contentDescription = stringResource(id = R.string.studio_cd_lut),
                tint = operationIconTint(enabled = enabled, active = lutName != null),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(id = R.string.studio_grade_lut_pick)) },
                onClick = { open = false; onPick() },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(id = R.string.studio_grade_lut_clear)) },
                onClick = { open = false; onClear() },
            )
        }
    }
}

/**
 * DCP camera-profile picker — "Choose profile…" (SAF) / "None (remove camera correction)". Primary
 * tint while a profile is loaded (the [active] flag).
 */
@Composable
internal fun DcpButton(
    active: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.Filled.PhotoCamera,
                contentDescription = stringResource(id = R.string.studio_cd_dcp),
                tint = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(id = R.string.studio_dcp_pick)) },
                onClick = { open = false; onPick() },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(id = R.string.studio_dcp_clear)) },
                onClick = { open = false; onClear() },
            )
        }
    }
}

/**
 * LCP lens-profile picker — "Choose profile…" (SAF) / "None (remove lens correction)". Primary tint
 * while a profile is loaded (the [active] flag). The bar sits just above the fun bar, so Material3
 * opens this dropdown upward automatically.
 */
@Composable
internal fun LcpButton(
    active: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
    onFocal: () -> Unit,
    currentUserFocal: Float? = null,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.Filled.Camera,
                contentDescription = stringResource(id = R.string.studio_cd_lcp),
                tint = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(id = R.string.studio_lcp_pick)) },
                onClick = { open = false; onPick() },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (currentUserFocal != null) {
                            stringResource(id = R.string.studio_lcp_focal_with_value, "%.0f".format(currentUserFocal))
                        } else {
                            stringResource(id = R.string.studio_lcp_focal)
                        },
                    )
                },
                onClick = { open = false; onFocal() },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(id = R.string.studio_lcp_clear)) },
                onClick = { open = false; onClear() },
            )
        }
    }
}
