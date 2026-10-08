package io.github.fotlab.fotlab.feature.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fotlab.fotlab.R

/** Height of the Studio fun bar — the former M3 top app bar's 64.dp. */
private val StudioScreenFunBarHeight = 64.dp

/**
 * The Studio fun bar: the shared skeleton of `FOTLAB-UIXDES-000002`, pinned to the screen's
 * bottom edge. Left-to-right: drawer menu, then the three *category* icons that dock one of the
 * Studio operation bars in the slot above — Theaters (DevelopFilm: Exposure / Denoise / Dehaze / Demosaic / White Balance),
 * Tune (TuneImage: Contrast / Saturation) and PhotoFilter (StyleFilter: LOG / LUT); a flexible
 * gap; the
 * file-open action and the overflow (three-dot) at the far right. The develop/grade tools
 * themselves no longer live here — they are `OperationalButton`s inside the operation bars, so
 * reordering them only touches the bar's list. The bar renders no title text
 * (`FOTLAB-UIXDES-000004` R6). Anchored at the bottom edge, every dropdown opens upward — including
 * the share action's format menu, which lists the two [StudioExportFormat] branches.
 */
@Composable
internal fun StudioScreenFunBar(
    barIsOpen: Boolean,
    onOpenDrawer: () -> Unit,
    onExitBar: () -> Unit,
    onOpenFile: () -> Unit,
    onShareFile: (StudioExportFormat) -> Unit,
    hasImage: Boolean,
    isPipelineRunning: Boolean,
    onStopPipeline: () -> Unit,
    onResetView: () -> Unit,
    onDevelopFilm: () -> Unit,
    onTuneImage: () -> Unit,
    onStyleFilter: () -> Unit,
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
                .height(StudioScreenFunBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = if (barIsOpen) onExitBar else onOpenDrawer) {
                    Icon(
                        imageVector = if (barIsOpen) Icons.Filled.Close else Icons.Filled.Menu,
                        contentDescription = stringResource(
                            id = if (barIsOpen) R.string.studio_cd_close_bar else R.string.studio_cd_drawer_open,
                        ),
                    )
                }
                IconButton(onClick = onDevelopFilm) {
                    Icon(
                        imageVector = Icons.Filled.Theaters,
                        contentDescription = stringResource(id = R.string.studio_cd_develop_film),
                    )
                }
                IconButton(onClick = onTuneImage) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = stringResource(id = R.string.studio_cd_tune_image),
                    )
                }
                IconButton(onClick = onStyleFilter) {
                    Icon(
                        imageVector = Icons.Filled.PhotoFilter,
                        contentDescription = stringResource(id = R.string.studio_cd_style_filter),
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (isPipelineRunning) {
                // A render is in flight: the slot becomes a square Stop button that cancels it.
                IconButton(onClick = onStopPipeline) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = stringResource(id = R.string.studio_cd_stop_pipeline),
                    )
                }
            } else if (hasImage) {
                // An image is resident: the open-file slot becomes the share action. Its drop-up menu
                // picks the format (PNG / JPG); the two branches are parallel — the bar hands the
                // choice back and the launcher encodes exactly that one.
                var shareMenuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { shareMenuOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.IosShare,
                            contentDescription = stringResource(id = R.string.studio_cd_share),
                        )
                    }
                    DropdownMenu(
                        expanded = shareMenuOpen,
                        onDismissRequest = { shareMenuOpen = false },
                    ) {
                        StudioExportFormat.entries.forEach { format ->
                            DropdownMenuItem(
                                text = { Text(text = stringResource(id = format.labelRes)) },
                                onClick = {
                                    shareMenuOpen = false
                                    onShareFile(format)
                                },
                            )
                        }
                    }
                }
            } else {
                IconButton(onClick = onOpenFile) {
                    Icon(
                        imageVector = Icons.Filled.AddPhotoAlternate,
                        contentDescription = stringResource(id = R.string.studio_cd_open_file),
                    )
                }
            }
            Box {
                IconButton(onClick = { overflowOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(id = R.string.studio_cd_more_options),
                    )
                }
                DropdownMenu(
                    expanded = overflowOpen,
                    onDismissRequest = { overflowOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(id = R.string.studio_reset_view)) },
                        leadingIcon = { Icon(imageVector = Icons.Filled.Refresh, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onResetView()
                        },
                    )
                }
            }
        }
    }
}

/**
 * The Studio drawer sheet: the Material3 [ModalDrawerSheet] at 80% of the module width
 * (`FOTLAB-UIXDES-000002` R3). The close button sits in the sheet's own bottom-left corner,
 * level with the fun bar's menu icon, so opening the drawer replaces that icon in place
 * (R6); the close row shares the fun bar's 64.dp height and the navigation-bar inset.
 *
 * It has no settings today. The quarter-resolution switch that used to live here is gone: rawler's
 * superpixel is a demosaic like any other, so it is an entry in the DevelopFilm bar's demosaic menu
 * next to `RAWTRP vng4` rather than a second, independent knob that could contradict the algorithm
 * choice (`rules/REVIEW/detail/OPTIMZ-PERFRM-000010.md`). The sheet stays because the fun bar's menu
 * icon opens it and R6 fixes the close affordance's position.
 *
 * TODO: drawer content — tool categories / recent edits. Module-private per `FOTLAB-UIXDES-000002` R5.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StudioDrawer(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth(0.8f),
    ) {
        Text(
            text = stringResource(id = R.string.app_nav_studio_label),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )

        // Push the close affordance to the bottom-left, level with the fun bar's menu icon.
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(StudioScreenFunBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(id = R.string.common_drawer_close),
                )
            }
        }
    }
}
