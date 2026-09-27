package io.github.fotlab.fotlab.feature.library

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.exifinterface.media.ExifInterface
import coil3.request.ImageRequest
import io.github.fotlab.fotlab.R
import io.github.fotlab.fotlab.ui.ZoomableAsyncImage
import io.github.fotlab.fotlab.ui.rememberZoomState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Full-screen viewer for library media, opened as its own navigation destination (the route
 * `library/viewer`) the moment a tile is tapped (`FOTLAB-UIXDES`, viewer-as-screen).
 *
 * It mirrors [io.github.fotlab.fotlab.feature.studio.StudioScreen]: a module-level Material3
 * `Scaffold` nested in the shell's root `Scaffold`, with its own fun bar in the `bottomBar` slot.
 * Because the viewer is now a real destination — not a `Dialog` window — the navigation-bar inset
 * is delivered normally and the fun bar consumes it with `windowInsetsPadding(WindowInsets.navigationBars)`,
 * so the bar always lands on the bottom edge of the region the display actually shows (the old
 * `Dialog` could not see that inset and clipped the bar off the bottom).
 *
 * Behaviour: a single tap opens the viewer on the tapped item; the viewer shows the media large with
 * a detail panel (EXIF for images, video metadata for clips) above the fun bar; a two-finger pinch
 * zooms the image; a single-finger horizontal swipe moves to the previous / next item, mixing images
 * and videos together in the folder's current sort order (`FsNodeRelationDao` orders children by
 * `time_created`). The fun bar's X pops the destination, and "open in Studio" switches to Studio.
 *
 * The viewer receives the already-sorted media list + tapped index through [LibraryCore.viewerSession],
 * written by the grid the moment a tile is tapped, so it pages exactly the order the user sees.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryViewerScreen(
    onDismiss: () -> Unit,
    onOpenInStudio: (FsNodeObject) -> Unit,
) {
    val session by LibraryCore.viewerSession.collectAsState(initial = null)
    // The session is written the moment before this destination is pushed. `collectAsState` shows
    // its initial `null` for the first frame, so the wait covers that gap; it only dismisses when
    // no session ever arrives (e.g. a process restore straight into this destination).
    LaunchedEffect(Unit) {
        withTimeoutOrNull(1_000) { LibraryCore.viewerSession.first { it != null } } ?: onDismiss()
    }
    val started = session ?: return
    val items = started.items
    val startIndex = started.startIndex

    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, items.lastIndex),
        pageCount = { items.size },
    )
    // The detail panel follows the persisted preference: hidden on first open, and the user's last
    // choice (shown / hidden) is remembered for the next open (`FOTLAB-UIXDES`, viewer layout).
    val scope = rememberCoroutineScope()
    val showDetails by LibraryCore.viewerShowInfo.collectAsState(initial = false)
    // One shared zoom state for the whole viewer: the page owns the transform, the pager stands
    // down while it is zoomed (or mid-pinch), and switching items returns to the fitted size.
    val zoomState = rememberZoomState()
    LaunchedEffect(pagerState.currentPage) { zoomState.reset() }

    Scaffold(
        // The viewer owns the navigation-bar inset (the shell zeroed its own contentWindowInsets),
        // so its fun bar can sit flush on the bottom edge of the drawable region.
        contentWindowInsets = WindowInsets.navigationBars,
        bottomBar = {
            ViewerFunBar(
                page = pagerState.currentPage,
                count = items.size,
                showDetails = showDetails,
                onClose = onDismiss,
                onOpenInStudio = { onOpenInStudio(items[pagerState.currentPage]) },
                onToggleInfo = { scope.launch { LibraryCore.setViewerShowInfo(!showDetails) } },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !zoomState.isZoomed && !zoomState.isTransforming,
                key = { items.getOrNull(it)?.fsNodeId ?: it },
            ) { page ->
                val node = items[page]
                val uri = node.uriStorage?.let(Uri::parse)
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        uri == null ->
                            Icon(
                                imageVector = Icons.Filled.BrokenImage,
                                contentDescription = stringResource(id = R.string.library_viewer_no_preview),
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxSize(0.3f),
                            )
                        node.typeMime.startsWith("video/") ->
                            ViewerVideo(uri = uri, modifier = Modifier.fillMaxSize())
                        node.typeMime.startsWith("image/") ->
                            ZoomableAsyncImage(
                                model = ImageRequest.Builder(LocalContext.current).data(uri).build(),
                                contentDescription = node.nameDisplay,
                                state = zoomState,
                                modifier = Modifier.fillMaxSize(),
                                // A one-finger swipe at the fitted size still changes items.
                                keepParentDraggable = true,
                            )
                        else ->
                            Icon(
                                imageVector = Icons.Filled.Image,
                                contentDescription = stringResource(id = R.string.library_viewer_no_preview),
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxSize(0.3f),
                            )
                    }
                }
            }

            // Detail panel floats above the fun bar over the image; it is only shown when the info
            // toggle is on. Like the old overlay's `above` slot, it keeps the controls reachable.
            if (showDetails) {
                ViewerDetails(
                    node = items[pagerState.currentPage],
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

/** Height of the viewer's own fun bar. */
private val ViewerFunBarHeight = 56.dp

/**
 * The viewer's fun bar — its own bottom bar, not a reused studio bar. A translucent black scrim keeps
 * the white icons visible over the (often bright) bottom of the photo; `windowInsetsPadding` lifts the
 * row above the system navigation bar so none of it is clipped (`FOTLAB-UIXDES`, viewer-as-screen).
 */
@Composable
private fun ViewerFunBar(
    page: Int,
    count: Int,
    showDetails: Boolean,
    onClose: () -> Unit,
    onOpenInStudio: () -> Unit,
    onToggleInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.5f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(ViewerFunBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(id = R.string.library_viewer_cd_close),
                    tint = Color.White,
                )
            }
            Text(
                text = "${page + 1} / $count",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenInStudio) {
                Icon(
                    imageVector = Icons.Filled.AddPhotoAlternate,
                    contentDescription = stringResource(id = R.string.library_viewer_cd_open_in_studio),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onToggleInfo) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = stringResource(id = R.string.library_viewer_cd_info),
                    tint = Color.White.copy(alpha = if (showDetails) 1f else 0.5f),
                )
            }
        }
    }
}

