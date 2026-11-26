package io.github.fotlab.fotlab.feature.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import io.github.fotlab.fotlab.R
import io.github.fotlab.fotlab.feature.camera.model.CameraQueueItem
import io.github.fotlab.fotlab.feature.camera.model.CameraQueueStates
import io.github.fotlab.fotlab.feature.camera.model.CameraQueueUri
import io.github.fotlab.fotlab.feature.camera.model.CameraTransferDirection
import io.github.fotlab.fotlab.feature.camera.model.PtpResponseException
import io.github.fotlab.fotlab.feature.camera.model.TransferKind
import io.github.fotlab.fotlab.feature.camera.model.TransferProgress
import io.github.fotlab.fotlab.feature.camera.model.TransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Runs the camera transfer queue (`FOTLAB-DATABS-000004`).
 *
 * The service is the queue's **runner**, not its owner: the rows live in the
 * `camera_file_queue` table and the service reads the pending ones back, one at a time.
 * That inversion is what makes the queue survive what a one-shot batch download could not —
 * a half-finished transfer, a closed screen, a vanished camera. The row is still there when
 * the next camera comes up, and the run continues from it.
 *
 * The service owns the transfer's *lifetime*, not its content: the bytes still come from
 * [CameraCore], so the session stays the single owner of the link.
 */
class CameraTransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var transferJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.camera_transfer_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RUN_QUEUE -> {
                startInForeground()
                transferJob?.cancel()
                transferJob = scope.launch { runQueue() }
            }
            ACTION_CANCEL -> {
                cancelRunning()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Runs every queued transfer that is not finished yet.
     *
     * The pending rows are read from the table, oldest batch first, and each one is executed
     * on its own. A row that completes is stamped with the finishing time; a row the device
     * refuses is stamped with `failed`; a row whose **link** dies is left unfinished, so the
     * next run picks it up again — the queue outlives the camera.
     */
    private suspend fun CoroutineScope.runQueue() {
        val manager = runCatching { CameraCore.queueManager() }.getOrNull()
        if (manager == null) {
            finish()
            return
        }
        try {
            val pending = manager.pending()
            if (pending.isNotEmpty()) {
                startInForeground()
                val watcher = launch {
                    runCatching { CameraCore.repository() }.getOrNull()?.progress
                        ?.collectLatest { progress -> progress?.let { publish(it) } }
                }
                try {
                    for (item in pending) {
                        if (!execute(item)) break
                    }
                } finally {
                    watcher.cancel()
                }
            }
        } catch (_: Throwable) {
            // The repository exposes the failure in its progress flow.
        } finally {
            CameraCore.setQueueRunning(null)
            finish()
        }
    }

    /**
     * Executes one queued transfer.
     *
     * @return `false` when the run must stop — the camera is gone, or the row was cancelled.
     */
    private suspend fun execute(item: CameraQueueItem): Boolean {
        CameraCore.setQueueRunning(item)
        val cameraRef = CameraQueueUri.parseCamera(item.uriCamera)
        if (cameraRef == null) {
            // The row cannot be interpreted, so it will never succeed either.
            CameraCore.markQueueItemFinished(item.id, CameraQueueStates.FAILED)
            return true
        }
        return try {
            when (item.direction) {
                CameraTransferDirection.CAMERA_TO_PHONE -> {
                    val handle = resolveHandle(cameraRef)
                    if (handle == null) {
                        // The file is not on the camera that is connected now. The row is
                        // stamped `failed` and the user decides whether to resume it on a
                        // camera that does have it.
                        CameraCore.markQueueItemFinished(item.id, CameraQueueStates.FAILED)
                        publishFailure(
                            item,
                            IllegalStateException("${cameraRef.fileName} is not on this camera"),
                        )
                    } else {
                        // The phone side carries the destination folder and the file name;
                        // the folder is everything before the last segment.
                        val folder =
                            item.uriPhone.substringBeforeLast('/', missingDelimiterValue = "")
                        val target = if (folder.isEmpty()) null else Uri.parse(folder)
                        CameraCore.download(listOf(handle), target)
                        CameraCore.markQueueItemFinished(item.id, CameraQueueStates.now())
                    }
                    Unit
                }
                CameraTransferDirection.PHONE_TO_CAMERA -> {
                    val source = Uri.parse(item.uriPhone)
                    CameraCore.upload(source, cameraRef.storageId, cameraRef.handle)
                    CameraCore.markQueueItemFinished(item.id, CameraQueueStates.now())
                    Unit
                }
            }
            true
        } catch (error: Throwable) {
            when (error) {
                // The device answered and refused: this row will never succeed, so it is
                // stamped with a terminal state rather than retried forever.
                is PtpResponseException -> {
                    CameraCore.markQueueItemFinished(item.id, CameraQueueStates.FAILED)
                    true
                }
                // The link is gone. The row stays unfinished, so the queue keeps it and the
                // next camera picks it up again.
                else -> {
                    publish(
                        TransferProgress(
                            kind = TransferKind.DOWNLOAD,
                            objectName = item.fileName,
                            state = TransferState.FAILED,
                            failure = error,
                        ),
                    )
                    false
                }
            }
        }
    }

    /**
     * The handle the transfer should use on the camera that is connected now.
     *
     * A PTP handle is a device-local number: the same handle means a different file on a
     * different camera. The stored handle is therefore checked first — it still names the
     * same file — and the file is looked up **by name** in the folder the row remembers
     * when it does not. That lookup is what lets the user carry a batch over to another
     * camera by resuming it there.
     *
     * @return the handle to transfer, or `null` when this camera does not have the file.
     */
    private suspend fun resolveHandle(ref: CameraQueueUri.CameraRef): Long? {
        val stored = CameraCore.objectName(ref.handle)
        if (stored != null && stored == ref.fileName) return ref.handle
        return CameraCore.findObjectByName(ref.storageId, ref.parent, ref.fileName)
    }

    /** Reports a failed row in the notification. */
    private fun publishFailure(item: CameraQueueItem, error: Throwable) {
        publish(
            TransferProgress(
                kind = TransferKind.DOWNLOAD,
                objectName = item.fileName,
                state = TransferState.FAILED,
                failure = error,
            ),
        )
    }

    /** Stops the running transfer and stamps the row it was working on. */
    private fun cancelRunning() {
        val running = CameraCore.queueRunning().value
        transferJob?.cancel("Download cancelled")
        transferJob = null
        scope.launch {
            running?.let { item ->
                runCatching { CameraCore.markQueueItemFinished(item.id, CameraQueueStates.CANCELED) }
            }
        }
        CameraCore.setQueueRunning(null)
        publish(
            TransferProgress(
                kind = TransferKind.DOWNLOAD,
                objectName = "",
                state = TransferState.CANCELLED,
            ),
        )
    }

    private fun startInForeground() {
        val notification = notification(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun publish(progress: TransferProgress) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification(progress))
    }

    private fun notification(progress: TransferProgress?): Notification {
        val text = when {
            progress == null -> getString(R.string.camera_transfer_preparing)
            progress.state == TransferState.COMPLETED -> getString(R.string.camera_transfer_completed)
            progress.state == TransferState.FAILED -> getString(R.string.camera_transfer_failed)
            progress.state == TransferState.CANCELLED -> getString(R.string.camera_transfer_cancelled)
            else -> getString(
                R.string.camera_transfer_progress,
                progress.objectName,
                (progress.fraction * 100).toInt(),
            )
        }
        val indeterminate = progress?.let { it.totalBytes <= 0L && it.state == TransferState.RUNNING } ?: true
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.camera_transfer_channel_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(progress?.state == TransferState.RUNNING || progress == null)
            .setProgress(100, ((progress?.fraction ?: 0f) * 100).toInt(), indeterminate)
            .build()
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "camera_transfer"
        private const val NOTIFICATION_ID = 7711
        const val ACTION_RUN_QUEUE: String = "io.github.fotlab.fotlab.camera.RUN_QUEUE"
        const val ACTION_CANCEL: String = "io.github.fotlab.fotlab.camera.CANCEL"

        /**
         * Asks the service to run the queue.
         *
         * The service reads the pending rows itself, so this call carries no payload: it
         * says "run what is queued", not "run this".
         */
        fun start(context: Context) {
            val intent = Intent(context, CameraTransferService::class.java).apply {
                action = ACTION_RUN_QUEUE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }
    }
}
