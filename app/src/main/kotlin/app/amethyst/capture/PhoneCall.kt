package app.amethyst.capture

import android.content.Context
import android.media.AudioManager

/**
 * Whether the phone is ringing or in a call - a phone call or one in an app (WhatsApp, Signal:
 * Android's communication mode). Read from the audio mode, which needs no permission; a
 * recording that sees it turn on ends ([app.amethyst.core.capture.MicrophoneWatch]).
 */
class PhoneCall(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)

    fun on(): Boolean = runCatching { audio?.mode in CALL_MODES }.getOrDefault(false)

    private companion object {
        val CALL_MODES = setOf(
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_CALL_SCREENING,
            AudioManager.MODE_CALL_REDIRECT,
            AudioManager.MODE_COMMUNICATION_REDIRECT,
        )
    }
}
