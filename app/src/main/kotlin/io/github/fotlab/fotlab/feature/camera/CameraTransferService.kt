package io.github.fotlab.fotlab.feature.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.fotlab.fotlab.R
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
 * Keeps a batch download alive while the UI is gone.
 *
 * The service owns the transfer's lifetime, not its content: the bytes still come
 * from [CameraCore], so the session stays the single owner of the link. A cancel
 * request stops the transfer job and publishes the cancelled state, which the
 * screen reads from the repository's progress flow.
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
            ACTION_DOWNLOAD -> {
                val handles = intent.getLongArrayExtra(EXTRA_HANDLES) ?: LongArray(0)
                if (handles.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startInForeground()
                transferJob?.cancel()
                transferJob = scope.launch {
                    val repository = runCatching { CameraCore.repository() }.getOrNull()
                    if (repository == null) {
                        finish()
                        return@launch
                    }
                    val watcher = launch {
                        repository.progress.collectLatest { progress ->
                            progress?.let { publish(it) }
                        }
                    }
                    try {
                        CameraCore.download(handles.toList())
                    } catch (_: Throwable) {
                        // The repository exposes the failure in its progress flow.
                    } finally {
                        watcher.cancel()
                        finish()
                    }
                }
            }
            ACTION_CANCEL -> {
                transferJob?.cancel("Download cancelled")
                transferJob = null
                publish(
                    TransferProgress(
                        kind = TransferKind.DOWNLOAD,
                        objectName = "",
                        state = TransferState.CANCELLED,
                    ),
                )
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
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
        const val ACTION_DOWNLOAD: String = "io.github.fotlab.fotlab.camera.DOWNLOAD"
        const val ACTION_CANCEL: String = "io.github.fotlab.fotlab.camera.CANCEL"
        const val EXTRA_HANDLES: String = "io.github.fotlab.fotlab.camera.HANDLES"
    }
}
