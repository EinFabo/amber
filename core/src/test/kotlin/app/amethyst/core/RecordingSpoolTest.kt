package app.amethyst.core

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.dictation.KeptDictation
import app.amethyst.core.dictation.RecordingSpool
import app.amethyst.core.engine.Problem
import app.amethyst.core.history.AesGcmLock
import app.amethyst.core.history.HistoryKeep
import app.amethyst.core.history.HistoryRetention
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RecordingSpoolTest {

    private val hour = 3_600_000L

    /** Noon of a day, so an hour or two either way stays on it. */
    private var now = 20_800 * HistoryRetention.DAY_MS + 12 * hour
    private val dir: File = Files.createTempDirectory("spool").toFile()

    /** Stand in for the history's keystore keys, one per day: [unlocked] false is a locked phone. */
    private var unlocked = true
    private val keys = mutableMapOf<Long, SecretKey>()
    private val lock = AesGcmLock(
        key = { d, create -> if (!unlocked) null else keys[d] ?: if (create) newKey().also { keys[d] = it } else null },
        days = { keys.keys.toSet() },
        destroyKey = { d -> keys.remove(d) },
    )
    private val kept = KeptDictation({ now })
    private val spool = RecordingSpool(dir, lock, kept) { now }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    /** The same folder seen by the next process: what this one left is a leftover there. */
    private fun nextStart(): RecordingSpool = RecordingSpool(dir, lock, KeptDictation({ now })) { now }

    /** [seconds] of 16 kHz mono, each sample telling where it is. */
    private fun samples(seconds: Double) = ShortArray((seconds * 16_000).toInt()) { (it % 30_000).toShort() }

    private fun files() = dir.listFiles().orEmpty().filter { it.isFile }

    /** Records [audio] through [writer] as the recorder would: 30 ms frames, each chunk written when full, the rest at the end. */
    private fun record(writer: RecordingSpool.Writer, audio: ShortArray, finish: Boolean = true): PcmAudio {
        val frame = ShortArray(480)
        var at = 0
        while (at < audio.size) {
            val n = minOf(frame.size, audio.size - at)
            audio.copyInto(frame, 0, at, at + n)
            writer.add(frame, n) { chunk -> writer.write(chunk) }
            at += n
        }
        val recorded = PcmAudio(audio)
        if (finish) writer.finish(recorded)?.let(writer::write)
        return recorded
    }

    @Test
    fun `a recording is written sealed while it runs, and the next start finds it`() {
        val writer = assertNotNull(spool.begin(HistoryKeep.DAY))
        val audio = samples(5.0)
        record(writer, audio)
        // Nothing of the samples in the clear: a stretch of them as bytes is nowhere in the file.
        val stored = files().single().readBytes()
        val stretch = ByteArray(64) { i -> if (i % 2 == 0) ((1000 + i / 2) and 0xFF).toByte() else ((1000 + i / 2) shr 8).toByte() }
        assertFalse(stored.toList().windowed(stretch.size).any { it == stretch.toList() })

        // The process is killed here: the next start offers what was recorded.
        val next = nextStart()
        val leftover = assertNotNull(next.leftover(HistoryKeep.DAY))
        assertEquals(now, leftover.startedAt)
        val recovered = assertNotNull(next.recover(leftover) { true })
        assertContentEquals(audio, recovered.samples)
        assertEquals(PcmAudio.DEFAULT_SAMPLE_RATE, recovered.sampleRate)
    }

    @Test
    fun `what a kill cut off mid-chunk is lost, what came before is recovered`() {
        val writer = assertNotNull(spool.begin(HistoryKeep.DAY))
        val audio = samples(5.0)
        // Killed before the end: the last part never reached the disk, and the last chunk is torn.
        record(writer, audio, finish = false)
        val file = files().single()
        file.writeBytes(file.readBytes().let { it.copyOf(it.size - 10) })
        val next = nextStart()
        val recovered = assertNotNull(next.recover(assertNotNull(next.leftover(HistoryKeep.DAY))) { true })
        val chunk = RecordingSpool.CHUNK_MS * 16
        assertTrue(recovered.samples.isNotEmpty())
        assertEquals(0, recovered.samples.size % chunk)
        assertContentEquals(audio.copyOf(recovered.samples.size), recovered.samples)
    }

    @Test
    fun `a dictation whose words came leaves nothing on the disk`() {
        val writer = assertNotNull(spool.begin(HistoryKeep.DAY))
        record(writer, samples(3.0))
        assertTrue(files().isNotEmpty())
        // Delivered: nothing kept for Try again.
        spool.ended(writer)
        assertTrue(files().isEmpty())
        assertNull(nextStart().leftover(HistoryKeep.DAY))
    }

    @Test
    fun `a cancel while listening keeps nothing, also what the recorder writes after it`() {
        val writer = assertNotNull(spool.begin(HistoryKeep.DAY))
        val frame = ShortArray(480) { 7 }
        val full = mutableListOf<ShortArray>()
        repeat(200) { writer.add(frame, frame.size) { full += it } }
        writer.write(full.first())
        // Cancelled while listening: the recording had no finish and nothing was kept.
        spool.ended(writer)
        assertTrue(files().isEmpty())
        // A chunk still on its way to the disk does not bring the file back.
        assertFalse(writer.write(full.last()))
        assertTrue(files().isEmpty())
    }

    @Test
    fun `a recording kept for Try again stays on the disk as long as in memory`() {
        val writer = assertNotNull(spool.begin(HistoryKeep.DAY))
        val audio = record(writer, samples(3.0))
        assertTrue(kept.keepRecording(audio, Problem.NoConnection("Groq")))
        spool.ended(writer)
        assertTrue(files().isNotEmpty())

        // Killed after the failure: the next start still offers it, within the kept time.
        now += KeptDictation.KEEP_MS - 1
        assertNotNull(nextStart().leftover(HistoryKeep.DAY))
        // Once the kept time is up, no more - the same time as in memory.
        now += 1
        assertNull(nextStart().leftover(HistoryKeep.DAY))
        assertTrue(files().isEmpty())
    }

    @Test
    fun `the kept recording leaves the disk when memory lets go of it`() {
        val writer = assertNotNull(spool.begin(HistoryKeep.DAY))
        val audio = record(writer, samples(3.0))
        kept.keepRecording(audio, null)
        spool.ended(writer)
        spool.sync()
        assertTrue(files().isNotEmpty())
        // Try again brought its words.
        kept.textRecognized()
        spool.sync()
        assertTrue(files().isEmpty())
    }

    @Test
    fun `a newer kept recording takes the place of the older one on the disk`() {
        val first = assertNotNull(spool.begin(HistoryKeep.DAY))
        kept.keepRecording(record(first, samples(2.0)), null)
        spool.ended(first)
        now += 1_000
        val second = assertNotNull(spool.begin(HistoryKeep.DAY))
        kept.keepRecording(record(second, samples(3.0)), null)
        spool.ended(second)
        spool.sync()
        assertEquals(1, files().count { it.name.endsWith(".rec") })
        assertEquals(now, nextStart().leftover(HistoryKeep.DAY)?.startedAt)
    }

    @Test
    fun `a run that ended before the last chunk was written still gets it into the kept file`() {
        val writer = assertNotNull(spool.begin(HistoryKeep.DAY))
        val audio = samples(3.0)
        val frame = ShortArray(480)
        var at = 0
        while (at < audio.size) {
            val n = minOf(frame.size, audio.size - at)
            audio.copyInto(frame, 0, at, at + n)
            writer.add(frame, n) { writer.write(it) }
            at += n
        }
        val recorded = PcmAudio(audio)
        val rest = assertNotNull(writer.finish(recorded))
        kept.keepRecording(recorded, null)
        spool.ended(writer)
        writer.write(rest)
        val next = nextStart()
        assertContentEquals(audio, next.recover(assertNotNull(next.leftover(HistoryKeep.DAY))) { true }?.samples)
    }

    @Test
    fun `a leftover is offered for 24 hours, then deleted`() {
        record(assertNotNull(spool.begin(HistoryKeep.DAY)), samples(2.0), finish = false)
        now += RecordingSpool.MAX_AGE_MS - 1
        assertNotNull(nextStart().leftover(HistoryKeep.DAY))
        now += 1
        assertNull(nextStart().leftover(HistoryKeep.DAY))
        assertTrue(files().isEmpty())
    }

    @Test
    fun `a leftover past its time goes without Home being opened`() {
        record(assertNotNull(spool.begin(HistoryKeep.WEEK)), samples(2.0), finish = false)
        // Within its time, pruning leaves it for Home to offer.
        now += RecordingSpool.MAX_AGE_MS - 1
        nextStart().prune(HistoryKeep.WEEK)
        assertEquals(1, files().count { it.name.endsWith(".rec") })
        // Past it, pruning alone deletes it - no leftover() asked - though the history keeps a week.
        now += 1
        nextStart().prune(HistoryKeep.WEEK)
        assertTrue(files().isEmpty())
    }

    @Test
    fun `pruning deletes a kept recording past its kept time and a time left without its recording`() {
        val writer = assertNotNull(spool.begin(HistoryKeep.DAY))
        assertTrue(kept.keepRecording(record(writer, samples(3.0)), Problem.NoConnection("Groq")))
        spool.ended(writer)
        // A time with no recording beside it, from a delete a kill interrupted.
        File(dir, "${now - 5_000}-0.until").writeText("${now + hour}")
        now += KeptDictation.KEEP_MS
        nextStart().prune(HistoryKeep.DAY)
        assertTrue(files().isEmpty())
    }

    @Test
    fun `pruning leaves this process's recording alone, and with the history off takes every leftover`() {
        record(assertNotNull(spool.begin(HistoryKeep.DAY)), samples(2.0), finish = false)
        now += RecordingSpool.MAX_AGE_MS
        // This process's own run is never pruned: its run's end decides.
        spool.prune(HistoryKeep.DAY)
        assertEquals(1, files().count { it.name.endsWith(".rec") })
        now -= RecordingSpool.MAX_AGE_MS
        nextStart().prune(HistoryKeep.OFF)
        assertTrue(files().isEmpty())
    }

    @Test
    fun `a clock set back does not keep a leftover longer`() {
        record(assertNotNull(spool.begin(HistoryKeep.DAY)), samples(2.0), finish = false)
        now -= hour
        assertNull(nextStart().leftover(HistoryKeep.DAY))
        assertTrue(files().isEmpty())
    }

    @Test
    fun `never written while the history is off`() {
        assertNull(spool.begin(HistoryKeep.OFF))
        assertTrue(files().isEmpty())
    }

    @Test
    fun `the history switched off offers nothing, and deleting all takes it from the disk`() {
        record(assertNotNull(spool.begin(HistoryKeep.DAY)), samples(2.0), finish = false)
        val next = nextStart()
        assertNull(next.leftover(HistoryKeep.OFF))
        next.deleteAll()
        assertTrue(files().isEmpty())
    }

    @Test
    fun `deleting all stops a recording being written`() {
        val writer = assertNotNull(spool.begin(HistoryKeep.DAY))
        val chunks = mutableListOf<ShortArray>()
        val frame = ShortArray(480) { 3 }
        repeat(200) { writer.add(frame, frame.size) { chunks += it } }
        writer.write(chunks[0])
        spool.deleteAll()
        assertFalse(writer.write(chunks[1]))
        assertTrue(files().isEmpty())
    }

    @Test
    fun `a locked phone writes nothing`() {
        unlocked = false
        val writer = assertNotNull(spool.begin(HistoryKeep.DAY))
        record(writer, samples(3.0))
        assertTrue(files().isEmpty())
    }

    @Test
    fun `a recording of this process is never a leftover`() {
        record(assertNotNull(spool.begin(HistoryKeep.DAY)), samples(2.0), finish = false)
        assertNull(spool.leftover(HistoryKeep.DAY))
    }

    @Test
    fun `only the newest leftover is offered, the older ones go`() {
        record(assertNotNull(spool.begin(HistoryKeep.DAY)), samples(2.0), finish = false)
        now += 60_000
        val second = nextStart()
        record(assertNotNull(second.begin(HistoryKeep.DAY)), samples(2.0), finish = false)
        val third = nextStart()
        assertEquals(now, third.leftover(HistoryKeep.DAY)?.startedAt)
        assertEquals(1, files().count { it.name.endsWith(".rec") })
    }

    @Test
    fun `a recovered recording is kept for Try again, and on the disk with it`() {
        record(assertNotNull(spool.begin(HistoryKeep.DAY)), samples(2.0), finish = false)
        val nextKept = KeptDictation({ now })
        val next = RecordingSpool(dir, lock, nextKept) { now }
        val audio = assertNotNull(next.recover(assertNotNull(next.leftover(HistoryKeep.DAY)), nextKept::keepRecovered))
        assertSame(audio, nextKept.recording())
        // No longer a leftover: it is this process's kept recording now.
        assertNull(next.leftover(HistoryKeep.DAY))
        assertTrue(files().isNotEmpty())
        // Its words came: gone from the disk too.
        nextKept.textRecognized()
        next.sync()
        assertTrue(files().isEmpty())
    }

    @Test
    fun `a leftover that cannot be opened is deleted`() {
        record(assertNotNull(spool.begin(HistoryKeep.DAY)), samples(2.0), finish = false)
        // "Delete all" in the history destroyed the keys.
        keys.clear()
        val next = nextStart()
        assertNull(next.recover(assertNotNull(next.leftover(HistoryKeep.DAY))) { true })
        assertTrue(files().isEmpty())
    }

    @Test
    fun `a leftover the user deletes is gone`() {
        record(assertNotNull(spool.begin(HistoryKeep.DAY)), samples(2.0), finish = false)
        val next = nextStart()
        next.discard(assertNotNull(next.leftover(HistoryKeep.DAY)))
        assertTrue(files().isEmpty())
        assertNull(next.leftover(HistoryKeep.DAY))
    }

    @Test
    fun `no more is written than the longest recording kept`() {
        val writer = assertNotNull(spool.begin(HistoryKeep.DAY))
        record(writer, samples(KeptDictation.MAX_RECORDING_MS / 1000.0 + 10), finish = true)
        val next = nextStart()
        val recovered = assertNotNull(next.recover(assertNotNull(next.leftover(HistoryKeep.DAY))) { true })
        assertTrue(recovered.durationMs <= KeptDictation.MAX_RECORDING_MS)
    }

    @Test
    fun `a recording kept in memory is kept again when recovered, a too long one is not`() {
        assertTrue(kept.keepRecovered(PcmAudio(samples(0.5))))
        assertFalse(kept.keepRecovered(PcmAudio(samples(KeptDictation.MAX_RECORDING_MS / 1000.0 + 1))))
    }
}