/**
 * Video page: a framework [android.widget.VideoView] with a [android.widget.MediaController] for
 * play / pause and seeking, released when the page leaves the composition.
 */
@Composable
private fun ViewerVideo(uri: Uri, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            android.widget.VideoView(ctx).apply {
                setVideoURI(uri)
                val controller = android.widget.MediaController(ctx)
                controller.setAnchorView(this)
                setMediaController(controller)
                requestFocus()
            }
        },
        modifier = modifier,
        onRelease = { it.stopPlayback() },
    )
}

/** A single label/value line of the detail panel. */
private data class DetailRow(val label: String, val value: String)

/**
 * Detail panel for the current item: common fields (name, type, size, dates) plus EXIF for images
 * and container metadata for videos. Loaded off the main thread and shown on a translucent strip.
 */
@Composable
private fun ViewerDetails(node: FsNodeObject, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val rows by produceState<List<DetailRow>>(emptyList(), node) {
        value = loadDetails(context, node)
    }

    Surface(
        color = Color.Black.copy(alpha = 0.62f),
        modifier = modifier.heightIn(max = 260.dp),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(rows) { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.width(96.dp),
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * Build the detail rows for [node]. Images get their pixel dimensions (via decode bounds) and
 * EXIF (orientation, camera, capture time, GPS, exposure, aperture, ISO, focal length, flash);
 * videos get duration and resolution from [MediaMetadataRetriever]; both get size and dates.
 */
private suspend fun loadDetails(context: Context, node: FsNodeObject): List<DetailRow> =
    withContext(Dispatchers.IO) {
        val uri = node.uriStorage?.let(Uri::parse)
        val rows = mutableListOf<DetailRow>()

        rows += DetailRow(stringS(context, R.string.library_viewer_label_name), node.nameDisplay)
        rows += DetailRow(stringS(context, R.string.library_viewer_label_type), node.typeMime)

        uri?.let { u ->
            runCatching {
                context.contentResolver.query(u, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            rows += DetailRow(
                                stringS(context, R.string.library_viewer_label_size),
                                Formatter.formatShortFileSize(context, cursor.getLong(sizeIdx)),
                            )
                        }
                    }
                }
            }
        }

        rows += DetailRow(
            stringS(context, R.string.library_viewer_label_added),
            DateUtils.formatDateTime(context, node.timeCreated, DATE_FLAGS),
        )
        node.timeModified?.let {
            rows += DetailRow(
                stringS(context, R.string.library_viewer_label_modified),
                DateUtils.formatDateTime(context, it, DATE_FLAGS),
            )
        }

        if (node.typeMime.startsWith("image/") && uri != null) {
            loadImageExif(context, uri, rows)
        } else if (node.typeMime.startsWith("video/") && uri != null) {
            loadVideoMeta(context, uri, rows)
        }

        rows
    }

private suspend fun loadImageExif(context: Context, uri: Uri, rows: MutableList<DetailRow>) {
    // Pixel dimensions without decoding the whole bitmap.
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                rows += DetailRow(
                    stringS(context, R.string.library_viewer_label_dimensions),
                    "${opts.outWidth} × ${opts.outHeight}",
                )
            }
        }
    }

    runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
            rows += DetailRow(
                stringS(context, R.string.library_viewer_label_orientation),
                exifOrientationText(orientation),
            )

            val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
            val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
            if (make.isNotBlank() || model.isNotBlank()) {
                rows += DetailRow(
                    stringS(context, R.string.library_viewer_label_camera),
                    "$make $model".trim(),
                )
            }

            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?.let { exifDateTime(it) }
                ?.let {
                    rows += DetailRow(stringS(context, R.string.library_viewer_label_taken), it)
                }

            exif.latLong?.let { ll ->
                rows += DetailRow(
                    stringS(context, R.string.library_viewer_label_location),
                    "%.5f, %.5f".format(ll[0], ll[1]),
                )
            }

            val exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
            if (exposure > 0) {
                val text = if (exposure >= 1) "${exposure}s" else "1/${(1 / exposure).roundToInt()} s"
                rows += DetailRow(stringS(context, R.string.library_viewer_label_exposure), text)
            }

            val aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
            if (aperture > 0) {
                rows += DetailRow(stringS(context, R.string.library_viewer_label_aperture), "f/$aperture")
            }

            val iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED)
                ?: exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
            if (!iso.isNullOrBlank()) {
                rows += DetailRow(stringS(context, R.string.library_viewer_label_iso), iso)
            }

            val focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
            if (focal > 0) {
                rows += DetailRow(stringS(context, R.string.library_viewer_label_focal), "$focal mm")
            }

            val flash = exif.getAttributeInt(ExifInterface.TAG_FLASH, -1)
            if (flash >= 0) {
                val fired = flash and 0x01 != 0
                rows += DetailRow(
                    stringS(context, R.string.library_viewer_label_flash),
                    if (fired) "Fired" else "Did not fire",
                )
            }
        }
    }
}

