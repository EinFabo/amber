package app.amethyst.core

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.dictation.DiagnosticsReport
import app.amethyst.core.dictation.DictationLog
import app.amethyst.core.dictation.DictationMemory
import app.amethyst.core.dictation.KeptDictation
import app.amethyst.core.dictation.NotInserted
import app.amethyst.core.dictation.TextShape
import app.amethyst.core.engine.Problem
import app.amethyst.core.insert.InsertionStrategy
import app.amethyst.core.insert.StrategyMemory
import app.amethyst.core.insert.UndoLast
import app.amethyst.core.privacy.SecurityMode
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.PrivacySettings
import app.amethyst.core.stats.DictationStat
import app.amethyst.core.stats.StatsLedger
import app.amethyst.core.text.Transcript
import java.nio.file.Files
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityModeTest {

    private val off = AmethystSettings.defaults(listOf("de-DE")).let { it.copy(insertion = it.insertion.copy(allowClipboard = true)) }
    private val on = off.copy(privacy = PrivacySettings(securityMode = true))

    private var now = 1_000_000L
    private val memory = DictationMemory(KeptDictation({ now }), DictationLog { now })

    private fun transcript(text: String) = Transcript(text = text, engineId = "parakeet", trustLevel = TrustLevel.ON_DEVICE, latencyMs = 300)

    @Test
    fun `absent from older settings, it is off`() {
        val decoded = app.amethyst.core.settings.SettingsCodec.decode("""{"schemaVersion":14}""", listOf("de-DE"))
        assertFalse(SecurityMode.on(decoded))
    }

    @Test
    fun `on, nothing may be kept, copied or written down per app`() {
        assertTrue(SecurityMode.keepsWords(off))
        assertFalse(SecurityMode.keepsWords(on))
        assertTrue(SecurityMode.mayUseClipboard(off))
        assertFalse(SecurityMode.mayUseClipboard(on))
        // The clipboard switch off stays off without the security mode, too.
        assertFalse(SecurityMode.mayUseClipboard(off.copy(insertion = off.insertion.copy(allowClipboard = false))))
        assertTrue(SecurityMode.mayCopy(off))
        assertFalse(SecurityMode.mayCopy(on))
        assertEquals("com.whatsapp", SecurityMode.statApp(off, "com.whatsapp"))
        assertNull(SecurityMode.statApp(on, "com.whatsapp"))
        assertTrue(SecurityMode.remembersApps(off))
        assertFalse(SecurityMode.remembersApps(on))
        assertFalse(SecurityMode.hidesWindows(off))
        assertTrue(SecurityMode.hidesWindows(on))
    }

    @Test
    fun `before the settings are read, everything acts as if the mode were on`() {
        assertTrue(SecurityMode.on(null as AmethystSettings?))
        assertFalse(SecurityMode.keepsWords(null))
        assertFalse(SecurityMode.mayUseClipboard(null))
        assertFalse(SecurityMode.mayCopy(null))
        assertNull(SecurityMode.statApp(null, "com.whatsapp"))
        assertFalse(SecurityMode.remembersApps(null))
        assertEquals(app.amethyst.core.history.HistoryKeep.OFF, SecurityMode.historyKeep(null))
        assertTrue(SecurityMode.hidesWindows(null))
    }

    @Test
    fun `the statistics strip the app as they write, by the settings of that moment`() {
        val stat = DictationStat(at = 1, day = 0, engine = "parakeet", trust = TrustLevel.ON_DEVICE, words = 4, audioMs = 2_000, waitMs = 400, app = "com.whatsapp")
        assertEquals("com.whatsapp", SecurityMode.statToKeep(off, stat).app)
        assertNull(SecurityMode.statToKeep(on, stat).app)
        assertNull(SecurityMode.statToKeep(null, stat).app)
        assertEquals(4, SecurityMode.statToKeep(on, stat).words)
    }

    @Test
    fun `at every start with the mode on, what it does not keep is looked for again`() {
        assertTrue(SecurityMode.repairsAtStart(null, on))
        assertFalse(SecurityMode.repairsAtStart(null, off))
        // Not on every later change of the settings: switching on wipes by itself.
        assertFalse(SecurityMode.repairsAtStart(PrivacySettings(securityMode = true), on))
        fun stat(app: String?) = DictationStat(at = 1, day = 0, engine = "parakeet", trust = TrustLevel.ON_DEVICE, words = 4, audioMs = 2_000, waitMs = 400, app = app)
        assertTrue(SecurityMode.namesApps(listOf(stat(null), stat("com.whatsapp"))))
        assertFalse(SecurityMode.namesApps(listOf(stat(null))))
        assertFalse(SecurityMode.namesApps(emptyList()))
    }

    @Test
    fun `only switching it on wipes - not reading the settings, not switching it off`() {
        assertTrue(SecurityMode.wipesOnSwitch(PrivacySettings(), PrivacySettings(securityMode = true)))
        assertFalse(SecurityMode.wipesOnSwitch(null, PrivacySettings(securityMode = true)))
        assertFalse(SecurityMode.wipesOnSwitch(PrivacySettings(securityMode = true), PrivacySettings(securityMode = true)))
        assertFalse(SecurityMode.wipesOnSwitch(PrivacySettings(securityMode = true), PrivacySettings()))
    }

    @Test
    fun `on, a dictation leaves nothing behind but its shape`() {
        memory.kept.keepRecording(PcmAudio(ShortArray(16_000) { 100 }), Problem.NoConnection("Groq"))
        memory.inserted(UndoLast.Insertion("com.whatsapp", "vorher"))

        memory.keepsWords = false

        // What was kept goes at once.
        assertNull(memory.kept.recording())
        assertNull(memory.lastInsertion)
        // A new dictation keeps nothing for Undo, Insert again, Copy last or "Try again".
        memory.log.recordSuccess(transcript("Treffen wir uns um acht"), InsertionStrategy.COMMIT_TEXT, inserted = true, targetPackage = "com.whatsapp")
        memory.inserted(UndoLast.Insertion("com.whatsapp", "Treffen wir uns um acht"))
        assertFalse(memory.kept.keepRecording(PcmAudio(ShortArray(16_000) { 100 }), Problem.NoConnection("Groq")))
        assertNull(memory.lastInsertion)
        assertNull(memory.sessionDictation())
        val record = memory.log.records.value.single()
        assertEquals("", record.text)
        assertEquals(TextShape(characters = 23, words = 5, lines = 1, language = record.shape?.language), record.shape)
        // Diagnostics show the shape, and nothing hidden to show.
        assertEquals(record.shape, DiagnosticsReport.anonymous(record, DiagnosticsReport.texts(listOf(record))).shape)
    }

    @Test
    fun `a record without words loses them from its error and steps too`() {
        memory.keepsWords = false
        memory.log.recordFailure("Could not type \"Treffen wir uns um acht\" here", text = "Treffen wir uns um acht")
        val record = memory.log.records.value.single()
        assertFalse("acht" in record.error.orEmpty())
        assertFalse("acht" in record.summary())
    }

    @Test
    fun `a record without words names no app - no package, no field's trace`() {
        assertTrue(memory.log.namesApps)
        memory.keepsWords = false
        assertFalse(memory.log.namesApps)
        memory.log.recordSuccess(
            transcript("Treffen wir uns um acht"), InsertionStrategy.COMMIT_TEXT, inserted = true, targetPackage = "com.whatsapp",
            note = "EditText in com.whatsapp → COMMIT_TEXT", timing = "route BUBBLE",
        )
        val record = memory.log.records.value.single()
        assertNull(record.targetPackage)
        assertNull(record.note)
        assertFalse("whatsapp" in record.summary())
        assertFalse(DiagnosticsReport.lines(record).any { "whatsapp" in it })
        // A failure without words loses them too.
        memory.log.recordSuccess(transcript(""), InsertionStrategy.COMMIT_TEXT, inserted = false, targetPackage = "com.whatsapp", note = "EditText in com.whatsapp")
        assertNull(memory.log.records.value.first().targetPackage)
        assertNull(memory.log.records.value.first().note)
    }

    @Test
    fun `off again, words are kept again`() {
        memory.keepsWords = false
        memory.keepsWords = true
        now += 1_000
        memory.log.recordSuccess(transcript("Hallo"), InsertionStrategy.COMMIT_TEXT, inserted = true, targetPackage = "com.whatsapp")
        assertEquals("Hallo", memory.sessionDictation()?.text)
    }

    @Test
    fun `words that did not go in are gone, and the toast says so`() {
        assertEquals(NotInserted.Hint.NOT_KEPT, NotInserted.hint(NotInserted.Why.NO_FIELD, onClipboard = false, kept = false))
        assertEquals(NotInserted.Hint.NOT_KEPT, NotInserted.hint(NotInserted.Why.REFUSED, onClipboard = false, kept = false))
        assertEquals(NotInserted.Hint.MAY_ARRIVE_NOT_KEPT, NotInserted.hint(NotInserted.Why.UNCERTAIN, onClipboard = false, kept = false))
        assertEquals(NotInserted.Hint.NONE, NotInserted.hint(NotInserted.Why.PASSWORD, onClipboard = false, kept = false))
        assertEquals(NotInserted.Hint.INSERT_AGAIN, NotInserted.hint(NotInserted.Why.NO_FIELD, onClipboard = false))
    }

    @Test
    fun `switching it on writes the statistics again without the apps`() {
        val file = Files.createTempDirectory("stats").resolve("dictations.jsonl").toFile()
        val ledger = StatsLedger(file) { ZoneOffset.UTC }
        fun stat(app: String?) = DictationStat(at = 1, day = 0, engine = "parakeet", trust = TrustLevel.ON_DEVICE, words = 4, audioMs = 2_000, waitMs = 400, app = app)
        ledger.record(stat("com.whatsapp"))
        ledger.record(stat(null))
        ledger.record(stat("org.telegram.messenger"))

        val written = ledger.rewrite(SecurityMode::withoutApps)

        assertEquals(3, written.size)
        assertTrue(ledger.all().all { it.app == null })
        assertEquals(listOf(4, 4, 4), ledger.all().map { it.words })
        assertFalse("whatsapp" in file.readText())
        assertFalse(java.io.File(file.path + ".new").exists())
    }

    @Test
    fun `rewriting statistics that are not there writes nothing`() {
        val file = Files.createTempDirectory("stats").resolve("dictations.jsonl").toFile()
        assertTrue(StatsLedger(file).rewrite(SecurityMode::withoutApps).isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun `the insertion methods per app can be forgotten at once`() {
        val strategies = StrategyMemory(mapOf("com.whatsapp" to InsertionStrategy.PASTE))
        strategies.clear()
        assertTrue(strategies.snapshot().isEmpty())
        assertEquals(StrategyMemory.DEFAULT_ORDER, strategies.order("com.whatsapp"))
    }
}
