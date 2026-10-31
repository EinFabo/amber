package app.amethyst.accessibility

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import app.amethyst.core.commands.CommandOutcome
import app.amethyst.core.commands.CommandTrace
import app.amethyst.core.commands.MediaKey
import app.amethyst.core.commands.Step
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The voice commands that act on the phone rather than the screen: media keys and the media
 * volume (with the system's own volume panel), and the flashlight. Nothing is read beyond the
 * volume and whether the light is on. What happened goes into the command's [CommandTrace].
 */
class DeviceControls(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val cameras = context.getSystemService(CameraManager::class.java)

    /** Pressed and released, as a headset button: whatever plays reacts, or nothing does. */
    fun press(key: MediaKey, trace: CommandTrace): CommandOutcome {
        val audio = audio ?: return CommandOutcome.Failed(NO_AUDIO).also { trace.note("media key: no audio service") }
        val code = when (key) {
            MediaKey.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaKey.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaKey.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaKey.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        // Android does not say whether anything played or paused.
        trace.note("media key ${KeyEvent.keyCodeToString(code)}: pressed and released")
        return CommandOutcome.Done(
            when (key) {
                MediaKey.PLAY -> "Play"
                MediaKey.PAUSE -> "Pause"
                MediaKey.NEXT -> "Next track"
                MediaKey.PREVIOUS -> "Previous track"
            },
        )
    }

    /** Null when the media volume can be changed here; else why not. */
    fun volumeProblem(): String? = when {
        audio == null -> NO_AUDIO
        audio.isVolumeFixed -> "This phone's volume cannot be changed."
        else -> null
    }

    /** One step of the media volume; [Step.Unchanged] at its limit - or where Android holds it back. */
    fun volumeStep(louder: Boolean, trace: CommandTrace): Step {
        val audio = audio ?: return Step.Failed(NO_AUDIO)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        val direction = if (louder) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        val after = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val mutedAfter = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        trace.note(
            "media volume $before -> $after of ${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}" +
                if (muted != mutedAfter) (if (mutedAfter) ", muted now" else ", unmuted") else "",
        )
        return if (after != before || mutedAfter != muted) Step.Done else Step.Unchanged
    }

    fun mute(on: Boolean, trace: CommandTrace): CommandOutcome {
        volumeProblem()?.let {
            trace.note("media volume: $it")
            return CommandOutcome.Failed(it)
        }
        val audio = audio ?: return CommandOutcome.Failed(NO_AUDIO)
        val change = if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        val adjusted = runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, change, AudioManager.FLAG_SHOW_UI) }
        val muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        trace.note(
            "${if (on) "mute" else "unmute"}: " + (adjusted.exceptionOrNull()?.let { "Android threw ${it::class.java.simpleName}, " } ?: "") +
                "the media stream is ${if (muted) "muted" else "not muted"} now",
        )
        return when {
            muted == on -> CommandOutcome.Done(if (on) "Muted" else "Sound on")
            else -> CommandOutcome.Failed(if (on) "Android did not mute the sound." else "Android did not turn the sound on.")
        }
    }

    /** The first camera with a flash; null until known. */
    private var flash: String? = null

    /** What the light is now, as Android last said; null before it said anything. */
    @Volatile
    private var lit: Boolean? = null
    private var watching = false

    private val torchState = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == flash) lit = enabled
        }
    }

    /** [on] null: the other way round from now. Main thread - the state arrives there. */
    suspend fun torch(on: Boolean?, trace: CommandTrace): CommandOutcome {
        val cameras = cameras ?: return CommandOutcome.Failed(NO_TORCH).also { trace.note("flashlight: no camera service") }
        val id = flash ?: firstFlash(cameras)?.also { flash = it }
        if (id == null) {
            trace.note("flashlight: no camera with a flash found")
            return CommandOutcome.Failed(NO_TORCH)
        }
        trace.note("flashlight: camera $id has a flash")
        if (!watching) {
            // Android reports the current state right after registering, then every change.
            cameras.registerTorchCallback(torchState, Handler(Looper.getMainLooper()))
            watching = true
        }
        if (on == null) {
            withTimeoutOrNull(STATE_WAIT_MS) { while (lit == null) delay(STATE_POLL_MS) }
            trace.note("flashlight was ${when (lit) { true -> "on"; false -> "off"; null -> "not reported within $STATE_WAIT_MS ms" }}")
        }
        val wanted = on ?: (lit != true)
        return try {
            cameras.setTorchMode(id, wanted)
            trace.note("flashlight ${if (wanted) "on" else "off"}: taken")
            CommandOutcome.Done(if (wanted) "Flashlight on" else "Flashlight off")
        } catch (e: CameraAccessException) {
            trace.note("flashlight: CameraAccessException, reason ${e.reason}")
            CommandOutcome.Failed("The camera is in use - the flashlight cannot be switched now.")
        } catch (e: IllegalArgumentException) {
            trace.note("flashlight: IllegalArgumentException for camera $id")
            CommandOutcome.Failed(NO_TORCH)
        }
    }

    fun close() {
        if (watching) runCatching { cameras?.unregisterTorchCallback(torchState) }
        watching = false
    }

    private fun firstFlash(cameras: CameraManager): String? = runCatching {
        cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }.getOrNull()

    private companion object {
        const val NO_AUDIO = "Android's audio service is not available."
        const val NO_TORCH = "This phone has no flashlight Amber can switch."
        const val STATE_WAIT_MS = 300L
        const val STATE_POLL_MS = 20L
    }
}
