package io.github.fotlab.fotlab.feature.studio

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fotlab.fotlab.R
import io.github.fotlab.fotlab.ui.operation.HorizontalOperationBar
import io.github.fotlab.fotlab.ui.operation.OperationalButton
import io.github.fotlab.fotlab_rawler.DemosaicCandidate

// ---------------------------------------------------------------------------
// Operation-bar categories and the buttons that populate them
// ---------------------------------------------------------------------------

/** The three Studio operation bars docked in the former grade-bar slot. */
internal enum class StudioOpBar { DevelopFilm, TuneImage, StyleFilter, Basic }

/**
 * Toggle helper: tapping the category icon for the already-active bar closes it (falls back to the
 * Basic bar); tapping a different bar switches to it; Basic is the floor and never toggles off.
 */
internal fun StudioOpBar.toggle(target: StudioOpBar): StudioOpBar =
    if (this == target) StudioOpBar.Basic else target

/**
 * Max height of the scrolling picker menus (Demosaic / LOG): five 48dp menu rows plus Material3's
 * 8dp top/bottom menu padding = 256dp.
 *
 * The cap MUST be applied through [DropdownMenu]'s own `modifier`, never by wrapping the items in
 * another scrolling `Column`: Material3 already hosts the menu content in a vertically scrolling
 * Column, and a scrollable child nested inside it is measured with unbounded height constraints,
 * crashing during layout ("Vertically scrollable component was measured with an infinity maximum
 * height constraints") before the popup is ever drawn.
 */
internal val PickerMenuMaxHeight = 256.dp

/**
 * DevelopFilm bar — the develop tools that used to live directly on the fun bar, now ordered
 * Exposure → Denoise → Dehaze → Demosaic → White Balance: exposure is first, the mosaic-cleaning
 * stages run before demosaic, and white balance sits after demosaic. Reordering the list below
 * reorders the bar.
 *
 * [demosaicCandidates] is the native catalogue, passed in rather than read here so the bar stays a
 * pure renderer of state the engine owns. Each tool is an icon-only `OperationalButton`; the dialogs
 * they open are owned by `StudioScreen`, so the bar itself carries no parameter UI (the function /
 * layout decoupling the screen keeps — `FOTLAB-UIXDES-000002`).
 */
@Composable
internal fun StudioOperationBarDevelopFilm(
    demosaicCandidates: List<DemosaicCandidate>,
    superpixelSupported: Boolean?,
    onAlgorithmPicked: (DemosaicCandidate) -> Unit,
    onDenoise: () -> Unit,
    onDehaze: () -> Unit,
    onCa: () -> Unit,
    onExposure: () -> Unit,
    onWhiteBalance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalOperationBar(
        modifier = modifier,
        items = listOf(
            OperationalButton(
                id = "exposure",
                label = stringResource(id = R.string.studio_label_exposure),
            ) { ExposureButton(onExposure) },
            OperationalButton(
                id = "ca",
                label = stringResource(id = R.string.studio_label_tca),
            ) { CaButton(onCa) },
            OperationalButton(
                id = "denoise",
                label = stringResource(id = R.string.studio_label_denoise),
            ) { DenoiseButton(onDenoise) },
            OperationalButton(
                id = "dehaze",
                label = stringResource(id = R.string.studio_label_dehaze),
            ) { DehazeButton(onDehaze) },
            OperationalButton(
                id = "demosaic",
                label = stringResource(id = R.string.studio_label_demosaic),
            ) { DemosaicButton(demosaicCandidates, superpixelSupported, onAlgorithmPicked) },
            OperationalButton(
                id = "wb",
                label = stringResource(id = R.string.studio_label_whitebalance),
            ) { WhiteBalanceButton(onWhiteBalance) },
        ),
    )
}

/**
 * TuneImage bar — the boost group: ACA and Clipping switches plus the Contrast and Saturation
 * parameter inputs.
 *
 * **ACA** (the purple-fringe corrector, `defringe_prophoto_unpurple.rs`) sits between Clipping and
 * Contrast on purpose: all three act on the same **linear ProPhoto-D50 editing buffer** — Clipping
 * clamps it, ACA removes the fringe in it, Contrast grades what comes after — so they belong to the
 * editing fork, not to the develop bar's pre-demosaic tools. Turning ACA on therefore counts as
 * engaging the grade (see `StudioEngine.requestRender`).
 */
@Composable
internal fun StudioOperationBarTuneImage(
    rawLoaded: Boolean,
    onOklabHighlight: () -> Unit,
    onClipping: () -> Unit,
    onLoca: () -> Unit,
    contrast: Float?,
    saturation: Float?,
    onContrast: (Float?) -> Unit,
    onSaturation: (Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalOperationBar(
        modifier = modifier,
        items = listOf(
            OperationalButton(
                id = "oklab",
                label = stringResource(id = R.string.studio_label_oklab),
            ) { OklabHighlightButton(onOklabHighlight, enabled = rawLoaded) },
            OperationalButton(
                id = "clipping",
                label = stringResource(id = R.string.studio_label_clipping),
            ) { ClippingButton(onClipping, enabled = rawLoaded) },
            OperationalButton(
                id = "loca",
                label = stringResource(id = R.string.studio_label_loca),
            ) { LocaButton(onLoca, enabled = rawLoaded) },
            OperationalButton(
                id = "contrast",
                label = stringResource(id = R.string.studio_cd_contrast),
            ) { ContrastButton(contrast = contrast, onContrast = onContrast, enabled = rawLoaded) },
            OperationalButton(
                id = "saturation",
                label = stringResource(id = R.string.studio_cd_saturation),
            ) { SaturationButton(saturation = saturation, onSaturation = onSaturation, enabled = rawLoaded) },
        ),
    )
}

/** StyleFilter bar — LOG and LUT. */
@Composable
internal fun StudioOperationBarStyleFilter(
    rawLoaded: Boolean,
    logSpace: String?,
    lutName: String?,
    logSpaces: List<String>,
    onLogSpace: (String?) -> Unit,
    onPickLut: () -> Unit,
    onClearLut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalOperationBar(
        modifier = modifier,
        items = listOf(
            OperationalButton(
                id = "log",
                label = stringResource(id = R.string.studio_label_log),
            ) {
                LogButton(
                    logSpace = logSpace,
                    logSpaces = logSpaces,
                    onLogSpace = onLogSpace,
                    enabled = rawLoaded,
                )
            },
            OperationalButton(
                id = "lut",
                label = stringResource(id = R.string.studio_label_lut),
            ) {
                LutButton(
                    lutName = lutName,
                    onPick = onPickLut,
                    onClear = onClearLut,
                    enabled = rawLoaded,
                )
            },
        ),
    )
}
