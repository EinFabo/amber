package app.amethyst.core.dictation

import app.amethyst.core.net.Redact
import app.amethyst.core.stats.countWords
import java.text.Normalizer

/**
 * A Diagnostics line without what was said, for a copy that gets shared and for logcat: the
 * words of a dictation or a command, a field's text or a server's answer may have ended up in an
 * error, a note or a command's steps. App names, timings and reasons ("negation dropped") stay -
 * they are what a report is for.
 *
 * Taken out, in this order:
 * - an address's path, query and user info ([Redact.text]): a server only as its host
 * - a server's answer after "HTTP 400: ": its length instead - an error body may quote the text
 * - a command's "words: …" line: how many instead
 * - whatever stands in quotes ("…", “…”, „…“, «…», ‘…’), up to the line's end when a quote is
 *   not closed (a body cut off): the rules and the commands quote what was heard
 * - wherever two words in a row of [line]'s `texts` show up, unquoted
 */
object Anonymize {

    /** [line] without words; [texts] are the dictations' and commands' own words to look for. */
    fun line(line: String, texts: Collection<String> = emptyList()): String = lines(listOf(line), texts).single()

    /** The same for many lines, against [texts] read once. */
    fun lines(lines: List<String>, texts: Collection<String>): List<String> {
        val phrases = Phrases.of(texts)
        return lines.map { ownWords(quotes(wordsLine(answerBodies(Redact.text(it)))), phrases) }
    }

    private val ANSWER = Regex("""\b(HTTP \d{3}): (.*)$""")

    private fun answerBodies(line: String): String = ANSWER.replace(line) { match ->
        val (status, body) = match.destructured
        if (body.isBlank()) status else "$status: answer of ${body.length} characters"
    }

    /** The command trace's "words: …" line (VoiceCommands.explain). */
    private val WORDS = Regex("""^(\s*words: )(.+)$""")

    private fun wordsLine(line: String): String = WORDS.replace(line) { match ->
        val (head, words) = match.destructured
        if (words == "(none)") match.value else head + countWords(words)
    }

    /** Opening quote -> the quotes that close it. */
    private val CLOSERS = mapOf(
        '"' to "\"",
        '“' to "”",
        '„' to "“”",
        '«' to "»",
        '»' to "«",
        '‘' to "’",
        '‚' to "‘’",
    )

    private fun quotes(line: String): String {
        if (line.none { it in CLOSERS }) return line
        val out = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            val c = line[i]
            val closers = CLOSERS[c]
            if (closers == null) {
                out.append(c)
                i++
                continue
            }
            val end = (i + 1 until line.length).firstOrNull { line[it] in closers }
            out.append(c).append(HIDDEN)
            if (end == null) break
            out.append(line[end])
            i = end + 1
        }
        return out.toString()
    }

    private val TOKEN = Regex("""[\p{L}\p{N}]+""")
    private val MARKS = Regex("""\p{Mn}+""")

    /** Lower case, accents gone: "Öffne" and the rules' "offne" are one word. */
    private fun normal(word: String): String =
        MARKS.replace(Normalizer.normalize(word, Normalizer.Form.NFD), "").lowercase()

    /** Two words in a row from the texts; at least one of them a word, not a number or a letter. */
    private class Phrases(val pairs: Set<String>) {
        companion object {
            fun of(texts: Collection<String>): Phrases = Phrases(
                texts.flatMapTo(HashSet()) { text ->
                    TOKEN.findAll(text).map { normal(it.value) }.zipWithNext()
                        .filter { (a, b) -> wordy(a) || wordy(b) }
                        .map { (a, b) -> "$a $b" }
                        .toList()
                },
            )

            private fun wordy(token: String) = token.count { it.isLetter() } >= 2
        }
    }

    /** At most this between two words of a phrase found in a line: a space, a comma, " · ". */
    private const val MAX_GAP = 3

    private fun ownWords(line: String, phrases: Phrases): String {
        if (phrases.pairs.isEmpty()) return line
        val tokens = TOKEN.findAll(line).toList()
        val spans = ArrayList<IntRange>()
        for (i in 0 until tokens.size - 1) {
            val a = tokens[i]
            val b = tokens[i + 1]
            if (b.range.first - a.range.last - 1 > MAX_GAP) continue
            if ("${normal(a.value)} ${normal(b.value)}" !in phrases.pairs) continue
            val last = spans.lastOrNull()
            if (last != null && a.range.first <= last.last) spans[spans.size - 1] = last.first..b.range.last
            else spans += a.range.first..b.range.last
        }
        if (spans.isEmpty()) return line
        return buildString {
            var at = 0
            for (span in spans) {
                append(line, at, span.first)
                append(HIDDEN)
                at = span.last + 1
            }
            append(line, at, line.length)
        }
    }

    private const val HIDDEN = "…"
}
