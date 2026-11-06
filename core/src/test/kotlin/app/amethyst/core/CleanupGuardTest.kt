package app.amethyst.core

import app.amethyst.core.settings.WritingStyle
import app.amethyst.core.text.CleanupGuard
import app.amethyst.core.text.CleanupGuard.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CleanupGuardTest {

    private fun verdict(
        original: String,
        cleaned: String,
        style: WritingStyle = WritingStyle.AS_SPOKEN,
        ownInstruction: Boolean = false,
        language: String? = null,
    ) = CleanupGuard.verdict(original, cleaned, style, ownInstruction, language)

    @Test
    fun `a plain tidy-up passes`() {
        assertEquals(Verdict.OK, verdict("ähm ich komme morgen nicht aber übermorgen", "Ich komme morgen nicht, aber übermorgen."))
        assertEquals(Verdict.OK, verdict("nur wenn es nicht regnet", "Nur wenn es nicht regnet."))
        assertEquals(Verdict.OK, verdict("ohne zucker bitte", "Ohne Zucker, bitte."))
        assertEquals(Verdict.OK, verdict("i don't think we should go there", "I don't think we should go there."))
    }

    @Test
    fun `a dropped negation is rejected`() {
        assertEquals(Verdict.NEGATION_DROPPED, verdict("ich komme morgen nicht", "Ich komme morgen."))
        assertEquals(Verdict.NEGATION_DROPPED, verdict("einen kaffee ohne zucker bitte", "Einen Kaffee mit Zucker, bitte."))
        assertEquals(Verdict.NEGATION_DROPPED, verdict("ich habe keine zeit dafür heute", "Ich habe Zeit dafür heute."))
        assertEquals(Verdict.NEGATION_DROPPED, verdict("we should never do that again", "We should do that again."))
        assertEquals(Verdict.NEGATION_DROPPED, verdict("i don't want this anymore", "I want this anymore."))
        assertEquals(Verdict.NEGATION_DROPPED, verdict("there is no milk left in the fridge", "There is milk left in the fridge."))
    }

    @Test
    fun `an added negation is rejected, a correction marker or not`() {
        assertEquals(Verdict.NEGATION_ADDED, verdict("ich komme morgen", "Ich komme morgen nicht."))
        assertEquals(Verdict.NEGATION_ADDED, verdict("um drei nee um vier komme ich", "Um vier komme ich nicht."))
        assertEquals(Verdict.NEGATION_ADDED, verdict("i can do it", "I can't do it."))
    }

    @Test
    fun `a negation that only changes its form passes`() {
        assertEquals(Verdict.OK, verdict("ich hab nicht zeit", "Ich hab keine Zeit."))
        assertEquals(CleanupGuard.negations("I do not know."), CleanupGuard.negations("I don't know."))
        assertEquals(CleanupGuard.negations("I can not come."), CleanupGuard.negations("I cannot come."))
    }

    @Test
    fun `no or nein that answers is content, not a negation to keep count of`() {
        assertEquals(0, CleanupGuard.negations("Nein, das passt."))
        assertEquals(0, CleanupGuard.negations("No, that's fine."))
        assertEquals(1, CleanupGuard.negations("I have no idea."))
        assertEquals(1, CleanupGuard.negations("Nein, ich habe keine Zeit."))
        assertEquals(Verdict.OK, verdict("nein ich habe keine zeit", "Nein, ich habe keine Zeit."))
        assertEquals(Verdict.OK, verdict("no i don't think so", "No, I don't think so."))
    }

    @Test
    fun `a self-correction may drop the taken-back negation or number`() {
        // The case from docs/OPEN.md.
        assertEquals(
            Verdict.OK,
            verdict(
                "bitte ein paar Namen raus, ohne Überprüfung erstmal. Nein, obwohl, stopp, mach mit Überprüfungen",
                "Bitte ein paar Namen raus, mit Überprüfungen.",
            ),
        )
        assertEquals(Verdict.OK, verdict("lass uns um drei treffen nee stopp um vier", "Lass uns um vier treffen."))
        assertEquals(Verdict.OK, verdict("send it at three no wait at four", "Send it at four."))
        assertEquals(Verdict.OK, verdict("ich komme um drei, nein, um vier", "Ich komme um vier."))
        assertEquals(Verdict.OK, verdict("wir brauchen zwei ich meine drei stühle", "Wir brauchen drei Stühle."))
        assertEquals(Verdict.OK, verdict("das geht nicht, quatsch, das geht doch", "Das geht doch."))
    }

    @Test
    fun `without a correction marker a dropped number is rejected`() {
        assertEquals(Verdict.NUMBER_DROPPED, verdict("wir treffen uns um drei am bahnhof", "Wir treffen uns am Bahnhof."))
        assertEquals(Verdict.NUMBER_DROPPED, verdict("das kostet zwölf euro fünfzig", "Das kostet zwölf Euro."))
        // A "Nein" at the very start answers; it takes nothing back.
        assertEquals(Verdict.NUMBER_DROPPED, verdict("nein wir kommen um acht nicht später", "Nein, wir kommen nicht später."))
    }

    @Test
    fun `a changed or added number is rejected, also after a correction marker`() {
        assertEquals(Verdict.NUMBER_ADDED, verdict("wir treffen uns um drei", "Wir treffen uns um vier."))
        assertEquals(Verdict.NUMBER_ADDED, verdict("wir treffen uns um drei", "Wir treffen uns um 4."))
        assertEquals(Verdict.NUMBER_ADDED, verdict("um drei nee stopp um vier komme ich", "Um fünf komme ich."))
        assertEquals(Verdict.NUMBER_ADDED, verdict("meet me at three", "Meet me at 3:30."))
    }

    @Test
    fun `numbers may come back as digits`() {
        assertEquals(Verdict.OK, verdict("wir treffen uns um zehn in raum drei", "Wir treffen uns um 10 in Raum 3."))
        assertEquals(Verdict.OK, verdict("am vierzehnten um halb drei in raum zweihundertvier", "Am 14. um halb drei in Raum 204."))
        assertEquals(Verdict.OK, verdict("zwei komma fünf prozent", "2,5 Prozent"))
        assertEquals(Verdict.OK, verdict("das kostet dreiundzwanzig euro", "Das kostet 23 Euro."))
        assertEquals(Verdict.OK, verdict("im jahr zweitausendsechsundzwanzig", "Im Jahr 2026."))
        assertEquals(Verdict.OK, verdict("tausend euro", "1.000 Euro"))
        assertEquals(Verdict.OK, verdict("um fünfzehn uhr dreißig", "Um 15:30 Uhr."))
        assertEquals(Verdict.OK, verdict("twenty three people came", "23 people came."))
        assertEquals(Verdict.OK, verdict("two hundred and four", "204"))
        assertEquals(Verdict.OK, verdict("on the fourteenth", "On the 14th."))
        assertEquals(Verdict.OK, verdict("es dauert zweieinhalb stunden", "Es dauert 2,5 Stunden."))
        assertEquals(Verdict.OK, verdict("ich komme um ein uhr", "Ich komme um 1 Uhr."))
        assertEquals(Verdict.OK, verdict("die dritte tür links", "Die 3. Tür links."))
        assertEquals(Verdict.OK, verdict("ruf mich unter null eins sieben sechs an", "Ruf mich unter 0176 an."))
        assertEquals(Verdict.NUMBER_ADDED, verdict("ruf mich unter null eins sieben sechs an", "Ruf mich unter 0167 an."))
    }

    @Test
    fun `number words are read as values`() {
        assertEquals(204L, CleanupGuard.numberWord("zweihundertvier"))
        assertEquals(21L, CleanupGuard.numberWord("einundzwanzig"))
        assertEquals(1100L, CleanupGuard.numberWord("tausendeinhundert"))
        assertEquals(14L, CleanupGuard.numberWord("vierzehnten"))
        assertEquals(20L, CleanupGuard.numberWord("zwanzigsten"))
        assertEquals(1L, CleanupGuard.numberWord("ersten"))
        assertEquals(20L, CleanupGuard.numberWord("twentieth"))
        for (word in listOf("stunden", "gesundheit", "unter", "winter", "seiten", "hund", "jahrhundert", "erstens", "zweitens", "mitte", "leute")) {
            assertEquals(null, CleanupGuard.numberWord(word), word)
        }
    }

    @Test
    fun `a translation is rejected as a change of language`() {
        assertEquals(Verdict.LANGUAGE, verdict("ich habe heute keine zeit für das meeting", "I have no time for the meeting today."))
        assertEquals(Verdict.LANGUAGE, verdict("I think we should go there with you", "Ich denke, wir sollten mit dir dorthin gehen."))
    }

    @Test
    fun `the language test also holds with an own instruction`() {
        assertEquals(
            Verdict.LANGUAGE,
            verdict("ich habe heute keine zeit für das meeting", "I have no time for the meeting today.", ownInstruction = true),
        )
    }

    @Test
    fun `the caller's language counts when the dictation's words are too few`() {
        // "Ich hab's" alone says nothing; the engine said German.
        assertEquals(Verdict.LANGUAGE, verdict("Ich hab's", "I've got it, you know.", language = "de", ownInstruction = true))
    }

    @Test
    fun `an English term in a German dictation is no change of language`() {
        assertEquals(
            Verdict.OK,
            verdict("kannst du den pull request bis morgen reviewen das wäre gut", "Kannst du den Pull Request bis morgen reviewen? Das wäre gut."),
        )
    }

    @Test
    fun `the own-word share with an instruction is lower, but there`() {
        val spoken = "hallo ich komme morgen gegen acht vorbei"
        assertEquals(Verdict.OWN_WORDS, verdict(spoken, "Moin, ich schaue morgen gegen acht rein."))
        assertEquals(Verdict.OK, verdict(spoken, "Moin, ich schaue morgen gegen acht rein.", ownInstruction = true))
        assertEquals(Verdict.OWN_WORDS, verdict(spoken, "Servus, schaue gegen acht morgen mal rein, bis dann.", ownInstruction = true))
    }

    @Test
    fun `a preamble the dictation did not have is rejected`() {
        assertEquals(Verdict.PREAMBLE, verdict("ich komme morgen vorbei", "Hier ist der bereinigte Text: Ich komme morgen vorbei."))
        assertEquals(Verdict.PREAMBLE, verdict("i will be there at eight", "Here is the cleaned text: I will be there at eight."))
        assertEquals(Verdict.OK, verdict("Einkaufsliste Tomaten Gurken Käse", "Einkaufsliste:\n- Tomaten\n- Gurken\n- Käse"))
        assertEquals(Verdict.OK, verdict("wir treffen uns um fünfzehn uhr dreißig im café", "Wir treffen uns um 15:30 Uhr im Café."))
    }

    @Test
    fun `lost line breaks are rejected`() {
        assertEquals(Verdict.LINE_BREAKS, verdict("Hallo Anna,\nich komme morgen.", "Hallo Anna, ich komme morgen."))
        assertEquals(Verdict.OK, verdict("Hallo Anna,\nich komme morgen.", "Hallo Anna,\nich komme morgen."))
    }

    @Test
    fun `a question stays a question, and length still counts`() {
        assertEquals(Verdict.QUESTION, verdict("Wie spät ist es?", "Es ist drei Uhr."))
        assertEquals(Verdict.LENGTH, verdict("wir treffen uns um acht beim alten bahnhof gleich neben dem kiosk", "Ja."))
        assertEquals(Verdict.EMPTY, verdict("hallo", " "))
    }

    @Test
    fun `correction markers`() {
        for (text in listOf("um drei nee um vier", "at three no wait at four", "ohne, stopp, mit", "zwei ich meine drei", "I mean four", "drei, nein, vier", "Ich komme. Nein, doch nicht.", "scratch that")) {
            assertTrue(CleanupGuard.hasCorrectionMarker(text), text)
        }
        for (text in listOf("Nein, ich habe keine Zeit.", "No, I don't think so.", "Ich komme morgen.", "Keine Zeit heute.", "Nein danke", "Neinsager gibt es immer")) {
            assertFalse(CleanupGuard.hasCorrectionMarker(text), text)
        }
    }

    @Test
    fun `whisper's spelling of a spoken stopp is a correction too`() {
        // On the phone (2026-11-06): "um zwei, nee stopp, um vier" came back from Whisper like this,
        // DeepSeek rightly kept only "um 4", and the guard threw the cleanup away.
        val heard = "Lass uns doch heute um 2 an die Stop um 4 Uhr am Bahnhof treffen"
        assertTrue(CleanupGuard.hasCorrectionMarker(heard))
        assertEquals(Verdict.OK, verdict(heard, "Lass uns doch heute um 4 Uhr am Bahnhof treffen."))
    }
}
