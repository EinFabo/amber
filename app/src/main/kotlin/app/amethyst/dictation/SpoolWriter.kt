package app.amethyst.dictation

import android.util.Log
import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.dictation.RecordingSpool
import app.amethyst.core.history.HistoryKeep
import java.util.concurrent.Executors

/**
 * Writes a running dictation's recording to [spool] - sealed with the history's keys of the day
 * ([app.amethyst.data.HistoryStore.lock]) - off the recorder's thread: the recorder only copies
 * its frames ([Run.frame]); sealing, which asks the keystore, and the file are on one thread of
 * their own, in order, and the run's end ([ended]) comes after its last chunk there.
 */
class SpoolWriter(private val spool: RecordingSpool) {

    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "amber-spool").apply { isDaemon = true } }

    /** One run's recording on its way to the disk. */
    inner class Run internal constructor(internal val writer: RecordingSpool.Writer) {

        @Volatile
        private var refused = false

        /** A frame the recorder kept, [count] samples of [frame]; on the recorder's thread, which reuses [frame]. */
        fun frame(frame: ShortArray, count: Int) = writer.add(frame, count, ::submit)

        /** The recording ended as [audio] (null: nothing worth keeping), its last chunk on its way. */
        fun recorded(audio: PcmAudio?) {
            writer.finish(audio)?.let(::submit)
        }

        private fun submit(chunk: ShortArray) = worker.execute {
            if (!writer.write(chunk) && !refused) {
                refused = true
                // Lengths only, never what was said.
                Log.i(TAG, "the recording is not kept on disk: it could not be sealed now (the phone locked, or no key)")
            }
        }
    }

    /** A run's recording begins: null while the history is [keep] off - then nothing is written. */
    fun begin(keep: HistoryKeep): Run? = runCatching { spool.begin(keep)?.let { Run(it) } }
        .onFailure { Log.w(TAG, "the recording is not kept on disk: ${it.javaClass.simpleName}") }
        .getOrNull()

    /** [run]'s dictation ended: its recording stays on disk only while it is kept for "Try again" ([RecordingSpool.ended]). */
    fun ended(run: Run?) {
        if (run == null) return
        worker.execute {
            try {
                spool.ended(run.writer)
            } catch (e: Exception) {
                Log.w(TAG, "the recording on disk was not tidied: ${e.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "Embersay"
    }
}
