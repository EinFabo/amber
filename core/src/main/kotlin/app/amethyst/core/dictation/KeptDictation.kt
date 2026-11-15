package app.amethyst.core.dictation

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.engine.Problem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What Amber keeps of a dictation for a little while, in memory only - never on disk: the
 * recording of a dictation whose recognition failed, for "Try again" in the bubble's quick menu.
 *
 * Kept for [keepMs] (10 minutes), one at a time - the last failed one; a recording longer than
 * [MAX_RECORDING_MS] is not kept (five minutes of 16 kHz mono are about 10 MB). Gone after the
 * time, after any dictation whose words came ([textRecognized]) - they are kept for Insert
 * again and Copy last even when they could not go in - or when cleared ([clear]).
 *
 * The one place to forget it all: a security mode switches [keeping] off, which clears what is
 * kept and keeps nothing more until it is on again.
 *
 * Kept by the dictation (its run's thread), read by the quick menu (the main thread): every call
 * holds the lock.
 */
class KeptDictation(
    private val clock: () -> Long,
    val keepMs: Long = KEEP_MS,
) {
    /** A failed dictation's recording, [keptAt] by [clock]. */
    class Recording(val audio: PcmAudio, val keptAt: Long)

    private var recording: Recording? = null

    private val _changes = MutableStateFlow(0L)

    /** Counts every change of what is kept, for a screen that shows it. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** Off: nothing is kept, and what was is forgotten at once. */
    @Volatile
    var keeping: Boolean = true
        set(value) {
            field = value
            if (!value) clear()
        }

    /**
     * Keeps [audio] of a dictation whose recognition failed for [why] (null: the run's time
     * limit ran out), in place of any recording kept before. False when it is
     * not kept: nothing to try again ([worthTryingAgain]), too long, or keeping is off.
     */
    @Synchronized
    fun keepRecording(audio: PcmAudio, why: Problem?): Boolean {
        if (!keeping || !worthTryingAgain(why) || audio.durationMs > MAX_RECORDING_MS || audio.samples.isEmpty()) return false
        recording = Recording(audio, clock())
        changed()
        return true
    }

    /** The recording kept for "Try again", or null - also once its time is up, which drops it. */
    @Synchronized
    fun recording(): PcmAudio? {
        expireLocked()
        return recording?.audio
    }

    /** Whether a recording is kept now, for the quick menu. */
    val hasRecording: Boolean get() = recording() != null

    /** When the kept recording's time is up (by [clock]), or null with none: when to call [expire]. */
    @Synchronized
    fun expiresAt(): Long? = recording?.let { it.keptAt + keepMs }

    /** A dictation's words came - this one's or the next: the failed recording is not needed any more. */
    @Synchronized
    fun textRecognized() {
        if (recording == null) return
        recording = null
        changed()
    }

    /** Drops what is past its time - the memory goes back, whether anyone asks again or not. */
    @Synchronized
    fun expire() = expireLocked()

    /** Forgets everything kept. */
    @Synchronized
    fun clear() {
        recording = null
        changed()
    }

    private fun expireLocked() {
        val kept = recording ?: return
        if (clock() - kept.keptAt >= keepMs) {
            recording = null
            changed()
        }
    }

    private fun changed() {
        _changes.value = _changes.value + 1
    }

    companion object {
        /** How long a failed recording is kept: long enough to find a signal, short enough to forget. */
        const val KEEP_MS = 10 * 60 * 1000L

        /** The longest recording kept, a little over the longest one Amber records (five minutes). */
        const val MAX_RECORDING_MS = 330_000L

        /**
         * Whether recognizing the same recording again can bring its text: after no connection, a
         * timeout, a refused key, no credit, a server error - anything but a recording in which
         * nothing was heard. [why] null is the run's time limit: worth it.
         */
        fun worthTryingAgain(why: Problem?): Boolean = when (why) {
            Problem.NothingRecognized, Problem.NoSpeechHeard -> false
            else -> true
        }
    }
}
