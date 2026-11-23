package app.amethyst.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log
import app.amethyst.AmethystApp
import app.amethyst.R

/**
 * A foreground service of type "microphone" for the length of one recording.
 *
 * It shows that Embersay records - a notification with a Stop button - and puts the process in
 * a foreground state while it does. The recording does not depend on it: the bubble records
 * directly in the accessibility service's process ([app.amethyst.core.capture.MicrophoneRouter]),
 * and Android may refuse a foreground service started from the background (Android 12+:
 * ForegroundServiceStartNotAllowedException; Android 14+: a SecurityException for the
 * microphone type without while-in-use access). A refusal is logged, said to the dictation for
 * its Diagnostics, and the recording goes on without the service.
 *
 * Until 0.35 this service ran all day, started on every visit to Embersay's screen, so that a
 * bubble tap was no background start. Google Play allows a foreground service only for as long
 * as the task the user started, so it now starts with a recording and stops when it ends.
 *
 * Started with startService, not startForegroundService: a service started that way that then
 * fails to call startForeground crashes the whole process ("did not then call startForeground"),
 * with the dictation in it. Started plainly, a refused startForeground is only a log line. A
 * plain start is allowed while the process is not idle, which the accessibility service's
 * binding sees to while the screen is on.
 */
class MicForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    /** Whether this instance reached the foreground - it shows a recording. */
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The notification's Stop: as a tap on the bubble would stop the recording.
            Log.i(TAG, "Stop tapped in the recording notification")
            AmethystApp.graph(this).dictation.stop()
            // Tapped as the recording ended: the tap itself started this service, nothing else keeps it.
            if (!foreground) stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            // The microphone type exists from Android 11; before, the manifest's type stands.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            // Refused from the background. The recording reads the microphone without this
            // service, so log it, tell the dictation, and go.
            Log.w(TAG, "the recording's foreground service was refused: ${e::class.java.name}")
            AmethystApp.graph(this).dictation.foregroundServiceRefused(e::class.java.simpleName)
            stopSelf()
            return START_NOT_STICKY
        }
        foreground = true
        // Never brought back by Android after the process died: the recording died with it.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** Low importance: no sound, no pop-up, shown in the shade while it records. */
    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_recording_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_recording_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        // Immutable and to this service only, which is not exported: nobody else can fill it in.
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, MicForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
        // No tap target of its own: opening Embersay mid-dictation would take the focus away from
        // the field the words are for. Collapsed - as a notification of low importance is in
        // the shade - Android shows the title and this line, not the actions; the line says
        // how to finish, so the title alone is not all there is (Pixel 9a, 2026-11-22). A Stop
        // visible while collapsed would take a media or custom layout, which this is not.
        builder
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentText(getString(R.string.notification_recording_text))
            .setSmallIcon(R.drawable.ic_amber)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_amber),
                    getString(R.string.notification_recording_stop),
                    stop,
                ).build(),
            )
        // Shown at once: a dictation is often over before Android's usual ten-second delay.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "Embersay"
        private const val CHANNEL_ID = "embersay_recording"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.embersay.action.STOP_RECORDING"

        /**
         * The channels of the notification that kept the service running all day: "amethyst_ready"
         * until 0.34 (low importance), "amber_ready_quiet" after it (minimum importance).
         */
        private val OLD_CHANNEL_IDS = listOf("amethyst_ready", "amber_ready_quiet")

        /**
         * Asks for the service as a recording starts. Null when asked; otherwise why Android
         * refused at once (the process counted as in the background) - the recording goes on
         * without it. A refusal that comes later, in the service, reaches the dictation by
         * [app.amethyst.dictation.DictationController.foregroundServiceRefused].
         */
        fun start(context: Context): String? = try {
            context.startService(Intent(context, MicForegroundService::class.java))
            null
        } catch (e: Exception) {
            Log.w(TAG, "could not start the recording's foreground service: ${e::class.java.name}")
            e::class.java.simpleName
        }

        /** The recording ended: the service and its notification go. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MicForegroundService::class.java)) }
                .onFailure { Log.w(TAG, "could not stop the recording's foreground service: ${it::class.java.name}") }
        }

        /** The ready notification's channels go with the update that ended it; nothing if they are gone. */
        fun dropOldChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            for (id in OLD_CHANNEL_IDS) {
                if (manager.getNotificationChannel(id) != null) manager.deleteNotificationChannel(id)
            }
        }
    }
}
