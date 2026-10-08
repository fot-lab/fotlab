package io.github.fotlab.fotlab.feature.studio

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import io.github.fotlab.fotlab.R
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

// ---------------------------------------------------------------------------
// Export — the share action's format choices
// ---------------------------------------------------------------------------

/**
 * The two formats the fun bar's share menu offers. They are parallel branches, not a
 * quality/format axis: the user picks one, and the export runs exactly that encoder.
 *
 * Both go through `Bitmap.compress`, the platform's own compression + container step, so the two
 * differ only in the [Bitmap.CompressFormat] handed to it and the file's extension / MIME type.
 * PNG is lossless (the platform ignores `quality` there — 100 is the strongest deflate the public
 * API offers, as there is no API to force a zlib level). JPG is lossy: [JPG_QUALITY] is the
 * quantization hint, and Android's JPEG encoder keeps its own chroma subsampling — `compress`
 * exposes no sampling-factor control, so 4:4:4 vs 4:2:0 is the platform's call, not ours.
 */
/** `quality` for the JPEG branch (the platform ignores it for PNG). 95 is the conventional
 * "visually near-lossless" JPEG setting; the chroma subsampling is the encoder's own choice. */
internal const val JPG_QUALITY: Int = 95

internal enum class StudioExportFormat(
    val labelRes: Int,
    val mimeType: String,
    val extension: String,
    val compressFormat: Bitmap.CompressFormat,
    val quality: Int,
) {
    Png(R.string.studio_export_png, "image/png", "png", Bitmap.CompressFormat.PNG, 100),
    Jpg(R.string.studio_export_jpg, "image/jpeg", "jpg", Bitmap.CompressFormat.JPEG, JPG_QUALITY),
    ;
}

/**
 * Decode whatever the canvas is currently showing and re-encode it as [format], returning the
 * file bytes, or null when nothing decodable is resident.
 *
 * The engine hands back either uncompressed PNG bytes (rawler path) or the source [Uri] (Coil
 * path); both are decoded to a [Bitmap] first, then handed to `Bitmap.compress` so the file the
 * user gets is a real compressed image rather than raw samples.
 */
internal fun encodeExport(
    context: Context,
    result: StudioRenderResult?,
    format: StudioExportFormat,
): ByteArray? {
    result ?: return null
    val bitmap = when (val r = result) {
        is StudioRenderResult.Ready -> when (val model = r.model) {
            is ByteBuffer -> BitmapFactory.decodeByteArray(model.array(), 0, model.array().size)
            is Uri -> context.contentResolver.openInputStream(model)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
            else -> null
        }
        else -> null
    } ?: return null
    return ByteArrayOutputStream().use { out ->
        bitmap.compress(format.compressFormat, format.quality, out)
        out.toByteArray()
    }
}

// ---------------------------------------------------------------------------
// SAF helpers — per-call "remember my last folder"
// ---------------------------------------------------------------------------

/**
 * Build an `ACTION_OPEN_DOCUMENT` intent that, when [initialUri] is non-null, starts the system
 * picker in that document's parent folder via [DocumentsContract.EXTRA_INITIAL_URI]. Passing the
 * *document* URI (not a tree) is exactly what makes the picker open where the previous pick landed,
 * which is how LUT / import keep their own independent "recent directory" instead of sharing the
 * single global SAF one.
 */
internal fun openDocumentIntent(mimeTypes: Array<String>, initialUri: Uri?): Intent =
    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mimeTypes.firstOrNull() ?: "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        if (initialUri != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
    }

/**
 * Build an `ACTION_CREATE_DOCUMENT` intent (the share export) that prefills the file name via
 * [Intent.EXTRA_TITLE] and, when [initialUri] is non-null, opens the picker in that export's parent
 * folder so repeated exports stay put. [mimeType] and the title's extension both come from the
 * chosen [StudioExportFormat].
 */
internal fun createDocumentIntent(mimeType: String, title: String, initialUri: Uri?): Intent =
    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mimeType
        putExtra(Intent.EXTRA_TITLE, title)
        if (initialUri != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
    }

/**
 * Take a persistable URI permission on a document the system picker just granted us, so the
 * [DocumentsContract.EXTRA_INITIAL_URI] hint survives process death. Some providers grant only
 * transient permission and throw on the persistable call — that is non-fatal, so we swallow it.
 */
internal fun persistUriPermission(context: Context, uri: Uri, write: Boolean) {
    runCatching {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        context.contentResolver.takePersistableUriPermission(uri, flags)
    }
}
