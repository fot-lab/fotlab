package io.github.fotlab.fotlab.feature.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Square thumbnail for a gallery node, in the spirit of Material Files: a real thumbnail for
 * image/video media (decoded from the node's `content://` URI), a folder glyph for
 * collections, and a MIME-type icon otherwise. The frame is always a square with a default
 * [MaterialTheme.colorScheme.surfaceVariant] background, and every thumbnail uses
 * [ContentScale.Fit] so the whole content stays visible inside it — for non-square images the
 * letterboxed bands show that background rather than the gallery behind it.
 */
@Composable
internal fun NodeThumbnail(node: FsNodeObject, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        when {
            node.isCollection() -> {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxSize().padding(percent = 18),
                )
            }
            isMedia(node.typeMime) -> {
                val uri = node.uriStorage?.let(Uri::parse)
                if (uri != null) {
                    MediaThumbnail(uri = uri)
                } else {
                    MimeIcon(node.typeMime)
                }
            }
            else -> MimeIcon(node.typeMime)
        }
    }
}

/** MIME-type glyph shown when there is no real thumbnail to decode. */
@Composable
private fun MimeIcon(mimeType: String) {
    Icon(
        imageVector = mimeIcon(mimeType),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxSize().padding(percent = 22),
    )
}

/** Decodes and shows the real thumbnail for a media URI, fitting it inside the square. */
@Composable
private fun MediaThumbnail(uri: Uri) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { loadThumbnail(context.contentResolver, uri) }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Icon(
            imageVector = Icons.Filled.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize().padding(percent = 22),
        )
    }
}

/** True for image/* and video/* entries, which get a real decoded thumbnail. */
internal fun isMedia(mime: String): Boolean = mime.startsWith("image/") || mime.startsWith("video/")

/** Pick the Material icon that represents a MIME type the way a file manager does. */
private fun mimeIcon(mime: String): ImageVector = when {
    mime.startsWith("image/") -> Icons.Filled.Image
    mime.startsWith("video/") -> Icons.Filled.Movie
    mime.startsWith("audio/") -> Icons.Filled.Audiotrack
    mime.startsWith("application/pdf") -> Icons.Filled.PictureAsPdf
    else -> Icons.Filled.InsertDriveFile
}

/**
 * Decode a thumbnail bitmap for [uri]. Image and video providers honour
 * [ContentResolver.loadThumbnail] (API 29+); older devices and non-media URIs fall back to a
 * MIME icon. Successful results are cached so scrolling does not re-decode them.
 */
private val thumbnailCache = object : LruCache<String, Bitmap?>(256) {}

private fun loadThumbnail(resolver: ContentResolver, uri: Uri): Bitmap? {
    val key = uri.toString()
    thumbnailCache.get(key)?.let { return it }
    val bitmap = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.loadThumbnail(uri, Size(256, 256), null)
        } else {
            resolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeStream(stream, null, opts)
            }
        }
    }.getOrNull()
    if (bitmap != null) thumbnailCache.put(key, bitmap)
    return bitmap
}
