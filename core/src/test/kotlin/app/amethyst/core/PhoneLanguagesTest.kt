package app.amethyst.core

import app.amethyst.core.engine.ConfigIssue
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.whisper.WhisperInput
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.PhoneLanguages
import app.amethyst.core.settings.SettingsCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The phone most tests run on: German first, then English - what the defaults always were. */
internal val TEST_PHONE = listOf("de-DE", "en-US")

class PhoneLanguagesTest {

    // --- engines that take one language -------------------------------------------------

    @Test
    fun `a german phone dictates german`() {
        assertEquals("de-DE", PhoneLanguages.singleLanguage(listOf("de-DE")))
    }

    @Test
    fun `an english phone dictates english`() {
        assertEquals("en-US", PhoneLanguages.singleLanguage(listOf("en-US")))
        assertEquals("en-GB", PhoneLanguages.singleLanguage(listOf("en-GB", "de-DE")))
    }

    @Test
    fun `the first of several phone languages wins`() {
        assertEquals("de-DE", PhoneLanguages.singleLanguage(listOf("de-DE", "en-US")))
        assertEquals("en-US", PhoneLanguages.singleLanguage(listOf("en-US", "de-DE")))
    }

    @Test
    fun `a language the app does not know is passed on as it is`() {
        assertEquals("fil-PH", PhoneLanguages.singleLanguage(listOf("fil-PH")))
        assertEquals("zh-Hans-CN", PhoneLanguages.singleLanguage(listOf("zh-Hans-CN")))
    }

    @Test
    fun `no phone language falls back to english`() {
        assertEquals("en-US", PhoneLanguages.singleLanguage(emptyList()))
        assertEquals("en-US", PhoneLanguages.singleLanguage(listOf("", "und", "  ")))
    }

    @Test
    fun `regional preferences and underscores do not leak into the tag`() {
        // Android 14 adds the user's units and first day of the week as a -u- extension.
        assertEquals("en-US", PhoneLanguages.singleLanguage(listOf("en-US-u-mu-celsius-fw-mon")))
        assertEquals("de-AT", PhoneLanguages.singleLanguage(listOf("de_AT")))
    }

    @Test
    fun `nonsense is skipped for the next usable language`() {
        assertEquals("de-DE", PhoneLanguages.singleLanguage(listOf("123", "de-DE")))
    }

    // --- Whisper ------------------------------------------------------------------------

    @Test
    fun `whisper on a german phone takes german only`() {
        assertEquals("de", PhoneLanguages.whisperLanguages(listOf("de-DE")))
    }

    @Test
    fun `whisper on an english phone takes english only`() {
        assertEquals("en", PhoneLanguages.whisperLanguages(listOf("en-US")))
    }

    @Test
    fun `whisper takes the phone's languages in their order`() {
        assertEquals("de,en", PhoneLanguages.whisperLanguages(listOf("de-DE", "en-US")))
        assertEquals("en,de", PhoneLanguages.whisperLanguages(listOf("en-US", "de-DE")))
    }

    @Test
    fun `regional variants of one language count once`() {
        assertEquals("en,de", PhoneLanguages.whisperLanguages(listOf("en-US", "en-GB", "de-DE")))
    }

    @Test
    fun `whisper gets two languages at most`() {
        // Each extra candidate is one more chance to pick the wrong language on a short dictation.
        assertEquals("de,en", PhoneLanguages.whisperLanguages(listOf("de-DE", "en-US", "fr-FR", "it-IT")))
    }

    @Test
    fun `languages whisper does not know are skipped`() {
        // Swiss German has no Whisper model; Standard German behind it does.
        assertEquals("de,en", PhoneLanguages.whisperLanguages(listOf("gsw-CH", "de-CH", "en-US")))
    }

