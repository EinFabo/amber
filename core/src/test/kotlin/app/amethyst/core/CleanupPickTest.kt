package app.amethyst.core

import app.amethyst.core.engine.AmberCatalog
import app.amethyst.core.engine.CatalogFetch
import app.amethyst.core.engine.ConfigIssue
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.EnginePresets.ModelChoice
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.engine.Servers
import app.amethyst.core.onboarding.CleanupPick
import app.amethyst.core.onboarding.CleanupService
import app.amethyst.core.onboarding.ServiceAnswer
import app.amethyst.core.onboarding.ServiceKey
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
        val answer = CleanupService.answer(address, CatalogFetch.Found(amberPc))
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
    fun `another server, one without cleanup models, or no answer, sets nothing up`() {
        val address = "http://192.168.1.20:8000/v1"
        assertEquals(ServiceAnswer.NotAmber, CleanupService.answer(address, CatalogFetch.NotAmber))
        assertEquals(ServiceAnswer.NoCleanupModel, CleanupService.answer(address, CatalogFetch.Found(amberPc.copy(cleanup = emptyList()))))
        assertEquals(ServiceAnswer.NoAnswer, CleanupService.answer(address, CatalogFetch.Failed("HTTP 401")))
    }

    @Test
    fun `another service without a setup changes nothing`() {
        assertEquals(onGroq, CleanupPick.SERVICE.applyTo(onGroq, keyReady = true, TEST_PHONE, null))
        assertFalse(CleanupPick.SERVICE.applyTo(local, keyReady = true, TEST_PHONE).cleanup != null)
    }
}
