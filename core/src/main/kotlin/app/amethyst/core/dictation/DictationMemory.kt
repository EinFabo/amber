package app.amethyst.core.dictation

import app.amethyst.core.insert.UndoLast
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Everything Amber holds of what was dictated, reached through this one place - and forgotten
 * in one call, [wipe]:
 * - [kept]: a failed dictation's recording for "Try again", and which dictation is the last one
 *   for Home, Insert again and Copy last;
 * - [log]: the records Diagnostics shows, each with its words;
 * - [lastInsertion]: what "Undo last" may take out of a field again, with its text;
 * - the [Holder]s outside :core that the app adds ([hold]): the clipboard while Amber's own clip
 *   is on it, the history of recent dictations, the scratch file a recording is compressed in.
 *
 * Not here, because they hold no words: the statistics (counts and times, and which app) and
 * the insertion method remembered per app.
 */
class DictationMemory(val kept: KeptDictation, val log: DictationLog) {

    /** Something outside :core that holds dictated words or a recording; forgotten with the rest. */
    fun interface Holder {
        /** Forgets what it holds. Called from any thread; may block for a moment (a file, the clipboard). */
        fun forget()
    }

    private val holders = CopyOnWriteArrayList<Holder>()

    /** Adds [holder] to what [wipe] forgets. */
    fun hold(holder: Holder) {
        holders += holder
    }

    /**
     * The last insertion "Undo last" may take out again: its app and exactly what went in.
     * In memory only.
     */
    @Volatile
    var lastInsertion: UndoLast.Insertion? = null
        private set

    /** A dictation went in as [insertion] (null: nothing that can be undone). */
    fun inserted(insertion: UndoLast.Insertion?) {
        lastInsertion = insertion
    }

    /** "Undo last" took it out: nothing left to undo. */
    fun undone() {
        lastInsertion = null
    }

    /** The last dictation of this session for Insert again and Copy last ([KeptDictation.sessionDictation]). */
    fun sessionDictation(): DictationRecord? = kept.sessionDictation(log.records.value)

    /**
     * Forgets everything dictated, for real: the kept recording and the last dictation, every
     * record of Diagnostics (dropped, not hidden), the insertion to undo, and what each
     * [Holder] holds. A holder that fails does not keep the others from forgetting; the first
     * failure is thrown once all were asked.
     */
    fun wipe() {
        kept.clear()
        log.clear()
        lastInsertion = null
        var failure: Exception? = null
        for (holder in holders) {
            try {
                holder.forget()
            } catch (e: Exception) {
                if (failure == null) failure = e
            }
        }
        failure?.let { throw it }
    }
}
