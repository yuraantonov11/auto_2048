package com.example.auto_2048

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat

class MediaProjectionService : Service() {

    companion object {
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 101
        private const val TAG = "Auto2048"

        @Volatile
        private var isForeground = false

        fun start(context: Context) {
            if (isForeground) return

            val intent = Intent(context, MediaProjectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun awaitForeground(timeoutMs: Long = 2000L): Boolean {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (!isForeground && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(50)
            }
            return isForeground
        }

        fun stop(context: Context) {
            val intent = Intent(context, MediaProjectionService::class.java)
            context.stopService(intent)
            isForeground = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Auto2048 Vision")
            .setContentText("Бот аналізує гру...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        // The system may revoke a previously granted MediaProjection
        // token (e.g. user revoked screen-capture consent, system killed
        // the session, or the previous bot session ended). When that
        // happens, attempting to start a mediaProjection FGS raises
        // SecurityException ("Starting FGS with type mediaProjection ...
        // requires permissions: all of [FOREGROUND_SERVICE_MEDIA_PROJECTION]
        // any of [CAPTURE_VIDEO_OUTPUT, PROJECT_MEDIA]"). The bot must
        // not crash in that case; instead it must stop itself so the
        // caller can request fresh user permission.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (security: SecurityException) {
            Log.w(TAG, "MediaProjection FGS rejected (token likely revoked): ${security.message}")
            isForeground = false
            // Stop the service so MainActivity's cleanup() releases any
            // partial state and the user is prompted for a fresh token.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isForeground = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}