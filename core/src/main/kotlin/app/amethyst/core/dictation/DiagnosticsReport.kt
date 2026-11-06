package app.amethyst.core.dictation

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The records Diagnostics shows, as plain text to paste into a chat or a bug report: per
 * record its time of day, its one-line summary, what was heard (or dictated), a command's
 * steps - each of several commands' lines under its heading - and the timing. What goes in is what the screen shows - nothing more is read for it.
 */
object DiagnosticsReport {

    /** Which records the page shows - and so which ones a copy holds. */
    enum class Filter(val label: String) {
        ALL("All"),
        DICTATIONS("Dictations"),
        COMMANDS("Commands"),
        ;

        fun apply(records: List<DictationRecord>): List<DictationRecord> = when (this) {
            ALL -> records
            DICTATIONS -> records.filterNot { it.command }
            COMMANDS -> records.filter { it.command }
        }
    }

    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private const val INDENT = "  "

    /**
     * [records] newest first, as Diagnostics lists them, times in [zone]. [title] names the app
     * and its version; [keyboard] are the keyboard lines the page shows, if any. [warning] is
     * a line the page shows above them (another Amber switched on too), right under the title.
     */
    fun of(
        records: List<DictationRecord>,
        zone: ZoneId,
        title: String,
        copiedAt: Long,
        keyboard: List<String> = emptyList(),
        warning: String? = null,
    ): String = buildString {
        val count = when (records.size) {
            0 -> "no records"
            1 -> "1 record"
            else -> "${records.size} records, newest first"
        }
        appendLine("$title - diagnostics copied ${DATE_TIME.format(local(copiedAt, zone))} - $count")
        warning?.let { appendLine("Note: $it") }
        for (record in records) {
            appendLine()
            appendLine("${TIME.format(local(record.atMillis, zone))}  ${record.summary()}")
            if (record.text.isNotBlank()) appendLine("$INDENT${if (record.command) "heard" else "text"}: ${record.text}")
            nested(record.details).forEach { appendLine("$INDENT$it") }
            record.timing?.let { appendLine("${INDENT}timing: $it") }
        }
        if (keyboard.isNotEmpty()) {
            appendLine()
            appendLine("Keyboard")
            keyboard.forEach { appendLine("$INDENT$it") }
        }
    }

    /** A heading of one of several commands said in one go: "step 2/3: Edit COPY". */
    private val STEP_HEADING = Regex("^step \\d+/\\d+: ")

    /**
     * A command's [details] as Diagnostics show them: the lines of each of several commands
     * indented under its heading ("step 2/3: Edit COPY") until the next step, the outcome or a
     * tap that stopped it. One command's lines stay as they are.
     */
    fun nested(details: List<String>): List<String> {
        var inStep = false
        return details.map { line ->
            when {
                STEP_HEADING.containsMatchIn(line) -> line.also { inStep = true }
                line.startsWith("outcome:") || line.startsWith("stopped by a tap") -> line.also { inStep = false }
                inStep -> "$INDENT$line"
                else -> line
            }
        }
    }

    private fun local(millis: Long, zone: ZoneId) = Instant.ofEpochMilli(millis).atZone(zone)
}
