package app.amethyst.core.settings

import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.whisper.WhisperInput

/**
 * The language a new engine starts with, taken from the languages set on the phone.
 *
 * :app hands over the system's locale list as BCP-47 tags, most preferred first
 * ("en-US", "de-DE"). Only defaults come from here: an engine that is already saved keeps
 * whatever language it has.
 */
object PhoneLanguages {

    /**
     * When the phone names no usable language. Android never really does that; English is
     * what Android itself falls back to, and what most speech services know best.
     */
    const val FALLBACK_TAG = "en-US"

    /**
     * How many languages Whisper detects between. Two covers the usual mixing; every further
     * candidate is one more chance to land on the wrong language in a short dictation.
     */
    const val WHISPER_MAX_LANGUAGES = 2

    fun defaultLanguageTag(kind: EngineKind, phone: List<String>): String =
        if (kind == EngineKind.WHISPER_CPP) whisperLanguages(phone) else singleLanguage(phone)

    /** The phone's first language, for engines that take one: "en-US". */
    fun singleLanguage(phone: List<String>): String = usableTags(phone).firstOrNull() ?: FALLBACK_TAG

    /**
     * The phone's languages as Whisper's bare codes ("de,en"): one per language, in the
     * phone's order, only those Whisper knows, at most [WHISPER_MAX_LANGUAGES]. A phone with a
     * single language gets that one alone, which Whisper then always writes in.
     */
    fun whisperLanguages(phone: List<String>): String {
        val codes = usableTags(phone)
            .mapNotNull { WhisperInput.knownCode(it.substringBefore('-')) }
            .distinct()
            .take(WHISPER_MAX_LANGUAGES)
        return codes.ifEmpty { listOf(FALLBACK_TAG.substringBefore('-')) }.joinToString(",")
    }

    /**
     * The phone's tags cleaned up: underscores as hyphens, and cut before the first singleton -
     * Android 14 appends the regional preferences ("en-US-u-mu-celsius"), which no recognizer
     * wants. "und" (undetermined) and anything that is no language tag are dropped.
     */
    internal fun usableTags(phone: List<String>): List<String> =
        phone.mapNotNull { raw ->
            raw.trim()
                .replace('_', '-')
                .split('-')
                .takeWhile { it.length > 1 }
                .joinToString("-")
                .takeIf { TAG.matches(it) && !it.equals("und", ignoreCase = true) }
        }.distinct()

    private val TAG = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
}
