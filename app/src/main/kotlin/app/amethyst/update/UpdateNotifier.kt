package app.amethyst.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.net.toUri
import app.amethyst.R
import app.amethyst.core.update.UpdateNotice

/**
 * The notification that a new Amber is out: one per version, on its own quiet channel
 * "Updates", and a tap opens the release page in the browser. Posted only with the
 * notification permission the microphone notice already asks for; without it the hints in
 * the app still show.
 */
class UpdateNotifier(private val context: Context) {

    private val manager: NotificationManager? get() = context.getSystemService(NotificationManager::class.java)

    /** Allowed to post: the permission on Android 13+, and notifications not switched off for Amber. */
    fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager?.areNotificationsEnabled() == true
    }

    fun post(notice: UpdateNotice) {
        val manager = manager ?: return
        if (!canNotify()) return
        createChannel(manager)
        val open = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(Intent.ACTION_VIEW, notice.url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.update_available_title, notice.version))
            .setContentText(context.getString(R.string.update_available_text))
            .setSmallIcon(R.drawable.ic_amber)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // One id: a newer version replaces the notice of an older one.
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w("Amber", "update notification not posted: ${it.javaClass.simpleName}") }
    }

    /** Low importance: in the shade and the status bar, without sound or a pop-up. */
    private fun createChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.update_channel_description)
                setShowBadge(true)
            },
        )
    }

    private companion object {
        const val CHANNEL_ID = "amber_updates"
        const val NOTIFICATION_ID = 1002
        const val REQUEST_CODE = 1002
    }
}
