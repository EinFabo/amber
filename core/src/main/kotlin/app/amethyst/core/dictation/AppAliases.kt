package app.amethyst.core.dictation

/**
 * The apps a shared Diagnostics report names, each as "app 1", "app 2" ... in the order the
 * report first names them. A report goes to a chat or a bug tracker; which apps someone dictates
 * into is theirs - but "the same app again" is often the clue, so every name keeps one alias for
 * the whole report. The screen keeps the names: it is the phone's owner who reads it.
 *
 * Names come from the records: the package a dictation went into, the label its app has in
 * Amber ("WhatsApp's own speech recognition" in the timing) - which takes the alias of that
 * package - and the packages the field trace names ("EditText in com.whatsapp", "input
 * connection (com.whatsapp)"), whichever app's field it found.
 */
class AppAliases(records: List<DictationRecord>) {

    private val aliases = HashMap<String, String>()

    /** A label -> the package it was seen with, so both read as the same app. */
    private val packageOf = HashMap<String, String>()

    private var count = 0

    init {
        for (record in records) {
            val pkg = record.targetPackage ?: continue
            record.appLabel?.let { packageOf.putIfAbsent(it, pkg) }
        }
    }

    /** "app N" for [name] - a package or a label - the same one every time in this report. */
    fun of(name: String): String {
        val key = packageOf[name] ?: name
        return aliases.getOrPut(key) { "app ${++count}" }
    }

    /** [lines] of [record] with every app it names as its alias. */
    fun lines(record: DictationRecord, lines: List<String>): List<String> {
        val known = listOfNotNull(record.targetPackage, record.appLabel)
        return lines.map { line(it, known) }
    }

    private fun line(line: String, known: List<String>): String {
        val names = (known + TRACED.findAll(line).map { it.value }).filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return line
        // Longest first, so "com.whatsapp.w4b" is not read as "com.whatsapp" and a tail.
        val pattern = names.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        return Regex("""(?<![\p{L}\p{N}_.])(?:$pattern)(?![\p{L}\p{N}_]|\.[\p{L}\p{N}])""").replace(line) { of(it.value) }
    }

    private companion object {
        /**
         * A package as the field trace writes it: after " in " (a node's class "in" its app) or
         * in "input connection (…)". Only there - elsewhere a dotted name is far more often a
         * server's host ("api.groq.com"), which a report is for.
         */
        val TRACED = Regex("""(?<=\bin |\bconnection \()[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)+""")
    }
}
