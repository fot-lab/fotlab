package io.github.fotlab.fotlab.feature.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import io.github.fotlab.fotlab.R

/**
 * Foreground-service guardian for the entire connection lifecycle — from the moment a
 * camera link comes up until the session closes for good.
 *
 * Android Doze and background throttling demote the process to IMPORTANCE_CACHED as
 * soon as the user leaves the camera screen (system folder picker for a download,
 * another app, launcher). The keepalive coroutine inside [CameraCore] runs on
 * Dispatchers.Default and its [kotlinx.coroutines.delay] is therefore throttled badly
 * enough that the camera-side TCP timeout fires first. A retry/backoff cycle suffers
 * from the same starvation: each [delay] in the exponential backoff gets stretched and
 * the cycle never converges before the camera gives up.
 *
 * A foreground service pins the process at IMPORTANCE_SERVICE the whole time — the
 * keepalive probe, the event reader and the reconnect loop all keep running on schedule.
 * [CameraTransferService] is a separate foreground service for queue execution; both
 * coexist, and this daemon outlives (and protects) whichever transfer runs on top.
 *
 * Lifecycle driven by [CameraCore] alone:
 *   · start — right after a successful [CameraCore.connect]
 *   · stop  — on the final [CameraCore.failLink] or user-initiated [CameraCore.disconnect]
 *   · stay  — during reconnect retry/backoff cycles, so recovery actually has a chance
 */
class CameraConnectionDaemon : Service() {

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.camera_connection_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME)
            ?: getString(R.string.camera_connection_default)
        startForeground(NOTIFICATION_ID, buildNotification(deviceName))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(deviceName: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.camera_connection_title))
            .setContentText(getString(R.string.camera_connection_text, deviceName))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "camera_connection"
        private const val NOTIFICATION_ID = 7712
        private const val EXTRA_DEVICE_NAME = "io.github.fotlab.fotlab.camera.CONNECTION_DEVICE"

        /** Pin the process for the full connection lifetime. */
        fun start(context: Context, deviceName: String) {
            val intent = Intent(context, CameraConnectionDaemon::class.java).apply {
                putExtra(EXTRA_DEVICE_NAME, deviceName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }

        /** Drop the pin — called only when the link is truly gone. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CameraConnectionDaemon::class.java)) }
        }
    }
}
