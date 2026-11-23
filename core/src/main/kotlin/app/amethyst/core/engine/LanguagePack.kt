package app.amethyst.core.engine

/**
 * Where Android's on-device recognition stands with the offline pack for the language it is to
 * hear. Android 13 and up answers this (the recognizer's lists, read by [of]); below, and for
 * the general recognizer that may send the voice away, there is no pack to ask about.
 */
enum class LanguagePack {
    /** On the phone: a dictation works. */
    INSTALLED,

    /** Android is downloading it. */
    DOWNLOADING,

    /** Not on the phone, but Android can fetch it: until then every dictation fails. */
    MISSING,

    /** Android's on-device recognition has no pack for this language at all. */
    UNSUPPORTED,
    ;

    companion object {
        /**
         * What the recognizer's lists of language tags say about [tag]. A tag of the same
         * region counts first, in every list, so a pack for "de-AT" that Android names but has
         * not fetched is not taken for the "de-DE" one on the phone; only then one of the same
         * language. Null when the lists give nothing to go by: all empty (a recognizer that does
         * not say), or [tag] in none and no list of what Android can fetch to rule it out.
         */
        fun of(tag: String, installed: List<String>, pending: List<String>, supported: List<String>): LanguagePack? {
            val lists = listOf(INSTALLED to installed, DOWNLOADING to pending, MISSING to supported)
            val wanted = normal(tag)
            lists.firstOrNull { (_, tags) -> tags.any { normal(it) == wanted } }?.let { return it.first }
            val language = wanted.substringBefore('-')
            lists.firstOrNull { (_, tags) -> tags.any { normal(it).substringBefore('-') == language } }?.let { return it.first }
            return UNSUPPORTED.takeIf { supported.isNotEmpty() }
        }

        /** "de_DE" and "de-de" are "de-de": Android's lists are not consistent in either. */
        private fun normal(tag: String): String = tag.trim().replace('_', '-').lowercase()
    }
}
