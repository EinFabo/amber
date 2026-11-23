package app.amethyst.core

import app.amethyst.core.engine.LanguagePack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanguagePackTest {

    private val supported = listOf("de-DE", "en-US", "en-GB", "fr-FR")

    @Test
    fun `the pack on the phone, on its way, or still to fetch`() {
        assertEquals(LanguagePack.INSTALLED, LanguagePack.of("de-DE", installed = listOf("de-DE"), pending = emptyList(), supported = supported))
        assertEquals(LanguagePack.DOWNLOADING, LanguagePack.of("de-DE", installed = listOf("en-US"), pending = listOf("de-DE"), supported = supported))
        assertEquals(LanguagePack.MISSING, LanguagePack.of("de-DE", installed = listOf("en-US"), pending = emptyList(), supported = supported))
    }

    @Test
    fun `android's spelling of a tag does not matter`() {
        assertEquals(LanguagePack.INSTALLED, LanguagePack.of("de-DE", installed = listOf("de_de"), pending = emptyList(), supported = emptyList()))
        assertEquals(LanguagePack.MISSING, LanguagePack.of(" en-us", installed = emptyList(), pending = emptyList(), supported = listOf("EN-US")))
    }

    @Test
    fun `the same region counts before the same language`() {
        // British English named but not fetched: the American pack on the phone is not it.
        assertEquals(LanguagePack.MISSING, LanguagePack.of("en-GB", installed = listOf("en-US"), pending = emptyList(), supported = supported))
        // A region Android does not name: its language's pack does.
        assertEquals(LanguagePack.INSTALLED, LanguagePack.of("de-AT", installed = listOf("de-DE"), pending = emptyList(), supported = supported))
        assertEquals(LanguagePack.MISSING, LanguagePack.of("de-CH", installed = emptyList(), pending = emptyList(), supported = supported))
        assertEquals(LanguagePack.MISSING, LanguagePack.of("de", installed = emptyList(), pending = emptyList(), supported = supported))
    }

    @Test
    fun `a language android cannot fetch`() {
        assertEquals(LanguagePack.UNSUPPORTED, LanguagePack.of("sw-KE", installed = listOf("en-US"), pending = emptyList(), supported = supported))
    }

    @Test
    fun `a recognizer that says nothing to go by gets no answer`() {
        assertNull(LanguagePack.of("de-DE", installed = emptyList(), pending = emptyList(), supported = emptyList()))
        // Only what is on the phone, and the language not among it: perhaps it can be fetched.
        assertNull(LanguagePack.of("de-DE", installed = listOf("en-US"), pending = emptyList(), supported = emptyList()))
    }
}
