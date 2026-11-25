package app.amethyst.core

import app.amethyst.core.text.LanguageGuess
import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageGuessTest {

    @Test
    fun `a tag naming one language gives its code`() {
        assertEquals("de", LanguageGuess.fromTag("de"))
        assertEquals("de", LanguageGuess.fromTag("de-DE"))
        assertEquals("de", LanguageGuess.fromTag("de_AT"))
        assertEquals("de", LanguageGuess.fromTag("german"))
        assertEquals("en", LanguageGuess.fromTag("English"))
        assertEquals("en", LanguageGuess.fromTag(" en-US "))
        assertEquals("fr", LanguageGuess.fromTag("fr"))
        // Any language the platform knows, by code or English name; script and region kept
        // where they change the writing.
        assertEquals("ja", LanguageGuess.fromTag("japanese"))
        assertEquals("sw", LanguageGuess.fromTag("sw"))
        assertEquals("yue", LanguageGuess.fromTag("yue"))
        assertEquals("zh-Hant", LanguageGuess.fromTag("zh-Hant"))
        assertEquals("pt-BR", LanguageGuess.fromTag("pt_BR"))
        assertEquals("fr", LanguageGuess.fromTag("fr-CA"))
        assertEquals("pt", LanguageGuess.baseOf("pt-BR"))
    }

    @Test
    fun `a list, nothing or something unknown gives none`() {
        assertEquals(null, LanguageGuess.fromTag("de,en"))
        assertEquals(null, LanguageGuess.fromTag(null))
        assertEquals(null, LanguageGuess.fromTag(""))
        assertEquals(null, LanguageGuess.fromTag("auto"))
        assertEquals(null, LanguageGuess.fromTag("xx-YY"))
        assertEquals(null, LanguageGuess.fromTag("klingon"))
    }

    @Test
    fun `the words decide only when they are clear`() {
        assertEquals("de", LanguageGuess.fromWords("Ich komme heute nicht, aber morgen."))
        assertEquals("en", LanguageGuess.fromWords("I think we should go there with you."))
        // Mixed: an English term in German is German.
        assertEquals("de", LanguageGuess.fromWords("Kannst du den Pull Request bis morgen reviewen, das wäre gut."))
        assertEquals("en", LanguageGuess.fromWords("See you later."))
        // Too short to tell.
        assertEquals(null, LanguageGuess.fromWords("Okay, danke."))
        assertEquals(null, LanguageGuess.fromWords("Bis gleich."))
        assertEquals(null, LanguageGuess.fromWords("Passt."))
        // Half and half.
        assertEquals(null, LanguageGuess.fromWords("ich und du and you and me und wir"))
    }

    @Test
    fun `clear words first, as the cleanup guard reads them, then detected, then the engine's one language`() {
        // An engine set to German that heard English: the prompt must not ask for German - the
        // model would translate, and the guard, going by the words, would throw the reply away.
        assertEquals("en", LanguageGuess.forDictation("See you later, I think.", null, "de-DE"))
        assertEquals("de", LanguageGuess.forDictation("Ich komme heute nicht, aber morgen.", null, "en-US"))
        assertEquals("de", LanguageGuess.forDictation("Ich komme heute nicht, aber morgen.", "english", "en-US"))
        // Words too few to tell: what the engine detected, else the one language it was set to.
        assertEquals("en", LanguageGuess.forDictation("Okay, cool.", "english", "de-DE"))
        assertEquals("de", LanguageGuess.forDictation("Okay, danke.", null, "de-DE"))
        assertEquals("de", LanguageGuess.forDictation("Ich komme heute nicht, aber morgen.", null, "de,en"))
        assertEquals(null, LanguageGuess.forDictation("Okay, danke.", null, "de,en"))
    }

    @Test
    fun `a language the few words lean away from is not named`() {
        // Short English from an engine set to German: "Reply in German" would translate it.
        assertEquals(null, LanguageGuess.forDictation("Sounds good to me.", null, "de-DE"))
        assertEquals(null, LanguageGuess.forDictation("Sounds good to me.", "german", null))
        assertEquals(null, LanguageGuess.forDictation("Okay, danke.", "english", "de-DE"))
        // Leaning the same way, or not at all: the engine's language stands.
        assertEquals("de", LanguageGuess.forDictation("Passt, danke.", null, "de-DE"))
        assertEquals("de", LanguageGuess.forDictation("Termin verschoben.", null, "de-DE"))
        assertEquals("fr", LanguageGuess.forDictation("Sounds good to me.", null, "fr"))
    }

    @Test
    fun `small words other languages share with German or English name neither`() {
        // "es", "des" are German markers, "a", "no", "do" English ones.
        assertEquals(null, LanguageGuess.fromWords("es que no es posible, es muy tarde hoy"))
        assertEquals(null, LanguageGuess.fromWords("il y a des problèmes avec des amis des enfants"))
        assertEquals(null, LanguageGuess.fromWords("eu vou a casa do meu pai no sábado"))
        assertEquals(null, LanguageGuess.fromWords("non è possibile, è troppo tardi per oggi"))
        assertEquals(null, LanguageGuess.leaning("es que no es posible, es muy tarde hoy"))
    }

    @Test
    fun `a language the recognizer detected or was set to is not overridden by German or English small words`() {
        // Each of these used to be named German or English, and the cleanup asked to translate.
        assertEquals("es", LanguageGuess.forDictation("es que no es posible, es muy tarde hoy", "es", null))
        assertEquals("fr", LanguageGuess.forDictation("il y a des problèmes avec des amis des enfants", "french", "de-DE"))
        assertEquals("pt", LanguageGuess.forDictation("eu vou a casa do meu pai no sábado", "pt", "en-US"))
        assertEquals("it", LanguageGuess.forDictation("non è possibile, è troppo tardi per oggi", null, "it-IT"))
        assertEquals("pt-BR", LanguageGuess.forDictation("eu vou a casa do meu pai no sábado", null, "pt-BR"))
        // Detected, even against clear German words: only between German and English do they decide.
        assertEquals("nl", LanguageGuess.forDictation("Ich komme heute nicht, aber morgen.", "nl", "de-DE"))
        // Nobody knows: the small words still never name German for Spanish.
        assertEquals(null, LanguageGuess.forDictation("es que no es posible, es muy tarde hoy", null, null))
        // Set to several other languages: neither German nor English, and nothing named.
        assertEquals(null, LanguageGuess.forDictation("Ich komme heute nicht, aber morgen.", null, "fr,es"))
        // Set to one other language, but the words are clearly German: a recognizer writes what it heard.
        assertEquals("de", LanguageGuess.forDictation("Ich komme heute nicht, aber morgen.", null, "fr"))
    }

    @Test
    fun `the normalizer gets German or English rules only where they may apply`() {
        assertEquals("es", LanguageGuess.forNormalizer("es que no es posible, es muy tarde hoy", "es", null))
        // Several other languages: the hint itself, which rules German and English out.
        assertEquals("fr,es", LanguageGuess.forNormalizer("es que no es posible, es muy tarde hoy", null, "fr,es"))
        // German or English may be it: nothing, the normalizer reads the words itself.
        assertEquals(null, LanguageGuess.forNormalizer("Okay, danke.", null, "de,en"))
        assertEquals(null, LanguageGuess.forNormalizer("Hallo Anna Komma ich komme um drei", null, "en-DE"))
        assertEquals(null, LanguageGuess.forNormalizer("Okay.", null, null))
        assertEquals("de", LanguageGuess.forNormalizer("Ich komme heute nicht, aber morgen.", null, "de,en"))
    }

    @Test
    fun `the words lean by their markers, however few`() {
        assertEquals("en", LanguageGuess.leaning("Sounds good to me."))
        assertEquals("de", LanguageGuess.leaning("Okay, danke."))
        assertEquals(null, LanguageGuess.leaning("Okay, cool."))
        assertEquals(null, LanguageGuess.leaning("danke, thanks"))
    }

    @Test
    fun `names for the prompt`() {
        assertEquals("German", LanguageGuess.nameOf("de"))
        assertEquals("German", LanguageGuess.nameOf("de-DE"))
        assertEquals("English", LanguageGuess.nameOf("en"))
        assertEquals("Spanish", LanguageGuess.nameOf("es"))
        assertEquals("Japanese", LanguageGuess.nameOf("ja-JP"))
        assertEquals("Swahili", LanguageGuess.nameOf("swahili"))
        assertEquals("Chinese (Traditional)", LanguageGuess.nameOf("zh-Hant"))
        assertEquals("Chinese (Simplified)", LanguageGuess.nameOf("zh-Hans"))
        assertEquals("Portuguese (Brazil)", LanguageGuess.nameOf("pt-BR"))
        assertEquals(null, LanguageGuess.nameOf("de,en"))
        assertEquals(null, LanguageGuess.nameOf(null))
        assertEquals(null, LanguageGuess.nameOf(""))
        assertEquals(null, LanguageGuess.nameOf("auto"))
        assertEquals(null, LanguageGuess.nameOf("xx"))
    }

    @Test
    fun `the language at the seam leans on a few words when nothing is sure`() {
        assertEquals("en", LanguageGuess.forSeam("The station.", detected = null, engineTag = null))
        assertEquals("de", LanguageGuess.forSeam("Zum Bahnhof.", detected = null, engineTag = "de-DE"))
        assertEquals("de", LanguageGuess.forSeam("Bahnhof.", detected = null, engineTag = "de"))
        assertEquals("fr,es", LanguageGuess.forSeam("La gare.", detected = null, engineTag = "fr,es"))
        assertEquals(null, LanguageGuess.forSeam("Bahnhof.", detected = null, engineTag = null))
    }
}
