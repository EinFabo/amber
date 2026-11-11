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
import app.amethyst.core.onboarding.CleanupPick
import app.amethyst.core.onboarding.CleanupService
import app.amethyst.core.onboarding.ServiceAnswer
import app.amethyst.core.onboarding.ServiceFields
import app.amethyst.core.onboarding.ServiceKey
import app.amethyst.core.onboarding.ServicePlan
import app.amethyst.core.onboarding.ServiceRoute
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.EngineConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CleanupPickTest {

    private val tinfoil = EnginePresets.TINFOIL.id
    private val parakeet = EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET)
    private val local = AmethystSettings.defaults(TEST_PHONE).withEngine(parakeet).copy(activeEngineId = parakeet.id)
    private val onGemma = local
        .withEngine(EngineConfig.fromPreset(EnginePresets.TINFOIL))
        .copy(cleanup = CleanupChoice(tinfoil, "gemma-4-31b"))
    private val onGroq = local
        .withEngine(EngineConfig.fromPreset(EnginePresets.GROQ))
        .copy(cleanup = CleanupChoice(EnginePresets.GROQ.id, "openai/gpt-oss-20b"))

    @Test
    fun `the first pick follows the current cleanup`() {
        assertEquals(CleanupPick.OFF, CleanupPick.initial(local))
        assertEquals(CleanupPick.TINFOIL, CleanupPick.initial(onGemma))
        assertEquals(CleanupPick.KEEP, CleanupPick.initial(onGroq))
    }

    @Test
    fun `off switches the cleanup off`() {
        assertNull(CleanupPick.OFF.applyTo(onGroq, keyReady = false, TEST_PHONE).cleanup)
    }

    @Test
    fun `tinfoil with a key adds the engine and the recommended model`() {
        val after = CleanupPick.TINFOIL.applyTo(local, keyReady = true, TEST_PHONE)
        assertEquals(CleanupChoice(tinfoil, "deepseek-v4-1-flash"), after.cleanup)
        assertTrue(after.engines.any { it.id == tinfoil })
        assertEquals(parakeet.id, after.activeEngineId)
    }

    @Test
    fun `a tinfoil cleanup on another model keeps its model`() {
        assertEquals(CleanupChoice(tinfoil, "gemma-4-31b"), CleanupPick.TINFOIL.applyTo(onGemma, keyReady = true, TEST_PHONE).cleanup)
    }

    @Test
    fun `tinfoil without a key and keep change nothing`() {
        assertEquals(local, CleanupPick.TINFOIL.applyTo(local, keyReady = false, TEST_PHONE))
        assertEquals(onGroq, CleanupPick.KEEP.applyTo(onGroq, keyReady = false, TEST_PHONE))
    }

    @Test
    fun `a tinfoil engine added here speaks the phone's language`() {
        val after = CleanupPick.TINFOIL.applyTo(local, keyReady = true, listOf("en-GB"))
        assertEquals("en-GB", after.engines.single { it.id == tinfoil }.languageTag)
    }

    // "Another service": a key, and an address when the key does not say where it goes.

    @Test
    fun `a key says which service it is, a claude key that it cannot be used yet`() {
        assertEquals(ServiceKey.Blank, CleanupService.read("  "))
        assertEquals(ServiceKey.Claude, CleanupService.read(" sk-ant-api03-abc"))
        assertEquals(ServerProviders.GROQ, (CleanupService.read("gsk_abc") as ServiceKey.Known).quick.provider)
        assertEquals(ServerProviders.OPENAI, (CleanupService.read("sk-proj-abc") as ServiceKey.Known).quick.provider)
        assertEquals(ServiceKey.Unknown, CleanupService.read("abcdefghjkmnpqrstuvw"))
    }

    @Test
    fun `a groq key sets up the same server the settings would, with its recommended cleanup model`() {
        val plan = CleanupService.known((CleanupService.read("gsk_abc") as ServiceKey.Known).quick)
        val setup = CleanupService.setup(local, plan, TEST_PHONE, random = { 0x123456 })
        val after = CleanupPick.SERVICE.applyTo(local, keyReady = true, TEST_PHONE, setup)

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
    fun `a server already at that address is used again, its speech model kept`() {
        val own = Servers.newServer("server-000001", "de-DE").copy(baseUrl = "https://api.openai.com/v1", modelId = "whisper-1")
        val settings = local.withEngine(own)
        val plan = CleanupService.known((CleanupService.read("sk-proj-abc") as ServiceKey.Known).quick)
        val setup = CleanupService.setup(settings, plan, TEST_PHONE)
        assertEquals("server-000001", setup.engine.id)
        assertEquals("whisper-1", setup.engine.modelId)
        assertEquals("api.openai.com", setup.engine.keyHost)
        val after = CleanupPick.SERVICE.applyTo(settings, keyReady = true, TEST_PHONE, setup)
        assertEquals(1, after.engines.count { Servers.isServer(it) })
        assertEquals(CleanupChoice("server-000001", "gpt-4.1-nano"), after.cleanup)
    }

    @Test
    fun `an unknown key asks for an address the server's key may go to`() {
        assertEquals(listOf(ConfigIssue.AddressMissing), CleanupService.addressProblems(" "))
        assertTrue(CleanupService.addressProblems("192.168.1.20:8000").isNotEmpty())
        assertTrue(CleanupService.addressProblems("http://192.168.1.20:8000/v1").isEmpty())
        assertEquals(null, CleanupService.knownAt("http://192.168.1.20:8000/v1"))
        assertEquals("https://api.groq.com/openai/v1", CleanupService.knownAt("https://api.groq.com/openai/v1")?.baseUrl)
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

        val setup = CleanupService.setup(local, plan, TEST_PHONE, random = { 7 })
        val after = CleanupPick.SERVICE.applyTo(local, keyReady = true, TEST_PHONE, setup)
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
        val setup = CleanupService.setup(local, plan, TEST_PHONE, random = { 9 })
        val after = CleanupPick.SERVICE.applyTo(local, keyReady = false, TEST_PHONE, setup)
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
    fun `continue goes by the key, or by the address once one is asked for`() {
        val groq = CleanupService.route(ServiceFields("gsk_abc"), otherAddress = false)
        assertEquals("https://api.groq.com/openai/v1", assertIs<ServiceRoute.Write>(groq).plan.baseUrl)
        assertEquals(ServiceRoute.Skip, CleanupService.route(ServiceFields(""), otherAddress = false))
        assertEquals(ServiceRoute.Skip, CleanupService.route(ServiceFields("sk-ant-api03-x"), otherAddress = false))
        // An sk- key for OpenRouter: "Other address" makes it an own server, asked for its models.
        assertEquals(
            ServiceRoute.Check("https://openrouter.ai/api/v1", "sk-or-v1-abc"),
            CleanupService.route(ServiceFields("sk-or-v1-abc", " https://openrouter.ai/api/v1 "), otherAddress = true),
        )
        assertEquals(ServiceRoute.Fix(listOf(ConfigIssue.AddressMissing)), CleanupService.route(ServiceFields("sk-or-v1-abc"), otherAddress = true))
        // No key at all: an own server in the home network.
        assertEquals(ServiceRoute.Check("http://192.168.1.20:11434/v1", ""), CleanupService.route(ServiceFields("", "http://192.168.1.20:11434/v1"), otherAddress = true))
        // A known service's address typed by hand is that service.
        assertIs<ServiceRoute.Write>(CleanupService.route(ServiceFields("xyz", "https://api.openai.com/v1"), otherAddress = false))
        assertTrue(CleanupService.asksAddress(ServiceFields("abcdefghjkmnpqrstuvw"), otherAddress = false))
        assertFalse(CleanupService.asksAddress(ServiceFields("gsk_abc"), otherAddress = false))
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
    fun `another service without a setup changes nothing`() {
        assertEquals(onGroq, CleanupPick.SERVICE.applyTo(onGroq, keyReady = true, TEST_PHONE, null))
        assertNull(CleanupPick.SERVICE.applyTo(local, keyReady = true, TEST_PHONE).cleanup)
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
