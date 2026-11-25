package app.amethyst.core.dictation

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.history.HistoryKeep
import app.amethyst.core.history.HistoryLock
import app.amethyst.core.history.HistoryRetention
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A dictation's recording on disk while it runs, so a process killed meanwhile - Android ending
 * sayEmber for memory, a crash - does not lose it: the next start offers it on Home ("Recover
 * last recording", [leftover]), to be recognized with the speech recognition set then.
 *
 * Only with the history on ([begin] with [HistoryKeep.OFF] writes nothing - the user chose no
 * words on disk) and sealed as the history is: AES-GCM with the history's key of the day the
 * recording began ([HistoryLock], in the app the keystore keys that work only while the phone is
 * unlocked - a locked phone writes nothing). Every [CHUNK_MS] of it is sealed on its own, its
 * place bound to it, so what a kill cuts off mid-chunk is lost and what came before opens. The
 * file's name, its start time, is in the clear: what is past its time goes without the key.
 *
 * How long a recording stays on disk:
 * - while its run is open, from the first chunk to the run's end ([ended]);
 * - after the run, only while [KeptDictation] keeps that very recording for "Try again" -
 *   for as long as there: its time is written beside it, and once [KeptDictation] lets go of it
 *   (its words came, its time is up, a newer one took its place, everything was forgotten:
 *   [sync]) it goes. A dictation whose words came, and a cancel while listening, leave nothing;
 * - a leftover of an earlier process for [MAX_AGE_MS] (24 hours) from its start, a kept one no
 *   longer than its kept time - a clock set back ends it at once, it cannot keep one longer. Only
 *   the newest is offered; older ones go. What is past its time goes at every start and whenever
 *   the history is pruned ([prune]), whether Home is opened or not.
 *
 * A recording [KeptDictation] holds whose disk copy was replaced - a newer dictation's run began
 * meanwhile and was cancelled while listening - stays in memory only. The history's keys going
 * ("Delete all", the history off: [deleteAll]) takes the spool with them.
 *
 * Files and keystore: not on the main thread. The run's writer is fed on the recorder's thread
 * ([Writer.add]) and writes on another ([Writer.write]); everything else holds this one's lock.
 */
class RecordingSpool(
    private val dir: File,
    private val lock: HistoryLock,
    private val kept: KeptDictation,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A recording an earlier process left, begun [startedAt] by the clock. */
    class Leftover internal constructor(internal val file: File, val startedAt: Long)

    /**
     * One run's recording on its way to the disk. The file is made at the first chunk sealed;
     * one that cannot be sealed (the phone locked, no key) stops it for good, nothing written.
     */
    inner class Writer internal constructor(internal val file: File, val startedAt: Long, private val sampleRate: Int) {

        private val chunkSamples = CHUNK_MS * sampleRate / 1000
        private val maxSamples = (KeptDictation.MAX_RECORDING_MS * sampleRate / 1000).toInt()
        private val buffer = ShortArray(chunkSamples)
        private var filled = 0
        private var taken = 0

        /** The run's recording once it ended ([finish]): what [KeptDictation] must hold for the file to stay. */
        @Volatile
        internal var audio: PcmAudio? = null
            private set

        private var index = 0
        private var closed = false

        /**
         * The first [count] samples of [frame], as kept by the recorder; copied. Every full
         * chunk goes to [full], for [write]. Nothing past the longest recording kept.
         */
        fun add(frame: ShortArray, count: Int, full: (ShortArray) -> Unit) {
            // Made only when a chunk is full: nothing new for the recorder's every frame.
            var chunks: MutableList<ShortArray>? = null
            synchronized(buffer) {
                var from = 0
                var left = minOf(count, maxSamples - taken).coerceAtLeast(0)
                taken += left
                while (left > 0) {
                    val n = minOf(left, chunkSamples - filled)
                    frame.copyInto(buffer, filled, from, from + n)
                    filled += n
                    from += n
                    left -= n
                    if (filled == chunkSamples) {
                        (chunks ?: mutableListOf<ShortArray>().also { chunks = it }) += buffer.copyOf()
                        filled = 0
                    }
                }
            }
            chunks?.forEach(full)
        }

        /** The recording ended as [audio] (null: nothing worth keeping): what is left of the last chunk, for [write]. */
        fun finish(audio: PcmAudio?): ShortArray? {
            this.audio = audio
            synchronized(buffer) {
                if (filled == 0) return null
                return buffer.copyOf(filled).also { filled = 0 }
            }
        }

        /** Seals [chunk] and adds it to the file. False when nothing was written: closed, or the key refused. */
        fun write(chunk: ShortArray): Boolean = synchronized(this) {
            if (closed || chunk.isEmpty()) return false
            val sealed = lock.seal(HistoryRetention.day(startedAt), bytes(chunk), associated(startedAt, sampleRate, index))
            if (sealed == null) {
                // The phone is locked, or there is no key: nothing at all is kept of this one.
                closeLocked()
                return false
            }
            return try {
                file.parentFile?.mkdirs()
                val fresh = !file.exists()
                FileOutputStream(file, true).use { out ->
                    val record = ByteBuffer.allocate((if (fresh) HEADER_BYTES else 0) + 4 + sealed.size)
                    if (fresh) record.put(MAGIC).put(VERSION).putInt(sampleRate)
                    record.putInt(sealed.size).put(sealed)
                    out.write(record.array())
                }
                index++
                true
            } catch (e: java.io.IOException) {
                closeLocked()
                false
            }
        }

        /** Nothing more is written, and the file goes. */
        internal fun close() = synchronized(this) { closeLocked() }

        private fun closeLocked() {
            closed = true
            file.delete()
            untilFile(file).delete()
        }
    }

    /** The recording [KeptDictation] keeps, [audio], and its file - with the [writer] that may still add its last chunk. */
    private class Kept(val file: File, val audio: PcmAudio, val writer: Writer?)

    /** This process's files: never a leftover. */
    private val own = mutableSetOf<String>()

    /** The runs' writers not closed yet: [deleteAll] stops them. */
    private val writers = mutableListOf<Writer>()

    private var keptFile: Kept? = null

    private val _changes = MutableStateFlow(0L)

    /** Counts every change of what a screen may offer: a leftover recovered, deleted, everything deleted. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /**
     * A run's recording begins: its writer, or null while the history is [keep] off - then
     * nothing is written. The file comes with the first chunk.
     */
    @Synchronized
    fun begin(keep: HistoryKeep, sampleRate: Int = PcmAudio.DEFAULT_SAMPLE_RATE): Writer? {
        if (keep == HistoryKeep.OFF) return null
        val startedAt = clock()
        var n = 0
        var file: File
        do {
            file = File(dir, "$startedAt-${n++}$SUFFIX")
        } while (file.exists() || file.name in own)
        own += file.name
        return Writer(file, startedAt, sampleRate).also { writers += it }
    }

    /**
     * [writer]'s run ended: its file stays while [KeptDictation] keeps the run's recording for
     * "Try again" - then for as long as there - and goes otherwise.
     */
    @Synchronized
    fun ended(writer: Writer) {
        val audio = writer.audio
        if (audio != null && kept.recording() === audio) {
            hold(Kept(writer.file, audio, writer))
        } else {
            writer.close()
            writers -= writer
        }
    }

    /** [KeptDictation] changed: the kept file follows - its time written anew, or gone with the recording it held. */
    @Synchronized
    fun sync() {
        val current = keptFile ?: return
        val audio = kept.recording()
        if (audio !== current.audio) {
            drop(current)
            keptFile = null
        } else {
            writeUntil(current.file)
        }
    }

    /**
     * The newest recording an earlier process left, while the history is [keep] on and it is
     * within its time; null otherwise. What is past its time is deleted, and every leftover but
     * the newest. Reads names only: no key needed.
     */
    @Synchronized
    fun leftover(keep: HistoryKeep): Leftover? {
        if (keep == HistoryKeep.OFF) return null
        val live = pruneLocked(keep)
        val newest = live.maxByOrNull { it.startedAt }
        live.filter { it !== newest }.forEach { delete(it.file) }
        return newest
    }

    /**
     * Deletes what earlier processes left that is past its time ([MAX_AGE_MS], or its kept time),
     * and a time written beside a recording that is gone - with the history [keep] off, every
     * leftover. Called at every start and whenever the history is pruned, so "24 hours at most"
     * holds for someone who never opens Home. This process's own recordings are left alone: its
     * runs and [KeptDictation] decide about them. Reads names only: no key needed.
     */
    @Synchronized
    fun prune(keep: HistoryKeep) {
        pruneLocked(keep)
    }

    /** [prune], and the leftovers within their time. */
    private fun pruneLocked(keep: HistoryKeep): List<Leftover> {
        val files = dir.listFiles().orEmpty().filter { it.isFile && it.name !in own }
        if (keep == HistoryKeep.OFF) {
            files.forEach { it.delete() }
            return emptyList()
        }
        // A time with no recording beside it: left by a delete a kill interrupted.
        files.filter { it.name.endsWith(UNTIL) && !File(dir, it.name.removeSuffix(UNTIL) + SUFFIX).exists() }.forEach { it.delete() }
        val now = clock()
        return files.filter { it.name.endsWith(SUFFIX) }.mapNotNull { file ->
            val startedAt = startedAt(file)
            if (startedAt == null || expired(file, startedAt, now)) {
                delete(file)
                null
            } else {
                Leftover(file, startedAt)
            }
        }
    }

    /**
     * Opens [leftover] and hands it to [keep] - [KeptDictation.keepRecovered]. Kept there, it is
     * this process's kept recording from now on, on disk as long as there ([sync]); null when it
     * could not be opened (its key gone, nothing whole in it) or [keep] refused it - then it goes.
     */
    @Synchronized
    fun recover(leftover: Leftover, keep: (PcmAudio) -> Boolean): PcmAudio? {
        val audio = open(leftover.file, leftover.startedAt)
        if (audio == null || !keep(audio)) {
            delete(leftover.file)
            changed()
            return null
        }
        own += leftover.file.name
        hold(Kept(leftover.file, audio, null))
        changed()
        return audio
    }

    /** The user deleted [leftover] without recovering it. */
    @Synchronized
    fun discard(leftover: Leftover) {
        delete(leftover.file)
        changed()
    }

    /**
     * Deletes every recording on disk and stops the runs writing - the history off, "Delete all",
     * everything forgotten. What [KeptDictation] holds stays in memory.
     */
    @Synchronized
    fun deleteAll() {
        writers.forEach { it.close() }
        writers.clear()
        keptFile = null
        dir.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
        changed()
    }

    private fun hold(next: Kept) {
        keptFile?.takeIf { it.file != next.file }?.let(::drop)
        keptFile = next
        writeUntil(next.file)
    }

    private fun drop(gone: Kept) {
        if (gone.writer != null) {
            gone.writer.close()
            writers -= gone.writer
        } else {
            delete(gone.file)
        }
    }

    private fun writeUntil(file: File) {
        val until = kept.expiresAt() ?: return
        runCatching { untilFile(file).writeText(until.toString()) }
    }

    /**
     * Past its time at [now]: [MAX_AGE_MS] after [startedAt], or once the kept time written beside
     * it is up - also when that cannot be read. A start after [now] is a clock set back: gone.
     */
    private fun expired(file: File, startedAt: Long, now: Long): Boolean {
        val age = now - startedAt
        if (age < 0 || age >= MAX_AGE_MS) return true
        val until = untilFile(file).takeIf { it.exists() } ?: return false
        val at = runCatching { until.readText().trim().toLong() }.getOrNull() ?: return true
        return now >= at
    }

    /** The samples sealed in [file], up to the first chunk that is not whole or does not open; null with none. */
    private fun open(file: File, startedAt: Long): PcmAudio? = try {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            if (!magic.contentEquals(MAGIC) || input.readByte() != VERSION) return null
            val sampleRate = input.readInt()
            if (sampleRate <= 0) return null
            val out = ByteArrayOutputStream()
            var index = 0
            while (true) {
                val size = try {
                    input.readInt()
                } catch (e: EOFException) {
                    break
                }
                if (size <= 0 || size > MAX_SEALED_BYTES) break
                val sealed = ByteArray(size)
                try {
                    input.readFully(sealed)
                } catch (e: EOFException) {
                    break
                }
                val plain = lock.open(HistoryRetention.day(startedAt), sealed, associated(startedAt, sampleRate, index)) ?: break
                out.write(plain)
                index++
            }
            val bytes = out.toByteArray()
            if (bytes.size < 2) {
                null
            } else {
                val samples = ShortArray(bytes.size / 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                PcmAudio(samples, sampleRate)
            }
        }
    } catch (e: java.io.IOException) {
        null
    }

    private fun delete(file: File) {
        file.delete()
        untilFile(file).delete()
    }

    private fun changed() {
        _changes.value = _changes.value + 1
    }

    private fun untilFile(file: File) = File(file.parentFile, file.name.removeSuffix(SUFFIX) + UNTIL)

    private fun startedAt(file: File): Long? = file.name.substringBefore('-').toLongOrNull()

    companion object {
        /** How much of a recording is sealed at a time: what a kill can cut off at most, besides what is on its way. */
        const val CHUNK_MS = 2_000

        /** How long a leftover is offered at most, from its start. */
        const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

        private const val SUFFIX = ".rec"
        private const val UNTIL = ".until"
        private val MAGIC = "AMSP".toByteArray()
        private const val VERSION: Byte = 1
        private const val HEADER_BYTES = 4 + 1 + 4

        /** Far above a sealed chunk (64 KB at 16 kHz, plus nonce and tag): a length beyond it is no chunk of ours. */
        private const val MAX_SEALED_BYTES = 1 shl 20

        private fun associated(startedAt: Long, sampleRate: Int, index: Int) = "amber-spool:$startedAt:$sampleRate:$index".toByteArray()

        private fun bytes(samples: ShortArray): ByteArray {
            val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            buffer.asShortBuffer().put(samples)
            return buffer.array()
        }
    }
}
