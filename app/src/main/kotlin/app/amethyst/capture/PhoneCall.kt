package app.amethyst.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager

/**
 * Whether the phone is ringing or in a call - a phone call or one in an app (WhatsApp, Signal:
 * Android's communication mode). Read from the audio mode, which needs no permission; a
 * recording that sees it turn on ends ([app.amethyst.core.capture.CallWatch]).
 *
 * The communication mode alone is not confirmed by a call state: the call state needs the phone
 * permission from Android 12, and it would not know a call in an app anyway. Only a mode that
 * turns on during the recording counts, so an app that merely sits in that mode stops nothing.
 */
class PhoneCall(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)

    fun on(): Boolean = runCatching { audio?.mode in CALL_MODES }.getOrDefault(false)

    private companion object {
        // The newer modes are plain ints copied in at compile time; an older Android never
        // reports them, so listing them there is harmless.
        @SuppressLint("InlinedApi")
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
