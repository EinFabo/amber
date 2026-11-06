package app.amethyst.core

import app.amethyst.core.text.GermanCommas
import app.amethyst.core.text.NormalizeOptions
import app.amethyst.core.text.TranscriptNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals

/** The comma before "und", "oder", "sowie": out where Duden has none, kept wherever it may close something. */
class JoinerCommasTest {

    private fun dropped(text: String) = GermanCommas.dropBeforeJoiners(text)

    private fun kept(text: String) = assertEquals(text, GermanCommas.dropBeforeJoiners(text), "should stay: $text")

    @Test
    fun `a list takes no comma before its last item`() {
        assertEquals("Ich kaufe Tomaten, Gurken und Käse.", dropped("Ich kaufe Tomaten, Gurken, und Käse."))
        assertEquals("Tomaten, Gurken und Käse.", dropped("Tomaten, Gurken, und Käse."))
        assertEquals("Wir brauchen Mehl, Eier, Milch und Zucker.", dropped("Wir brauchen Mehl, Eier, Milch, und Zucker."))
        assertEquals("Wir brauchen Mehl, frische Eier und Zucker.", dropped("Wir brauchen Mehl, frische Eier, und Zucker."))
        assertEquals("Er kam, sah und siegte.", dropped("Er kam, sah, und siegte."))
        assertEquals("Rot, grün oder blau?", dropped("Rot, grün, oder blau?"))
        assertEquals("Wir brauchen Mehl, Eier sowie Zucker.", dropped("Wir brauchen Mehl, Eier, sowie Zucker."))
        assertEquals("Seite 1, 2 und 3.", dropped("Seite 1, 2, und 3."))
    }

    @Test
    fun `two main clauses or a list of two need no comma`() {
        assertEquals("Ich komme morgen und du bringst den Kuchen mit.", dropped("Ich komme morgen, und du bringst den Kuchen mit."))
        assertEquals("Wir können ins Kino gehen oder wir bleiben zu Hause.", dropped("Wir können ins Kino gehen, oder wir bleiben zu Hause."))
        assertEquals("Ich habe kein Brot und keine Butter.", dropped("Ich habe kein Brot, und keine Butter."))
        assertEquals("Ich kaufe Tomaten und Käse.", dropped("Ich kaufe Tomaten, und Käse."))
        // Each sentence on its own.
        assertEquals("Das passt. Ich komme morgen und du bringst Kuchen.", dropped("Das passt. Ich komme morgen, und du bringst Kuchen."))
    }

    @Test
    fun `a comma that closes a subordinate clause, an infinitive group or a relative clause stays`() {
        kept("Er sagt, dass er kommt, und geht.")
        kept("Ich weiß, dass er kommt, und sie geht.")
        kept("Er kommt, wenn er Zeit hat, und bringt Kuchen mit.")
        kept("Ich gehe raus, um einzukaufen, und komme gleich wieder.")
        kept("Ich habe versucht anzurufen, und dann kam der Bus.")
        kept("Der Mann, der dort steht, und seine Frau kommen.")
        kept("Das Buch, das du mir gegeben hast, und der Brief sind weg.")
        kept("Wenn du kommst, und wenn sie geht, ist alles gut.")
        kept("Ob er kommt, und wann, weiß keiner.")
        kept("Je länger ich warte, und je mehr ich nachdenke, desto besser.")
        kept("Ich weiß nicht ob er kommt, und sie auch nicht.")
    }

    @Test
    fun `an apposition or an insertion keeps its closing comma`() {
        kept("Mein Bruder, ein Arzt, und meine Schwester kommen.")
        kept("Er kommt, vermutlich, und bringt Kuchen mit.")
        kept("Er hat, glaube ich, und das ist sicher, gekündigt.")
        kept("Er hat, und das wusste niemand, schon gekündigt.")
        kept("Das Haus (das alte), und der Garten.")
        kept("Er sagte: „Ich komme“, und ging.")
        kept("Wir brauchen Brot, Butter, und, wenn du willst, Käse.")
    }

    @Test
    fun `an interjection or address at the start keeps its comma`() {
        kept("Ja, und dann?")
        kept("Okay, und jetzt?")
        kept("Anna, und du?")
        kept("Na gut, und du?")
        kept("Hallo liebe Anna, und wie geht es dir?")
        kept("Ich komme, und du?")
    }

    @Test
    fun `an addition after the joiner keeps the comma`() {
        kept("Ich komme morgen, und zwar um acht.")
        kept("Er hilft immer gern, und das ohne zu fragen.")
        kept("Kommst du heute noch, oder nicht?")
        kept("Das ist doch gut so, oder?")
        kept("Ich komme morgen vorbei, oder besser übermorgen.")
    }

    @Test
    fun `when something follows that a comma might close, it stays`() {
        kept("Ich komme morgen, und du bringst Kuchen mit, wenn du magst.")
        kept("Ich komme morgen, und wenn es regnet, bleibe ich zu Hause.")
    }

    @Test
    fun `text without such a comma is left as it is`() {
        kept("Ich kaufe Tomaten, Gurken und Käse.")
        kept("Und dann kam er.")
        kept("Ich komme und du gehst.")
        kept("und,")
        kept("")
    }

    @Test
    fun `the normalizer drops them in German text only, and only with German commas on`() {
        assertEquals("Ich kaufe Tomaten, Gurken und Käse.", TranscriptNormalizer.normalize("Ich kaufe Tomaten, Gurken, und Käse.", language = "de"))
        // After a cleanup, line by line.
        assertEquals(
            "Ich brauche Tomaten, Gurken und Käse.\nUnd dann noch Milch, Brot und Eier.",
            TranscriptNormalizer.normalize("Ich brauche Tomaten, Gurken, und Käse.\nUnd dann noch Milch, Brot, und Eier.", language = "de", keepLineBreaks = true),
        )
        assertEquals("We need apples, pears, and bananas.", TranscriptNormalizer.normalize("We need apples, pears, and bananas.", language = "en"))
        assertEquals(
            "Ich kaufe Tomaten, Gurken, und Käse.",
            TranscriptNormalizer.normalize("Ich kaufe Tomaten, Gurken, und Käse.", NormalizeOptions(germanCommas = false), language = "de"),
        )
        // The commas apply() adds still come first, and stay.
        assertEquals("Er sagt, dass er kommt, und geht.", TranscriptNormalizer.normalize("Er sagt dass er kommt, und geht.", language = "de"))
    }
}
