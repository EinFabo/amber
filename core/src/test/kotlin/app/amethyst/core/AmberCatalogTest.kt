package app.amethyst.core

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.engine.AmberCatalog
import app.amethyst.core.engine.AmberCatalogs
import app.amethyst.core.engine.CatalogFetch
import app.amethyst.core.engine.CatalogThrottle
import app.amethyst.core.engine.EngineCapabilities
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.EnginePresets.ModelChoice
import app.amethyst.core.engine.OpenAiCompatibleEngine
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.engine.TranscribeOptions
import app.amethyst.core.net.HttpBody
import app.amethyst.core.net.HttpResponse
import app.amethyst.core.net.MultipartPart
import app.amethyst.core.net.SecureChannel
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.SettingsCodec
import app.amethyst.core.settings.SettingsSummaries
import app.amethyst.core.settings.StoredCatalog
import app.amethyst.core.text.ChatCleanup
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The example from the phone API contract, as Amber PC serves it. */
internal const val AMBER_PC_MODELS = """
{
  "object": "list",
  "data": [
    {
      "id": "acft-large-v3-turbo-q8_0", "object": "model", "owned_by": "amber",
      "amber": { "kind": "speech", "label": "Large v3 Turbo", "note": "on this PC · NVIDIA GPU (CUDA)", "default": true }
    },
    {
      "id": "qwen3-4b-instruct-2507-q4km", "object": "model", "owned_by": "amber",
      "amber": { "kind": "cleanup", "label": "Qwen3 4B Instruct", "note": "on this PC · NVIDIA GPU (CUDA)", "default": true }
    }
  ],
  "amber": { "service": "amber-pc", "version": "0.4.2" }
}
"""

class AmberCatalogTest {

    private val own = EngineConfig.fromPreset(EnginePresets.SELF_HOSTED)
        .copy(baseUrl = "http://192.168.1.20:8765/k3y/v1", modelId = "acft-large-v3-turbo-q8_0")

    private val pc = assertNotNull(AmberCatalog.parse(AMBER_PC_MODELS))

    // --- parsing ---

    @Test
    fun `an amber list gives both kinds, the defaults recommended`() {
        assertEquals("amber-pc", pc.service)
        assertEquals("0.4.2", pc.version)
        assertEquals("Amber PC", pc.name)
        assertEquals(
            listOf(ModelChoice("acft-large-v3-turbo-q8_0", "Large v3 Turbo", "on this PC · NVIDIA GPU (CUDA)", recommended = true)),
            pc.speech,
        )
        assertEquals(
            listOf(ModelChoice("qwen3-4b-instruct-2507-q4km", "Qwen3 4B Instruct", "on this PC · NVIDIA GPU (CUDA)", recommended = true)),
            pc.cleanup,
        )
    }

    @Test
    fun `an amber server lists every model, unknown kinds and extra fields are skipped`() {
        val body = """
            {"object":"list","extra":{"x":1},"data":[
              {"id":"large-v3","object":"model","created":0,"amber":{"kind":"speech","label":"Large v3","note":"GPU · 3 GB","default":false,"size":3}},
              {"id":"turbo","object":"model","amber":{"kind":"speech","label":"Turbo","note":"GPU","default":true}},
              {"id":"embed","object":"model","amber":{"kind":"embedding","label":"Embed","default":true}},
              {"id":"plain","object":"model"},
              "not an object",
              {"object":"model","amber":{"kind":"speech","label":"No id"}},
              {"id":"qwen","amber":{"kind":"cleanup","label":"Qwen","note":"CPU"}}
            ],"amber":{"service":"amber-server","version":"1.0.0","audio":["wav","opus"]}}
        """
        val catalog = assertNotNull(AmberCatalog.parse(body))
        assertEquals("Amber Server", catalog.name)
        assertEquals(listOf("large-v3", "turbo"), catalog.speech.map { it.id })
        assertEquals(listOf(false, true), catalog.speech.map { it.recommended })
        assertEquals(listOf("qwen"), catalog.cleanup.map { it.id })
        assertFalse(catalog.cleanup.single().recommended)
    }

