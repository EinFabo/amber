package app.amethyst.core

import app.amethyst.core.text.GermanCommas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GermanCommasTest {

    private fun commas(text: String) = GermanCommas.apply(text)

    @Test
    fun `a subordinate clause gets its comma`() {
        assertEquals("Ich glaube, dass er morgen kommt.", commas("Ich glaube dass er morgen kommt."))
        assertEquals("Wir bleiben drinnen, weil es regnet.", commas("Wir bleiben drinnen weil es regnet."))
        assertEquals("Sag Bescheid, wenn du da bist.", commas("Sag Bescheid wenn du da bist."))
        assertEquals("Ich weiß nicht, ob das klappt.", commas("Ich weiß nicht ob das klappt."))
        assertEquals("Er kam, obwohl er krank war.", commas("Er kam obwohl er krank war."))
        assertEquals("Ruf an, bevor du losfährst.", commas("Ruf an bevor du losfährst."))
    }

    @Test
    fun `the words that belong to the conjunction go behind the comma`() {
        assertEquals("Ich komme, auch wenn es spät wird.", commas("Ich komme auch wenn es spät wird."))
        assertEquals("Er ging, ohne dass jemand es merkte.", commas("Er ging ohne dass jemand es merkte."))
        assertEquals("Es regnete, so dass wir blieben.", commas("Es regnete so dass wir blieben."))
        assertEquals("Ich mache das, nur um dich zu ärgern.", commas("Ich mache das nur um dich zu ärgern."))
        assertEquals("Pass auf, besonders wenn es glatt ist.", commas("Pass auf besonders wenn es glatt ist."))
        // "aber wenn": the comma goes before "aber", the one that joins.
        assertEquals("Man kann es trinken, aber wenn du krank bist, lieber nicht.", commas("Man kann es trinken aber wenn du krank bist, lieber nicht."))
    }

    @Test
    fun `an infinitive group with um, ohne or anstatt gets its comma`() {
        assertEquals("Ich gehe raus, um einzukaufen.", commas("Ich gehe raus um einzukaufen."))
        assertEquals("Sie kam, um uns zu helfen.", commas("Sie kam um uns zu helfen."))
        assertEquals("Er ging, ohne zu fragen.", commas("Er ging ohne zu fragen."))
    }

    @Test
    fun `um and ohne as prepositions stay as they are`() {
        for (text in listOf(
            "Wir treffen uns um 10 Uhr am Bahnhof.",
            "Wir treffen uns um acht Uhr.",
            "Der Laden liegt gleich um die Ecke.",
            "Gepäck ohne einen Passagier zu versenden ist verboten.",
            "Ich trinke Kaffee ohne Zucker.",
            "Rund um die alte Stadt gibt es viel zu sehen.",
            "Er machte Werbung ohne Genehmigung anzufragen.",
        )) {
            assertEquals(text, commas(text))
        }
    }

    @Test
    fun `sondern, a joining aber and während with a subject`() {
        assertEquals("Nicht heute, sondern morgen.", commas("Nicht heute sondern morgen."))
        assertEquals("Ich wollte kommen, aber ich war krank.", commas("Ich wollte kommen aber ich war krank."))
        assertEquals("Er las, während sie kochte.", commas("Er las während sie kochte."))
    }

    @Test
    fun `particles and prepositions that only look like conjunctions are left alone`() {
        for (text in listOf(
            "Das ist aber schön.",
            "Nach und nach setzte sich aber die Erkenntnis durch.",
            "Während der Fahrt schlief sie.",
            "Er schlief während der Fahrt.",
            "Je nachdem wie das Wetter wird.",
            "Wir fahren zu den Victoria Falls im Sommer.",
        )) {
            assertEquals(text, commas(text))
        }
    }

    @Test
    fun `existing punctuation, sentence starts and joiners are respected`() {
        for (text in listOf(
            "Ich glaube, dass er kommt.",
            "Wenn du kommst bring Kuchen mit.",
            "Auch wenn es regnet gehen wir.",
            "Er sagt, dass es regnet und dass es kalt ist.",
            "Er sagt: \"Obwohl es regnet, gehen wir.\"",
            "Das war es. Weil es regnet, bleiben wir.",
            "Sie fragt (falls du kommst) nach dir.",
            "Es hat ein Gehirn (selbst wenn es klein ist) wie wir.",
        )) {
            assertEquals(text, commas(text))
        }
    }

    @Test
    fun `several clauses in one sentence each get theirs`() {
        assertEquals(
            "Ich hoffe, dass du kommst, weil ich dich vermisse.",
            commas("Ich hoffe dass du kommst weil ich dich vermisse."),
        )
    }

    @Test
    fun `a conjunction at the very end is no clause yet`() {
        assertEquals("Ich weiß nicht ob", commas("Ich weiß nicht ob"))
    }

    @Test
    fun `German is recognised by the engine's language or by its words`() {
        val text = "Ich glaube dass er kommt"
        assertTrue(GermanCommas.isGerman("de", text))
        assertTrue(GermanCommas.isGerman("de-DE", text))
        assertTrue(GermanCommas.isGerman("german", text))
        assertFalse(GermanCommas.isGerman("en", text), "a set language wins over the words")
        assertTrue(GermanCommas.isGerman("de,en", text))
        assertFalse(GermanCommas.isGerman("de,en", "I think that he is coming tomorrow"))
        assertTrue(GermanCommas.isGerman(null, text))
        assertFalse(GermanCommas.isGerman(null, "I think that he is coming tomorrow"))
        assertFalse(GermanCommas.isGerman(null, "Okay"))
    }
}
