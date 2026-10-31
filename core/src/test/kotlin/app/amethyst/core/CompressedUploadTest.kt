package app.amethyst.core

import app.amethyst.core.audio.AudioEncoder
import app.amethyst.core.audio.EncodedAudio
import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.engine.AmberCatalog
import app.amethyst.core.engine.AmberCatalogs
import app.amethyst.core.engine.CatalogFetch
import app.amethyst.core.engine.CompressedUpload
import app.amethyst.core.engine.EngineCapabilities
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.OpenAiCompatibleEngine
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.TranscribeOptions
import app.amethyst.core.net.HttpBody
import app.amethyst.core.net.HttpRequest
import app.amethyst.core.net.HttpResponse
import app.amethyst.core.net.MultipartPart
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.StoredCatalog
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Compressed uploads: an Amber server that lists `opus` in its catalog gets the recording as
 * Ogg Opus; everyone else gets the WAV they always got.
 */
class CompressedUploadTest {

    private val own = EngineConfig.fromPreset(EnginePresets.SELF_HOSTED)
        .copy(baseUrl = "http://192.168.1.20:8765/k3y/v1", modelId = "acft-large-v3-turbo-q8_0")

    private val pc = assertNotNull(AmberCatalog.parse(AMBER_PC_MODELS))

    /** The same PC once it decodes Opus. */
    private val opusPc = assertNotNull(
        AmberCatalog.parse(AMBER_PC_MODELS.replace(""""version": "0.4.2" }""", """"version": "0.5.0", "audio": ["wav", "opus"] }""")),
    )

    private val audio = PcmAudio(ShortArray(16_000) { (it % 500).toShort() })

    /** Stands in for Android's Opus encoder: a few bytes that start like an Ogg file. */
    private val ogg = "OggS-not-really-opus".encodeToByteArray()

    private inner class FakeEncoder : AudioEncoder {
        var calls = 0

        @Volatile
        var thread: String? = null

        override fun encode(audio: PcmAudio): EncodedAudio {
            calls++
            thread = Thread.currentThread().name
            return EncodedAudio.oggOpus(ogg)
        }
    }

    private fun settingsWith(catalog: AmberCatalog?): AmethystSettings {
        val settings = AmethystSettings.defaults().withEngine(own)
        return if (catalog == null) settings else AmberCatalogs.apply(settings, own.id, own.baseUrl!!, CatalogFetch.Found(catalog))
    }

    private fun engine(transport: FakeHttpTransport, upload: CompressedUpload?) = OpenAiCompatibleEngine(
        id = own.id,
        displayName = own.displayName,
        baseUrl = own.baseUrl!!,
        modelId = own.modelId!!,
        transport = transport,
        apiKeyProvider = { "k3y" },
        capabilities = EngineCapabilities(TrustLevel.CLOUD, needsApiKey = false),
        compressedUpload = upload,
    )

    private fun HttpRequest.parts() = assertIs<HttpBody.Multipart>(body).parts

    private fun HttpRequest.file() = assertIs<MultipartPart.FilePart>(parts().single { it.name == "file" })

    private fun HttpRequest.fields() = parts().filterIsInstance<MultipartPart.Field>().associate { it.name to it.value }

    private val ok = """{"text":"Hallo"}"""

    // --- which format goes up ---

    @Test
    fun `a server whose list takes opus gets the recording as dictation dot ogg`() = runTest {
        val encoder = FakeEncoder()
        val upload = assertNotNull(AmberCatalogs.compressedUpload(settingsWith(opusPc), own, encoder) {})
        val transport = FakeHttpTransport.json(ok)

        assertEquals("Hallo", engine(transport, upload).transcribe(audio, TranscribeOptions(languageTag = "de-DE")).getOrThrow().text)

        val request = transport.requests.single()
        val file = request.file()
        assertEquals("dictation.ogg", file.fileName)
        assertEquals("audio/ogg", file.contentType)
        assertContentEquals(ogg, file.bytes)
        assertEquals(1, encoder.calls)
        // Only the file changes: the rest of the form is what a WAV upload carries.
        assertEquals(mapOf("model" to own.modelId, "response_format" to "json", "language" to "de"), request.fields())
        assertEquals("http://192.168.1.20:8765/k3y/v1/audio/transcriptions", request.url)
    }

