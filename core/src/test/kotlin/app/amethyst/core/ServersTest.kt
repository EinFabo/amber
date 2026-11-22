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
    fun `a groq or openai key alone says which service it is`() {
        assertEquals(ServerProviders.GROQ, Servers.quickForKey(" gsk_abc123")?.provider)
        assertEquals(ServerProviders.OPENAI, Servers.quickForKey("sk-proj-abc")?.provider)
        assertEquals(null, Servers.quickForKey("sk-ant-api03-abc"), "an Anthropic key is no OpenAI key")
        assertEquals(null, Servers.quickForKey("abcdefghjkmnpqrstuvw"))
    }

    @Test
    fun `only openai's own key shapes go to openai, other sk- keys ask for their address`() {
        val openai = ServerProviders.OPENAI
        assertEquals(openai, Servers.quickForKey("sk-proj-Ab3_x-9Yz")?.provider)
        assertEquals(openai, Servers.quickForKey("sk-svcacct-Ab3x9Yz")?.provider)
        assertEquals(openai, Servers.quickForKey("sk-admin-Ab3x9Yz")?.provider)
        assertEquals(openai, Servers.quickForKey("sk-" + "Ab3xT3BlbkFJ9Yz".repeat(4).take(48))?.provider, "a legacy key")
        assertEquals(null, Servers.quickForKey("sk-cp-4f9a2b7c1d8e"), "a local proxy's key")
        assertEquals(null, Servers.quickForKey("sk-or-v1-0123456789abcdef0123456789abcdef"), "OpenRouter")
        assertEquals(null, Servers.quickForKey("sk-lf-0123456789abcdef"))
        assertEquals(null, Servers.quickForKey("sk-ant-api03-" + "a".repeat(48)))
        assertEquals(null, Servers.quickForKey("sk-short"))
        assertEquals(ServerProviders.GROQ, Servers.quickForKey("gsk_" + "a".repeat(52))?.provider)
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

    @Test
    fun `a key given over https does not go over http to the same host`() {
        val own = KeyBinding.bind(Servers.newServer("server-000001", null).copy(baseUrl = "https://192.168.1.20:8443/v1", modelId = "large-v3"))
        assertEquals("192.168.1.20", own.keyHost)
        assertEquals("https", own.keyScheme)
        assertTrue(KeyBinding.allows(own))
        assertTrue(KeyBinding.allows(own.copy(baseUrl = "HTTPS://192.168.1.20/v1")), "scheme case and port do not matter")
        val plain = own.copy(baseUrl = "http://192.168.1.20:8000/v1")
        assertFalse(KeyBinding.allows(plain))
        assertEquals(listOf(ConfigIssue.KeyForOtherAddress("https://192.168.1.20")), EngineConfigValidator.validate(plain, hasApiKey = true))
        // Given over http, it stays there too: no quiet move to https either way.
        val lan = KeyBinding.bind(plain)
        assertTrue(KeyBinding.allows(lan))
        assertFalse(KeyBinding.allows(lan.copy(baseUrl = "https://192.168.1.20/v1")))
        // Another host: the host is all it takes to say where the key belongs.
        assertEquals("192.168.1.20", KeyBinding.shown(lan.copy(baseUrl = "https://elsewhere.example/v1")))
    }

    @Test
    fun `a key bound before schemes were keeps working and gets the scheme it is used over`() {
        val before = EngineConfig.fromPreset(EnginePresets.GROQ).copy(keyScheme = null)
        assertTrue(KeyBinding.allows(before))
        assertTrue(KeyBinding.allows(before.copy(baseUrl = "http://api.groq.com/openai/v1")), "nothing to tell it by yet")
        val completed = KeyBinding.bindIfUnbound(before)
        assertEquals("https", completed.keyScheme)
        assertEquals("api.groq.com", completed.keyHost)
        assertFalse(KeyBinding.allows(completed.copy(baseUrl = "http://api.groq.com/openai/v1")))
        // Bound to another host: it waits to be typed again, no scheme is made up for it.
        val elsewhere = before.copy(baseUrl = "https://elsewhere.example/v1")
        assertSame(elsewhere, KeyBinding.bindIfUnbound(elsewhere))
    }

    @Test
    fun `stored settings bind every key when they are read, not only at the next change`() {
        val unbound = EngineConfig.fromPreset(EnginePresets.GROQ).copy(keyHost = null, keyScheme = null)
        val raw = SettingsCodec.encode(AmethystSettings.defaults(listOf("de-DE")).let { it.copy(engines = it.engines + unbound) })
        val read = SettingsCodec.decode(raw, listOf("de-DE")).engines.single { it.id == unbound.id }
        assertEquals("api.groq.com", read.keyHost)
        assertEquals("https", read.keyScheme)
        assertFalse(KeyBinding.allows(read.copy(baseUrl = "https://evil.example.com/openai/v1")))
    }

    @Test
    fun `a key with no address yet goes to the first one it gets, and only there`() {
        // Typed before the address: nothing to bind it to, and nothing to send it to either.
        val keyFirst = Servers.newServer("server-000003", null)
        assertEquals(null, KeyBinding.bind(keyFirst).keyHost)
        val withAddress = Servers.normalized(
            AmethystSettings.defaults(listOf("de-DE")).let { it.copy(engines = it.engines + keyFirst.copy(baseUrl = "https://pc.example/v1")) },
        ).engines.single { it.id == keyFirst.id }
        assertEquals("pc.example", withAddress.keyHost)
        assertFalse(KeyBinding.allows(withAddress.copy(baseUrl = "https://other.example/v1")))
    }

    // --- names ---

    @Test
    fun `a server is named after what it is`() {
        val server = Servers.newServer("server-1", null)
        assertEquals("Server", ServerNames.plainName(server, null))
        assertEquals("Groq", ServerNames.plainName(server.copy(baseUrl = "https://api.groq.com/openai/v1"), null))
        assertEquals("transcribe.example.org", ServerNames.plainName(server.copy(baseUrl = "https://www.transcribe.example.org/v1"), null))
        val pc = assertNotNull(AmberCatalog.parse(AMBER_PC_MODELS))
        assertEquals("Embersay PC", ServerNames.plainName(server.copy(baseUrl = "http://192.168.1.20:8765/k/v1"), pc))
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
        assertEquals(listOf("Embersay PC (192.168.1.20)", "Embersay PC (192.168.1.30)"), named)
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

    @Test
    fun `a host java's URI does not know still has a name a key is bound to`() {
        assertEquals("my_box.lan", EnginePresets.hostOf("http://my_box.lan:8000/v1"))
        assertEquals("müller-box.de", EnginePresets.hostOf("https://Müller-Box.de/v1"))
        assertEquals("api.groq.com", EnginePresets.hostOf("https://api.groq.com/openai/v1"))
        // Nothing that could name one host and reach another.
        assertEquals(null, EnginePresets.hostOf("https://evil.example\\@my_box.lan/v1"))
        assertEquals(null, EnginePresets.hostOf("https://user@my_box.lan/v1"))
        val bound = KeyBinding.bind(EngineConfig(id = "x", kind = app.amethyst.core.engine.EngineKind.OPENAI_COMPATIBLE, displayName = "x", baseUrl = "http://my_box.lan:8000/v1", apiKeyAlias = "k"))
        assertEquals("my_box.lan", bound.keyHost)
        assertFalse(KeyBinding.allows(bound.copy(baseUrl = "https://elsewhere.example/v1")))
    }

    @Test
    fun `a known service's own address needs no field, any other does`() {
        assertTrue(Servers.atKnownAddress(EnginePresets.GROQ.baseUrl))
        assertTrue(Servers.atKnownAddress(" ${EnginePresets.OPENAI.baseUrl}/ "))
        assertTrue(Servers.atKnownAddress(EnginePresets.GROQ.baseUrl!!.uppercase()))
        assertFalse(Servers.atKnownAddress("https://proxy.example.com/groq/v1"))
        assertFalse(Servers.atKnownAddress(""))
        assertFalse(Servers.atKnownAddress(null))
    }
}