    @Test
    fun `a missing kind is an empty list, a missing label the id`() {
        val body = """{"data":[{"id":"turbo","amber":{"kind":"speech"}}],"amber":{"service":"amber-pc","version":"0.4.3"}}"""
        val catalog = assertNotNull(AmberCatalog.parse(body))
        assertEquals(listOf(ModelChoice("turbo", "turbo", "")), catalog.speech)
        assertTrue(catalog.cleanup.isEmpty())
    }

    @Test
    fun `a plain openai list is not amber`() {
        val body = """{"object":"list","data":[{"id":"whisper-1","object":"model","owned_by":"openai"}]}"""
        assertNull(AmberCatalog.parse(body))
    }

    @Test
    fun `garbage is not amber`() {
        for (body in listOf("", "}{", "[]", "null", "42", "<html>Sign in to this Wi-Fi</html>", """{"amber":"amber-pc"}""")) {
            assertNull(AmberCatalog.parse(body), body)
        }
    }

    private fun withAudio(audio: String) = """{"data":[],"amber":{"service":"amber-pc","version":"0.5.0","audio":$audio}}"""

    @Test
    fun `the upload formats an amber takes come with its list`() {
        val catalog = assertNotNull(AmberCatalog.parse(withAudio("""["wav","opus"]""")))
        assertEquals(listOf("wav", "opus"), catalog.audio)
        assertTrue(catalog.takesOpus)
    }

    @Test
    fun `an amber that names no upload formats takes wav`() {
        assertEquals(listOf("wav"), pc.audio)
        assertFalse(pc.takesOpus)
        for (audio in listOf("[]", "null", "\"opus\"", """{"opus":true}""")) {
            assertEquals(listOf("wav"), assertNotNull(AmberCatalog.parse(withAudio(audio)), audio).audio, audio)
        }
    }

    @Test
    fun `upload formats this app does not know are ignored`() {
        assertEquals(listOf("opus", "wav"), assertNotNull(AmberCatalog.parse(withAudio("""["flac","opus",3,null,"wav","opus"]"""))).audio)
        assertEquals(listOf("wav"), assertNotNull(AmberCatalog.parse(withAudio("""["flac","mp3"]"""))).audio)
    }

    @Test
    fun `the list is asked for next to the transcription endpoint`() {
        val expected = "http://192.168.1.20:8765/k3y/v1/models"
        for (typed in listOf("http://192.168.1.20:8765/k3y/v1", " http://192.168.1.20:8765/k3y/v1/ ", "http://192.168.1.20:8765/k3y", "http://192.168.1.20:8765/k3y/v1/audio/transcriptions")) {
            assertEquals(expected, AmberCatalog.modelsUrl(typed), typed)
        }
    }

    // --- fetching ---

    @Test
    fun `fetching asks the models endpoint with the engine's key and a short timeout`() = runTest {
        val transport = FakeHttpTransport.json(AMBER_PC_MODELS)
        val outcome = AmberCatalog.fetch(transport, "https://pc.example.org/k3y/v1", apiKey = "secret")

        assertEquals(CatalogFetch.Found(pc), outcome)
        val request = transport.requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://pc.example.org/k3y/v1/models", request.url)
        assertEquals("Bearer secret", request.headers["Authorization"])
        assertEquals(5_000L, request.timeoutMs)
        assertNull(request.body)
    }

    @Test
    fun `without a key no authorization header is sent`() = runTest {
        val transport = FakeHttpTransport.json(AMBER_PC_MODELS)
        AmberCatalog.fetch(transport, own.baseUrl!!, apiKey = null)
        AmberCatalog.fetch(transport, own.baseUrl!!, apiKey = " ")
        assertTrue(transport.requests.none { it.headers.containsKey("Authorization") })
    }

    @Test
    fun `another server's model list says it is not amber`() = runTest {
        val transport = FakeHttpTransport.json("""{"object":"list","data":[{"id":"Systran/faster-whisper-large-v3","object":"model"}]}""")
        assertEquals(CatalogFetch.NotAmber, AmberCatalog.fetch(transport, own.baseUrl!!, null))
    }

