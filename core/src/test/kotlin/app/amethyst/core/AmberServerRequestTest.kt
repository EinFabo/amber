package app.amethyst.core

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.engine.AmberCatalog
import app.amethyst.core.engine.AmberCatalogs
import app.amethyst.core.engine.CatalogFetch
import app.amethyst.core.engine.EngineCapabilities
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.OpenAiCompatibleEngine
import app.amethyst.core.engine.TranscribeOptions
import app.amethyst.core.engine.whisper.WhisperInput
import app.amethyst.core.net.HttpBody
import app.amethyst.core.net.MultipartPart
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.EngineConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a transcription request tells an Amber server that other servers are never told: every
 * language picked, to detect among.
 */
class AmberServerRequestTest {

    private val own = EngineConfig.fromPreset(EnginePresets.SELF_HOSTED)
        .copy(baseUrl = "http://192.168.1.20:8765/k3y/v1", modelId = "acft-large-v3-turbo-q8_0")

    private val pc = assertNotNull(AmberCatalog.parse(AMBER_PC_MODELS))

    private val audio = PcmAudio(ShortArray(16_000) { (it % 500).toShort() })

    private fun settingsWith(catalog: AmberCatalog?): AmethystSettings {
        val settings = AmethystSettings.defaults().withEngine(own)
        return if (catalog == null) settings else AmberCatalogs.apply(settings, own.id, own.baseUrl!!, CatalogFetch.Found(catalog))
    }

    /** The form fields one transcription sent, by name. */
    private suspend fun fieldsSent(amberServer: Boolean, languageTag: String?): Map<String, String> {
        val transport = FakeHttpTransport.json("""{"text":"Okay, danke."}""")
        OpenAiCompatibleEngine(
            id = own.id,
            displayName = own.displayName,
            baseUrl = own.baseUrl!!,
            modelId = own.modelId!!,
            transport = transport,
            capabilities = EngineCapabilities(TrustLevel.CLOUD, needsApiKey = false),
            amberServer = amberServer,
        ).transcribe(audio, TranscribeOptions(languageTag = languageTag)).getOrThrow()
        return assertIs<HttpBody.Multipart>(transport.requests.single().body).parts
            .filterIsInstance<MultipartPart.Field>().associate { it.name to it.value }
    }

    @Test
    fun `an engine is an amber server only with a list from its own address`() {
        assertTrue(AmberCatalogs.isAmber(settingsWith(pc), own))
        assertFalse(AmberCatalogs.isAmber(settingsWith(null), own))
        val moved = own.copy(baseUrl = "http://192.168.1.30:8765/k3y/v1")
        assertFalse(AmberCatalogs.isAmber(settingsWith(pc), moved), "a list from the old address says nothing")
        assertFalse(AmberCatalogs.isAmber(settingsWith(pc), own.copy(requireEnclave = true)))
    }

    @Test
    fun `an amber server gets every language picked`() = runTest {
        assertEquals("de,en", fieldsSent(amberServer = true, languageTag = "de,en")["language"])
        assertEquals("de,en", fieldsSent(amberServer = true, languageTag = "de-DE, en")["language"])
        assertEquals("en,de", fieldsSent(amberServer = true, languageTag = "en,de")["language"], "the first wins a tie")
    }

    @Test
    fun `one language is one code, none is no field`() = runTest {
        assertEquals("de", fieldsSent(amberServer = true, languageTag = "de-DE")["language"])
        assertEquals("de", fieldsSent(amberServer = false, languageTag = "de-DE")["language"])
        assertNull(fieldsSent(amberServer = true, languageTag = null)["language"])
        assertNull(fieldsSent(amberServer = true, languageTag = " ")["language"])
        assertNull(fieldsSent(amberServer = false, languageTag = null)["language"])
    }

    @Test
    fun `any other server gets no language for a list, so it detects instead of translating`() = runTest {
        assertNull(fieldsSent(amberServer = false, languageTag = "de,en")["language"])
        assertNull(fieldsSent(amberServer = false, languageTag = "de-DE, en_US")["language"])
        assertEquals("de", fieldsSent(amberServer = false, languageTag = "de, de-AT")["language"], "the same language twice is one")
    }

    @Test
    fun `the language picker keeps the order of picking`() {
        assertEquals("en", WhisperInput.toggledLanguage("", "en"))
        assertEquals("en,de", WhisperInput.toggledLanguage("en", "de"))
        assertEquals("en,de,fr", WhisperInput.toggledLanguage("en,de", "fr"))
        assertEquals("en,fr", WhisperInput.toggledLanguage("en,de,fr", "de"))
        assertEquals("de,fr,en", WhisperInput.toggledLanguage("de,fr", "en"))
        assertEquals("", WhisperInput.toggledLanguage("de", "de"))
    }
}