    @Test
    fun `without opus in the list, without a list, or for any other server, there is nothing to compress`() {
        val encoder = FakeEncoder()
        assertNull(AmberCatalogs.compressedUpload(settingsWith(pc), own, encoder) {}, "an Amber that takes only WAV")
        assertNull(AmberCatalogs.compressedUpload(settingsWith(null), own, encoder) {}, "an own server without a list")
        val moved = own.copy(baseUrl = "http://192.168.1.30:9000/v1")
        assertNull(AmberCatalogs.compressedUpload(settingsWith(opusPc), moved, encoder) {}, "the list belongs to the old address")
        assertNull(AmberCatalogs.compressedUpload(settingsWith(opusPc), own.copy(requireEnclave = true), encoder) {}, "an enclave engine")
        assertNull(AmberCatalogs.compressedUpload(settingsWith(opusPc), own, null) {}, "no encoder on this phone")
        // Groq, OpenAI and Tinfoil are untouched, whatever is stored under their id.
        for (preset in listOf(EnginePresets.GROQ, EnginePresets.OPENAI, EnginePresets.TINFOIL)) {
            val engine = EngineConfig.fromPreset(preset)
            val settings = AmethystSettings.defaults().withEngine(engine)
                .copy(serverCatalogs = mapOf(engine.id to StoredCatalog.of(engine.baseUrl!!, opusPc)))
            assertNull(AmberCatalogs.compressedUpload(settings, engine, encoder) {}, preset.id)
        }
        assertEquals(0, encoder.calls)
    }

    @Test
    fun `an encoder that cannot, or fails, leaves the recording as wav`() = runTest {
        val encoders = listOf(
            AudioEncoder { null },
            AudioEncoder { error("codec gone") },
            AudioEncoder { EncodedAudio.oggOpus(ByteArray(0)) },
        )
        for (encoder in encoders) {
            val upload = assertNotNull(AmberCatalogs.compressedUpload(settingsWith(opusPc), own, encoder) {})
            val transport = FakeHttpTransport.json(ok)
            engine(transport, upload).transcribe(audio, TranscribeOptions()).getOrThrow()
            val file = transport.requests.single().file()
            assertEquals("dictation.wav", file.fileName)
            assertEquals("audio/wav", file.contentType)
            assertContentEquals(audio.toWav(), file.bytes)
        }
    }

    @Test
    fun `wav goes up exactly as it does without a compressed upload`() = runTest {
        val options = TranscribeOptions(languageTag = "de-DE", vocabularyHint = "Amber", precedingText = "Das ist")
        val plain = FakeHttpTransport.json(ok)
        engine(plain, upload = null).transcribe(audio, options).getOrThrow()
        val declined = FakeHttpTransport.json(ok)
        engine(declined, CompressedUpload(AudioEncoder { null })).transcribe(audio, options).getOrThrow()

        val expected = plain.requests.single()
        val actual = declined.requests.single()
        assertEquals(expected.copy(body = null), actual.copy(body = null))
        assertEquals(expected.parts().size, actual.parts().size)
        expected.parts().zip(actual.parts()).forEach { (a, b) ->
            assertEquals(a.name, b.name)
            when (a) {
                is MultipartPart.Field -> assertEquals(a, b)
                is MultipartPart.FilePart -> {
                    val file = assertIs<MultipartPart.FilePart>(b)
                    assertEquals(a.fileName, file.fileName)
                    assertEquals(a.contentType, file.contentType)
                    assertContentEquals(a.bytes, file.bytes)
                }
            }
        }
    }

    @Test
    fun `the encoder runs off the caller's thread`() = runTest {
        val encoder = FakeEncoder()
        engine(FakeHttpTransport.json(ok), CompressedUpload(encoder)).transcribe(audio, TranscribeOptions()).getOrThrow()
        assertNotEquals(Thread.currentThread().name, assertNotNull(encoder.thread))
    }

    // --- a server that announced more than it takes ---

