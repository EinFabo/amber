package app.amethyst.core.privacy

/** One piece of the privacy policy as the app shows it ([PrivacyPolicy.parse]). */
sealed interface PolicyBlock {
    data class Title(val text: String) : PolicyBlock
    data class Heading(val text: String) : PolicyBlock
    data class Paragraph(val text: String) : PolicyBlock
    data class Point(val text: String) : PolicyBlock

    /** A line in square brackets: something the developer still has to fill in, shown as missing. */
    data class Missing(val text: String) : PolicyBlock
}

/**
 * The privacy policy inside the app: Google Play accepts "a link or text within the app", and
 * the policy is not on the web yet. Its text is one raw resource per language
 * (res/raw/privacy_policy.txt, res/raw-de/privacy_policy.txt), the English one the same words
 * as docs/PRIVACY.md; this reads the small part of Markdown they use.
 */
object PrivacyPolicy {

    /**
     * Where the policy is published on the web; empty until it is on Fabi's own domain. Once
     * set, About offers "Open on the web" beside the text in the app.
     */
    const val PRIVACY_URL = ""

    /** [url] if it is a page to open: set, and https. */
    fun webAddress(url: String = PRIVACY_URL): String? = url.trim().takeIf { it.startsWith("https://") }

    /**
     * "# " a title, "## " a heading, "- " a point, a line in square brackets something still
     * missing; any other run of lines is a paragraph. A block ends at a blank line or where the
     * next one starts, and a block's lines are joined with spaces - the files wrap where an
     * editor does, the screen where it has to.
     */
    fun parse(text: String): List<PolicyBlock> {
        val blocks = mutableListOf<PolicyBlock>()
        val lines = mutableListOf<String>()
        var open: Open? = null

        fun flush() {
            val joined = lines.joinToString(" ")
            when (open) {
                Open.POINT -> blocks += PolicyBlock.Point(joined)
                Open.PARAGRAPH -> blocks += PolicyBlock.Paragraph(joined)
                null -> Unit
            }
            lines.clear()
            open = null
        }

        fun start(kind: Open, line: String) {
            flush()
            lines += line
            open = kind
        }

        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> flush()
                line.startsWith("## ") -> { flush(); blocks += PolicyBlock.Heading(line.removePrefix("## ").trim()) }
                line.startsWith("# ") -> { flush(); blocks += PolicyBlock.Title(line.removePrefix("# ").trim()) }
                line.startsWith("- ") -> start(Open.POINT, line.removePrefix("- ").trim())
                line.startsWith("[") && line.endsWith("]") && open == null -> blocks += PolicyBlock.Missing(line)
                // An indented line goes on with the point above it; one that is not starts the
                // paragraph after the list.
                open == Open.POINT && !raw.startsWith(" ") -> start(Open.PARAGRAPH, line)
                open == null -> start(Open.PARAGRAPH, line)
                else -> lines += line
            }
        }
        flush()
        return blocks
    }

    private enum class Open { POINT, PARAGRAPH }
}
