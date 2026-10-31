package app.amethyst.core

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.commands.CommandLanguages
import app.amethyst.core.commands.Understanding
import app.amethyst.core.commands.VoiceCommands
import app.amethyst.core.engine.EngineCapabilities
import app.amethyst.core.engine.OpenAiCompatibleEngine
import app.amethyst.core.engine.SttEngine
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.TranscribeOptions
import app.amethyst.core.engine.whisper.WhisperCppEngine
import app.amethyst.core.engine.whisper.WhisperRuntime
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.text.Transcript
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A voice command is a second of audio: too little for Whisper to tell German from English, so
 * it is recognized in one language at a time - the phone's first - and the rules get the first
 * words they understand.
 */
class CommandLanguagesTest {

    // --- which languages, in which order --------------------------------------------------

    @Test
    fun `a German phone tries German first, then the rest of the list`() =
        assertEquals(listOf("de", "en"), CommandLanguages.attempts("de,en", phoneLanguage = "de"))

    @Test
    fun `an English phone tries English first`() =
        assertEquals(listOf("en", "de"), CommandLanguages.attempts("de,en", phoneLanguage = "en"))

    @Test
    fun `a phone language outside the list keeps the list's order`() {
        assertEquals(listOf("de", "en"), CommandLanguages.attempts("de,en", phoneLanguage = "fr"))
        assertEquals(listOf("de", "en"), CommandLanguages.attempts("de,en", phoneLanguage = null))
    }

    @Test
    fun `regions fold to the language`() =
        assertEquals(listOf("en", "de"), CommandLanguages.attempts("de-DE, en_US", phoneLanguage = "en-GB"))

    @Test
    fun `one language, or detection, is one attempt as set`() {
        assertEquals(listOf("de-DE"), CommandLanguages.attempts("de-DE", phoneLanguage = "en"))
        assertEquals(listOf("en"), CommandLanguages.attempts("en", phoneLanguage = "de"))
        assertEquals(listOf("auto"), CommandLanguages.attempts("auto", phoneLanguage = "de"))
        assertEquals(listOf<String?>(null), CommandLanguages.attempts(null, phoneLanguage = "de"))
    }

    @Test
    fun `a cap keeps the first attempts`() =
        assertEquals(listOf("fr", "de"), CommandLanguages.attempts("de,en,fr", phoneLanguage = "fr", maxAttempts = 2))

    // --- the prompt -----------------------------------------------------------------------

    @Test
    fun `each language has its own short command vocabulary`() {
        val german = assertNotNull(CommandLanguages.promptFor("de"))
        val english = assertNotNull(CommandLanguages.promptFor("en"))
        assertTrue("Öffne WhatsApp" in german, german)
        assertTrue("Open WhatsApp" in english, english)
        assertEquals(german, CommandLanguages.promptFor("de-DE"))
        assertNull(CommandLanguages.promptFor("fr"))
        assertNull(CommandLanguages.promptFor("auto"))
        assertNull(CommandLanguages.promptFor(null))
    }

    @Test
    fun `every phrase of a prompt is a command the rules understand`() {
        listOf("de", "en").forEach { language ->
            val phrases = CommandLanguages.promptFor(language)!!.split(". ").map { it.trim() }
            assertTrue(phrases.size >= 5, "$language: $phrases")
            phrases.forEach { phrase ->
                assertTrue(VoiceCommands.parse(phrase) { listOf("WhatsApp") } is Understanding.Understood, "$language: \"$phrase\"")
            }
        }
    }

    @Test
    fun `the prompt read back is an echo, one of its commands is not`() {
        val german = CommandLanguages.promptFor("de")
        assertTrue(CommandLanguages.isEcho(german!!, german))
        assertTrue(CommandLanguages.isEcho(" öffne whatsapp, zurück ", german), "two of its phrases in a row")
        assertTrue(CommandLanguages.isEcho("Zurück. Startbildschirm.", german))
        assertFalse(CommandLanguages.isEcho("Zurück.", german), "one of its phrases is what the user may have said")
        assertFalse(CommandLanguages.isEcho("WhatsApp", german))
        assertFalse(CommandLanguages.isEcho("Öffne WhatsApp.", german))
        assertFalse(CommandLanguages.isEcho("Öffne Spotify", german))
        assertFalse(CommandLanguages.isEcho("Zurück. Zurück.", german), "not in the prompt's order")
        assertFalse(CommandLanguages.isEcho("Zurück", null))
    }

    // --- recognizing ----------------------------------------------------------------------

    private val audio = PcmAudio(ShortArray(16_000))
    private val whisperLike = EngineCapabilities(TrustLevel.ON_DEVICE, commandLanguageAttempts = Int.MAX_VALUE, commandPrompt = true)

