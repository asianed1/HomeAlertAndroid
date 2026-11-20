package com.homealert.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.homealert.android.HomeAlertApplication
import com.homealert.android.MainActivity

class MonitoringForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val cameraLabel = intent?.getStringExtra(EXTRA_CAMERA_LABEL) ?: "HomeAlert Camera"
        val notification = buildNotification(cameraLabel)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLock()
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomeAlert::CameraWakeLock")
            .apply { acquire(12 * 60 * 60 * 1000L) }
    }

    private fun buildNotification(cameraLabel: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, HomeAlertApplication.CHANNEL_MONITORING)
            .setContentTitle("HomeAlert Armed: $cameraLabel")
            .setContentText("Passive YUV motion detection active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
    }

    override fun onDestroy() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.homealert.android.action.START_MONITOR"
        const val ACTION_STOP = "com.homealert.android.action.STOP_MONITOR"
        const val EXTRA_CAMERA_LABEL = "camera_label"

        fun start(context: Context, cameraLabel: String) {
            val intent = Intent(context, MonitoringForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CAMERA_LABEL, cameraLabel)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, MonitoringForegroundService::class.java))
            }
        }
    }
}