    @Test
    fun `a 400 on the compressed upload goes once more as wav, and wav from then on`() = runTest {
        var settings = settingsWith(opusPc)
        val encoder = FakeEncoder()
        val transport = FakeHttpTransport { request ->
            if (request.file().contentType == "audio/ogg") {
                HttpResponse(400, """{"error":{"message":"unsupported audio","type":"invalid_request_error"}}""".encodeToByteArray())
            } else {
                HttpResponse(200, ok.encodeToByteArray())
            }
        }
        var refusals = 0
        val upload = assertNotNull(
            AmberCatalogs.compressedUpload(settings, own, encoder) {
                refusals++
                settings = AmberCatalogs.compressionRefused(settings, own.id, own.baseUrl!!)
            },
        )
        val engine = engine(transport, upload)

        assertEquals("Hallo", engine.transcribe(audio, TranscribeOptions(languageTag = "de-DE")).getOrThrow().text)
        assertEquals(listOf("audio/ogg", "audio/wav"), transport.requests.map { it.file().contentType })
        assertEquals(transport.requests[0].fields(), transport.requests[1].fields(), "the same request, only as WAV")
        assertContentEquals(audio.toWav(), transport.requests[1].file().bytes)
        assertEquals(1, refusals)

        // The same engine asked again (a voice command's second language): WAV straight away.
        engine.transcribe(audio, TranscribeOptions(languageTag = "en-US")).getOrThrow()
        assertEquals(listOf("audio/ogg", "audio/wav", "audio/wav"), transport.requests.map { it.file().contentType })
        assertEquals(1, encoder.calls)
        assertEquals(1, refusals)

        // The next dictation's engine too, and the rest of the list stays as it was.
        assertNull(AmberCatalogs.compressedUpload(settings, own, encoder) {})
        val kept = assertNotNull(settings.catalogFor(own))
        assertFalse(kept.takesOpus)
        assertEquals(opusPc.speech, kept.speech)
        assertEquals(opusPc.cleanup, kept.cleanup)

        // Until the list is fetched again.
        settings = AmberCatalogs.apply(settings, own.id, own.baseUrl!!, CatalogFetch.Found(opusPc))
        assertNotNull(AmberCatalogs.compressedUpload(settings, own, encoder) {})
    }

    @Test
    fun `after the switch to wav a refused prompt is still left out once`() = runTest {
        val transport = FakeHttpTransport { request ->
            when {
                request.file().contentType == "audio/ogg" -> HttpResponse(400, "unsupported audio".encodeToByteArray())
                request.fields().containsKey("prompt") -> HttpResponse(400, "prompt not supported".encodeToByteArray())
                else -> HttpResponse(200, ok.encodeToByteArray())
            }
        }
        engine(transport, CompressedUpload(FakeEncoder())).transcribe(audio, TranscribeOptions(precedingText = "Das ist")).getOrThrow()
        assertEquals(
            listOf("audio/ogg" to true, "audio/wav" to true, "audio/wav" to false),
            transport.requests.map { it.file().contentType to it.fields().containsKey("prompt") },
        )
    }

    @Test
    fun `a 400 on wav is the server's answer, not a format question`() = runTest {
        var refusals = 0
        val transport = FakeHttpTransport.json("""{"error":{"message":"bad language"}}""", code = 400)
        val result = engine(transport, CompressedUpload(AudioEncoder { null }) { refusals++ }).transcribe(audio, TranscribeOptions())
        assertEquals(400, assertIs<SttFailure>(result.exceptionOrNull()).statusCode)
        assertEquals(1, transport.requests.size)
        assertEquals(0, refusals)
    }

    @Test
    fun `a refusal is remembered for its engine and address only`() {
        val other = own.copy(id = "other")
        val settings = AmberCatalogs.apply(settingsWith(opusPc).withEngine(other), other.id, other.baseUrl!!, CatalogFetch.Found(opusPc))

        val refused = AmberCatalogs.compressionRefused(settings, own.id, own.baseUrl!!)
        assertFalse(assertNotNull(refused.catalogFor(own)).takesOpus)
        assertTrue(assertNotNull(refused.catalogFor(other)).takesOpus)
        // An answer about an address the engine has left since changes nothing.
        assertSame(settings, AmberCatalogs.compressionRefused(settings, own.id, "http://192.168.1.30:9000/v1"))
        // Nothing left to remember: the settings stay as they are, so nothing is written.
        assertSame(refused, AmberCatalogs.compressionRefused(refused, own.id, own.baseUrl!!))
        val plain = settingsWith(null)
        assertSame(plain, AmberCatalogs.compressionRefused(plain, own.id, own.baseUrl!!))
    }
}
