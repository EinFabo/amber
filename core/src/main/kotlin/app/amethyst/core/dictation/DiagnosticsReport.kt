package app.amethyst.core.dictation

import app.amethyst.core.settings.Features
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The records Diagnostics shows, as plain text to paste into a chat or a bug report: per
 * record its time of day, its one-line summary, the shape of what was heard (or dictated), a
 * command's steps - each of several commands' lines under its heading - and the timing. What
 * goes in is what the screen shows until "Show text" - nothing more is read for it, and no word
 * of a dictation, a command or a field ([anonymous]).
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

    /**
     * The filters Diagnostics offers: none without voice commands - every entry is then a
     * dictation (a command left from before shows among them).
     */
    fun filters(voiceCommands: Boolean = Features.VOICE_COMMANDS): List<Filter> =
        if (voiceCommands) Filter.entries else emptyList()

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
        val texts = texts(records)
        appendLine("$title - diagnostics copied ${DATE_TIME.format(local(copiedAt, zone))} - $count")
        warning?.let { appendLine("Note: ${Anonymize.line(it, texts)}") }
        for (record in records) {
            val shown = anonymous(record, texts)
            appendLine()
            appendLine("${TIME.format(local(record.atMillis, zone))}  ${shown.summary}")
            shown.shape?.let { appendLine("$INDENT${if (record.command) "heard" else "text"}: ${it.describe()}") }
            shown.lines.forEach { appendLine("$INDENT$it") }
        }
        if (keyboard.isNotEmpty()) {
            appendLine()
            appendLine("Keyboard")
            Anonymize.lines(keyboard, texts).forEach { appendLine("$INDENT$it") }
        }
    }

    /**
     * A record as a copy has it and its card shows it until "Show text": its words as their
     * [shape] (null without words), its [summary] and [lines] without them ([Anonymize]).
     */
    data class Anonymous(val shape: TextShape?, val summary: String, val lines: List<String>)

    /**
     * [record] without its words - nor those of [texts], every record's words shown with it
     * ([texts]): a command's steps may quote what an earlier one heard.
     */
    fun anonymous(record: DictationRecord, texts: Collection<String> = texts(listOf(record))): Anonymous {
        val all = Anonymize.lines(listOf(record.summary()) + lines(record), texts)
        return Anonymous(
            shape = record.text.takeIf { it.isNotBlank() }?.let(TextShape::of),
            summary = all.first(),
            lines = all.drop(1),
        )
    }

    /** What [records] heard or dictated, to keep out of every line shown with them. */
    fun texts(records: List<DictationRecord>): List<String> = records.map { it.text }.filter { it.isNotBlank() }

    /** [record]'s lines under its summary as they are: a command's steps ([nested]), then the timing. */
    fun lines(record: DictationRecord): List<String> =
        nested(record.details) + listOfNotNull(record.timing?.let { "timing: $it" })

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
