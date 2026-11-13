package app.amethyst.core

import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.onboarding.EngineName
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.RecognitionNote
import app.amethyst.core.settings.RecognitionRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecognitionRowsTest {

    private val settings = AmethystSettings.defaults(TEST_PHONE)
    private val parakeet = EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET)
    private val whisper = EngineConfig.fromPreset(EnginePresets.LOCAL_WHISPER)
    private val android = EngineConfig.fromPreset(EnginePresets.ANDROID_ON_DEVICE)
    private val tinfoil = EngineConfig.fromPreset(EnginePresets.TINFOIL)

    @Test
    fun `a row's name leaves out where it runs`() {
        assertEquals(EngineName.Plain("Parakeet TDT"), RecognitionRows.name(parakeet))
        assertEquals(EngineName.Plain("Whisper base"), RecognitionRows.name(whisper))
        assertEquals(EngineName.AndroidRecognizer, RecognitionRows.name(android))
    }

    @Test
    fun `a Whisper model the introduction does not offer is Whisper`() {
        assertEquals(EngineName.Plain("Whisper"), RecognitionRows.name(whisper.copy(modelId = "futo-multilingual-39")))
        assertEquals(EngineName.Plain("Whisper"), RecognitionRows.name(whisper.copy(modelId = "imported-model")))
    }

    @Test
    fun `a server goes by its own name`() {
        assertEquals(EngineName.Own(tinfoil), RecognitionRows.name(tinfoil))
        val mine = EngineConfig.fromPreset(EnginePresets.SERVER).copy(displayName = "Office (fast)")
        assertEquals(EngineName.Own(mine), RecognitionRows.name(mine))
    }

    @Test
    fun `no row shows a model's id`() {
        val presets = listOf(
            EnginePresets.ANDROID_ON_DEVICE, EnginePresets.LOCAL_WHISPER, EnginePresets.LOCAL_PARAKEET,
            EnginePresets.TINFOIL, EnginePresets.GROQ, EnginePresets.OPENAI,
        )
        presets.map { EngineConfig.fromPreset(it) }.forEach { engine ->
            val note = RecognitionRows.note(engine, settings.withEngine(engine), medianWaitMs = null)
            if (engine.modelId != null) assertTrue(note.model != engine.modelId, engine.id)
        }
    }

    @Test
    fun `a recognition on the phone says the wait measured here, or nothing`() {
        assertEquals(RecognitionNote(model = null, stopsAtPause = false, typicalMs = 700), RecognitionRows.note(parakeet, settings, 700))
        assertTrue(RecognitionRows.note(parakeet, settings, null).isEmpty)
        assertTrue(RecognitionRows.note(whisper, settings, null).isEmpty)
    }

    @Test
    fun `Android's recognizer says that it stops at a pause`() {
        val note = RecognitionRows.note(android, settings, null)
        assertEquals(RecognitionNote(model = null, stopsAtPause = true, typicalMs = null), note)
        assertFalse(note.isEmpty)
    }

    @Test
    fun `a server's model goes by the name its service gives it`() {
        val note = RecognitionRows.note(tinfoil, settings.withEngine(tinfoil), 4_800)
        assertEquals(RecognitionNote(model = "Voxtral Small 24B", stopsAtPause = false, typicalMs = 4_800), note)
    }

    @Test
    fun `a model the service does not list is not named`() {
        val unknown = tinfoil.copy(modelId = "some-model-2031")
        assertTrue(RecognitionRows.note(unknown, settings.withEngine(unknown), null).isEmpty)
        val own = EngineConfig.fromPreset(EnginePresets.SERVER).copy(baseUrl = "https://example.org/v1", modelId = "whisper-1")
        assertTrue(RecognitionRows.note(own, settings.withEngine(own), null).isEmpty)
        assertTrue(RecognitionRows.note(tinfoil.copy(modelId = null), settings, null).isEmpty)
    }
}
