package io.github.fotlab.fotlab.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import android.net.Uri
import coil3.compose.AsyncImage

/** Fraction of the square the folder glyph fills; the remainder is the inset. */
private const val FolderGlyphFraction = 0.64f

/** Fraction of the square a MIME-type glyph fills; the remainder is the inset. */
private const val MimeGlyphFraction = 0.56f

/**
 * Square thumbnail for a library node, in the spirit of Material Files: a real thumbnail for
 * image/video media (decoded from the node's `content://` URI by Coil), a folder glyph for
 * collections, and a MIME-type icon otherwise.
 *
 * Thumbnails are loaded through [AsyncImage], which owns the memory **and** disk cache as well
 * as bitmap pooling — so we neither hand-manage a cache nor re-decode a node on every recomposition
 * or cold start. The frame is always a square with a default [MaterialTheme.colorScheme.surfaceVariant]
 * background, and every thumbnail uses [ContentScale.Fit] so the whole content stays visible inside
 * it — for non-square images the letterboxed bands show that background rather than the library behind it.
 */
@Composable
internal fun NodeThumbnail(node: FsNodeObject, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when {
            node.isCollection() -> {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxSize(FolderGlyphFraction),
                )
            }
            isMedia(node.typeMime) -> {
                val uri = node.uriStorage?.let(Uri::parse)
                if (uri != null) {
                    // Coil caches the decoded result on disk; the placeholder covers the brief
                    // load and any undecodable URI (it falls back to the MIME icon below).
                    AsyncImage(
                        model = uri,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                        placeholder = rememberVectorPainter(Icons.Filled.Image),
                        error = rememberVectorPainter(Icons.Filled.Image),
                    )
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
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = mimeIcon(mimeType),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(MimeGlyphFraction),
        )
    }
}

/** True for image and video MIME types, which get a real decoded thumbnail. */
internal fun isMedia(mime: String): Boolean = mime.startsWith("image/") || mime.startsWith("video/")

/** Pick the Material icon that represents a MIME type the way a file manager does. */
private fun mimeIcon(mime: String): ImageVector = when {
    mime.startsWith("image/") -> Icons.Filled.Image
    mime.startsWith("video/") -> Icons.Filled.Movie
    mime.startsWith("audio/") -> Icons.Filled.Audiotrack
    mime.startsWith("application/pdf") -> Icons.Filled.PictureAsPdf
    else -> Icons.Filled.InsertDriveFile
}
