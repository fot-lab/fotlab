package io.github.fotlab.fotlab.feature.library

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.exifinterface.media.ExifInterface
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.fotlab.fotlab.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Full-screen viewer for library media, opened on a single tap of an image or video tile.
 *
 * Behaviour follows the request: a single tap opens the dialog on the tapped item; the dialog
 * shows the media large with a detail panel (EXIF for images, video metadata for clips) at the
 * bottom; a two-finger pinch zooms the image; a single-finger horizontal swipe moves
 * to the previous / next item, mixing images and videos together in the folder's current sort
 * order (`FsNodeRelationDao` orders children by `time_created`); and the top-left X closes it.
 *
 * The viewer receives the already-sorted media list of the folder plus the tapped index, so it
 * navigates exactly the order the user sees in the grid / list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryViewerDialog(
    items: List<FsNodeObject>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, items.lastIndex),
        pageCount = { items.size },
    )
    var showDetails by remember { mutableStateOf(true) }
    // While the image is zoomed, single-finger gestures pan it instead of paging.
    var transformed by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BackHandler(onBack = onDismiss)

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !transformed,
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
                            ViewerImage(
                                uri = uri,
                                modifier = Modifier.fillMaxSize(),
                                onTransformChanged = { transformed = it },
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

            // Top bar: close (top-left X) and a details toggle (top-right).
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(id = R.string.library_viewer_cd_close),
                        tint = Color.White,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${pagerState.currentPage + 1} / ${items.size}",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium,
                )
                IconButton(onClick = { showDetails = !showDetails }) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = stringResource(id = R.string.library_viewer_cd_info),
                        tint = Color.White,
                    )
                }
            }

            // Bottom detail panel for the current item.
            if (showDetails) {
                ViewerDetails(
                    node = items[pagerState.currentPage],
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                )
            }
        }
    }
}

/**
 * Image page: decoded at full size by Coil and scaled to fit, with two-finger pinch zoom and
 * one-finger pan. [onTransformChanged] lets the pager disable horizontal paging while the image
 * is zoomed, so a single-finger horizontal swipe still switches items when the image sits at its
 * default size ([zoomPan] only consumes the gesture once a second finger is down or the image is
 * already transformed).
 */
@Composable
private fun ViewerImage(
    uri: Uri,
    modifier: Modifier = Modifier,
    onTransformChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(scale) {
        onTransformChanged(scale != 1f)
    }

    AsyncImage(
        model = ImageRequest.Builder(context).data(uri).build(),
        contentDescription = null,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .zoomPan(
                getScale = { scale },
                getOffset = { offset },
                setScale = { scale = it },
                setOffset = { offset = it },
            ),
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
    )
}

/**
 * Custom pinch-zoom / pan detector that coexists with the pager's horizontal swipe: a gesture is
 * only consumed (and applied) once a second finger is down or the image is already zoomed. A
 * single-finger drag at the default size is left untouched so the pager can page to the next item.
 * Zoom is clamped to [1f, 5f]; returning to scale 1 also resets the pan so the image snaps back to
 * its fit.
 */
private fun Modifier.zoomPan(
    getScale: () -> Float,
    getOffset: () -> Offset,
    setScale: (Float) -> Unit,
    setOffset: (Offset) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val startScale = getScale()
        var prevSpacing = 0f
        var prevCentroid: Offset? = null
        do {
            val event = awaitPointerEvent()
            val changes = event.changes.filter { it.pressed }
            val n = changes.size
            if (n == 0) break
            val centroid = if (n == 1) {
                changes.first().position
            } else {
                changes.fold(Offset.Zero) { acc, c -> acc + c.position } / n.toFloat()
            }
            val spacing = if (n >= 2) (changes[0].position - changes[1].position).getDistance() else 0f

            // Consume only when this is a real transform (pinch, or pan while already zoomed).
            val consume = startScale != 1f || n >= 2
            if (consume && prevCentroid != null) {
                val s = getScale()
                val o = getOffset()
                val zoom = if (prevSpacing > 0f && n >= 2) spacing / prevSpacing else 1f
                val pan = centroid - prevCentroid

                var newScale = (s * zoom).coerceIn(1f, 5f)
                var newOffset = o + pan
                if (newScale <= 1.0001f) {
                    newScale = 1f
                    newOffset = Offset.Zero
                }
                setScale(newScale)
                setOffset(newOffset)
                changes.forEach { it.consume() }
            }

            prevSpacing = spacing
            prevCentroid = centroid
        } while (event.changes.any { it.pressed })
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
    val parsed = EXIF_DATE_FORMAT.parse(raw)
    parsed?.let { DateUtils.formatDateTime(null, it.time, DATE_FLAGS) }
}.getOrNull()

private fun stringS(context: Context, resId: Int): String = context.getString(resId)

private val DATE_FLAGS = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
    DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_NUMERIC_DATE

private val EXIF_DATE_FORMAT = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
