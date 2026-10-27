package app.nocturne.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import app.nocturne.R
import app.nocturne.ui.MainActivity

/**
 * Keeps the app's process in a foreground state of type "microphone".
 *
 * This is the whole reason the service exists. Android 14 and 15 refuse to let an app start
 * microphone capture from the background, and the bubble is tapped while a completely
 * different app is in front. Starting this service once, while Nocturne itself is visible
 * during onboarding, grants the process while-in-use microphone access for as long as it
 * lives - so a later tap is not a background start. The green microphone indicator still
 * appears only while audio is actually being read.
 */
class MicForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            // Thrown when the app was already in the background on Android 14+. The caller
            // then falls back to MicProxyActivity, so log and stop rather than crash.
            Log.w(TAG, "startForeground(microphone) refused", e)
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.notification_ready_title))
            .setContentText(getString(R.string.notification_ready_text))
            .setSmallIcon(R.drawable.ic_moon)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "Nocturne"
        private const val CHANNEL_ID = "nocturne_ready"
        private const val NOTIFICATION_ID = 1001

        /** Must be called while the app is visible, or Android 14+ will refuse it. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MicForegroundService::class.java),
                )
            }.onFailure { Log.w(TAG, "could not start mic service", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MicForegroundService::class.java))
        }

        /**
         * True once the service actually reached the foreground. The dictation controller
         * reads this to decide whether it needs the proxy-activity fallback.
         */
        val isRunning: Boolean get() = running

        @Volatile
        private var running = false
    }
}
