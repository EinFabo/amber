package app.amethyst.core.dictation

import app.amethyst.core.insert.InsertionStrategy
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.text.Transcript
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What happened on the last few dictations, kept in memory.
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

    fun recordSuccess(
        transcript: Transcript,
        strategy: InsertionStrategy,
        inserted: Boolean,
        targetPackage: String?,
        /** How the target was found, shown when the text did not make it into the field. */
        note: String? = null,
        /** Where the time went, one line. */
        timing: String? = null,
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
        ),
    )

    fun recordFailure(message: String, engineId: String? = null, timing: String? = null) = add(
        DictationRecord(
            text = "",
            engineId = engineId,
            trustLevel = null,
            latencyMs = null,
            strategy = null,
            inserted = false,
            targetPackage = null,
            error = message,
            atMillis = clock(),
            timing = timing,
        ),
    )

    fun clear() {
        _records.value = emptyList()
    }

    private fun add(record: DictationRecord) {
        _records.value = (listOf(record) + _records.value).take(capacity)
    }

    companion object {
        const val DEFAULT_CAPACITY = 20
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
) {
    val failed: Boolean get() = error != null

    /** One line for the diagnostics card. German, user-facing. */
    fun summary(): String = when {
        error != null -> "Fehler: $error"
        !inserted -> "Erkannt, aber nicht eingefügt (${strategy?.name ?: "keine Methode"})" +
            (note?.let { " – $it" } ?: "")
        else -> buildString {
            append(engineId ?: "unbekannte Engine")
            latencyMs?.let { append(" · $it ms") }
            strategy?.let { append(" · ${it.name}") }
            targetPackage?.let { append(" · $it") }
        }
    }
}