    @Test
    fun `no answer, an error or an unreadable answer is a failed fetch`() = runTest {
        assertIs<CatalogFetch.Failed>(AmberCatalog.fetch(FakeHttpTransport.failing(java.io.IOException("unreachable")), own.baseUrl!!, null))
        assertIs<CatalogFetch.Failed>(AmberCatalog.fetch(FakeHttpTransport.json(AMBER_PC_MODELS, code = 401), own.baseUrl!!, null))
        assertIs<CatalogFetch.Failed>(AmberCatalog.fetch(FakeHttpTransport.json("Bad gateway", code = 502), own.baseUrl!!, null))
        assertIs<CatalogFetch.Failed>(AmberCatalog.fetch(FakeHttpTransport.json("<html>portal</html>"), own.baseUrl!!, null))
    }

    // --- the provider ---

    @Test
    fun `an own server with a stored catalog is an amber provider`() {
        val provider = ServerProviders.forUrl(own.baseUrl, pc)
        assertEquals("Amber PC", provider.name)
        assertEquals(pc.speech, provider.speechModels)
        assertEquals(pc.cleanup, provider.cleanupModels)
        assertFalse(provider.fixedAddress)
        assertTrue(provider.hasModelList)
        assertTrue(provider.listsItself)
        // No prices: it runs on the owner's own machine.
        val all = provider.speechModels + provider.cleanupModels
        assertTrue(all.all { it.usdPerMIn == 0.0 && it.usdPerMOut == 0.0 && it.usdPerRequest == 0.0 && it.usdPerMinute == 0.0 })

        val server = ServerProviders.forUrl(own.baseUrl, pc.copy(service = "amber-server"))
        assertEquals("Amber Server", server.name)
    }

    @Test
    fun `without a catalog an own server keeps typing its models`() {
        val provider = ServerProviders.forUrl(own.baseUrl, null)
        assertSame(ServerProviders.CUSTOM, provider)
        assertFalse(provider.hasModelList)
        assertFalse(provider.listsItself)
    }

    @Test
    fun `known services keep their own lists`() {
        for (provider in ServerProviders.known) {
            assertTrue(provider.hasModelList, provider.id)
            assertFalse(provider.listsItself, provider.id)
        }
        assertSame(ServerProviders.GROQ, ServerProviders.forUrl(EnginePresets.GROQ.baseUrl, pc))
    }

    @Test
    fun `an engine's provider comes from the catalog stored for it, at its address`() {
        val settings = AmethystSettings.defaults().withEngine(own)
            .copy(serverCatalogs = mapOf(own.id to StoredCatalog.of(own.baseUrl!!, pc)))
        assertEquals("Amber PC", ServerProviders.forEngine(own, settings).name)
        // The same address typed another way is the same server.
        val retyped = own.copy(baseUrl = "http://192.168.1.20:8765/k3y/")
        assertEquals(pc, settings.withEngine(retyped).catalogFor(retyped))
        // Pointed elsewhere since: that list belongs to the old address.
        val moved = own.copy(baseUrl = "http://192.168.1.30:9000/v1")
        assertSame(ServerProviders.CUSTOM, ServerProviders.forEngine(moved, settings.withEngine(moved)))
        // Another engine's catalog is not this one's.
        val other = own.copy(id = "other")
        assertNull(settings.withEngine(other).catalogFor(other))
    }

    @Test
    fun `a chosen model the server no longer lists is said so`() {
        val provider = ServerProviders.forUrl(own.baseUrl, pc)
        assertNull(provider.notOfferedNote("acft-large-v3-turbo-q8_0", provider.speechModels))
        assertNull(provider.notOfferedNote(null, provider.speechModels))
        assertNull(provider.notOfferedNote(" ", provider.speechModels))
        assertContains(assertNotNull(provider.notOfferedNote("large-v2", provider.speechModels)), "Not offered by the PC any more")
        val server = ServerProviders.forUrl(own.baseUrl, pc.copy(service = "amber-server"))
        assertContains(assertNotNull(server.notOfferedNote("gone", server.cleanupModels)), "Not offered by the server any more")
        // A model typed for Groq is the user's own business.
        assertNull(ServerProviders.GROQ.notOfferedNote("whisper-large-v2", ServerProviders.GROQ.speechModels))
    }