    /** Answers per language asked for; records every request. */
    private class FakeEngine(
        private val answers: Map<String?, Result<String>>,
        override val capabilities: EngineCapabilities,
    ) : SttEngine {
        override val id = "local-whisper"
        override val displayName = "Whisper"
        val calls = mutableListOf<TranscribeOptions>()

        override suspend fun transcribe(audio: PcmAudio, options: TranscribeOptions): Result<Transcript> {
            calls += options
            val answer = answers[options.languageTag] ?: Result.failure(SttFailure(SttFailure.NOTHING_RECOGNIZED, "no answer scripted"))
            return answer.map { Transcript(it, id, TrustLevel.ON_DEVICE, 0, detectedLanguage = options.languageTag) }
        }
    }

    private fun understood(text: String) = VoiceCommands.parse(text) { listOf("WhatsApp", "Spotify") } is Understanding.Understood

    private fun nothing(technical: String = "whisper returned no speech: \"\"") =
        Result.failure<String>(SttFailure(SttFailure.NOTHING_RECOGNIZED, technical))

    private suspend fun recognize(
        engine: FakeEngine,
        tag: String? = "de,en",
        phone: String? = "de",
        lines: MutableList<String> = mutableListOf(),
    ) = CommandLanguages.recognize(engine, audio, TranscribeOptions(languageTag = tag), phone, ::understood, lines::add)

    @Test
    fun `German first on a German phone, one pass when the rules understand it`() = runTest {
        val engine = FakeEngine(mapOf("de" to Result.success("Öffne WhatsApp."), "en" to Result.success("If now what's up?")), whisperLike)
        val recognition = recognize(engine)
        assertEquals("Öffne WhatsApp.", recognition.result.getOrThrow().text)
        assertEquals(listOf<String?>("de"), engine.calls.map { it.languageTag })
        assertEquals(1, recognition.passes)
    }

    @Test
    fun `the next language is tried when the rules do not understand the first`() = runTest {
        val engine = FakeEngine(mapOf("en" to Result.success("If now what's up?"), "de" to Result.success("Öffne WhatsApp.")), whisperLike)
        val recognition = recognize(engine, phone = "en")
        assertEquals("Öffne WhatsApp.", recognition.result.getOrThrow().text)
        assertEquals(listOf<String?>("en", "de"), engine.calls.map { it.languageTag })
        assertEquals(2, recognition.passes)
    }

    @Test
    fun `none understood - the first attempt's words stand`() = runTest {
        val engine = FakeEngine(mapOf("de" to Result.success("Ich habe kein WhatsApp."), "en" to Result.success("I have no WhatsApp.")), whisperLike)
        val recognition = recognize(engine)
        assertEquals("Ich habe kein WhatsApp.", recognition.result.getOrThrow().text)
        assertEquals(2, recognition.passes)
    }

    @Test
    fun `nothing heard in the first language - the first words heard stand`() = runTest {
        val engine = FakeEngine(mapOf("de" to nothing(), "en" to Result.success("Hello there.")), whisperLike)
        assertEquals("Hello there.", recognize(engine).result.getOrThrow().text)
    }

    @Test
    fun `nothing heard in any language is nothing recognized`() = runTest {
        val engine = FakeEngine(mapOf("de" to nothing(), "en" to nothing()), whisperLike)
        val error = recognize(engine).result.exceptionOrNull()
        assertTrue(error is SttFailure && error.nothingRecognized, "$error")
        assertEquals(2, engine.calls.size)
    }

