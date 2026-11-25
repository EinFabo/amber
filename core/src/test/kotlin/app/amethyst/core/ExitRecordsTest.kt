package app.amethyst.core

import app.amethyst.core.dictation.DiagnosticsReport
import app.amethyst.core.dictation.ExitKind
import app.amethyst.core.dictation.ExitLog
import app.amethyst.core.dictation.ExitRecord
import app.amethyst.core.dictation.ExitRecords
import app.amethyst.core.dictation.SystemExit
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExitRecordsTest {

    private val secret = "Treffpunkt Hauptbahnhof Gleis neun"

    /** A throwable as a crash has it: frames of the app among those of the platform. */
    private fun crash(message: String = secret, appFrames: Int = 3, cause: Throwable? = null): Throwable =
        IllegalStateException(message, cause).apply {
            stackTrace = buildList {
                add(StackTraceElement("android.view.View", "performClick", "View.java", 7000))
                repeat(appFrames) { add(StackTraceElement("app.amethyst.bubble.BubbleController", "render$it", "BubbleController.kt", 100 + it)) }
                add(StackTraceElement("com.whatsapp.Conversation", "onType", "Conversation.java", 12))
                add(StackTraceElement("android.os.Looper", "loop", "Looper.java", 200))
            }.toTypedArray()
        }

    @Test
    fun `a crash keeps its class and the app's frames, never its message`() {
        val record = ExitRecords.fromThrowable(crash(), nowMs = 42)
        assertEquals(42, record.atMs)
        assertEquals(ExitKind.CRASH, record.kind)
        assertTrue(record.detail.startsWith("java.lang.IllegalStateException"), record.detail)
        assertTrue("at app.amethyst.bubble.BubbleController.render0(BubbleController.kt:100)" in record.detail, record.detail)
        assertFalse("Treffpunkt" in record.detail, record.detail)
        assertFalse("android.view" in record.detail, record.detail)
        assertFalse("whatsapp" in record.detail, record.detail)
    }

    @Test
    fun `at most eight frames of the app`() {
        val record = ExitRecords.fromThrowable(crash(appFrames = 20), nowMs = 1)
        assertEquals(ExitRecords.MAX_FRAMES, record.detail.lines().count { it.startsWith("at ") })
    }

    @Test
    fun `a cause is named by its class, and its frames count when the outer one has none`() {
        val cause = crash(message = "inner $secret", appFrames = 2)
        val outer = RuntimeException("outer $secret", cause).apply { stackTrace = emptyArray() }
        val record = ExitRecords.fromThrowable(outer, nowMs = 1)
        val lines = record.detail.lines()
        assertEquals("java.lang.RuntimeException, caused by java.lang.IllegalStateException", lines.first())
        assertEquals(2, lines.count { it.startsWith("at app.amethyst.") })
        assertFalse("Treffpunkt" in record.detail || "inner" in record.detail || "outer" in record.detail, record.detail)
    }

    @Test
    fun `a cause that refers back to itself does not loop`() {
        val a = IllegalStateException(secret)
        val b = RuntimeException(secret, a)
        a.initCause(b)
        assertTrue(ExitRecords.fromThrowable(b, nowMs = 1).detail.isNotBlank())
    }

    private fun record(at: Long, kind: ExitKind = ExitKind.LOW_MEMORY) = ExitRecord(at, kind, "")

    @Test
    fun `merge keeps the newest ten, newest first`() {
        val old = (1L..8L).map { record(it * 100_000) }
        val new = (9L..14L).map { record(it * 100_000) }
        val merged = ExitRecords.merge(old, new)
        assertEquals(ExitRecords.MAX, merged.size)
        assertEquals((14L downTo 5L).map { it * 100_000 }, merged.map { it.atMs })
    }

    @Test
    fun `merge drops a record it already has, by time and kind`() {
        val old = listOf(record(1_000), record(2_000, ExitKind.ANR))
        val merged = ExitRecords.merge(old, listOf(record(1_000), record(2_000, ExitKind.LOW_MEMORY)))
        assertEquals(listOf(2_000L to ExitKind.ANR, 2_000L to ExitKind.LOW_MEMORY, 1_000L to ExitKind.LOW_MEMORY).toSet(), merged.map { it.atMs to it.kind }.toSet())
        assertEquals(3, merged.size)
    }

    @Test
    fun `the crash Android reports after the app recorded it is the same crash, and keeps the app's frames`() {
        val ours = ExitRecords.fromThrowable(crash(), nowMs = 10_000)
        val android = ExitRecords.fromSystem(SystemExit(ExitRecords.REASON_CRASH, importance = 100, status = 0, atMs = 10_400))!!
        assertEquals(listOf(ours), ExitRecords.merge(listOf(ours), listOf(android)))
        // Another crash a minute later is another one.
        val later = android.copy(atMs = 70_000)
        assertEquals(2, ExitRecords.merge(listOf(ours), listOf(later)).size)
    }

    @Test
    fun `Android's reasons become kinds, and an end that was asked for or routine is no record`() {
        fun kind(reason: Int, importance: Int = 100) = ExitRecords.fromSystem(SystemExit(reason, importance, status = 9, atMs = 5))?.kind
        assertEquals(ExitKind.CRASH, kind(ExitRecords.REASON_CRASH))
        assertEquals(ExitKind.NATIVE_CRASH, kind(ExitRecords.REASON_CRASH_NATIVE))
        assertEquals(ExitKind.ANR, kind(ExitRecords.REASON_ANR))
        assertEquals(ExitKind.LOW_MEMORY, kind(ExitRecords.REASON_LOW_MEMORY))
        assertEquals(ExitKind.OTHER, kind(ExitRecords.REASON_SIGNALED))
        assertEquals(ExitKind.OTHER, kind(ExitRecords.REASON_EXCESSIVE_RESOURCE_USAGE))
        // The user ended it, an update replaced it, a permission was taken back: nothing unexpected.
        for (asked in listOf(1, 8, 10, 11, 15, 16)) assertNull(kind(asked), "reason $asked")
        // Android tidying up an app nobody used (cached): routine, not "stopped unexpectedly".
        assertNull(kind(ExitRecords.REASON_LOW_MEMORY, importance = 400))
        assertNull(kind(ExitRecords.REASON_OTHER, importance = 500))
        // A crash is one whatever the app was doing.
        assertEquals(ExitKind.CRASH, kind(ExitRecords.REASON_CRASH, importance = 400))
        // The detail names the reason, and the signal of a kill.
        assertEquals("Android: SIGNALED (signal 9), importance 100", ExitRecords.fromSystem(SystemExit(2, 100, 9, 5))!!.detail)
        assertEquals("Android: LOW_MEMORY, importance 125", ExitRecords.fromSystem(SystemExit(3, 125, 0, 5))!!.detail)
    }

    // The file in noBackupFilesDir.

    private val dir = Files.createTempDirectory("exits").toFile()
    private val file = File(dir, "exits.json")

    @Test
    fun `a crash is on disk for the next start`() {
        ExitLog(file).crashed(crash(), nowMs = 1_000)
        val next = ExitLog(file)
        assertEquals(listOf(ExitKind.CRASH), next.records.value.map { it.kind })
        assertFalse(secret in file.readText())
    }

    @Test
    fun `Android's exits are read once, an earlier one is not added again`() {
        val log = ExitLog(file)
        val found = listOf(SystemExit(3, 100, 0, 2_000), SystemExit(10, 100, 0, 3_000), SystemExit(6, 100, 0, 1_000))
        log.foundAtStart(found)
        assertEquals(listOf(2_000L, 1_000L), log.records.value.map { it.atMs })
        log.clear()
        // The next start gets the same list from Android, and one more.
        val next = ExitLog(file)
        next.foundAtStart(found + SystemExit(4, 100, 0, 4_000))
        assertEquals(listOf(4_000L), next.records.value.map { it.atMs })
    }

    @Test
    fun `cleared and restored, and a file it cannot read or write is no trouble for the app`() {
        val log = ExitLog(file)
        log.crashed(crash(), nowMs = 1_000)
        val cleared = log.records.value
        log.clear()
        assertEquals(emptyList(), ExitLog(file).records.value)
        log.restore(cleared)
        assertEquals(cleared, ExitLog(file).records.value)

        file.writeText("{ not json")
        var trouble: String? = null
        assertEquals(emptyList(), ExitLog(file) { trouble = it }.records.value)
        assertTrue(trouble != null && secret !in trouble!!)

        val blocked = File(file, "inside a file")
        ExitLog(blocked).crashed(crash(), nowMs = 2_000)
    }

    @Test
    fun `the handler records the crash and hands it on to the one before`() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        try {
            var handedOn: Throwable? = null
            Thread.setDefaultUncaughtExceptionHandler { _, e -> handedOn = e }
            val log = ExitLog(file)
            log.installCrashHandler(clock = { 7_000 })
            val error = crash()
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
            assertSame(error, handedOn)
            assertEquals(listOf(7_000L), ExitLog(file).records.value.map { it.atMs })
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    @Test
    fun `the copy lists them, with what happened and no message`() {
        val records = listOf(
            ExitRecords.fromSystem(SystemExit(3, 100, 0, 1_793_455_351_000))!!,
            ExitRecords.fromThrowable(crash(), nowMs = 1_793_455_000_000),
        )
        val text = DiagnosticsReport.of(emptyList(), ZoneOffset.UTC, "sayEmber 1.0", copiedAt = 1_793_455_400_000, exits = records)
        assertTrue("Stopped unexpectedly" in text, text)
        assertTrue("out of memory" in text, text)
        assertTrue("Android: LOW_MEMORY, importance 100" in text, text)
        assertTrue("java.lang.IllegalStateException" in text, text)
        assertTrue("at app.amethyst.bubble.BubbleController.render0" in text, text)
        assertFalse("Treffpunkt" in text, text)
        // None, no section.
        assertFalse("Stopped" in DiagnosticsReport.of(emptyList(), ZoneOffset.UTC, "sayEmber 1.0", copiedAt = 0))
    }
}
