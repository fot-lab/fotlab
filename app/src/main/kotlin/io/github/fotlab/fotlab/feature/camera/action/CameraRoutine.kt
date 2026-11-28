package io.github.fotlab.fotlab.feature.camera.action

import io.github.fotlab.fotlab.feature.camera.model.PTP_PARENT_ROOT
import io.github.fotlab.fotlab.feature.camera.model.PtpMediaFilter
import io.github.fotlab.fotlab.feature.camera.model.PtpObject
import io.github.fotlab.fotlab.feature.camera.model.PtpObjectInfo
import io.github.fotlab.fotlab.feature.camera.model.PtpStorage
import io.github.fotlab.fotlab.feature.camera.model.TransferProgress
import io.github.fotlab.fotlab.feature.camera.protocol.PtpSession
import io.github.fotlab.fotlab.feature.camera.profile.CameraProfile
import io.github.fotlab.fotlab.feature.camera.transport.SonyHttpTransport
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Top-level facade for the camera feature.
 *
 * One instance wraps one live [CameraActionContext] — the context's 4-segment
 * [TransportPath] enum value drives every opcode / HTTP method selection in the
 * action routines below. The facade itself is transport-agnostic: callers ask
 * for [storages] / [objects] / [thumbnail] / [download] and never know which
 * wire protocol answered.
 *
 * Construction is handled by [io.github.fotlab.fotlab.feature.camera.CameraCore.connect],
 * which builds the right [TransportPath] from device discovery info.
 */
class CameraRoutine private constructor(
    private val ctx: CameraActionContext,
) {

    /** Live transfer progress — the UI observes this. */
    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()

    /** Human-readable label for logs. */
    val label: String get() = ctx.label

    // ── Connection info (fully descriptive from one enum value) ──────

    val path: TransportPath get() = ctx.path
    val ptpStdOnly: Boolean get() = ctx.ptpStdOnly

    // ── Browse surface ────────────────────────────────────────────────

    suspend fun storages(): List<PtpStorage> = StoragesAction.execute(ctx)

    suspend fun objects(
        storageId: Long,
        parent: Long = PTP_PARENT_ROOT,
        filter: PtpMediaFilter = PtpMediaFilter.ALL,
        batchSize: Int = 500,
    ): List<PtpObject> = ListObjectsAction.execute(ctx, storageId, parent, filter, batchSize)

    suspend fun objectInfo(handle: Long): PtpObjectInfo = ObjectInfoAction.execute(ctx, handle)

    suspend fun thumbnail(handle: Long): ByteArray = ThumbnailAction.execute(ctx, handle)

    // ── Transfer surface ──────────────────────────────────────────────

    suspend fun download(
        handle: Long,
        totalBytes: Long,
        output: OutputStream,
        onProgress: (Long) -> Unit = {},
    ): Long {
        val kind = io.github.fotlab.fotlab.feature.camera.model.TransferKind.DOWNLOAD
        _progress.value = TransferProgress(kind = kind, objectName = handle.toString(), totalBytes = totalBytes)
        return DownloadAction.execute(ctx, handle, totalBytes, output) { completed ->
            _progress.value = TransferProgress(
                kind = kind,
                objectName = handle.toString(),
                totalBytes = totalBytes,
                completedBytes = completed,
            )
            onProgress(completed)
        }
    }

    // ── Write surface ─────────────────────────────────────────────────

    suspend fun delete(handle: Long) = DeleteAction.execute(ctx, handle)

    suspend fun upload(
        storageId: Long,
        parent: Long,
        name: String,
        sizeBytes: Long,
        source: InputStream,
    ): Long {
        _progress.value = TransferProgress(
            kind = io.github.fotlab.fotlab.feature.camera.model.TransferKind.UPLOAD,
            objectName = name,
            totalBytes = sizeBytes,
        )
        return UploadAction.execute(ctx, storageId, parent, name, sizeBytes, source) { completed ->
            _progress.value = TransferProgress(
                kind = io.github.fotlab.fotlab.feature.camera.model.TransferKind.UPLOAD,
                objectName = name,
                totalBytes = sizeBytes,
                completedBytes = completed,
            )
        }
    }

    suspend fun createFolder(storageId: Long, parent: Long, name: String): Long =
        CreateFolderAction.execute(ctx, storageId, parent, name)

    // ── Factory ────────────────────────────────────────────────────────

    companion object {

        /** Build a routine for a PTP session on the given [TransportPath]. */
        fun forPtp(
            session: PtpSession,
            path: TransportPath,
            profile: CameraProfile,
            ptpStdOnly: Boolean,
        ): CameraRoutine {
            require(path.isPtp) { "forPtp() requires a PTP path, got $path" }
            val ctx = CameraActionContext(
                path = path,
                ptpStdOnly = ptpStdOnly,
                ptpSession = session,
                profile = profile,
            )
            return CameraRoutine(ctx)
        }

        /** Build a routine for an HTTP transport on the given [TransportPath].
         *  HTTP paths have no PTP opcodes to suppress — ptpStdOnly is fixed to false. */
        fun forHttp(
            transport: SonyHttpTransport,
            path: TransportPath,
            profile: CameraProfile,
        ): CameraRoutine {
            require(path.isHttp) { "forHttp() requires an HTTP path, got $path" }
            val ctx = CameraActionContext(
                path = path,
                ptpStdOnly = false, // HTTP has no PTP vendor opcodes — gate is irrelevant
                ptpSession = null,
                profile = profile,
                httpTransport = transport,
            )
            return CameraRoutine(ctx)
        }
    }
}
