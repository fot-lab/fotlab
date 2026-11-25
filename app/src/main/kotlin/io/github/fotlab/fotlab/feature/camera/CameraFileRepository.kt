package io.github.fotlab.fotlab.feature.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.github.fotlab.fotlab.feature.camera.model.PtpFormats
import io.github.fotlab.fotlab.feature.camera.model.PtpMediaFilter
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

    /** Downloads objects into Downloads/Camera; MediaStore entries stay pending until complete. */
    suspend fun download(objects: List<PtpObject>): List<Uri> = withContext(Dispatchers.IO) {
        val written = ArrayList<Uri>(objects.size)
        objects.forEachIndexed { index, item ->
            val info = operations.objectInfo(item.handle)
            val name = uniqueName(item.name, info.format)
            val mime = PtpFormats.mimeType(info.format)
            val sink = createSink(name, mime)
            _progress.value = TransferProgress(
                kind = TransferKind.DOWNLOAD,
                objectName = name,
                objectIndex = index + 1,
                objectCount = objects.size,
                totalBytes = info.sizeBytes,
            )
            try {
                operations.download(item.handle, info.sizeBytes, sink.output) { completed ->
                    _progress.value = TransferProgress(
                        kind = TransferKind.DOWNLOAD,
                        objectName = name,
                        objectIndex = index + 1,
                        objectCount = objects.size,
                        completedBytes = completed,
                        totalBytes = info.sizeBytes,
                    )
                }
                sink.output.close()
                sink.finish()
                written += sink.uri
                _progress.value = TransferProgress(
                    kind = TransferKind.DOWNLOAD,
                    objectName = name,
                    objectIndex = index + 1,
                    objectCount = objects.size,
                    completedBytes = info.sizeBytes,
                    totalBytes = info.sizeBytes,
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
                    totalBytes = info.sizeBytes,
                    state = TransferState.FAILED,
                    failure = error,
                )
                throw error
            }
        }
        written
    }

    suspend fun delete(handles: List<Long>) = withContext(Dispatchers.IO) {
        handles.forEach { operations.delete(it) }
    }

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

    private fun createSink(name: String, mime: String): Sink {
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
}
