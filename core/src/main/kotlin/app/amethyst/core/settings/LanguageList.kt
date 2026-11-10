package app.amethyst.core.settings

import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.whisper.WhisperInput

/** How a speech recognition takes the languages it is given. */
enum class LanguageUse {
    /**
     * A list, most likely first: Whisper and an Amber server decide among them per dictation;
     * any other server gets one when one is picked and detects by itself for several.
     */
    SEVERAL,

    /** One: Android's speech recognition gets the first, or the phone's language for none. */
    FIRST,

    /** None: it detects the language itself (Parakeet). No languages are shown. */
    DETECTS_ITSELF,
}

/**
 * The one way to read and change a speech recognition's languages ([EngineConfig.languageTag]).
 *
 * Stored as before, so an upgrade changes nothing: a comma-separated list of BCP-47 tags or
 * bare codes ("de,en", "en-US"), empty or null for automatic. A list holds bare codes for the
 * kinds that take [LanguageUse.SEVERAL] ("de") and whole tags for Android ("de-DE"), where the
 * region picks the recognizer's variant. Defaults come from the phone ([PhoneLanguages]).
 */
object LanguageList {

    /**
     * Offered as chips besides the phone's languages and the ones picked; every other language
     * Whisper knows is behind "More languages" ([more]).
     */
    val COMMON = listOf("de", "en", "fr", "es", "it", "nl", "pl", "tr")

    fun use(kind: EngineKind): LanguageUse = when (kind) {
        EngineKind.WHISPER_CPP, EngineKind.OPENAI_COMPATIBLE -> LanguageUse.SEVERAL
        EngineKind.ANDROID_ON_DEVICE -> LanguageUse.FIRST
        EngineKind.LOCAL_SHERPA, EngineKind.ELEVENLABS_REALTIME -> LanguageUse.DETECTS_ITSELF
    }

    /** [config]'s languages, most likely first; empty for automatic and for one that detects itself. */
    fun of(config: EngineConfig): List<String> = parse(config.languageTag, use(config.kind))

    /** [stored] read as a list for [use]: cleaned, without doubles, without what is no language. */
    fun parse(stored: String?, use: LanguageUse): List<String> {
        if (use == LanguageUse.DETECTS_ITSELF) return emptyList()
        val tags = PhoneLanguages.usableTags(stored.orEmpty().split(','))
        return when (use) {
            LanguageUse.SEVERAL -> tags.map(::code).distinct()
            // Two variants of one language are no choice Android can use: the first stays.
            else -> tags.distinctBy(::code)
        }
    }

    /** [config] with [languages], most likely first; for [LanguageUse.FIRST] only the first is kept. */
    fun with(config: EngineConfig, languages: List<String>): EngineConfig {
        val use = use(config.kind)
        if (use == LanguageUse.DETECTS_ITSELF) return config
        val list = parse(languages.joinToString(","), use).let { if (use == LanguageUse.FIRST) it.take(1) else it }
        return config.copy(languageTag = list.joinToString(",").ifEmpty { null })
    }

    /**
     * [languages] after a tap on [tag]: picked, it goes; not picked, it is added at the end - so
     * the order of picking is the order of preference. For [LanguageUse.FIRST] a tap picks that
     * one alone, and a tap on the one picked leaves none (the phone's language).
     */
    fun toggled(languages: List<String>, tag: String, use: LanguageUse): List<String> {
        val picked = languages.indexOfFirst { code(it) == code(tag) }
        return when {
            picked >= 0 -> languages.filterIndexed { i, _ -> i != picked }
            use == LanguageUse.FIRST -> listOf(tag)
            else -> languages + tag
        }
    }

    /** What [config] is handed for one dictation ([app.amethyst.core.engine.TranscribeOptions.languageTag]). */
    fun forDictation(config: EngineConfig): String? {
        val list = of(config)
        return when (use(config.kind)) {
            LanguageUse.DETECTS_ITSELF -> null
            LanguageUse.FIRST -> list.firstOrNull()
            LanguageUse.SEVERAL -> list.joinToString(",").ifEmpty { null }
        }
    }

    /** The languages a new [kind] starts with, from the [phone]'s ([PhoneLanguages]). */
    fun defaults(kind: EngineKind, phone: List<String>): List<String> =
        parse(PhoneLanguages.defaultLanguageTag(kind, phone), use(kind))

    /** Whether [config] has the languages it would start with on this [phone]. */
    fun isDefault(config: EngineConfig, phone: List<String>): Boolean =
        of(config) == defaults(config.kind, phone)

    /**
     * The chips to show: the ones picked, in their order, then the phone's languages and the
     * [COMMON] ones not picked. For [LanguageUse.FIRST] the phone's own tags ("en-GB" rather
     * than "en"), so Android hears the variant the phone is set to.
     */
    fun offered(picked: List<String>, use: LanguageUse, phone: List<String>): List<String> {
        if (use == LanguageUse.DETECTS_ITSELF) return emptyList()
        val phoneTags = PhoneLanguages.usableTags(phone).let { tags ->
            if (use == LanguageUse.SEVERAL) tags.mapNotNull { WhisperInput.knownCode(code(it)) } else tags
        }
        return (picked + phoneTags + COMMON).distinctBy(::code)
    }

    /** Every other language Whisper knows, for "More languages": not in [shown]. */
    fun more(shown: List<String>): List<String> {
        val taken = shown.map(::code).toSet()
        return WhisperInput.KNOWN_LANGUAGES.filter { it !in taken }
    }

    /** The language of [tag] alone, as Whisper spells it where it knows it: "de-DE" -> "de", "iw" -> "he". */
    fun code(tag: String): String {
        val primary = tag.trim().substringBefore('-').substringBefore('_').lowercase()
        return WhisperInput.knownCode(primary) ?: primary
    }
}
