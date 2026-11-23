package app.amethyst.core.dictation

import app.amethyst.core.commands.CommandOutcome
import app.amethyst.core.insert.InsertionStrategy
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.text.Transcript
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What happened on the last few dictations and voice commands, kept in memory.
 *
 * The bubble's own state resets to resting a second after a dictation, which is right for the
 * bubble and useless for diagnosing a first install. This keeps the outcome around - which
 * engine, how long it took, which insertion strategy actually worked, in which app, or what
 * the error was - so the diagnostics card can show something concrete instead of "it didn't
 * work".
 *
 * Deliberately in :core and not in the Android module: it is a ring buffer, it needs no
 * platform API, and it is the sort of thing that silently grows without bound unless it is
 * tested.
 */
class DictationLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val _records = MutableStateFlow<List<DictationRecord>>(emptyList())

    /** Newest first. */
    val records: StateFlow<List<DictationRecord>> = _records.asStateFlow()

    val last: DictationRecord? get() = _records.value.firstOrNull()

    /**
     * The newest dictation with recognized words - also one whose insertion failed ("You
     * switched to another app meanwhile"): Insert again and Copy last are for just that. Not a
     * failure without words, not a command.
     */
    val lastDictation: DictationRecord?
        get() = lastDictation(_records.value)

    fun recordSuccess(
        transcript: Transcript,
        strategy: InsertionStrategy,
        inserted: Boolean,
        targetPackage: String?,
        /** How the target was found, shown when the text did not make it into the field. */
        note: String? = null,
        /** Where the time went, one line. */
        timing: String? = null,
        /** The apps' names in Amber [timing] may use, each with its package ([DictationRecord.appLabels]). */
        appLabels: Map<String, String> = emptyMap(),
    ) = add(
        DictationRecord(
            text = transcript.text,
            engineId = transcript.engineId,
            trustLevel = transcript.trustLevel,
            latencyMs = transcript.latencyMs,
            strategy = strategy,
            inserted = inserted,
            targetPackage = targetPackage,
            error = null,
            atMillis = clock(),
            note = note,
            timing = timing,
            appLabels = appLabels,
        ),
    )

    /**
     * [details]: for a command, what happened before it could be read ([DictationRecord.details]).
     * [text]: a dictation's words when they were recognized but did not go in.
     */
    fun recordFailure(
        message: String,
        engineId: String? = null,
        timing: String? = null,
        command: Boolean = false,
        details: List<String> = emptyList(),
        text: String = "",
        appLabels: Map<String, String> = emptyMap(),
    ) = add(
        DictationRecord(
            text = text,
            engineId = engineId,
            trustLevel = null,
            latencyMs = null,
            strategy = null,
            inserted = false,
            targetPackage = null,
            error = message,
            atMillis = clock(),
            timing = timing,
            command = command,
            details = details,
            appLabels = appLabels,
        ),
    )

    /**
     * A voice command: what was heard and what came of it, with [details] - what the rules made
     * of the words and what the executor did. Nothing was inserted anywhere.
     */
    fun recordCommand(
        heard: String,
        outcome: CommandOutcome,
        engineId: String?,
        timing: String? = null,
        details: List<String> = emptyList(),
        appLabels: Map<String, String> = emptyMap(),
    ) = add(
        DictationRecord(
            text = heard,
            engineId = engineId,
            trustLevel = null,
            latencyMs = null,
            strategy = null,
            inserted = false,
            targetPackage = null,
            error = outcome.message.takeUnless { outcome is CommandOutcome.Done },
            atMillis = clock(),
            note = outcome.message,
            timing = timing,
            command = true,
            details = details,
            appLabels = appLabels,
        ),
    )

    /**
     * Off in the security mode: a record keeps the shape of its words, not the words
     * ([DictationRecord.withoutWords]) - nothing for Insert again, Copy last or "Show text".
     */
    @Volatile
    var keepsWords: Boolean = true

    /**
     * Whether a record may name the app a dictation went into - in its timing, in how its field
     * was found: only while words are kept. The security mode keeps neither, so Diagnostics tell
     * whoever takes the phone no more than the statistics do.
     */
    val namesApps: Boolean get() = keepsWords

    fun clear() {
        _records.value = emptyList()
    }

    /**
     * Diagnostics' Undo after [clear]: [cleared] (newest first, as [records] gave them) back under
     * whatever was recorded since, within the capacity. Cleared before the security mode was
     * switched on, they come back without their words too.
     */
    fun restore(cleared: List<DictationRecord>) {
        val kept = if (keepsWords) cleared else cleared.map { it.withoutWords() }
        _records.value = (_records.value + kept).take(capacity)
    }

    private fun add(record: DictationRecord) {
        val kept = if (keepsWords) record else record.withoutWords()
        _records.value = (listOf(kept) + _records.value).take(capacity)
    }

    companion object {
        /** Commands and dictations share it: room for a session of trying commands out. */
        const val DEFAULT_CAPACITY = 50

        /** [lastDictation] of [records] (newest first), for a screen that collects them. */
        fun lastDictation(records: List<DictationRecord>): DictationRecord? =
            records.firstOrNull { !it.command && it.text.isNotBlank() }
    }
}

