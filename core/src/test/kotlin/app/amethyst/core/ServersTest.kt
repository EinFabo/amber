package app.amethyst.core

import app.amethyst.core.engine.AmberCatalog
import app.amethyst.core.engine.ConfigIssue
import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.KeyBinding
import app.amethyst.core.engine.ServerNames
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.engine.Servers
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.SettingsCodec
import app.amethyst.core.settings.StoredCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ServersTest {

    private val base = AmethystSettings.defaults(TEST_PHONE)

    @Test
    fun `add offers one server entry beside the phone's own and tinfoil`() {
        assertEquals(
            listOf("android-on-device", "local-whisper", "local-parakeet", "tinfoil-whisper", "server"),
            EnginePresets.offered.map { it.id },
        )
    }

    @Test
    fun `a new server gets an opaque id of its own and a key alias for it`() {
        val numbers = ArrayDeque(listOf(0xabc, 0xabc, 0x123))
        val id = Servers.newId({ numbers.removeFirst() }, taken = setOf("server-000abc"))
        assertEquals("server-000123", id)
        val server = Servers.newServer(id, "de")
        assertEquals("key.server-000123", server.apiKeyAlias)
        assertTrue(Servers.isServer(server))
        assertFalse(Servers.isServer(EngineConfig.fromPreset(EnginePresets.TINFOIL)))
    }

    @Test
    fun `a quick pick fills in the service's address and model, keeping one of its own models`() {
        val server = Servers.newServer("server-1", null)
        val groq = Servers.pointAt(server, Servers.QUICK.first { it.provider == ServerProviders.GROQ })
        assertEquals("https://api.groq.com/openai/v1", groq.baseUrl)
        assertEquals("whisper-large-v3-turbo", groq.modelId)
        val kept = Servers.pointAt(groq.copy(modelId = "whisper-large-v3"), Servers.QUICK.first { it.provider == ServerProviders.GROQ })
        assertEquals("whisper-large-v3", kept.modelId)
        val openai = Servers.pointAt(groq, Servers.QUICK.first { it.provider == ServerProviders.OPENAI })
        assertEquals("gpt-4o-mini-transcribe", openai.modelId)
    }

    @Test
    fun `a key is needed at a known service, optional at an own server`() {
        val server = Servers.newServer("server-1", null)
        assertTrue(Servers.keyRequired(server.copy(baseUrl = "https://api.openai.com/v1")))
        assertFalse(Servers.keyRequired(server.copy(baseUrl = "http://192.168.1.20:8000/v1")))
        val own = server.copy(baseUrl = "http://192.168.1.20:8000/v1", modelId = "large-v3")
        assertEquals(emptyList(), EngineConfigValidator.validate(own, hasApiKey = false))
        assertEquals(listOf(ConfigIssue.ApiKeyMissing), EngineConfigValidator.validate(own.copy(baseUrl = "https://api.groq.com/openai/v1"), hasApiKey = false))
    }

    // --- the key goes only where it was given ---

    @Test
    fun `a key bound to one host is not allowed at another`() {
        val groq = KeyBinding.bind(EngineConfig.fromPreset(EnginePresets.GROQ))
        assertEquals("api.groq.com", groq.keyHost)
        assertTrue(KeyBinding.allows(groq))
        assertTrue(KeyBinding.allows(groq.copy(baseUrl = "https://API.groq.com/openai/v1")), "host case does not matter")
        val moved = groq.copy(baseUrl = "https://evil.example.com/openai/v1")
        assertFalse(KeyBinding.allows(moved))
        assertEquals(listOf(ConfigIssue.KeyForOtherAddress("api.groq.com")), EngineConfigValidator.validate(moved, hasApiKey = true))
        // Typed again for the new address, it belongs there.
        assertTrue(KeyBinding.allows(KeyBinding.bind(moved)))
    }

    @Test
    fun `a preset with a key starts bound to its own host`() {
        assertEquals("api.openai.com", EngineConfig.fromPreset(EnginePresets.OPENAI).keyHost)
        assertEquals("inference.tinfoil.sh", EngineConfig.fromPreset(EnginePresets.TINFOIL).keyHost)
    }

    @Test
    fun `an unbound key is bound to the address it is used with, a bound one stays`() {
        val unbound = EngineConfig.fromPreset(EnginePresets.GROQ).copy(keyHost = null)
        assertEquals("api.groq.com", KeyBinding.bindIfUnbound(unbound).keyHost)
        val bound = unbound.copy(keyHost = "api.groq.com", baseUrl = "https://elsewhere.example/v1")
        assertSame(bound, KeyBinding.bindIfUnbound(bound))
        val keyless = EngineConfig.fromPreset(EnginePresets.SELF_HOSTED).copy(baseUrl = "http://nas:8000/v1")
        assertEquals(null, KeyBinding.bindIfUnbound(keyless).keyHost)
    }

    // --- names ---

    @Test
    fun `a server is named after what it is`() {
        val server = Servers.newServer("server-1", null)
        assertEquals("Server", ServerNames.plainName(server, null))
        assertEquals("Groq", ServerNames.plainName(server.copy(baseUrl = "https://api.groq.com/openai/v1"), null))
        assertEquals("transcribe.example.org", ServerNames.plainName(server.copy(baseUrl = "https://www.transcribe.example.org/v1"), null))
        val pc = assertNotNull(AmberCatalog.parse(AMBER_PC_MODELS))
        assertEquals("Amber PC", ServerNames.plainName(server.copy(baseUrl = "http://192.168.1.20:8765/k/v1"), pc))
    }

    @Test
    fun `two servers of the same kind are told apart by their host`() {
        val a = Servers.newServer("server-a", null).copy(baseUrl = "http://192.168.1.20:8765/v1")
        val b = Servers.newServer("server-b", null).copy(baseUrl = "http://192.168.1.30:8765/v1")
        val pc = assertNotNull(AmberCatalog.parse(AMBER_PC_MODELS))
        val settings = base.withEngine(a).withEngine(b).copy(
            serverCatalogs = mapOf("server-a" to StoredCatalog.of(a.baseUrl!!, pc), "server-b" to StoredCatalog.of(b.baseUrl!!, pc)),
        )
        val named = ServerNames.apply(settings).engines.filter(Servers::isServer).map { it.displayName }
        assertEquals(listOf("Amber PC (192.168.1.20)", "Amber PC (192.168.1.30)"), named)
        // Tinfoil and the phone's own keep their names; nothing to change, the same settings come back.
        val again = ServerNames.apply(settings)
        assertSame(again, ServerNames.apply(again))
    }

    // --- schema 12 ---

    @Test
    fun `schema 12 binds stored keys to their address and names the servers`() {
        val old = """{"activeEngineId":"groq","engines":[
            {"id":"groq","kind":"OPENAI_COMPATIBLE","displayName":"Groq","baseUrl":"https://api.groq.com/openai/v1","modelId":"whisper-large-v3-turbo","apiKeyAlias":"key.groq"},
            {"id":"self-hosted","kind":"OPENAI_COMPATIBLE","displayName":"Own server","baseUrl":"http://192.168.1.77:8765/v1","modelId":"large-v3"},
            {"id":"tinfoil-whisper","kind":"OPENAI_COMPATIBLE","displayName":"Tinfoil (Enclave)","baseUrl":"https://inference.tinfoil.sh/v1","modelId":"voxtral-small-24b","apiKeyAlias":"key.tinfoil-whisper","requireEnclave":true}
            ],"schemaVersion":11}"""
        val migrated = SettingsCodec.decode(old, TEST_PHONE)
        val byId = migrated.engines.associateBy { it.id }
        assertEquals("api.groq.com", byId.getValue("groq").keyHost)
        assertEquals("inference.tinfoil.sh", byId.getValue("tinfoil-whisper").keyHost)
        assertEquals(null, byId.getValue("self-hosted").keyHost, "no key, nothing to bind")
        assertEquals("Groq", byId.getValue("groq").displayName)
        assertEquals("192.168.1.77", byId.getValue("self-hosted").displayName)
        assertEquals("Tinfoil (Enclave)", byId.getValue("tinfoil-whisper").displayName)
        assertEquals("groq", migrated.activeEngineId)
        assertEquals(AmethystSettings.SCHEMA_VERSION, migrated.schemaVersion)
    }

    @Test
    fun `settings already at schema 12 are left as they are`() {
        val moved = KeyBinding.bind(EngineConfig.fromPreset(EnginePresets.GROQ)).copy(baseUrl = "https://elsewhere.example/v1")
        val settings = base.withEngine(moved)
        val decoded = SettingsCodec.decode(SettingsCodec.encode(settings), TEST_PHONE)
        assertEquals("api.groq.com", decoded.engines.first { it.id == "groq" }.keyHost)
        assertNotEquals(true, KeyBinding.allows(decoded.engines.first { it.id == "groq" }))
    }
}