    @Test
    fun `an engine error stops the attempts and stands`() = runTest {
        val broken = SttFailure("The local model failed to run.", "whisper transcribe: whisper_full returned -7")
        val engine = FakeEngine(mapOf("de" to Result.failure(broken), "en" to Result.success("Open WhatsApp.")), whisperLike)
        val recognition = recognize(engine)
        assertEquals(broken, recognition.result.exceptionOrNull())
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `an engine error after words keeps the words`() = runTest {
        val broken = SttFailure("The local model failed to run.", "whisper transcribe: whisper_full returned -7")
        val engine = FakeEngine(mapOf("de" to Result.success("Hallo du."), "en" to Result.failure(broken)), whisperLike)
        assertEquals("Hallo du.", recognize(engine).result.getOrThrow().text)
    }

    @Test
    fun `each attempt carries its language's vocabulary as the prompt`() = runTest {
        val engine = FakeEngine(mapOf("de" to Result.success("Hallo."), "en" to Result.success("Hello.")), whisperLike)
        recognize(engine)
        assertEquals(listOf(CommandLanguages.promptFor("de"), CommandLanguages.promptFor("en")), engine.calls.map { it.precedingText })
    }

    @Test
    fun `the prompt read back counts as nothing heard`() = runTest {
        val echo = CommandLanguages.promptFor("de")!!
        val engine = FakeEngine(mapOf("de" to Result.success(echo), "en" to Result.success("Open WhatsApp.")), whisperLike)
        assertEquals("Open WhatsApp.", recognize(engine).result.getOrThrow().text)

        val onlyEchoes = FakeEngine(mapOf("de" to Result.success(echo), "en" to Result.success(CommandLanguages.promptFor("en")!!)), whisperLike)
        val error = recognize(onlyEchoes).result.exceptionOrNull()
        assertTrue(error is SttFailure && error.nothingRecognized, "$error")
    }

    @Test
    fun `one language gets one pass, with its prompt`() = runTest {
        val engine = FakeEngine(mapOf("de-DE" to Result.success("Zurück.")), whisperLike)
        val recognition = recognize(engine, tag = "de-DE", phone = "en")
        assertEquals("Zurück.", recognition.result.getOrThrow().text)
        assertEquals(listOf<String?>("de-DE"), engine.calls.map { it.languageTag })
        assertEquals(CommandLanguages.promptFor("de"), engine.calls.single().precedingText)
    }

    @Test
    fun `detection gets one pass without a prompt`() = runTest {
        val engine = FakeEngine(mapOf(null to Result.success("Zurück.")), whisperLike)
        recognize(engine, tag = null)
        assertNull(engine.calls.single().precedingText)
    }

    @Test
    fun `an engine that takes no language per request gets the plain pass it always had`() = runTest {
        val engine = FakeEngine(mapOf("de,en" to Result.success("Öffne WhatsApp.")), EngineCapabilities(TrustLevel.ON_DEVICE))
        val lines = mutableListOf<String>()
        val recognition = recognize(engine, lines = lines)
        assertEquals("Öffne WhatsApp.", recognition.result.getOrThrow().text)
        assertEquals(listOf(TranscribeOptions(languageTag = "de,en")), engine.calls)
        assertTrue(lines.isEmpty(), "$lines")
    }

    @Test
    fun `a server gets one retry at most and no prompt`() = runTest {
        val server = EngineCapabilities(TrustLevel.CLOUD, commandLanguageAttempts = 2)
        val engine = FakeEngine(mapOf("fr" to Result.success("Bonjour."), "de" to Result.success("Hallo."), "en" to Result.success("Open WhatsApp.")), server)
        val recognition = recognize(engine, tag = "de,en,fr", phone = "fr")
        assertEquals("Bonjour.", recognition.result.getOrThrow().text)
        assertEquals(listOf<String?>("fr", "de"), engine.calls.map { it.languageTag })
        assertTrue(engine.calls.all { it.precedingText == null })
    }

    @Test
    fun `Diagnostics get every attempt and which one was taken`() = runTest {
        val engine = FakeEngine(mapOf("en" to Result.success("If now what's up?"), "de" to Result.success("Öffne WhatsApp.")), whisperLike)
        val lines = mutableListOf<String>()
        recognize(engine, phone = "en", lines = lines)
        assertEquals(
            listOf(
                "heard as en: \"If now what's up?\" - not understood",
                "heard as de: \"Öffne WhatsApp.\" - understood",
                "taken: de, the first the rules understand",
            ),
            lines,
        )

        val none = mutableListOf<String>()
        recognize(FakeEngine(mapOf("de" to Result.success("Hallo du."), "en" to nothing()), whisperLike), lines = none)
        assertEquals(
            listOf(
                "heard as de: \"Hallo du.\" - not understood",
                "heard as en: nothing (whisper returned no speech: \"\")",
                "taken: de - none understood, so the first words heard stand",
            ),
            none,
        )

        val echo = mutableListOf<String>()
        val prompt = CommandLanguages.promptFor("de")!!
        recognize(FakeEngine(mapOf("de" to Result.success(prompt)), whisperLike), tag = "de", lines = echo)
        assertEquals(listOf("heard as de: \"$prompt\" - the prompt read back, taken as nothing heard"), echo)
    }

    // --- which engines ---------------------------------------------------------------------

    @Test
    fun `local Whisper tries every language with a prompt, a server one retry without`() {
        val whisper = WhisperCppEngine("local-whisper", "Whisper", "/m.bin", NoRuntime, threads = 4)
        assertEquals(Int.MAX_VALUE, whisper.capabilities.commandLanguageAttempts)
        assertTrue(whisper.capabilities.commandPrompt)

        val server = OpenAiCompatibleEngine("groq", "Groq", "https://api.groq.com/openai", "whisper-large-v3", FakeHttpTransport.json("{\"text\": \"\"}"))
        assertEquals(2, server.capabilities.commandLanguageAttempts)
        assertFalse(server.capabilities.commandPrompt)
    }

    private object NoRuntime : WhisperRuntime {
        override fun ensureLoaded(modelPath: String) = Unit
        override fun transcribe(samples: FloatArray, language: String, audioCtx: Int, threads: Int, prompt: String) = ""
        override fun abort() = Unit
    }
}
