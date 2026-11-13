package app.amethyst.core

import app.amethyst.core.engine.AmberCatalog
import app.amethyst.core.engine.CleanupRouting
import app.amethyst.core.engine.ModelList
import app.amethyst.core.engine.ServerModels
import app.amethyst.core.engine.ConfigIssue
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.EnginePresets.ModelChoice
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.engine.Servers
import app.amethyst.core.onboarding.CleanupServices
import app.amethyst.core.onboarding.CleanupService
import app.amethyst.core.onboarding.ServiceAnswer
import app.amethyst.core.onboarding.ServiceFields
import app.amethyst.core.onboarding.ServiceKey
import app.amethyst.core.onboarding.ServicePlan
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.EngineConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A cleanup's server from a key or an address: what [CleanupService] makes of them. */
class CleanupServiceTest {

    private val parakeet = EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET)
    private val local = AmethystSettings.defaults(TEST_PHONE).withEngine(parakeet).copy(activeEngineId = parakeet.id)

    @Test
    fun `a key says which service it is, a claude key that it is claude`() {
        assertEquals(ServiceKey.Blank, CleanupService.read("  "))
        assertEquals(ServiceKey.Claude, CleanupService.read(" sk-ant-api03-abc"))
        assertEquals(ServerProviders.GROQ, (CleanupService.read("gsk_abc") as ServiceKey.Known).quick.provider)
        assertEquals(ServerProviders.OPENAI, (CleanupService.read("sk-proj-abc") as ServiceKey.Known).quick.provider)
        assertEquals(ServiceKey.Unknown, CleanupService.read("abcdefghjkmnpqrstuvw"))
    }

    @Test
    fun `a groq key sets up the same server the settings would, with its recommended cleanup model`() {
        val plan = CleanupService.known((CleanupService.read("gsk_abc") as ServiceKey.Known).quick)
        val setup = CleanupService.setup(local, plan, TEST_PHONE, keyGiven = true, random = { 0x123456 })
        val after = CleanupServices.forEveryApp(local, setup)

        val engine = after.engines.single { it.id == "server-123456" }
        assertEquals(Servers.newServer("server-123456", "de-DE").copy(
            displayName = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            modelId = "whisper-large-v3-turbo",
            keyHost = "api.groq.com",
        ), engine)
        assertEquals("key.server-123456", setup.engine.apiKeyAlias)
        assertEquals(CleanupChoice(engine.id, ServerProviders.GROQ.cleanupModels.first { it.recommended }.id), after.cleanup)
        assertEquals(parakeet.id, after.activeEngineId, "the recognition stays as it was")
        assertEquals(after, Servers.normalized(after), "stored as the settings store would")
    }

    @Test
    fun `a claude key sets up claude for the cleanup only, with haiku first`() {
        val plan = CleanupService.claude()
        assertEquals(ServicePlan("https://api.anthropic.com/v1", null, "claude-haiku-5-5"), plan)
        val setup = CleanupService.setup(local, plan, TEST_PHONE, keyGiven = true, random = { 0xc1a0de })
        val after = CleanupServices.forEveryApp(local, setup)

        val engine = after.engines.single { it.id == "server-c1a0de" }
        assertEquals("Claude", engine.displayName)
        assertEquals("api.anthropic.com", engine.keyHost, "the key goes to Anthropic only")
        assertNull(engine.modelId, "no speech model: Claude recognizes none")
        assertEquals(CleanupChoice(engine.id, "claude-haiku-5-5"), after.cleanup)
        assertEquals(parakeet.id, after.activeEngineId, "the recognition stays as it was")
        // Ready for the cleanup, never for recognition.
        assertTrue(CleanupRouting.ready(after, after.cleanup!!) { true })
        assertTrue(Servers.cleanupOnly(after, engine))
        assertFalse(engine in Servers.recognizers(after))
    }

    @Test
    fun `claude is never a recognition, whatever points at it`() {
        val claude = Servers.newServer("server-c", "de-DE").copy(baseUrl = "https://api.anthropic.com/v1", keyHost = "api.anthropic.com")
        val added = local.withEngine(claude)
        // Not even while nothing cleans up with it, nor with a model typed into it.
        assertTrue(Servers.cleanupOnly(added, claude))
        assertTrue(Servers.cleanupOnly(added, claude.copy(modelId = "whisper-1")))
        assertFalse(claude in Servers.recognizers(added))
        assertFalse(Servers.recognizes(claude))
        assertTrue(Servers.recognizes(parakeet))
        // Its problem is that it recognizes nothing, not a missing model name; the cleanup does without.
        assertEquals(
            listOf(ConfigIssue.NoSpeechRecognition("Claude")),
            app.amethyst.core.engine.EngineConfigValidator.validate(claude, hasApiKey = true),
        )
        assertTrue(app.amethyst.core.engine.EngineConfigValidator.readyForCleanup(claude, hasApiKey = true))
        assertFalse(app.amethyst.core.engine.EngineConfigValidator.readyForCleanup(claude, hasApiKey = false))
        // Removing the active engine never hands dictation to Claude.
        assertEquals(parakeet.id, Servers.nextActive(listOf(claude, parakeet)))
        assertEquals("", Servers.nextActive(listOf(claude)))
    }

    @Test
    fun `a server already at that address is used again, its speech model kept`() {
        val own = Servers.newServer("server-000001", "de-DE").copy(baseUrl = "https://api.openai.com/v1", modelId = "whisper-1")
        val settings = local.withEngine(own)
        val plan = CleanupService.known((CleanupService.read("sk-proj-abc") as ServiceKey.Known).quick)
        val setup = CleanupService.setup(settings, plan, TEST_PHONE, keyGiven = true)
        assertEquals("server-000001", setup.engine.id)
        assertEquals("whisper-1", setup.engine.modelId)
        assertEquals("api.openai.com", setup.engine.keyHost)
        val after = CleanupServices.forEveryApp(settings, setup)
        assertEquals(1, after.engines.count { Servers.isServer(it) })
        assertEquals(CleanupChoice("server-000001", "gpt-4.1-nano"), after.cleanup)
    }

    @Test
    fun `an unknown key asks for an address the server's key may go to`() {
        assertEquals(listOf(ConfigIssue.AddressMissing), CleanupService.addressProblems(" "))
        assertTrue(CleanupService.addressProblems("192.168.1.20:8000").isNotEmpty())
        assertTrue(CleanupService.addressProblems("http://192.168.1.20:8000/v1").isEmpty())
    }

    private val amberPc = AmberCatalog(
        service = AmberCatalog.SERVICE_PC,
        version = "1.0",
        speech = listOf(ModelChoice("large-v3", "Whisper large-v3", ""), ModelChoice("turbo", "Whisper turbo", "", recommended = true)),
        cleanup = listOf(ModelChoice("gemma", "Gemma", ""), ModelChoice("qwen", "Qwen", "", recommended = true)),
    )

    @Test
    fun `an amber pc's list gives the speech and cleanup models it recommends, and is kept`() {
        val address = "http://192.168.1.20:8000/v1"
        val answer = CleanupService.answer(address, ModelList.Amber(amberPc))
        val plan = assertIs<ServiceAnswer.Ready>(answer).plan
        assertEquals("turbo", plan.speechModel)
        assertEquals("qwen", plan.cleanupModel)

        val setup = CleanupService.setup(local, plan, TEST_PHONE, keyGiven = true, random = { 7 })
        val after = CleanupServices.forEveryApp(local, setup)
        val engine = after.engines.single { it.id == "server-000007" }
        assertEquals("Amber PC", engine.displayName)
        assertEquals(amberPc.name, after.catalogFor(engine)?.name)
        assertEquals(CleanupChoice(engine.id, "qwen"), after.cleanup)
        assertEquals("192.168.1.20", engine.keyHost)
    }

    @Test
    fun `another server's list is picked from, no list or no answer asks for the model's name`() {
        val address = " http://192.168.1.20:11434/v1 "
        val own = "http://192.168.1.20:11434/v1"
        assertEquals(ServiceAnswer.NoCleanupModel, CleanupService.answer(address, ModelList.Amber(amberPc.copy(cleanup = emptyList()))))
        assertEquals(ServiceAnswer.Pick(own, listOf("llama3.2", "qwen3:4b")), CleanupService.answer(address, ModelList.Plain(listOf("llama3.2", "qwen3:4b"))))
        assertEquals(ServiceAnswer.Type(own, answered = true), CleanupService.answer(address, ModelList.Plain(emptyList())))
        assertEquals(ServiceAnswer.Type(own, answered = false), CleanupService.answer(address, ModelList.Failed("HTTP 401")))
    }

    @Test
    fun `an ollama for the cleanup only - no key, no speech model, its chat model - is ready for the cleanup`() {
        val plan = CleanupService.chosen("http://192.168.1.20:11434/v1", " llama3.2 ")!!
        assertNull(CleanupService.chosen("http://192.168.1.20:11434/v1", "  "))
        val setup = CleanupService.setup(local, plan, TEST_PHONE, keyGiven = false, random = { 9 })
        val after = CleanupServices.forEveryApp(local, setup)
        val engine = after.engines.single { it.id == "server-000009" }
        assertEquals("http://192.168.1.20:11434/v1", engine.baseUrl)
        assertNull(engine.modelId)
        assertEquals("192.168.1.20", engine.displayName)
        assertEquals(CleanupChoice(engine.id, "llama3.2"), after.cleanup)
        assertTrue(CleanupRouting.ready(after, after.cleanup!!) { false }, "a key is optional at an own server")
        assertEquals(parakeet.id, after.activeEngineId)
    }

    @Test
    fun `a cloud server still needs its key for the cleanup, and an address`() {
        val openRouter = Servers.newServer("server-1", null).copy(baseUrl = "https://openrouter.ai/api/v1")
        val settings = local.withEngine(openRouter).copy(cleanup = CleanupChoice("server-1", "x"))
        assertTrue(CleanupRouting.ready(settings, settings.cleanup!!) { true })
        val groq = local.withEngine(openRouter.copy(baseUrl = "https://api.groq.com/openai/v1")).copy(cleanup = CleanupChoice("server-1", "x"))
        assertFalse(CleanupRouting.ready(groq, groq.cleanup!!) { false })
        val nowhere = local.withEngine(openRouter.copy(baseUrl = null)).copy(cleanup = CleanupChoice("server-1", "x"))
        assertFalse(CleanupRouting.ready(nowhere, nowhere.cleanup!!) { true })
    }

    @Test
    fun `a model list is amber's, another server's ids, or nothing`() {
        assertEquals(ModelList.Plain(listOf("llama3.2", "qwen3:4b")), ServerModels.parse("""{"object":"list","data":[{"id":"llama3.2","object":"model"},{"id":"qwen3:4b"},{"id":"llama3.2"},{"object":"model"}]}"""))
        assertEquals(ModelList.Plain(emptyList()), ServerModels.parse("""{"data":[]}"""))
        assertIs<ModelList.Failed>(ServerModels.parse("<html>portal</html>"))
    }

    @Test
    fun `a stranger's model ids are plain, not too long, and not too many`() {
        val bidi = "gpt‮evil"
        val long = "m".repeat(129)
        val body = """{"data":[{"id":"$bidi"},{"id":"$long"},{"id":"ok\nno"},{"id":"../x"},{"id":"meta-llama/llama-3.3-70b"}]}"""
        assertEquals(ModelList.Plain(listOf("meta-llama/llama-3.3-70b")), ServerModels.parse(body))
        val many = (1..1000).joinToString(",") { """{"id":"m$it"}""" }
        val list = assertIs<ModelList.Plain>(ServerModels.parse("""{"data":[$many]}""")).ids
        assertEquals(ServerModels.MAX_MODELS, list.size)
        assertEquals("m1", list.first())
    }

    @Test
    fun `a long list is searched, a short one shown whole, and only a listed model is picked`() {
        val few = listOf("llama3.2", "qwen3:4b")
        assertFalse(CleanupService.searchable(few))
        assertEquals(few, CleanupService.shownModels(few, "zzz"))
        val many = (1..300).map { "vendor/model-$it" } + "anthropic/claude-haiku"
        assertTrue(CleanupService.searchable(many))
        assertEquals(CleanupService.MODELS_SHOWN, CleanupService.shownModels(many, "").size)
        assertEquals(listOf("anthropic/claude-haiku"), CleanupService.shownModels(many, " CLAUDE "))
        val pick = ServiceAnswer.Pick(" https://openrouter.ai/api/v1 ", many)
        assertEquals(ServicePlan("https://openrouter.ai/api/v1", null, "vendor/model-7"), CleanupService.picked(pick, "vendor/model-7"))
        assertNull(CleanupService.picked(pick, "vendor/model"))
    }

    @Test
    fun `a server for the cleanup only is no choice for recognition, one just added still is`() {
        val ollama = Servers.newServer("server-9", "de-DE").copy(baseUrl = "http://192.168.1.20:11434/v1")
        val added = local.withEngine(ollama)
        assertFalse(Servers.cleanupOnly(added, ollama), "just added, no model yet: still being set up")
        assertTrue(ollama in Servers.recognizers(added))
        val forCleanup = added.copy(cleanup = CleanupChoice("server-9", "llama3.2"))
        assertTrue(Servers.cleanupOnly(forCleanup, ollama))
        assertFalse(ollama in Servers.recognizers(forCleanup))
        assertTrue(parakeet.id in Servers.recognizers(forCleanup).map { it.id })
        val withSpeech = ollama.copy(modelId = "whisper")
        assertFalse(Servers.cleanupOnly(forCleanup.withEngine(withSpeech), withSpeech))
        val perApp = added.copy(
            profiles = listOf(
                app.amethyst.core.settings.AppProfile(
                    "com.whatsapp", "WhatsApp",
                    appCleanup = app.amethyst.core.settings.AppCleanup(app.amethyst.core.settings.AppCleanupMode.MODEL, CleanupChoice("server-9", "x")),
                ),
            ),
        )
        assertTrue(Servers.cleanupOnly(perApp, ollama))
        // Its speech model cleared while dictation goes to it: a recognition engine missing its model.
        val active = forCleanup.copy(activeEngineId = "server-9")
        assertFalse(Servers.cleanupOnly(active, ollama))
        assertTrue(ollama in Servers.recognizers(active))
    }

    @Test
    fun `a model list is asked once, with the key, and an error is no list`() = kotlinx.coroutines.test.runTest {
        val transport = FakeHttpTransport.json("""{"data":[{"id":"gpt-oss:20b"}]}""")
        assertEquals(ModelList.Plain(listOf("gpt-oss:20b")), ServerModels.fetch(transport, "http://192.168.1.20:11434/v1", "k"))
        assertEquals("http://192.168.1.20:11434/v1/models", transport.requests.single().url)
        assertEquals("Bearer k", transport.requests.single().headers["Authorization"])
        assertIs<ModelList.Failed>(ServerModels.fetch(FakeHttpTransport.failing(java.io.IOException("x")), "http://h:1/v1", null))
        assertIs<ModelList.Failed>(ServerModels.fetch(FakeHttpTransport.json("{}", code = 401), "http://h:1/v1", null))
    }

    @Test
    fun `an amber address with its key in it goes apart, whichever field it is typed into`() {
        val key = "abcdefghjkmnpqrstuvw"
        val own = "http://192.168.1.20:8000/v1"
        assertEquals(ServiceFields(key, own), ServiceFields().withAddress("http://192.168.1.20:8000/$key/v1"))
        assertEquals(ServiceFields(key, own), ServiceFields().withKey("http://192.168.1.20:8000/$key/v1"))
        assertEquals(ServiceFields("gsk_x", own), ServiceFields("gsk_x").withAddress(own))
        assertEquals(ServiceFields("gsk_y", own), ServiceFields("gsk_x", own).withKey("gsk_y"))
        assertEquals(ServiceFields("", "http://192.168.1.20:11434/v1"), ServiceFields("gsk_x").withKey(" http://192.168.1.20:11434/v1"))
    }
}
