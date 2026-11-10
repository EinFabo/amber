package app.amethyst.core

import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.OpenAiCompatibleEngine
import app.amethyst.core.engine.Servers
import app.amethyst.core.engine.whisper.WhisperInput
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.LanguageList
import app.amethyst.core.settings.LanguageUse
import app.amethyst.core.settings.SettingsCodec
import app.amethyst.core.text.TranscriptNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageListTest {

    private val whisper = EngineConfig.fromPreset(EnginePresets.LOCAL_WHISPER)
    private val android = EngineConfig.fromPreset(EnginePresets.ANDROID_ON_DEVICE)
    private val parakeet = EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET)
    private val server = Servers.newServer("server-1", null)

    // --- how each kind takes languages ---------------------------------------------------

    @Test
    fun `whisper and servers take several, android the first, parakeet detects itself`() {
        assertEquals(LanguageUse.SEVERAL, LanguageList.use(EngineKind.WHISPER_CPP))
        assertEquals(LanguageUse.SEVERAL, LanguageList.use(EngineKind.OPENAI_COMPATIBLE))
        assertEquals(LanguageUse.FIRST, LanguageList.use(EngineKind.ANDROID_ON_DEVICE))
        assertEquals(LanguageUse.DETECTS_ITSELF, LanguageList.use(EngineKind.LOCAL_SHERPA))
    }

    // --- reading what is stored: nothing lost on upgrade ----------------------------------

    @Test
    fun `what older versions stored reads as the same languages`() {
        assertEquals(listOf("de", "en"), LanguageList.of(whisper.copy(languageTag = "de,en")))
        assertEquals(listOf("de", "en"), LanguageList.of(server.copy(languageTag = "de-DE, en")))
        assertEquals(listOf("en"), LanguageList.of(server.copy(languageTag = "en-US")))
        assertEquals(listOf("de-DE"), LanguageList.of(android.copy(languageTag = "de-DE")))
        assertEquals(emptyList(), LanguageList.of(whisper.copy(languageTag = null)))
        assertEquals(emptyList(), LanguageList.of(whisper.copy(languageTag = "")))
    }

    @Test
    fun `each engine is handed what it was handed before`() {
        assertEquals("de,en", LanguageList.forDictation(whisper.copy(languageTag = "de,en")))
        assertEquals("de-DE", LanguageList.forDictation(android.copy(languageTag = "de-DE")))
        assertNull(LanguageList.forDictation(whisper.copy(languageTag = null)))
        // A server's field is the same either way: bare codes.
        for (stored in listOf("en-US", "de-DE, en", "de")) {
            val config = server.copy(languageTag = stored)
            for (amber in listOf(true, false)) {
                assertEquals(
                    OpenAiCompatibleEngine.languageField(stored, amber),
                    OpenAiCompatibleEngine.languageField(LanguageList.forDictation(config), amber),
                )
            }
        }
    }

    @Test
    fun `android gets the first of a list, never the list`() {
        // The old free field let anyone type a list, which Android's recognizer cannot take.
        assertEquals("de-DE", LanguageList.forDictation(android.copy(languageTag = "de-DE,en-US")))
    }

    @Test
    fun `parakeet gets no language, whatever is stored`() {
        assertNull(LanguageList.forDictation(parakeet.copy(languageTag = "de-DE")))
        assertEquals(emptyList(), LanguageList.of(parakeet.copy(languageTag = "de-DE")))
    }

    @Test
    fun `parakeet's text is still read as the language stored for it, else the phone's`() {
        assertEquals("de-DE", LanguageList.spokenHint(parakeet.copy(languageTag = "de-DE"), listOf("de-DE")))
        assertEquals("de-DE", LanguageList.spokenHint(parakeet.copy(languageTag = null), listOf("de-DE")))
        // It may hear any of the phone's languages: all of them, so the words decide.
        assertEquals("en-US,de-DE", LanguageList.spokenHint(parakeet.copy(languageTag = "en-US"), listOf("en-US", "de-DE")))
        assertEquals("de-DE,en-US", LanguageList.spokenHint(parakeet.copy(languageTag = "de-DE"), listOf("en-US")))
        // Android with none picked hears the phone's language; the others are as handed.
        assertEquals("de-DE", LanguageList.spokenHint(android.copy(languageTag = null), TEST_PHONE))
        assertEquals("de,en", LanguageList.spokenHint(whisper.copy(languageTag = "de,en"), TEST_PHONE))
        assertNull(LanguageList.spokenHint(whisper.copy(languageTag = null), TEST_PHONE))
    }

    @Test
    fun `a german parakeet dictation keeps its um - it is no english filler there`() {
        // Parakeet names no language; before LanguageList its stored "de-DE" decided this.
        val hint = LanguageList.spokenHint(parakeet.copy(languageTag = "de-DE"), TEST_PHONE)
        assertEquals("Treffen um 10 Uhr", TranscriptNormalizer.normalize("Treffen um 10 Uhr", language = hint))
    }

    @Test
    fun `doubles, regional extensions and nonsense are cleaned up when read`() {
        assertEquals(listOf("de", "en"), LanguageList.of(whisper.copy(languageTag = "de-DE,de,en_US,!!")))
        assertEquals(listOf("en-US"), LanguageList.of(android.copy(languageTag = "en-US-u-mu-celsius")))
        // Two variants of one language are one choice for Android: the first stays.
        assertEquals(listOf("en-GB"), LanguageList.of(android.copy(languageTag = "en-GB,en-US")))
    }

    // --- changing them -------------------------------------------------------------------

    @Test
    fun `a tap adds at the end and takes out what is picked, so order is preference`() {
        val use = LanguageUse.SEVERAL
        assertEquals(listOf("de", "en"), LanguageList.toggled(listOf("de"), "en", use))
        assertEquals(listOf("en"), LanguageList.toggled(listOf("de", "en"), "de", use))
        assertEquals(listOf("en", "de"), LanguageList.toggled(listOf("en"), "de", use))
        // The same language under another spelling is the one picked.
        assertEquals(listOf("en"), LanguageList.toggled(listOf("de-DE", "en"), "de", use))
    }

    @Test
    fun `for android a tap picks one language alone, and a second tap leaves the phone's`() {
        val use = LanguageUse.FIRST
        assertEquals(listOf("fr"), LanguageList.toggled(listOf("de-DE"), "fr", use))
        assertEquals(emptyList(), LanguageList.toggled(listOf("de-DE"), "de-DE", use))
    }

    @Test
    fun `stored as the engines read it`() {
        assertEquals("de,en", LanguageList.with(whisper, listOf("de-DE", "en")).languageTag)
        assertEquals("en-GB", LanguageList.with(android, listOf("en-GB", "de-DE")).languageTag)
        assertNull(LanguageList.with(whisper, emptyList()).languageTag)
        // Parakeet keeps whatever it had: nothing of it is used.
        assertEquals(parakeet, LanguageList.with(parakeet, listOf("de")))
    }

    @Test
    fun `a list written survives the settings round trip`() {
        val config = LanguageList.with(whisper, listOf("en", "de", "fr"))
        val settings = SettingsCodec.decode(SettingsCodec.encode(defaultsWith(config)), TEST_PHONE)
        assertEquals(listOf("en", "de", "fr"), LanguageList.of(settings.engines.first { it.id == whisper.id }))
    }

    private fun defaultsWith(config: EngineConfig) =
        app.amethyst.core.settings.AmethystSettings.defaults(TEST_PHONE).withEngine(config)

    // --- defaults and what is offered ----------------------------------------------------

    @Test
    fun `defaults are the phone's languages, as each kind takes them`() {
        assertEquals(listOf("de", "en"), LanguageList.defaults(EngineKind.WHISPER_CPP, TEST_PHONE))
        assertEquals(listOf("de-DE"), LanguageList.defaults(EngineKind.ANDROID_ON_DEVICE, TEST_PHONE))
        assertEquals(emptyList(), LanguageList.defaults(EngineKind.LOCAL_SHERPA, TEST_PHONE))
        assertTrue(LanguageList.isDefault(whisper.copy(languageTag = "de,en"), TEST_PHONE))
        assertFalse(LanguageList.isDefault(whisper.copy(languageTag = "en"), TEST_PHONE))
    }

    @Test
    fun `offered are the picked first, then the phone's, then the common ones, each once`() {
        val offered = LanguageList.offered(listOf("fr"), LanguageUse.SEVERAL, listOf("ja-JP", "de-DE"))
        assertEquals(listOf("fr", "ja", "de"), offered.take(3))
        assertEquals(offered.distinct(), offered)
        assertTrue(LanguageList.COMMON.all { it in offered })
    }

    @Test
    fun `android is offered the phone's own variant`() {
        val offered = LanguageList.offered(emptyList(), LanguageUse.FIRST, listOf("en-GB", "de-AT"))
        assertEquals(listOf("en-GB", "de-AT"), offered.take(2))
        assertFalse("en" in offered)
        assertFalse("de" in offered)
    }

    @Test
    fun `parakeet is offered nothing`() {
        assertEquals(emptyList(), LanguageList.offered(emptyList(), LanguageUse.DETECTS_ITSELF, TEST_PHONE))
    }

    @Test
    fun `more languages are the rest of whisper's`() {
        val shown = LanguageList.offered(emptyList(), LanguageUse.SEVERAL, TEST_PHONE)
        val more = LanguageList.more(shown)
        assertTrue("ja" in more)
        assertFalse(more.any { it in shown })
        assertEquals(WhisperInput.KNOWN_LANGUAGES.size, shown.size + more.size)
    }
}
