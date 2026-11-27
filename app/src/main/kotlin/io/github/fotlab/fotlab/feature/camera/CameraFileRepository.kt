package io.github.fotlab.fotlab.feature.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.github.fotlab.fotlab.feature.camera.model.PtpFormats
import io.github.fotlab.fotlab.feature.camera.model.PtpMediaFilter
import io.github.fotlab.fotlab.feature.camera.model.PtpResponseException
import io.github.fotlab.fotlab.feature.camera.model.PtpObject
import io.github.fotlab.fotlab.feature.camera.model.PtpStorage
import io.github.fotlab.fotlab.feature.camera.model.TransferKind
import io.github.fotlab.fotlab.feature.camera.model.TransferProgress
import io.github.fotlab.fotlab.feature.camera.model.TransferState
import io.github.fotlab.fotlab.feature.camera.operation.PtpFileOperations
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * User-facing file operations: where bytes come from and where they land.
 *
 * This is the layer that knows about Android storage. Everything above it works
 * in PTP terms (handles, storages, parents), everything below it works in bytes.
 */
class CameraFileRepository(
    private val context: Context,
    private val operations: PtpFileOperations,
) {
    private val resolver = context.contentResolver
    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()

    suspend fun storages(): List<PtpStorage> = withContext(Dispatchers.IO) {
        operations.storages()
    }

    suspend fun objects(
        storageId: Long,
        parent: Long,
        filter: PtpMediaFilter = PtpMediaFilter.ALL,
    ): List<PtpObject> = withContext(Dispatchers.IO) {
        operations.objects(storageId, parent, filter)
    }

    suspend fun thumbnail(handle: Long): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { operations.thumbnail(handle) }.getOrNull()
    }

    /**
     * Downloads objects into [target].
     *
     * [target] is a SAF tree URI the user picked: every object becomes a document created in
     * that tree, so the bytes land where the user chose instead of in a fixed app folder.
     * A `null` target falls back to the public downloads area (MediaStore on API 29+).
     */
    suspend fun download(objects: List<PtpObject>, target: Uri? = null): List<Uri> =
        withContext(Dispatchers.IO) {
            val written = ArrayList<Uri>(objects.size)
        objects.forEachIndexed { index, item ->
            // CameraCore.download() already fetched objectInfo and built PtpObject, so the
            // handle → name/format/size mapping is authoritative here — do NOT re-query
            // objectInfo a second time. The extra round-trip is both wasteful and racy: a
            // device that is slow or flaky on metadata can return a mismatched size (or
            // even refuse the handle between the two reads), and we'd then download the
            // wrong number of bytes into a sink sized by stale info.
            val name = uniqueName(item.name, item.format)
            val mime = PtpFormats.mimeType(item.format)
            val sink = createSink(name, mime, target)
            _progress.value = TransferProgress(
                kind = TransferKind.DOWNLOAD,
                objectName = name,
                objectIndex = index + 1,
                objectCount = objects.size,
                totalBytes = item.sizeBytes,
            )
            try {
                operations.download(item.handle, item.sizeBytes, sink.output) { completed ->
                    _progress.value = TransferProgress(
                        kind = TransferKind.DOWNLOAD,
                        objectName = name,
                        objectIndex = index + 1,
                        objectCount = objects.size,
                        completedBytes = completed,
                        totalBytes = item.sizeBytes,
                    )
                }
                sink.output.flush()
                sink.output.close()
                sink.finish()
                // Verify the bytes actually landed on the phone. SAF docs and MediaStore
                // items can both be inspected through the content resolver: the column
                // gives us the post-write size without reopening the stream.
                val actualSize = resolveWrittenSize(sink.uri)
                if (actualSize != item.sizeBytes) {
                    sink.discard()
                    throw IOException(
                        "Downloaded $name but size mismatch: expected ${item.sizeBytes}B, " +
                            "wrote ${actualSize}B",
                    )
                }
                written += sink.uri
                _progress.value = TransferProgress(
                    kind = TransferKind.DOWNLOAD,
                    objectName = name,
                    objectIndex = index + 1,
                    objectCount = objects.size,
                    completedBytes = item.sizeBytes,
                    totalBytes = item.sizeBytes,
                    state = TransferState.COMPLETED,
                )
            } catch (error: Throwable) {
                runCatching { sink.output.close() }
                sink.discard()
                _progress.value = TransferProgress(
                    kind = TransferKind.DOWNLOAD,
                    objectName = name,
                    objectIndex = index + 1,
                    objectCount = objects.size,
                    totalBytes = item.sizeBytes,
                    state = TransferState.FAILED,
                    failure = error,
                )
                throw error
            }
        }
        written
    }

    /**
     * The byte count the system reports for a URI we just wrote to.
     *
     * SAF documents expose their size through `OpenableColumns.SIZE`; a MediaStore item is
     * also queryable the same way. Returns `-1` when the resolver cannot answer — that is
     * not an error by itself, just an opaque provider.
     */
    private fun resolveWrittenSize(uri: Uri): Long {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0) return cursor.getLong(index)
            }
        }
        return -1
    }

    /**
     * The file name of an object, or `null` when the device does not know the handle.
     *
     * A refused request is an ordinary answer — the handle is simply not there — while a
     * dead link propagates, because it means the session is gone.
     */
    suspend fun objectName(handle: Long): String? = withContext(Dispatchers.IO) {
        try {
            operations.objectInfo(handle).name
        } catch (error: PtpResponseException) {
            null
        }
    }

    /**
     * The handle of the object with this name in this folder, or `null` when there is none.
     *
     * A PTP handle is a device-local number, so this lookup is what carries a queued
     * transfer over to another camera: the file is found again by name in the folder the
     * row remembers.
     */
    suspend fun findObjectByName(storageId: Long, parent: Long, name: String): Long? =
        withContext(Dispatchers.IO) {
            operations.objects(storageId, parent, PtpMediaFilter.ALL)
                .firstOrNull { it.name == name }
                ?.handle
        }

    suspend fun delete(handles: List<Long>) = withContext(Dispatchers.IO) {
        handles.forEach { operations.delete(it) }
    }

    /** Creates a folder (a PTP association) on the device. */
    suspend fun createFolder(storageId: Long, parent: Long, name: String): Long =
        withContext(Dispatchers.IO) { operations.createFolder(storageId, parent, name) }

    /** Uploads a content URI to the selected storage and parent folder. */
    suspend fun upload(source: Uri, storageId: Long, parent: Long): Long = withContext(Dispatchers.IO) {
        val (name, size) = describe(source)
        val stream = resolver.openInputStream(source) ?: throw IOException("Cannot read $source")
        stream.use { input ->
            _progress.value = TransferProgress(
                kind = TransferKind.UPLOAD,
                objectName = name,
                totalBytes = size,
            )
            operations.upload(storageId, parent, name, size, input) { completed ->
                _progress.value = TransferProgress(
                    kind = TransferKind.UPLOAD,
                    objectName = name,
                    completedBytes = completed,
                    totalBytes = size,
                )
            }
        }.also { handle ->
            _progress.value = TransferProgress(
                kind = TransferKind.UPLOAD,
                objectName = name,
                completedBytes = size,
                totalBytes = size,
                state = TransferState.COMPLETED,
            ).copy(objectName = "$name (handle $handle)")
        }
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload"
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
                }
            }
        return sanitize(name) to size
    }

    private fun uniqueName(name: String, format: Long): String {
        val safe = sanitize(name)
        return if (safe.contains('.')) safe else "$safe.${PtpFormats.extension(format, safe)}"
    }

    private fun sanitize(name: String): String = name
        .replace('/', '_')
        .replace('\\', '_')
        .replace('\u0000', '_')
        .ifBlank { "camera-object" }

    private data class Sink(val uri: Uri, val output: OutputStream, val finish: () -> Unit, val discard: () -> Unit)

    /**
     * The place a downloaded object is written to.
     *
     * With a SAF tree URI the object becomes a document inside the folder the user picked;
     * the name is made unique inside that tree the way MediaStore makes it unique on its own.
     * Without one the object lands in the public downloads area under a camera folder.
     */
    private fun createSink(name: String, mime: String, treeUri: Uri?): Sink {
        if (treeUri != null) {
            val uri = createDocument(treeUri, mime, name)
                ?: throw IOException("Cannot create $name in the chosen folder")
            val output = resolver.openOutputStream(uri) ?: throw IOException("Cannot open $uri")
            return Sink(
                uri = uri,
                output = output,
                finish = {},
                discard = { runCatching { resolver.delete(uri, null, null) } },
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Camera")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Cannot create a Downloads entry")
            val output = resolver.openOutputStream(uri) ?: throw IOException("Cannot open $uri")
            return Sink(
                uri = uri,
                output = output,
                finish = {
                    val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                },
                discard = { runCatching { resolver.delete(uri, null, null) } },
            )
        }

        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Camera",
        )
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Cannot create ${directory.absolutePath}")
        }
        val file = File(directory, name)
        return Sink(
            uri = Uri.fromFile(file),
            output = FileOutputStream(file),
            finish = {},
            discard = { runCatching { if (file.exists()) file.delete() } },
        )
    }

    /**
     * Create a document under a SAF tree, with a name no sibling already uses.
     *
     * `DocumentsContract.createDocument` rejects a duplicate name on some providers and
     * silently renames it on others, so the name is checked against the tree's children first
     * and suffixed until it is free.
     */
    private fun createDocument(treeUri: Uri, mime: String, name: String): Uri? {
        val taken = existingNames(treeUri)
        var candidate = name
        var counter = 1
        while (candidate in taken) {
            val stem = name.substringBeforeLast('.', name)
            val extension = name.substringAfterLast('.', "")
            candidate = if (extension.isEmpty()) "$stem ($counter)" else "$stem ($counter).$extension"
            counter++
        }
        // `createDocument` wants the *parent document*, not the tree: the tree URI names the
        // tree, the document URI names the folder inside it.
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        // A provider needs a concrete type; a wildcard MIME type is not one.
        val concreteMime = if (mime.contains('*')) "application/octet-stream" else mime
        return runCatching {
            DocumentsContract.createDocument(resolver, parent, concreteMime, candidate)
        }.getOrNull()
    }

    /** The display names already present directly under a SAF tree. */
    private fun existingNames(treeUri: Uri): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        return runCatching {
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    buildSet {
                        val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        if (index >= 0) {
                            while (cursor.moveToNext()) {
                                cursor.getString(index)?.let { add(it) }
                            }
                        }
                    }
                } ?: emptySet()
        }.getOrDefault(emptySet())
    }
}
