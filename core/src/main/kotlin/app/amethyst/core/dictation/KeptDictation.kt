package app.amethyst.core.dictation

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.engine.Problem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What Amber keeps of a dictation for a little while, in memory: the recording of a dictation
 * whose recognition failed, for "Try again" in the bubble's quick menu, and the last dictation's
 * words as Home ([lastDictation]) and the quick menu ([sessionDictation]) offer them - the words
 * themselves stay in [DictationLog]'s records. With the history on, [RecordingSpool] keeps the
 * kept recording sealed on disk as well, for exactly as long, so a process killed meanwhile does
 * not lose it.
 *
 * Kept for [keepMs] (10 minutes), one at a time - the last failed one; a recording longer than
 * [MAX_RECORDING_MS] is not kept (five minutes of 16 kHz mono are about 10 MB). Gone after the
 * time, after any dictation whose words came ([textRecognized]) - they are kept for Insert
 * again and Copy last even when they could not go in - or when cleared ([clear]).
 *
 * One of the holders [DictationMemory] reaches - the one place that forgets everything dictated.
 * The security mode switches [keeping] off, which clears what is kept and keeps nothing more
 * until it is on again.
 *
 * Kept by the dictation (its run's thread), read by the quick menu (the main thread): every call
 * holds the lock.
 */
class KeptDictation(
    /** The clock of [DictationLog]'s records: their [DictationRecord.atMillis] is compared with it. */
    private val clock: () -> Long,
    val keepMs: Long = KEEP_MS,
) {
    /** A failed dictation's recording, [keptAt] by [clock]; [unheard] when no speech was heard in it ([keepUnheard]). */
    class Recording(val audio: PcmAudio, val keptAt: Long, val unheard: Boolean = false)

    private var recording: Recording? = null

    /** When everything was last forgotten ([clear]): a dictation from before is not offered again. */
    private var clearedAt = Long.MIN_VALUE

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
     * not kept: nothing to try again ([worthTryingAgain]), too long, or keeping is off - and
     * then a recording kept before stays as it was, its time not started again.
     */
    @Synchronized
    fun keepRecording(audio: PcmAudio, why: Problem?): Boolean {
        if (!worthTryingAgain(why)) return false
        return keepLocked(audio)
    }

    /**
     * Keeps [audio] in which no speech was heard, when it is longer than [MIN_UNHEARD_MS]: a
     * user who thought for a while before speaking, or spoke too quietly for the threshold, gets
     * "Try again" instead of nothing. This is the controller's rule, not [worthTryingAgain]: that
     * one is about a failed recognition, where the same recording fails the same way again.
     *
     * Never in place of a kept recording that had speech in it (a failed or a recovered one):
     * the user who stops a slip of a dictation after "No connection ... Try again" keeps the
     * recording that matters. Only in place of nothing, of an earlier one without speech, or of
     * one past its time.
     */
    @Synchronized
    fun keepUnheard(audio: PcmAudio): Boolean {
        if (audio.durationMs <= MIN_UNHEARD_MS) return false
        expireLocked()
        if (recording?.unheard == false) return false
        return keepLocked(audio, unheard = true)
    }

    /**
     * Keeps [audio] of a recording cut off by the end of an earlier process, which the user
     * recovered on Home ([RecordingSpool.recover]), for "Try again" - in place of any recording
     * kept before. False while keeping is off or when it is too long.
     */
    @Synchronized
    fun keepRecovered(audio: PcmAudio): Boolean = keepLocked(audio)

    private fun keepLocked(audio: PcmAudio, unheard: Boolean = false): Boolean {
        if (!keeping || audio.durationMs > MAX_RECORDING_MS || audio.samples.isEmpty()) return false
        recording = Recording(audio, clock(), unheard)
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

    /**
     * The last dictation with words in [records] (newest first, [DictationLog.lastDictation]),
     * for Home's "Last dictation": for [keepMs] after it was recorded - the time a failed
     * recording is kept, too. Null after that, once cleared, or while keeping is off.
     */
    @Synchronized
    fun lastDictation(records: List<DictationRecord>): DictationRecord? =
        sessionDictation(records)?.takeIf { clock() - it.atMillis < keepMs }

    /**
     * The last dictation with words in [records] for "Insert again" and "Copy last" in the quick
     * menu: this session's, however old - until cleared, and not while keeping is off.
     */
    @Synchronized
    fun sessionDictation(records: List<DictationRecord>): DictationRecord? {
        if (!keeping) return null
        return DictationLog.lastDictation(records)?.takeIf { it.atMillis > clearedAt }
    }

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

    /** Forgets everything kept: the recording, and the last dictation's words for Home and the quick menu. */
    @Synchronized
    fun clear() {
        recording = null
        clearedAt = clock()
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

        /** A recording without speech is kept only when longer than this: shorter is a slip of the finger. */
        const val MIN_UNHEARD_MS = 1_000L

        /**
         * Whether recognizing the same recording again can bring its text - only after a problem
         * that goes away by itself or by a fix in Settings, so "Try again" is worth offering:
         * no connection, no answer in time, a server error or a service down, too many requests,
         * a key missing or refused, no credit, a model gone from the server, a wrong address, an
         * answer that could not be read, a dictation the user stopped after the recording ended,
         * a model on the phone that ran out of memory or failed (let go of at once, so the next go
         * loads it afresh, or the one set by then). [why] null is the run's time limit: worth it.
         *
         * Not after anything else: nothing heard, a recording too long or in a format a server
         * refuses, a processor too old, an enclave that could not be verified, an unencrypted
         * address refused, a recognizer that records by itself - the same recording would fail
         * the same way, and "Try again" would go round in circles.
         *
         * The toast names the reason before "Try again": `tools/check_strings.py` reads this list
         * to keep every such toast within two lines.
         */
        fun worthTryingAgain(why: Problem?): Boolean = when (why) {
            null,
            is Problem.NoConnection,
            is Problem.NoAnswerInTime,
            is Problem.ServerError,
            is Problem.ServiceDown,
            Problem.RateLimited,
            is Problem.NoApiKey,
            is Problem.KeyRejected,
            is Problem.NoCredit,
            is Problem.ModelNotOnServer,
            Problem.AddressNotFound,
            is Problem.AnswerUnreadable,
            Problem.Cancelled,
            Problem.OutOfMemory,
            Problem.LocalModelFailed,
            -> true
            else -> false
        }

        /**
         * Whether the toast after [why] is the reason followed by "Try again", for a recording
         * kept by [worthTryingAgain]. Not after a refused key: it is refused again until the user
         * fixes it, so its own toast names that fix first and "Try again" after it - the recording
         * is still kept, and nothing else would say so. `tools/check_strings.py` reads the
         * problems left out here.
         */
        fun toastSaysTryAgain(why: Problem?): Boolean = worthTryingAgain(why) && why !is Problem.KeyRejected
    }
}
