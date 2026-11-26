package io.github.fotlab.fotlab.feature.camera

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * The name a source document will carry once it is on the device.
 *
 * An upload is **queued** before it runs, so the name has to be known at that moment: the
 * queued row carries it, and the upload writes the file under exactly that name.
 */
object CameraSourceName {

    /** The display name of [uri], cleaned up the way the device would refuse it. */
    fun displayName(context: Context, uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload"
        val resolver = context.contentResolver
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        cursor.getString(index)?.let { name = it }
                    }
                }
            }
        }
        return sanitize(name)
    }

    /** The name without the characters the device would not take in a path segment. */
    fun sanitize(name: String): String = name
        .replace('/', '_')
        .replace('\\', '_')
        .replace('\u0000', '_')
        .ifBlank { "camera-object" }
}
