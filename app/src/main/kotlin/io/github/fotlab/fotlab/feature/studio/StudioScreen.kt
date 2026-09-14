package io.github.fotlab.fotlab.feature.studio

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.input.pointer.PointerEvent
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.fotlab.fotlab.R
import io.github.fotlab.fotlab.feature.library.LibraryCore
import kotlinx.coroutines.launch

/**
 * Studio screen (UI) — a Snapseed-style editor and the second independent screen, owned by the
 * `feature/studio` package alongside its lower layer [StudioEngine] (`FOTLAB-STRUCT-000001`).
 *
 * Like every screen it fills the whole region above the bottom navigation bar and splits it into two
 * sibling regions: its own top bar and the content region below it (`FOTLAB-UIXDES-000002` R3). The
 * top bar follows the shared skeleton — drawer toggle at the far left, overflow at the far right, and
 * a file-open action just left of the overflow (`FOTLAB-UIXDES-000002`). The module also owns its
 * drawer and its bottom action bar, none of which is shared with the shell.
 *
 * The open action lands the picked file in the Library directory the user is currently viewing
 * (shared app state, never the Recycle view — `LibraryCore.currentDirectoryId`) and renders it on the
 * canvas through its virtual `uri_storage` path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen() {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val uriString by StudioEngine.currentNodeUri.collectAsState()
    val uri = uriString?.let(Uri::parse)
    // Zoom / pan live here so the overflow menu's "Reset view" can snap them back to the default.
    val scale = remember(uriString) { mutableStateOf(1f) }
    val offset = remember(uriString) { mutableStateOf(Offset.Zero) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { picked ->
        if (picked != null) {
            scope.launch {
                // Land the picked file in the Library directory currently on screen (shared state,
                // never Recycle), then surface it on the Studio canvas.
                LibraryCore.importUris(LibraryCore.currentDirectoryId.value, listOf(picked))
                StudioEngine.setCurrentNode(picked.toString())
            }
        }
    }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            StudioDrawer(onClose = { scope.launch { drawerState.close() } })
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            StudioTopBar(
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onOpenFile = { importLauncher.launch(arrayOf("*/*")) },
                onResetView = {
                    scale.value = 1f
                    offset.value = Offset.Zero
                },
            )

            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (uri != null) {
                    // Rendering path: Coil AsyncImage, the same route the Library viewer uses
                    // (RAW / format-sniffing decode is a TODO in StudioEngine).
                    StudioZoomImage(
                        uri = uri,
                        scale = scale,
                        offset = offset,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = stringResource(id = R.string.studio_open_prompt),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            StudioBottomBar()
        }
    }
}

/**
 * The Studio top bar: the shared skeleton of `FOTLAB-UIXDES-000002` — drawer toggle at the far left,
 * overflow (three-dot) at the far right, and the file-open action just left of the overflow. The bar
 * renders no title text (`FOTLAB-UIXDES-000004` R6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioTopBar(
    onOpenDrawer: () -> Unit,
    onOpenFile: () -> Unit,
    onResetView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var overflowOpen by remember { mutableStateOf(false) }

    TopAppBar(
        title = {},
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = stringResource(id = R.string.studio_cd_drawer_open),
                )
            }
        },
        actions = {
            IconButton(onClick = onOpenFile) {
                Icon(
                    imageVector = Icons.Filled.FileOpen,
                    contentDescription = stringResource(id = R.string.studio_cd_open_file),
                )
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
        },
    )
}

/**
 * The Studio drawer sheet: the Material3 [ModalDrawerSheet] at 80% of the module width
 * (`FOTLAB-UIXDES-000002` R3). The close button sits in the sheet's own top-left corner, aligned with
 * the top bar's three-line icon, so opening the drawer replaces that icon in place (R6).
 *
 * TODO: drawer content — tool categories / recent edits. Module-private per `FOTLAB-UIXDES-000002` R5.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioDrawer(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth(0.8f),
    ) {
        IconButton(onClick = onClose, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(id = R.string.common_drawer_close),
            )
        }
        Text(
            text = stringResource(id = R.string.app_nav_studio_label),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/**
 * Single image on the Studio canvas. Decoded through Coil and scaled to fit; two-finger pinch zooms
 * and one-finger drag pans while zoomed, via [Modifier.zoomable] — a reworked, flicker-free version
 * of the Library viewer's detector (anchored to the focal point, pan only while zoomed).
 */
@Composable
private fun StudioZoomImage(
    uri: Uri,
    scale: androidx.compose.runtime.MutableState<Float>,
    offset: androidx.compose.runtime.MutableState<Offset>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context).data(uri).build(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                translationX = offset.value.x
                translationY = offset.value.y
            }
            .zoomable(scale, offset),
    )
}

/**
 * Snapseed-style bottom action bar: Looks / Tools / Export. Editing itself is not built yet — these
 * are the home for those actions, kept here so the layout matches the reference app.
 */
@Composable
private fun StudioBottomBar(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Text(text = stringResource(id = R.string.studio_tools_looks))
            Text(text = stringResource(id = R.string.studio_tools))
            Text(text = stringResource(id = R.string.studio_export))
        }
    }
}

/**
 * Robust pinch-zoom / pan detector. Unlike the Library viewer's version it anchors the zoom to the
 * gesture's focal point so the pixel under the fingers stays put, clamps to [minScale]–[maxScale],
 * snaps back to the default when scale reaches [minScale], and only pans once actually zoomed — which
 * removes the high-frequency flicker the old detector caused. A single finger at scale 1 is left
 * untouched so the page can still be paged / scrolled by the parent.
 */
private fun Modifier.zoomable(
    scale: androidx.compose.runtime.MutableState<Float>,
    offset: androidx.compose.runtime.MutableState<Offset>,
    minScale: Float = 1f,
    maxScale: Float = 5f,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        var lastCentroid: Offset? = null
        var lastSpacing = 0f
        do {
            val event: PointerEvent = awaitPointerEvent()
            val down = event.changes.filter { it.pressed }
            if (down.isEmpty()) break

            val centroid = if (down.size == 1) {
                down[0].position
            } else {
                var sx = 0f
                var sy = 0f
                for (c in down) {
                    sx += c.position.x
                    sy += c.position.y
                }
                Offset(sx / down.size, sy / down.size)
            }
            val spacing = if (down.size >= 2) {
                (down[0].position - down[1].position).getDistance()
            } else {
                0f
            }

            if (lastCentroid != null) {
                when {
                    down.size >= 2 && lastSpacing > 0f -> {
                        val factor = spacing / lastSpacing
                        val raw = (scale.value * factor).coerceIn(minScale, maxScale)
                        if (raw <= minScale) {
                            scale.value = minScale
                            offset.value = Offset.Zero
                        } else {
                            // Keep the focal point stationary: scale around the centroid.
                            val ratio = raw / scale.value
                            offset.value = centroid + (offset.value - centroid) * ratio
                            scale.value = raw
                        }
                    }
                    down.size == 1 && scale.value > minScale -> {
                        offset.value = offset.value + (centroid - lastCentroid)
                    }
                }
            }
            down.forEach { it.consume() }
            lastCentroid = centroid
            lastSpacing = spacing
        } while (event.changes.any { it.pressed })
    }
}