private suspend fun loadVideoMeta(context: Context, uri: Uri, rows: MutableList<DetailRow>) {
    val retriever = MediaMetadataRetriever()
    runCatching {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?.let { ms ->
                val seconds = (ms / 1000).toInt()
                val text = "%d:%02d".format(seconds / 60, seconds % 60)
                rows += DetailRow(stringS(context, R.string.library_viewer_label_duration), text)
            }
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        if (!w.isNullOrBlank() && !h.isNullOrBlank()) {
            rows += DetailRow(stringS(context, R.string.library_viewer_label_dimensions), "$w × $h")
        }
    }.also { runCatching { retriever.release() } }
}

private fun exifOrientationText(value: Int): String = when (value) {
    ExifInterface.ORIENTATION_ROTATE_90 -> "Rotate 90° CW"
    ExifInterface.ORIENTATION_ROTATE_180 -> "Rotate 180°"
    ExifInterface.ORIENTATION_ROTATE_270 -> "Rotate 270° CW"
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> "Flip horizontal"
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> "Flip vertical"
    ExifInterface.ORIENTATION_TRANSPOSE -> "Transpose"
    ExifInterface.ORIENTATION_TRANSVERSE -> "Transverse"
    ExifInterface.ORIENTATION_NORMAL -> "Normal"
    else -> "Normal"
}

private fun exifDateTime(raw: String): String? = runCatching {
    EXIF_DATE_FORMAT.parse(raw)?.let { EXIF_DISPLAY_FORMAT.format(it) }
}.getOrNull()

private fun stringS(context: Context, resId: Int): String = context.getString(resId)

private val DATE_FLAGS = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
    DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_NUMERIC_DATE

private val EXIF_DATE_FORMAT = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)

private val EXIF_DISPLAY_FORMAT = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
