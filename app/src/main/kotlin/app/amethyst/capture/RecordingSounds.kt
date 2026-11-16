package app.amethyst.capture

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * The start and stop tones (Settings -> Recording, off by default): Android's own tone
 * generator - no sound file, so nothing to license - on the media stream, quiet. Not the system
 * sounds' stream: on a phone set to vibrate or silent it plays nothing, and someone who turned
 * the tones on did not hear them (Fabi, Pixel 9a, 2026-11-16). The media volume is the one the
 * volume keys change, so it is turned down the usual way. A generator per tone,
 * released right after it: nothing is held between dictations.
 */
class RecordingSounds {

    enum class Tone(val type: Int, val ms: Int) {
        /** The microphone is open: one short rising beep. */
        START(ToneGenerator.TONE_PROP_BEEP, 90),

        /** The recording ended: the acknowledging double beep. */
        STOP(ToneGenerator.TONE_PROP_ACK, 120),
    }

    private val main = Handler(Looper.getMainLooper())

    /** Plays [tone]; any thread. A phone that refuses a tone generator plays nothing. */
    fun play(tone: Tone) {
        main.post {
            val generator = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME) }
                .onFailure { Log.w(TAG, "no tone generator: ${it.javaClass.simpleName}") }
                .getOrNull() ?: return@post
            if (!generator.startTone(tone.type, tone.ms)) {
                generator.release()
                return@post
            }
            main.postDelayed({ generator.release() }, tone.ms + RELEASE_AFTER_MS)
        }
    }

    private companion object {
        const val TAG = "Amber"

        /** Of ToneGenerator.MAX_VOLUME (100): quiet, under the stream's own volume. */
        const val VOLUME = 40

        const val RELEASE_AFTER_MS = 100L
    }
}
