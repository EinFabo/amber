package app.amethyst.core

import app.amethyst.core.engine.AmberCatalog
import app.amethyst.core.engine.CleanupRouting
import app.amethyst.core.engine.ConfigIssue
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.EnginePresets.ModelChoice
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.engine.Servers
import app.amethyst.core.onboarding.CleanupService
import app.amethyst.core.onboarding.CleanupServices
import app.amethyst.core.onboarding.KeyVerdict
import app.amethyst.core.onboarding.ServicePlan
import app.amethyst.core.onboarding.ServiceRow
import app.amethyst.core.onboarding.ServiceSlot
import app.amethyst.core.onboarding.ServiceState
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppCleanup
import app.amethyst.core.settings.AppCleanupMode
import app.amethyst.core.settings.AppProfile
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.StoredCatalog
import app.amethyst.core.settings.WritingStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CleanupServicesTest {

    private val parakeet = EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET)
    private val local = AmethystSettings.defaults(TEST_PHONE).withEngine(parakeet).copy(activeEngineId = parakeet.id)
    private val tinfoil = EngineConfig.fromPreset(EnginePresets.TINFOIL)
    private val groq = EngineConfig.fromPreset(EnginePresets.GROQ)
    private val ollama = Servers.newServer("server-000011", "de-DE").copy(baseUrl = "http://192.168.1.20:11434/v1")
    private val noKey: (String?) -> Boolean = { false }
    private val everyKey: (String?) -> Boolean = { it != null }

    private fun rows(settings: AmethystSettings, hasKey: (String?) -> Boolean = noKey) = CleanupServices.rows(settings, hasKey)
    private fun List<ServiceRow>.of(slot: ServiceSlot) = single { it.slot == slot }

    // The list.

    @Test
    fun `a new phone lists tinfoil, claude, groq and openai, none set up and each one key away`() {
        val rows = rows(local)
        assertEquals(listOf(ServiceSlot.TINFOIL, ServiceSlot.CLAUDE, ServiceSlot.GROQ, ServiceSlot.OPENAI), rows.map { it.slot })
        assertEquals(listOf("Tinfoil", "Claude", "Groq", "OpenAI"), rows.map { it.provider.name })
        assertTrue(rows.all { it.engine == null && it.state == ServiceState.NEEDS_KEY && !it.keyStored && it.keyRequired })
        assertEquals(listOf("TINFOIL", "CLAUDE", "GROQ", "OPENAI"), rows.map { it.id })
    }

    @Test
    fun `the recognition on the phone is no cleanup service`() {
        assertTrue(rows(local).none { it.engine?.id == parakeet.id })
    }

    @Test
    fun `a tinfoil key that outlived its engine makes tinfoil ready`() {
        val row = rows(local) { it == "key.tinfoil-whisper" }.of(ServiceSlot.TINFOIL)
        assertEquals(ServiceState.READY, row.state)
        assertTrue(row.keyStored)
        assertNull(row.engine)
    }

    @Test
    fun `a server that is set up takes its service's row, own servers come last in the order added`() {
        val router = Servers.newServer("server-000012", null).copy(baseUrl = "https://openrouter.ai/api/v1")
        val rows = rows(local.withEngine(ollama).withEngine(groq).withEngine(router).withEngine(tinfoil), everyKey)
        assertEquals(
            listOf(ServiceSlot.TINFOIL, ServiceSlot.CLAUDE, ServiceSlot.GROQ, ServiceSlot.OPENAI, ServiceSlot.SERVER, ServiceSlot.SERVER),
            rows.map { it.slot },
        )
        assertEquals(listOf(tinfoil.id, "CLAUDE", groq.id, "OPENAI", ollama.id, router.id), rows.map { it.id })
        assertEquals(ServiceState.READY, rows.of(ServiceSlot.GROQ).state)
        assertTrue(rows.of(ServiceSlot.GROQ).keyStored)
        assertEquals(ServiceState.NEEDS_KEY, rows.of(ServiceSlot.CLAUDE).state)
        assertFalse(rows.last().keyRequired, "a key is optional at an own server")
    }

    @Test
    fun `a known service without its key needs the key, an own server without one is ready`() {
        val rows = rows(local.withEngine(groq).withEngine(ollama))
        assertEquals(ServiceState.NEEDS_KEY, rows.of(ServiceSlot.GROQ).state)
        assertFalse(rows.of(ServiceSlot.GROQ).keyStored)
        assertEquals(ServiceState.READY, rows.of(ServiceSlot.SERVER).state)
    }

    @Test
    fun `a server without a speech model is ready for the cleanup all the same`() {
        val claude = Servers.newServer("server-c1a0de", null).copy(baseUrl = EnginePresets.ANTHROPIC_BASE_URL)
        val row = rows(local.withEngine(claude), everyKey).of(ServiceSlot.CLAUDE)
        assertEquals(ServiceState.READY, row.state)
        assertEquals(claude.id, row.engine?.id)
    }

    @Test
    fun `two servers at one service get a row each`() {
        val second = Servers.newServer("server-000002", null).copy(baseUrl = "https://api.groq.com/openai/v1")
        val rows = rows(local.withEngine(groq).withEngine(second), everyKey)
        assertEquals(listOf(groq.id, second.id), rows.filter { it.slot == ServiceSlot.GROQ }.map { it.id })
    }

    @Test
    fun `a key given for another address counts as missing`() {
        val moved = groq.copy(keyHost = "api.openai.com")
        val row = rows(local.withEngine(moved), everyKey).of(ServiceSlot.GROQ)
        assertEquals(ServiceState.NEEDS_KEY, row.state)
        assertFalse(row.keyStored, "it must not go to this address")
        assertTrue(row.issues.isEmpty())
        assertEquals(ConfigIssue.KeyForOtherAddress("api.openai.com"), row.keyElsewhere, "said, so the sheet can say why")

        // An own server too: the key it has was given elsewhere, so here one is asked for.
        val own = ollama.copy(keyHost = "192.168.1.99")
        val ownRow = rows(local.withEngine(own), everyKey).of(ServiceSlot.SERVER)
        assertEquals(ServiceState.NEEDS_KEY, ownRow.state)
        assertTrue(ownRow.keyRequired)
        assertFalse(rows(local.withEngine(ollama), everyKey).of(ServiceSlot.SERVER).keyRequired)
        assertNull(rows(local.withEngine(groq)).of(ServiceSlot.GROQ).keyElsewhere, "a key that is simply missing")
    }

    @Test
    fun `an address that cannot be asked needs setting up, and says why`() {
        val nowhere = Servers.newServer("server-000003", null)
        val plain = Servers.newServer("server-000004", null).copy(baseUrl = "http://example.com/v1")
        val rows = rows(local.withEngine(nowhere).withEngine(plain), everyKey)
        assertEquals(ServiceState.NEEDS_SETUP, rows.single { it.id == nowhere.id }.state)
        assertEquals(listOf<ConfigIssue>(ConfigIssue.AddressMissing), rows.single { it.id == nowhere.id }.issues)
        assertEquals(listOf<ConfigIssue>(ConfigIssue.HttpOnlyInOwnNetwork), rows.single { it.id == plain.id }.issues)
    }

    private val amberPc = AmberCatalog(
        service = AmberCatalog.SERVICE_PC,
        version = "1.0",
        speech = listOf(ModelChoice("turbo", "Whisper turbo", "", recommended = true)),
        cleanup = listOf(ModelChoice("gemma", "Gemma", ""), ModelChoice("qwen", "Qwen", "", recommended = true)),
    )

    @Test
    fun `an amber pc lists its own models, the recommended one first`() {
        val pc = ollama.copy(baseUrl = "http://192.168.1.20:8765/v1")
        val settings = local.withEngine(pc).copy(serverCatalogs = mapOf(pc.id to StoredCatalog.of(pc.baseUrl!!, amberPc)))
        val row = rows(settings).of(ServiceSlot.SERVER)
        assertEquals(listOf("qwen", "gemma"), CleanupServices.listed(row.provider).map { it.id })
        assertEquals("qwen", CleanupServices.preselected(row, inUse = null))
    }

    @Test
    fun `the row a choice runs on is found by its server, a removed one is none`() {
        val rows = rows(local.withEngine(groq), everyKey)
        assertEquals(ServiceSlot.GROQ, CleanupServices.rowOf(rows, CleanupChoice(groq.id, "x"))?.slot)
        assertNull(CleanupServices.rowOf(rows, CleanupChoice("gone", "x")))
        assertNull(CleanupServices.rowOf(rows, null))
    }

    @Test
    fun `an address says which known service it is`() {
        assertEquals(ServiceSlot.GROQ, CleanupServices.slotAt(" https://api.groq.com/openai/v1 "))
        assertEquals(ServiceSlot.CLAUDE, CleanupServices.slotAt("https://api.anthropic.com/v1"))
        assertEquals(ServiceSlot.TINFOIL, CleanupServices.slotAt(EnginePresets.TINFOIL_BASE_URL))
        assertNull(CleanupServices.slotAt("https://openrouter.ai/api/v1"))
        assertNull(CleanupServices.slotAt(""))
        assertSame(ServerProviders.OPENAI, CleanupServices.providerOf(ServiceSlot.OPENAI))
        assertSame(ServerProviders.CUSTOM, CleanupServices.providerOf(ServiceSlot.SERVER))
    }

    // The model a sheet starts with.

    @Test
    fun `a sheet starts with the model in use on that server, else the recommended one`() {
        val settings = local.withEngine(groq)
        val row = rows(settings, everyKey).of(ServiceSlot.GROQ)
        assertEquals("openai/gpt-oss-20b", CleanupServices.preselected(row, inUse = null))
        assertEquals("llama-3.3-70b-versatile", CleanupServices.preselected(row, CleanupChoice(groq.id, "llama-3.3-70b-versatile")))
        assertEquals("openai/gpt-oss-20b", CleanupServices.preselected(row, CleanupChoice("another", "llama-3.3-70b-versatile")))
        assertEquals("claude-haiku-5-5", CleanupServices.preselected(rows(local).of(ServiceSlot.CLAUDE), CleanupChoice(groq.id, "x")))
    }

    @Test
    fun `an own server that lists nothing has no model to start with, but keeps the one in use`() {
        val row = rows(local.withEngine(ollama)).of(ServiceSlot.SERVER)
        assertNull(CleanupServices.preselected(row, inUse = null))
        assertEquals("llama3.2", CleanupServices.preselected(row, CleanupChoice(ollama.id, "llama3.2")))
    }

    // The key typed into a sheet.

    @Test
    fun `a key that is another known service's is said to be, and not sent to this one`() {
        assertEquals(KeyVerdict.Fine, CleanupServices.keyVerdict(ServiceSlot.GROQ, " "))
        assertEquals(KeyVerdict.Fine, CleanupServices.keyVerdict(ServiceSlot.GROQ, "gsk_abc"))
        assertEquals(KeyVerdict.Other(ServiceSlot.CLAUDE), CleanupServices.keyVerdict(ServiceSlot.GROQ, "sk-ant-api03-abc"))
        assertEquals(KeyVerdict.Other(ServiceSlot.GROQ), CleanupServices.keyVerdict(ServiceSlot.OPENAI, "gsk_abc"))
        assertEquals(KeyVerdict.Other(ServiceSlot.OPENAI), CleanupServices.keyVerdict(ServiceSlot.TINFOIL, "sk-proj-abc"))
        assertTrue(CleanupServices.blocks(ServiceSlot.GROQ, KeyVerdict.Other(ServiceSlot.CLAUDE)))
        assertTrue(CleanupServices.blocks(ServiceSlot.TINFOIL, KeyVerdict.Other(ServiceSlot.OPENAI)))
        assertFalse(CleanupServices.blocks(ServiceSlot.GROQ, KeyVerdict.Fine))
    }

    @Test
    fun `claude takes only an anthropic key`() {
        assertEquals(KeyVerdict.Fine, CleanupServices.keyVerdict(ServiceSlot.CLAUDE, "sk-ant-api03-abc"))
        assertEquals(KeyVerdict.NotClaude, CleanupServices.keyVerdict(ServiceSlot.CLAUDE, "abcdefghjkmnpqrstuvw"))
        assertEquals(KeyVerdict.Other(ServiceSlot.GROQ), CleanupServices.keyVerdict(ServiceSlot.CLAUDE, "gsk_abc"))
        assertTrue(CleanupServices.blocks(ServiceSlot.CLAUDE, KeyVerdict.NotClaude))
    }

    @Test
    fun `an own server may take a key in a known shape - it is only a hint there`() {
        val verdict = CleanupServices.keyVerdict(ServiceSlot.SERVER, "sk-proj-abc")
        assertEquals(KeyVerdict.Other(ServiceSlot.OPENAI), verdict)
        assertFalse(CleanupServices.blocks(ServiceSlot.SERVER, verdict))
        assertEquals(KeyVerdict.Fine, CleanupServices.keyVerdict(ServiceSlot.SERVER, "sk-or-v1-abc"))
        assertEquals(KeyVerdict.Fine, CleanupServices.keyVerdict(ServiceSlot.GROQ, "abcdefghjkmnpqrstuvw"), "an unknown shape is the test's to judge")
    }

    // What Use writes.

    @Test
    fun `use on a service that is set up keeps its server and puts the model on it`() {
        val settings = local.withEngine(groq)
        val row = rows(settings, everyKey).of(ServiceSlot.GROQ)
        val setup = assertNotNull(CleanupServices.setup(settings, row, " llama-3.3-70b-versatile ", keyGiven = false, TEST_PHONE))
        assertEquals(groq, setup.engine)
        val after = CleanupServices.forEveryApp(settings, setup)
        assertEquals(CleanupChoice(groq.id, "llama-3.3-70b-versatile"), after.cleanup)
        assertEquals(settings.engines, after.engines)
        assertEquals(parakeet.id, after.activeEngineId, "the recognition stays as it was")
    }

    @Test
    fun `use needs a model`() {
        val settings = local.withEngine(groq)
        assertNull(CleanupServices.setup(settings, rows(settings, everyKey).of(ServiceSlot.GROQ), "  ", keyGiven = false, TEST_PHONE))
    }

    @Test
    fun `the row's own server is used, not another at the same address`() {
        val second = Servers.newServer("server-000002", null).copy(baseUrl = groq.baseUrl, keyHost = "api.groq.com")
        val settings = local.withEngine(groq).withEngine(second)
        val row = rows(settings, everyKey).single { it.id == second.id }
        val setup = assertNotNull(CleanupServices.setup(settings, row, "llama-3.1-8b-instant", keyGiven = false, TEST_PHONE))
        val after = CleanupServices.forEveryApp(settings, setup)
        assertEquals(CleanupChoice(second.id, "llama-3.1-8b-instant"), after.cleanup)
        assertEquals(Servers.normalized(settings).engines, after.engines, "both stay as the store keeps them")
    }

    @Test
    fun `a key given now is bound to the address, a key stored for another host is never taken along`() {
        val moved = groq.copy(keyHost = "api.openai.com")
        val settings = local.withEngine(moved)
        val row = rows(settings, everyKey).of(ServiceSlot.GROQ)

        assertNull(CleanupServices.setup(settings, row, "openai/gpt-oss-20b", keyGiven = false, TEST_PHONE), "the old key stays where it was given")

        val with = assertNotNull(CleanupServices.setup(settings, row, "openai/gpt-oss-20b", keyGiven = true, TEST_PHONE))
        assertEquals("api.groq.com", with.engine.keyHost)
        val now = CleanupServices.forEveryApp(settings, with)
        assertTrue(CleanupRouting.ready(now, now.cleanup!!, everyKey))
    }

    @Test
    fun `use never puts the cleanup on a service that could not run it`() {
        // A known service without its key, a server without an address, Tinfoil off its enclave.
        val nowhere = Servers.newServer("server-000003", null)
        val offEnclave = tinfoil.copy(baseUrl = "https://proxy.example/v1", keyHost = "proxy.example")
        val settings = local.withEngine(nowhere).withEngine(offEnclave)
        rows(settings, everyKey).filter { it.state != ServiceState.READY }.forEach { row ->
            assertNull(CleanupServices.setup(settings, row, "x", keyGiven = false, TEST_PHONE), row.id)
        }
        assertNull(CleanupServices.setup(settings, rows(settings, everyKey).single { it.id == nowhere.id }, "x", keyGiven = true, TEST_PHONE))
        assertNull(CleanupServices.setup(settings, rows(settings, everyKey).of(ServiceSlot.TINFOIL), "x", keyGiven = true, TEST_PHONE))
    }

    @Test
    fun `every setup is one the cleanup can run on`() {
        val router = Servers.newServer("server-000012", null).copy(baseUrl = "https://openrouter.ai/api/v1")
        val bare = EngineConfig.fromPreset(EnginePresets.SELF_HOSTED).copy(baseUrl = "http://192.168.1.20:8000/v1")
        listOf(local, local.withEngine(groq).withEngine(tinfoil).withEngine(ollama).withEngine(router).withEngine(bare)).forEach { settings ->
            rows(settings, noKey).forEach { row ->
                val model = CleanupServices.preselected(row, null) ?: "llama3.2"
                val setup = CleanupServices.setup(settings, row, model, keyGiven = true, TEST_PHONE) ?: return@forEach
                val after = CleanupServices.forEveryApp(settings, setup)
                assertEquals(CleanupChoice(setup.engine.id, model), after.cleanup, row.id)
                assertTrue(CleanupRouting.ready(after, after.cleanup!!, everyKey), row.id)
                assertNotNull(after.engines.single { it.id == setup.engine.id }.apiKeyAlias, "a place for the key: ${row.id}")
            }
        }
    }

    @Test
    fun `tinfoil's row is its engine's whatever its address, and never another service's`() {
        val offEnclave = tinfoil.copy(baseUrl = "https://api.groq.com/openai/v1", keyHost = "api.groq.com")
        val rows = rows(local.withEngine(offEnclave), everyKey)
        val row = rows.of(ServiceSlot.TINFOIL)
        assertEquals(tinfoil.id, row.engine?.id)
        assertEquals(ServiceState.NEEDS_SETUP, row.state)
        assertEquals(listOf<ConfigIssue>(ConfigIssue.EnclaveAddressOnly(EnginePresets.TINFOIL_BASE_URL)), row.issues)
        assertNull(rows.of(ServiceSlot.GROQ).engine, "no Groq key is asked for an engine that only sends to its enclave")

        val plain = Servers.newServer("server-000005", null).copy(baseUrl = EnginePresets.TINFOIL_BASE_URL)
        assertEquals(listOf(plain.id), rows(local.withEngine(plain), everyKey).filter { it.slot == ServiceSlot.TINFOIL }.map { it.id })
    }

    @Test
    fun `every known provider has its row`() {
        ServerProviders.known.forEach { provider ->
            val slot = assertNotNull(CleanupServices.slotAt("https://${provider.hosts.first()}/v1"), provider.name)
            assertSame(provider, CleanupServices.providerOf(slot))
        }
    }

    // Settings that moved on while a sheet was open or a key was tested.

    @Test
    fun `a row a moment old - its server pointed elsewhere or removed - sets nothing up`() {
        val settings = local.withEngine(ollama)
        val row = rows(settings).of(ServiceSlot.SERVER)
        val moved = settings.withEngine(ollama.copy(baseUrl = "http://192.168.1.99:11434/v1"))
        assertNull(CleanupServices.setup(moved, row, "llama3.2", keyGiven = false, TEST_PHONE))
        assertNull(CleanupServices.setup(local, row, "llama3.2", keyGiven = false, TEST_PHONE))
    }

    @Test
    fun `a setup that no longer fits writes nothing - a key never follows its server elsewhere`() {
        val settings = local.withEngine(groq)
        val setup = assertNotNull(CleanupServices.setup(settings, rows(settings, noKey).of(ServiceSlot.GROQ), "llama-3.3-70b-versatile", keyGiven = true, TEST_PHONE))

        val moved = settings.withEngine(groq.copy(baseUrl = "https://evil.example/v1"))
        assertNull(CleanupServices.added(moved, setup))
        assertEquals(moved, CleanupServices.forEveryApp(moved, setup))
        assertEquals(local, CleanupServices.forEveryApp(local, setup), "removed meanwhile: not added again")
    }

    @Test
    fun `a server that changed meanwhile keeps what it is, and only gets what use gives it`() {
        val settings = local.withEngine(groq)
        val setup = assertNotNull(CleanupServices.setup(settings, rows(settings, noKey).of(ServiceSlot.GROQ), "llama-3.3-70b-versatile", keyGiven = true, TEST_PHONE))
        val newer = settings.withEngine(groq.copy(modelId = "whisper-large-v3", languageTag = "en-GB", keyHost = null))
        val after = CleanupServices.forEveryApp(newer, setup)
        assertEquals(groq.copy(modelId = "whisper-large-v3", languageTag = "en-GB"), after.engines.single { it.id == groq.id })
    }

    @Test
    fun `an own server from before keys had a place gets one`() {
        val bare = EngineConfig.fromPreset(EnginePresets.SELF_HOSTED).copy(baseUrl = "http://192.168.1.20:8000/v1")
        assertNull(bare.apiKeyAlias)
        val settings = local.withEngine(bare)
        val setup = assertNotNull(CleanupServices.setup(settings, rows(settings).of(ServiceSlot.SERVER), "qwen", keyGiven = true, TEST_PHONE))
        assertEquals("key.${bare.id}", setup.engine.apiKeyAlias)
        assertEquals("192.168.1.20", CleanupServices.forEveryApp(settings, setup).engines.single { it.id == bare.id }.keyHost)
    }

    @Test
    fun `tinfoil comes from its preset, in the phone's language, with the model chosen`() {
        val row = rows(local).of(ServiceSlot.TINFOIL)
        val setup = assertNotNull(CleanupServices.setup(local, row, "gemma-4-31b", keyGiven = true, listOf("en-GB")))
        assertEquals(EngineConfig.fromPreset(EnginePresets.TINFOIL, "en-GB"), setup.engine)
        assertTrue(setup.engine.requireEnclave)
        val after = CleanupServices.forEveryApp(local, setup)
        assertEquals(CleanupChoice(EnginePresets.TINFOIL.id, "gemma-4-31b"), after.cleanup)
        assertEquals(parakeet.id, after.activeEngineId)
        assertTrue(CleanupRouting.ready(after, after.cleanup!!, everyKey))
    }

    @Test
    fun `claude is set up as a server for the cleanup only, with the model chosen`() {
        val row = rows(local).of(ServiceSlot.CLAUDE)
        val setup = assertNotNull(CleanupServices.setup(local, row, "claude-sonnet-5-5", keyGiven = true, TEST_PHONE, random = { 0xc1a0de }))
        val after = CleanupServices.forEveryApp(local, setup)
        val engine = after.engines.single { it.id == "server-c1a0de" }
        assertEquals("Claude", engine.displayName)
        assertEquals("api.anthropic.com", engine.keyHost)
        assertEquals("key.server-c1a0de", engine.apiKeyAlias)
        assertNull(engine.modelId)
        assertEquals(CleanupChoice(engine.id, "claude-sonnet-5-5"), after.cleanup)
        assertTrue(Servers.cleanupOnly(after, engine))
        assertEquals(after, Servers.normalized(after), "stored as the settings store would")
        assertEquals(ServiceSlot.CLAUDE, CleanupServices.rowOf(rows(after, everyKey), after.cleanup)?.slot)
    }

    @Test
    fun `groq and openai are set up as the settings would add them, with the model chosen`() {
        val setup = assertNotNull(
            CleanupServices.setup(local, rows(local).of(ServiceSlot.GROQ), "llama-3.3-70b-versatile", keyGiven = true, TEST_PHONE, random = { 0x123456 }),
        )
        assertEquals(
            Servers.newServer("server-123456", "de-DE").copy(
                baseUrl = "https://api.groq.com/openai/v1",
                modelId = "whisper-large-v3-turbo",
                keyHost = "api.groq.com",
            ),
            setup.engine,
        )
        val after = CleanupServices.forEveryApp(local, setup)
        assertEquals("Groq", after.engines.single { it.id == "server-123456" }.displayName)
        assertEquals(CleanupChoice("server-123456", "llama-3.3-70b-versatile"), after.cleanup)

        val openAi = assertNotNull(CleanupServices.setup(local, rows(local).of(ServiceSlot.OPENAI), "gpt-4o-mini", keyGiven = true, TEST_PHONE, random = { 1 }))
        assertEquals("https://api.openai.com/v1", openAi.engine.baseUrl)
        assertEquals("gpt-4o-mini", openAi.cleanupModel)
    }

    @Test
    fun `an own server that is not there yet is set up from its address, not from a row`() {
        val row = ServiceRow(ServiceSlot.SERVER, ServerProviders.CUSTOM, engine = null, state = ServiceState.READY, keyStored = false)
        assertNull(CleanupServices.setup(local, row, "llama3.2", keyGiven = false, TEST_PHONE))
    }

    @Test
    fun `an amber's list that just came is kept with its server`() {
        val pc = ollama.copy(baseUrl = "http://192.168.1.20:8765/v1")
        val settings = local.withEngine(pc)
        val row = rows(settings).of(ServiceSlot.SERVER)
        val setup = assertNotNull(CleanupServices.setup(settings, row, "qwen", keyGiven = false, TEST_PHONE, catalog = amberPc))
        val after = CleanupServices.forEveryApp(settings, setup)
        val engine = after.engines.single { it.id == pc.id }
        assertEquals("Amber PC", engine.displayName)
        assertEquals(amberPc.name, after.catalogFor(engine)?.name)
        assertEquals(CleanupChoice(pc.id, "qwen"), after.cleanup)
    }

    @Test
    fun `one app's own model leaves the cleanup for every app alone`() {
        val whatsapp = AppProfile("com.whatsapp", "WhatsApp", WritingStyle.CASUAL, instruction = "use du")
        val mail = AppProfile("com.google.android.gm", "Gmail", WritingStyle.FORMAL)
        val settings = local.withEngine(tinfoil).copy(cleanup = CleanupChoice(tinfoil.id, "gemma-4-31b"), profiles = listOf(whatsapp, mail))
        val row = rows(settings, everyKey).of(ServiceSlot.CLAUDE)
        val setup = assertNotNull(CleanupServices.setup(settings, row, "claude-haiku-5-5", keyGiven = true, TEST_PHONE, random = { 5 }))
        val after = CleanupServices.forApp(settings, setup, "com.whatsapp")
        assertEquals(CleanupChoice(tinfoil.id, "gemma-4-31b"), after.cleanup)
        val own = AppCleanup(AppCleanupMode.MODEL, CleanupChoice("server-000005", "claude-haiku-5-5"))
        assertEquals(listOf(whatsapp.copy(appCleanup = own), mail), after.profiles, "its style and instruction stay, the other app too")
        assertTrue(Servers.cleanupOnly(after, after.engines.single { it.id == "server-000005" }))
    }

    @Test
    fun `an app removed meanwhile gets nothing back, and no server is added for it`() {
        val setup = assertNotNull(CleanupServices.setup(local, rows(local).of(ServiceSlot.CLAUDE), "claude-haiku-5-5", keyGiven = true, TEST_PHONE))
        assertEquals(local, CleanupServices.forApp(local, setup, "com.example.removed"))
    }

    // CleanupService.setup, as an own server's sheet calls it.

    @Test
    fun `an own server's address already set up, entered again without a key, keeps the binding it had`() {
        val router = Servers.newServer("server-000012", null).copy(baseUrl = "https://openrouter.ai/api/v1", keyHost = "example.org")
        val settings = local.withEngine(router)
        val plan = ServicePlan("https://openrouter.ai/api/v1", null, "x")
        assertEquals("example.org", CleanupService.setup(settings, plan, TEST_PHONE, keyGiven = false).engine.keyHost)
        assertEquals("openrouter.ai", CleanupService.setup(settings, plan, TEST_PHONE, keyGiven = true).engine.keyHost)
        assertEquals("openrouter.ai", CleanupService.setup(local, plan, TEST_PHONE, keyGiven = false).engine.keyHost, "a new server is bound to where it is")
    }
}
