package app.amethyst.core

import app.amethyst.core.engine.ModelLoadGuard
import app.amethyst.core.engine.ModelLoadGuard.FailureCount
import app.amethyst.core.engine.ModelLoadGuard.Health
import app.amethyst.core.engine.ModelLoadGuard.Marker
import app.amethyst.core.engine.ModelLoadGuard.Verdict
import app.amethyst.core.engine.ModelLoadMarks
import app.amethyst.core.engine.ModelMarkedDamaged
import app.amethyst.core.engine.Problem
import app.amethyst.core.engine.sherpa.ParakeetModel
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelLoadGuardTest {

    private val hour = 60 * 60 * 1000L
    private val parakeet = "parakeet-tdt-0.6b-v3-int8"
    private val whisper = "futo-multilingual-74"

    @Test
    fun `a marker left from a load that never finished means the model is damaged`() {
        val marker = ModelLoadGuard.before(parakeet, nowMs = 1_000)
        assertEquals(Marker(parakeet, 1_000), marker)
        assertEquals(Verdict.Damaged(parakeet), ModelLoadGuard.verdictAtStart(marker, nowMs = 1_000 + 30_000))
    }

    @Test
    fun `no marker means nothing happened`() {
        assertEquals(Verdict.None, ModelLoadGuard.verdictAtStart(null, nowMs = 5_000))
    }

    @Test
    fun `a marker older than a day is an unrelated kill long ago`() {
        val marker = Marker(parakeet, startedAtMs = 0)
        assertEquals(Verdict.Damaged(parakeet), ModelLoadGuard.verdictAtStart(marker, nowMs = 24 * hour))
        assertEquals(Verdict.None, ModelLoadGuard.verdictAtStart(marker, nowMs = 24 * hour + 1))
    }

    @Test
    fun `a marker from a clock that went back since counts as recent`() {
        assertEquals(Verdict.Damaged(whisper), ModelLoadGuard.verdictAtStart(Marker(whisper, 10 * hour), nowMs = hour))
    }

    @Test
    fun `a marker reads back as written, and a cut-off one as none`() {
        val marker = Marker(whisper, 1_732_000_000_000)
        assertEquals(marker, Marker.decode(marker.encode()))
        assertNull(Marker.decode(""))
        assertNull(Marker.decode("$whisper\n"))
        assertNull(Marker.decode("\n123\n"))
    }

    @Test
    fun `the same model failing twice in a row is treated as damaged`() {
        val first = ModelLoadGuard.afterFailure(whisper, Problem.LocalModelFailed, previous = null)
        assertEquals(FailureCount(whisper, 1), first.count)
        assertEquals(Verdict.None, first.verdict)
        val second = ModelLoadGuard.afterFailure(whisper, Problem.LocalModelFailed, first.count)
        assertEquals(FailureCount(whisper, 2), second.count)
        assertEquals(Verdict.Damaged(whisper), second.verdict)
    }

    @Test
    fun `a failure of another model starts the count over`() {
        val first = ModelLoadGuard.afterFailure(whisper, Problem.LocalModelFailed, previous = null)
        val other = ModelLoadGuard.afterFailure(parakeet, Problem.LocalModelFailed, first.count)
        assertEquals(FailureCount(parakeet, 1), other.count)
        assertEquals(Verdict.None, other.verdict)
    }

    @Test
    fun `running out of memory or an old processor does not count as damage`() {
        val first = ModelLoadGuard.afterFailure(whisper, Problem.LocalModelFailed, previous = null)
        for (why in listOf(Problem.OutOfMemory, Problem.ProcessorTooOld)) {
            val after = ModelLoadGuard.afterFailure(whisper, why, first.count)
            assertEquals(first.count, after.count, "$why")
            assertEquals(Verdict.None, after.verdict, "$why")
        }
        assertNull(ModelLoadGuard.afterFailure(whisper, Problem.OutOfMemory, previous = null).count)
    }

    @Test
    fun `a failure count reads back as written`() {
        val count = FailureCount(parakeet, 1)
        assertEquals(count, FailureCount.decode(count.encode()))
        assertNull(FailureCount.decode("garbage"))
    }

    @Test
    fun `a check decides from the hash, and without one the model stays damaged`() {
        assertNull(ModelLoadGuard.afterCheck(hashMatches = true))
        assertEquals(Health.DAMAGED, ModelLoadGuard.afterCheck(hashMatches = false))
        assertEquals(Health.DAMAGED, ModelLoadGuard.afterCheck(hashMatches = null))
    }

    @Test
    fun `a file matches only at its size and hash`() {
        val file = Files.createTempFile("model", ".bin").toFile()
        file.writeBytes(ByteArray(1000) { it.toByte() })
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertTrue(ModelLoadGuard.matches(file, 1000, hash))
        assertTrue(ModelLoadGuard.matches(file, 1000, hash.uppercase()))
        assertFalse(ModelLoadGuard.matches(file, 999, hash))
        file.writeBytes(ByteArray(1000) { (it + 1).toByte() })
        assertFalse(ModelLoadGuard.matches(file, 1000, hash))
        assertFalse(ModelLoadGuard.matches(File(file.parentFile, "missing-${file.name}"), 1000, hash))
    }

    @Test
    fun `every Parakeet file that is not there or not whole is broken`() {
        val empty = Files.createTempDirectory("parakeet").toFile()
        assertEquals(ParakeetModel.FILES, ParakeetModel.brokenFiles(empty))
        File(empty, "tokens.txt").writeText("not the tokens")
        assertEquals(ParakeetModel.FILES, ParakeetModel.brokenFiles(empty))
    }

    // The guard's files: what the app keeps in noBackupFilesDir.

    private val dir = Files.createTempDirectory("guard").toFile()
    private var now = 100 * hour

    private fun marks() = ModelLoadMarks(dir) { now }

    @Test
    fun `a load that finishes or throws leaves no marker`() {
        val marks = marks()
        assertEquals(7, marks.loading(parakeet) { 7 })
        assertFailsWith<IllegalStateException> { marks.loading(parakeet) { error("bad file") } }
        assertEquals(emptyList(), marks().leftAtStart())
        assertNull(marks().health(parakeet))
    }

    @Test
    fun `a load the process died in is found at the next start, and the model is not loaded until checked`() {
        // The process ends inside the native call: the block never returns, nothing after it runs.
        val before = marks()
        before.markerFor(parakeet).also { it.parentFile.mkdirs() }.writeText(ModelLoadGuard.before(parakeet, now).encode())

        now += 5_000
        val after = marks()
        assertEquals(listOf(Verdict.Damaged(parakeet)), after.leftAtStart())
        assertEquals(Health.CHECKING, after.health(parakeet))
        assertEquals(listOf(parakeet), after.checking())
        assertFailsWith<ModelMarkedDamaged> { after.loading(parakeet) { error("must not load") } }
        // Found once: the marker is gone, the mark stays for the next start.
        assertEquals(emptyList(), marks().leftAtStart())
        assertEquals(Health.CHECKING, marks().health(parakeet))
    }

    @Test
    fun `a marker found by the first load is still reported at start`() {
        marks().markerFor(whisper).also { it.parentFile.mkdirs() }.writeText(ModelLoadGuard.before(whisper, now).encode())
        val marks = marks()
        assertFailsWith<ModelMarkedDamaged> { marks.loading(whisper) { 1 } }
        assertEquals(listOf(Verdict.Damaged(whisper)), marks.leftAtStart())
    }

    @Test
    fun `an old marker is let go of without a mark`() {
        marks().markerFor(whisper).also { it.parentFile.mkdirs() }.writeText(ModelLoadGuard.before(whisper, now).encode())
        now += 25 * hour
        val marks = marks()
        assertEquals(emptyList(), marks.leftAtStart())
        assertNull(marks.health(whisper))
        assertEquals(3, marks.loading(whisper) { 3 })
    }

    @Test
    fun `a check that found the files intact clears the mark, a damaged one keeps it`() {
        val marks = marks()
        marks.suspect(parakeet)
        marks.suspect(whisper)
        marks.checked(parakeet, ModelLoadGuard.afterCheck(hashMatches = true))
        marks.checked(whisper, ModelLoadGuard.afterCheck(hashMatches = false))
        assertNull(marks().health(parakeet))
        assertEquals(Health.DAMAGED, marks().health(whisper))
        assertEquals(emptyList(), marks().checking())
        marks.clear(whisper)
        assertNull(marks().health(whisper))
    }

    @Test
    fun `two failures in a row across a restart mark the model, a success between them does not`() {
        assertEquals(Verdict.None, marks().failed(whisper, Problem.LocalModelFailed))
        assertEquals(Verdict.Damaged(whisper), marks().failed(whisper, Problem.LocalModelFailed))
        assertEquals(Health.CHECKING, marks().health(whisper))
        // Refused while suspect: that failure counts for nothing new, and a damaged model stays damaged.
        assertEquals(Verdict.None, marks().failed(whisper, Problem.LocalModelFailed))
        marks().checked(whisper, Health.DAMAGED)
        assertEquals(Verdict.None, marks().failed(whisper, Problem.LocalModelFailed))
        assertEquals(Health.DAMAGED, marks().health(whisper))

        val marks = marks()
        marks.clear(whisper)
        assertEquals(Verdict.None, marks.failed(whisper, Problem.LocalModelFailed))
        marks.worked(whisper)
        assertEquals(Verdict.None, marks().failed(whisper, Problem.LocalModelFailed))
    }

    @Test
    fun `a guard that cannot write its files still lets the model load`() {
        // Storage full, or the folder gone: here a file where the folder should be, so nothing can be written in it.
        val blocked = File(dir, "blocked").apply { writeText("") }
        val marks = ModelLoadMarks(blocked) { now }
        assertEquals(emptyList(), marks.leftAtStart())
        assertNull(marks.health(parakeet))
        assertEquals(7, marks.loading(parakeet) { 7 })
        assertFailsWith<IllegalStateException> { marks.loading(parakeet) { error("bad file") } }
        // Counting and checking work in memory for this process, without a word on disk.
        assertEquals(Verdict.None, marks.failed(whisper, Problem.LocalModelFailed))
        assertEquals(Verdict.Damaged(whisper), marks.failed(whisper, Problem.LocalModelFailed))
        assertEquals(Health.CHECKING, marks.health(whisper))
        marks.checked(whisper, ModelLoadGuard.afterCheck(hashMatches = true))
        assertNull(marks.health(whisper))
        assertEquals(5, marks.loading(whisper) { 5 })
    }

    @Test
    fun `a marker found at the start whose mark cannot be written is still reported, and kept in memory`() {
        marks().markerFor(parakeet).also { it.parentFile.mkdirs() }.writeText(ModelLoadGuard.before(parakeet, now).encode())
        // The health file cannot be written: its temporary sibling is a folder.
        File(dir, "health.new").mkdirs()
        val marks = marks()
        assertEquals(listOf(Verdict.Damaged(parakeet)), marks.leftAtStart())
        assertEquals(Health.CHECKING, marks.health(parakeet))
        assertEquals(listOf(parakeet), marks.checking())
    }

    @Test
    fun `a model id that is no file name still gets a marker of its own`() {
        val odd = marks().markerFor("../evil/id")
        assertEquals(dir, odd.parentFile.parentFile)
    }
}
