package app.amethyst.core

import app.amethyst.core.engine.AmberAddress
import app.amethyst.core.engine.AmberCatalog
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.KeyBinding
import app.amethyst.core.engine.Servers
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.StoredCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AmberAddressTest {

    private val key = "abcdefghjkmnpqrstuvw"

    @Test
    fun `an old amber address is split into address and key`() {
        assertEquals(AmberAddress.Split("http://192.168.1.20:8765/v1", key), AmberAddress.split("http://192.168.1.20:8765/$key/v1"))
        assertEquals(AmberAddress.Split("https://amber.example.com/v1", key), AmberAddress.split(" https://amber.example.com/$key/v1/ "))
        assertEquals(AmberAddress.Split("http://pc:8765/v1", key), AmberAddress.split("http://pc:8765/$key"))
    }

    @Test
    fun `an ordinary address is left alone`() {
        assertNull(AmberAddress.split("https://api.groq.com/openai/v1"))
        assertNull(AmberAddress.split("http://192.168.1.20:8000/v1"))
        assertNull(AmberAddress.split("http://nas:8000/${key}x/v1"), "21 characters is not a key")
        assertNull(AmberAddress.split("http://nas:8000/$key/api"), "not followed by v1")
        assertNull(AmberAddress.split("http://nas:8000/ABCDEFGHJKMNPQRSTUVW/v1"), "Amber keys are lowercase")
        assertNull(AmberAddress.split(""))
    }

    @Test
    fun `a pairing link gives both, also with an old address inside`() {
        assertEquals(
            AmberAddress.Split("https://amber.example.com/v1", key),
            AmberAddress.split("amber://server?url=https%3A%2F%2Famber.example.com%2Fv1&key=$key"),
        )
        assertEquals(
            AmberAddress.Split("http://pc:8765/v1", key),
            AmberAddress.split("amber://server?url=http%3A%2F%2Fpc%3A8765%2F$key%2Fv1&key=$key"),
        )
        assertNull(AmberAddress.split("amber://server?url=javascript%3Aalert(1)&key=$key"))
        assertNull(AmberAddress.split("amber://other?url=https%3A%2F%2Fa.b%2Fv1&key=$key"))
    }

    @Test
    fun `a stored amber server moves its key out of the address, keeping its model list`() {
        val old = "http://192.168.1.20:8765/$key/v1"
        val pc = assertNotNull(AmberCatalog.parse(AMBER_PC_MODELS))
        val own = EngineConfig.fromPreset(EnginePresets.SELF_HOSTED).copy(baseUrl = old, modelId = "large-v3")
        val settings = AmethystSettings.defaults(TEST_PHONE).withEngine(own)
            .copy(serverCatalogs = mapOf(own.id to StoredCatalog.of(old, pc)))
        val (moved, moves) = AmberAddress.movePathKeys(settings)
        assertEquals(listOf(AmberAddress.Move("self-hosted", "key.self-hosted", key)), moves)
        val engine = moved.engines.first { it.id == "self-hosted" }
        assertEquals("http://192.168.1.20:8765/v1", engine.baseUrl)
        assertEquals("key.self-hosted", engine.apiKeyAlias)
        assertEquals("192.168.1.20", engine.keyHost)
        assertTrue(KeyBinding.allows(engine))
        assertEquals(pc.speech, moved.catalogFor(engine)?.speech, "the list stays with the new address")
        // Done once: nothing left to move.
        assertEquals(emptyList(), AmberAddress.movePathKeys(moved).second)
    }

    @Test
    fun `a server that is not known to be amber keeps its address`() {
        val other = Servers.newServer("server-1", null).copy(baseUrl = "http://nas:8000/$key/v1")
        val settings = AmethystSettings.defaults(TEST_PHONE).withEngine(other)
        assertSame(settings, AmberAddress.movePathKeys(settings).first)
    }

    @Test
    fun `every server gets a place for a key`() {
        val own = EngineConfig.fromPreset(EnginePresets.SELF_HOSTED).copy(baseUrl = "http://nas:8000/v1")
        val normalized = Servers.normalized(AmethystSettings.defaults(TEST_PHONE).withEngine(own))
        assertEquals("key.self-hosted", normalized.engines.first { it.id == "self-hosted" }.apiKeyAlias)
    }
}