    @Test
    fun `android codes whisper spells differently are translated`() {
        assertEquals("no", PhoneLanguages.whisperLanguages(listOf("nb-NO")))
        assertEquals("tl", PhoneLanguages.whisperLanguages(listOf("fil-PH")))
        assertEquals("he", PhoneLanguages.whisperLanguages(listOf("iw-IL")))
        assertEquals("id", PhoneLanguages.whisperLanguages(listOf("in-ID")))
        assertEquals("jw", PhoneLanguages.whisperLanguages(listOf("jv-ID")))
    }

    @Test
    fun `scripts and regions do not matter to whisper`() {
        assertEquals("zh,en", PhoneLanguages.whisperLanguages(listOf("zh-Hant-TW", "en-US-u-mu-celsius")))
    }

    @Test
    fun `whisper falls back to english when nothing usable is left`() {
        assertEquals("en", PhoneLanguages.whisperLanguages(emptyList()))
        assertEquals("en", PhoneLanguages.whisperLanguages(listOf("gsw-CH")))
    }

    @Test
    fun `whisper's default is the format whisper reads`() {
        val list = PhoneLanguages.whisperLanguages(listOf("fr-FR", "de-DE"))
        assertEquals(list, WhisperInput.languageFor(list))
    }

    // --- where the defaults land ----------------------------------------------------------

    @Test
    fun `new engines take their default from the phone`() {
        val phone = listOf("en-US", "de-DE")
        assertEquals("en-US", EngineConfig.defaultLanguageTag(EngineKind.ANDROID_ON_DEVICE, phone))
        assertEquals("en-US", EngineConfig.defaultLanguageTag(EngineKind.OPENAI_COMPATIBLE, phone))
        assertEquals("en,de", EngineConfig.defaultLanguageTag(EngineKind.WHISPER_CPP, phone))
    }

    @Test
    fun `the first launch engine dictates in the phone's language`() {
        val english = AmethystSettings.defaults(listOf("en-US"))
        assertEquals("en-US", english.activeEngine?.languageTag)
        val german = AmethystSettings.defaults(listOf("de-DE", "en-US"))
        assertEquals("de-DE", german.activeEngine?.languageTag)
    }

    @Test
    fun `every default passes the engine check`() {
        val phones = listOf(
            emptyList(),
            listOf("de-DE"),
            listOf("en-US-u-mu-celsius"),
            listOf("zh-Hans-CN", "fil-PH"),
            listOf("gsw-CH"),
        )
        for (phone in phones) {
            for (preset in listOf(EnginePresets.ANDROID_ON_DEVICE, EnginePresets.LOCAL_WHISPER, EnginePresets.GROQ)) {
                val config = EngineConfig.fromPreset(preset, EngineConfig.defaultLanguageTag(preset.kind, phone))
                val problems = EngineConfigValidator.validate(config, hasApiKey = true)
                assertTrue(problems.none { it is ConfigIssue.InvalidLanguageCode }, "$phone on ${preset.id}: $problems")
            }
        }
    }

    @Test
    fun `nothing saved yet decodes to the phone's language`() {
        assertEquals("en-US", SettingsCodec.decode("", listOf("en-US")).activeEngine?.languageTag)
    }

    @Test
    fun `saved engines keep their language on a phone set to another one`() {
        val saved = AmethystSettings.defaults(listOf("de-DE"))
            .withEngine(EngineConfig.fromPreset(EnginePresets.LOCAL_WHISPER, "de,en"))
        val decoded = SettingsCodec.decode(SettingsCodec.encode(saved), listOf("en-US"))
        assertEquals(saved, decoded)
    }

    @Test
    fun `the version 1 migration does not depend on the phone`() {
        val old = AmethystSettings(
            engines = listOf(
                EngineConfig.fromPreset(EnginePresets.LOCAL_WHISPER, "de-DE"),
                EngineConfig.fromPreset(EnginePresets.ANDROID_ON_DEVICE, "de-DE"),
            ),
            schemaVersion = 1,
        )
        val migrated = SettingsCodec.decode(SettingsCodec.encode(old), listOf("fr-FR"))
        assertEquals(listOf("de,en", "de-DE"), migrated.engines.map { it.languageTag })
    }
}