    @Test
    fun `the cleanup summary names the model as the pc lists it`() {
        val settings = AmethystSettings.defaults().withEngine(own).copy(
            serverCatalogs = mapOf(own.id to StoredCatalog.of(own.baseUrl!!, pc)),
            cleanup = CleanupChoice(own.id, "qwen3-4b-instruct-2507-q4km"),
        )
        assertEquals("Qwen3 4B Instruct", SettingsSummaries.cleanup(settings))
        assertEquals("qwen3-4b-instruct-2507-q4km", SettingsSummaries.cleanup(settings.copy(serverCatalogs = emptyMap())))
    }

    // --- keeping it ---

    private val withOwn = AmethystSettings.defaults().withEngine(own)

    @Test
    fun `a fetched catalog is stored for its engine and address`() {
        val updated = AmberCatalogs.apply(withOwn, own.id, own.baseUrl!!, CatalogFetch.Found(pc))
        assertEquals(StoredCatalog.of(own.baseUrl!!, pc), updated.serverCatalogs[own.id])
        assertEquals(pc, updated.catalogFor(own))
    }

    @Test
    fun `a failed fetch keeps the stored catalog`() {
        val stored = AmberCatalogs.apply(withOwn, own.id, own.baseUrl!!, CatalogFetch.Found(pc))
        assertSame(stored, AmberCatalogs.apply(stored, own.id, own.baseUrl!!, CatalogFetch.Failed("HTTP 502")))
    }

    @Test
    fun `a server that is not amber clears it`() {
        val stored = AmberCatalogs.apply(withOwn, own.id, own.baseUrl!!, CatalogFetch.Found(pc))
        val cleared = AmberCatalogs.apply(stored, own.id, own.baseUrl!!, CatalogFetch.NotAmber)
        assertNull(cleared.serverCatalogs[own.id])
        assertSame(ServerProviders.CUSTOM, ServerProviders.forEngine(own, cleared))
    }

    @Test
    fun `an answer for an address the engine has left changes nothing`() {
        val moved = withOwn.withEngine(own.copy(baseUrl = "http://192.168.1.30:9000/v1"))
        assertSame(moved, AmberCatalogs.apply(moved, own.id, own.baseUrl!!, CatalogFetch.Found(pc)))
    }

    @Test
    fun `an unchanged catalog leaves the settings as they are`() {
        val stored = AmberCatalogs.apply(withOwn, own.id, own.baseUrl!!, CatalogFetch.Found(pc))
        assertSame(stored, AmberCatalogs.apply(stored, own.id, own.baseUrl!!, CatalogFetch.Found(pc)))
    }

    @Test
    fun `a removed engine's catalog goes with it`() {
        val stored = AmberCatalogs.apply(withOwn, own.id, own.baseUrl!!, CatalogFetch.Found(pc))
        val removed = stored.copy(engines = stored.engines.filterNot { it.id == own.id })
        assertTrue(AmberCatalogs.apply(removed, own.id, own.baseUrl!!, CatalogFetch.Found(pc)).serverCatalogs.isEmpty())
    }

    @Test
    fun `only own servers with a usable address are asked`() {
        assertTrue(AmberCatalogs.isCandidate(own))
        assertTrue(AmberCatalogs.isCandidate(own.copy(modelId = null)), "the list is how a model gets picked")
        assertTrue(AmberCatalogs.isCandidate(own.copy(baseUrl = "https://pc.example.org/k3y/v1")))
        for (preset in listOf(EnginePresets.TINFOIL, EnginePresets.GROQ, EnginePresets.OPENAI)) {
            assertFalse(AmberCatalogs.isCandidate(EngineConfig.fromPreset(preset)), preset.id)
        }
        assertFalse(AmberCatalogs.isCandidate(own.copy(baseUrl = null)))
        assertFalse(AmberCatalogs.isCandidate(own.copy(baseUrl = "http://example.com/v1")), "no key over plain http to the internet")
        assertFalse(AmberCatalogs.isCandidate(own.copy(requireEnclave = true)))
        assertFalse(AmberCatalogs.isCandidate(EngineConfig.fromPreset(EnginePresets.LOCAL_WHISPER)))
        assertFalse(AmberCatalogs.isCandidate(own.copy(kind = EngineKind.WHISPER_CPP)))
    }