data class DictationRecord(
    val text: String,
    val engineId: String?,
    val trustLevel: TrustLevel?,
    val latencyMs: Long?,
    val strategy: InsertionStrategy?,
    val inserted: Boolean,
    val targetPackage: String?,
    val error: String?,
    val atMillis: Long,
    val note: String? = null,
    val timing: String? = null,
    /** A voice command, not a dictation: [text] is what was heard, [note] what came of it. */
    val command: Boolean = false,
    /**
     * A command's steps, in order ([app.amethyst.core.commands.CommandTrace]): what the rules
     * made of the words, what was tried, why it stopped. Shown in Diagnostics, never logged;
     * never a field's text. Empty for a dictation.
     */
    val details: List<String> = emptyList(),
    /**
     * The shape of the words when they were not kept ([withoutWords], the security mode): what
     * Diagnostics show in their place. Null while [text] holds them.
     */
    val shape: TextShape? = null,
    /**
     * The names the run's apps have in Amber ([app.amethyst.core.settings.AppProfile.label]),
     * each with its package, when its [timing] may use them - "WhatsApp's own speech
     * recognition" for the app the run started in, "cleanup off for Signal" for the one whose
     * field got the text, which may be another: a shared report gives each label the alias of
     * its package ([AppAliases]).
     */
    val appLabels: Map<String, String> = emptyMap(),
) {
    val failed: Boolean get() = error != null

    /**
     * This record without its words: their [shape] instead of [text], and every line that could
     * quote them - the error, the note, a command's steps - without them ([Anonymize]). Nor
     * does it name the app: no [targetPackage], and a dictation's [note] - how its field was
     * found, "EditText in com.whatsapp" - goes whole. Its [timing] is built without app names
     * by whoever records it ([DictationLog.namesApps]).
     */
    fun withoutWords(): DictationRecord {
        // A command's note is what came of it, not a field's trace.
        val anonymous = copy(text = "", targetPackage = null, appLabels = emptyMap(), note = note.takeIf { command })
        if (text.isBlank()) return anonymous
        val words = listOf(text)
        return anonymous.copy(
            shape = TextShape.of(text),
            error = error?.let { Anonymize.line(it, words) },
            note = anonymous.note?.let { Anonymize.line(it, words) },
            details = Anonymize.lines(details, words),
        )
    }

    /** One line for the diagnostics card. User-facing. */
    fun summary(): String = when {
        command -> "Command" + (engineId?.let { " · $it" } ?: "") + " · " + (note ?: error ?: "")
        error != null -> "Error: $error"
        !inserted -> "Recognized but not inserted (${strategy?.name ?: "no method"})" +
            (note?.let { " – $it" } ?: "")
        else -> buildString {
            append(engineId ?: "unknown engine")
            latencyMs?.let { append(" · $it ms") }
            strategy?.let { append(" · ${it.name}") }
            targetPackage?.let { append(" · $it") }
        }
    }
}
