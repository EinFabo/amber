package app.amethyst.capture

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Pauses music and videos while a recording runs (Settings -> Recording, off by default): Amber
 * asks Android for the audio focus for the recording only and gives it back the moment the
 * recording ends - the player goes on by itself.
 *
 * Exclusive transient focus, not "may duck": from Android 8 a request that allows ducking lets
 * the system turn the music down by itself and keep it playing - into the microphone, and not
 * what "pause" says. Exclusive transient is the request Android names for speech recognition and
 * voice memos: players pause, and notification sounds stay out of the recording as well.
 */
class MediaPause(context: Context) {

    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)

    private val request: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            // Nothing to do when another app takes it back: the recording goes on regardless.
            .setOnAudioFocusChangeListener { }
            .build()

    @Volatile
    private var held = false

    /** Asks for the focus, once; any thread. Refused, the music simply plays on. */
    @Synchronized
    fun hold() {
        if (held) return
        val granted = runCatching { audio?.requestAudioFocus(request) }
            .onFailure { Log.w(TAG, "audio focus request failed: ${it.javaClass.simpleName}") }
            .getOrNull() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        held = granted
    }

    /** Gives the focus back, if it was held; any thread. */
    @Synchronized
    fun release() {
        if (!held) return
        held = false
        runCatching { audio?.abandonAudioFocusRequest(request) }
            .onFailure { Log.w(TAG, "audio focus release failed: ${it.javaClass.simpleName}") }
    }

    private companion object {
        const val TAG = "Embersay"
    }
}