    @Test
    fun `a list is asked for at most once a minute per engine unless forced`() {
        var now = 0L
        val throttle = CatalogThrottle(intervalMs = 60_000) { now }
        // Each fetch answers at once here.
        fun claim(id: String, url: String, force: Boolean = false) = throttle.claim(id, url, force).also { throttle.finished(id, url) }
        assertTrue(claim(own.id, own.baseUrl!!))
        now = 59_999
        assertFalse(claim(own.id, own.baseUrl!!))
        assertTrue(claim("other", own.baseUrl!!), "per engine")
        assertTrue(claim(own.id, "http://192.168.1.30:9000/v1"), "a new address is asked at once")
        assertTrue(claim(own.id, own.baseUrl!!, force = true), "a passed connection test asks anyway")
        now = 119_998
        assertFalse(claim(own.id, own.baseUrl!!))
        now = 119_999
        assertTrue(claim(own.id, own.baseUrl!!))
    }

    @Test
    fun `the stored catalog carries no part of the address or its key`() {
        val keyed = "http://192.168.1.20:8765/s3cr3t-k3y-0451/v1"
        val stored = StoredCatalog.of(keyed, pc)
        val encoded = Json.encodeToString(StoredCatalog.serializer(), stored)
        assertFalse("s3cr3t-k3y-0451" in encoded, encoded)
        assertFalse("192.168.1.20" in encoded, encoded)
        assertEquals(64, stored.address.length, "a SHA-256 in hex")
        assertTrue(stored.isFor(keyed))
    }

    @Test
    fun `the fingerprint matches across spellings of the same address`() {
        val fingerprint = AmberCatalog.fingerprint(own.baseUrl!!)
        for (typed in listOf(
            " http://192.168.1.20:8765/k3y/v1/ ",
            "http://192.168.1.20:8765/k3y",
            "http://192.168.1.20:8765/k3y/v1/audio/transcriptions",
            "Http://192.168.1.20:8765/k3y/v1",
        )) {
            assertEquals(fingerprint, AmberCatalog.fingerprint(typed), typed)
        }
        for (other in listOf("http://192.168.1.20:8765/K3Y/v1", "http://192.168.1.20:8765/other/v1", "http://192.168.1.20:8766/k3y/v1", "https://192.168.1.20:8765/k3y/v1")) {
            assertFalse(fingerprint == AmberCatalog.fingerprint(other), other)
        }
    }

    @Test
    fun `saving an engine at another address drops its catalog`() {
        val stored = AmberCatalogs.apply(withOwn, own.id, own.baseUrl!!, CatalogFetch.Found(pc))
        assertTrue(stored.withEngine(own.copy(baseUrl = "http://192.168.1.30:9000/v1")).serverCatalogs.isEmpty())
        assertTrue(stored.withEngine(own.copy(baseUrl = null)).serverCatalogs.isEmpty())
        // The same address, another model or spelling: the list stays.
        assertEquals(stored.serverCatalogs, stored.withEngine(own.copy(modelId = "other")).serverCatalogs)
        assertEquals(stored.serverCatalogs, stored.withEngine(own.copy(baseUrl = "http://192.168.1.20:8765/k3y/")).serverCatalogs)
        // Other engines' lists are theirs.
        val other = own.copy(id = "other")
        val both = AmberCatalogs.apply(stored.withEngine(other), other.id, other.baseUrl!!, CatalogFetch.Found(pc))
        assertEquals(setOf(other.id), both.withEngine(own.copy(baseUrl = "http://192.168.1.30:9000/v1")).serverCatalogs.keys)
    }

    @Test
    fun `an entry that kept the plain address is stale, not an error`() {
        val raw = """{"activeEngineId":"self-hosted","engines":[{"id":"self-hosted","kind":"OPENAI_COMPATIBLE",
            "displayName":"Own server","baseUrl":"${own.baseUrl}","modelId":"acft-large-v3-turbo-q8_0"}],
            "serverCatalogs":{"self-hosted":{"baseUrl":"${own.baseUrl}","service":"amber-pc","version":"0.4.2",
            "speech":[{"id":"acft-large-v3-turbo-q8_0","label":"Large v3 Turbo"}]}},"schemaVersion":10}"""
        val decoded = SettingsCodec.decode(raw)
        assertEquals(own.id, decoded.engines.single().id, "the settings themselves survive")
        assertNull(decoded.catalogFor(decoded.engines.single()))
        assertSame(ServerProviders.CUSTOM, ServerProviders.forEngine(decoded.engines.single(), decoded))
        assertTrue(decoded.withEngine(decoded.engines.single()).serverCatalogs.isEmpty(), "dropped on the next save")
    }

    @Test
    fun `a forced fetch waits for one already running`() {
        val throttle = CatalogThrottle(intervalMs = 60_000) { 0L }
        assertTrue(throttle.claim(own.id, own.baseUrl!!))
        assertFalse(throttle.claim(own.id, own.baseUrl!!, force = true), "the running fetch answers for it")
        throttle.finished(own.id, " http://192.168.1.20:8765/k3y/ ")
        assertTrue(throttle.claim(own.id, own.baseUrl!!, force = true))
    }

    @Test
    fun `transcription, chat and model list share one root, however the address is typed`() {
        for (typed in listOf("http://192.168.1.20:8765/k3y", "http://192.168.1.20:8765/k3y/v1/", "http://192.168.1.20:8765/k3y/v1/audio/transcriptions")) {
            assertEquals("http://192.168.1.20:8765/k3y/v1/audio/transcriptions", OpenAiCompatibleEngine.endpointUrl(typed), typed)
            assertEquals("http://192.168.1.20:8765/k3y/v1/chat/completions", ChatCleanup.chatUrl(typed), typed)
            assertEquals("http://192.168.1.20:8765/k3y/v1/models", AmberCatalog.modelsUrl(typed), typed)
        }
    }

    // --- the requests ---

    @Test
    fun `the chosen speech model goes unchanged into the transcription form`() = runTest {
        for (model in listOf(pc.speech.single { it.recommended }.id, "no-longer-listed")) {
            val transport = FakeHttpTransport.json("""{"text":"Hallo"}""")
            val engine = OpenAiCompatibleEngine(
                id = own.id, displayName = own.displayName, baseUrl = own.baseUrl!!, modelId = model, transport = transport,
                capabilities = EngineCapabilities(TrustLevel.CLOUD, needsApiKey = false),
            )
            engine.transcribe(PcmAudio(ShortArray(16_000)), TranscribeOptions()).getOrThrow()
            val parts = assertIs<HttpBody.Multipart>(transport.requests.single().body).parts
            assertEquals(model, parts.filterIsInstance<MultipartPart.Field>().single { it.name == "model" }.value)
        }
    }

    @Test
    fun `the chosen cleanup model goes unchanged into the chat request`() = runTest {
        val model = pc.cleanup.single().id
        val transport = FakeHttpTransport {
            HttpResponse(200, """{"choices":[{"message":{"role":"assistant","content":"Hallo, Welt."}}]}""".encodeToByteArray())
        }
        val cleanup = ChatCleanup(
            baseUrl = own.baseUrl!!,
            model = model,
            channel = SecureChannel(own.baseUrl!!, own.displayName, transport, null, TrustLevel.CLOUD),
            apiKeyProvider = { null },
        )
        cleanup.clean("hallo welt").getOrThrow()
        val request = transport.requests.single()
        assertEquals("http://192.168.1.20:8765/k3y/v1/chat/completions", request.url)
        val body = Json.parseToJsonElement(assertIs<HttpBody.Bytes>(request.body).bytes.decodeToString()).jsonObject
        assertEquals(model, body["model"]!!.jsonPrimitive.content)
    }
}
