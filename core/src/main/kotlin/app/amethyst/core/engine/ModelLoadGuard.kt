package app.amethyst.core.engine

import app.amethyst.core.dictation.ExitRecords
import app.amethyst.core.dictation.SystemExit
import app.amethyst.core.engine.whisper.WhisperModels
import java.io.File
import java.security.MessageDigest

/**
 * Keeps a damaged model on the phone from ending the app on every dictation.
 *
 * sherpa-onnx and whisper.cpp do not all fail politely on a bad file: some damage makes native
 * code abort the whole process, which no Kotlin catch sees. So a small marker is written just
 * before a model's native load and deleted right after it, whether it worked or threw. A marker
 * still there at the next start means the process ended inside the load ([verdictAtStart]) -
 * unless Android says why it ended, and the reason is not the file's: out of memory, a force
 * stop, a crash in the app's own code, an update. Then the model is not loaded again until its
 * files are checked against the catalog's hash. A file the user picked has no hash: one such end
 * gives it another chance, a second within a day marks it damaged ([strike]). A model that fails
 * politely ([Problem.LocalModelFailed]) twice in a row is treated the same way ([afterFailure]) -
 * "Try again" would otherwise go round in circles.
 *
 * The rules live here; [ModelLoadMarks] keeps them in files.
 */
object ModelLoadGuard {

    /**
     * A marker older than this is an unrelated end long ago - a phone switched off during a
     * load, then left in a drawer - not a sign of a bad file.
     */
    const val STALE_AFTER_MS = 24 * 60 * 60 * 1000L

    /** Polite failures of one model in a row that make it suspect. */
    const val FAILURES_IN_A_ROW = 2

    /** Unexplained ends inside a load, within [STALE_AFTER_MS] of each other, that make a file without a hash damaged. */
    const val STRIKES = 2

    /**
     * Ends Android explains with a reason that is not the model file's: the low-memory killer
     * (the common end on a 3 GB phone loading a big model), a force stop or a swipe from the
     * recent apps, a crash in the app's own Java or Kotlin code (a native one may be the file's),
     * an update, a permission taken back, the process ending itself.
     */
    private val NOT_THE_FILE = setOf(
        ExitRecords.REASON_LOW_MEMORY, ExitRecords.REASON_USER_REQUESTED, ExitRecords.REASON_USER_STOPPED,
        ExitRecords.REASON_CRASH, ExitRecords.REASON_EXIT_SELF, ExitRecords.REASON_PERMISSION_CHANGE,
        ExitRecords.REASON_PACKAGE_STATE_CHANGE, ExitRecords.REASON_PACKAGE_UPDATED,
    )

    /** Written before [modelId]'s native load starts, at [startedAtMs] (wall clock: it outlives the process). */
    data class Marker(val modelId: String, val startedAtMs: Long) {
        fun encode(): String = "$modelId\n$startedAtMs\n"

        companion object {
            /** Null for a file cut off while it was written - read as no marker. */
            fun decode(text: String): Marker? {
                val lines = text.lines()
                val id = lines.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val at = lines.getOrNull(1)?.trim()?.toLongOrNull() ?: return null
                return Marker(id, at)
            }
        }
    }

    sealed interface Verdict {
        /** Nothing points at the model's files. */
        data object None : Verdict

        /** [modelId] is suspect: not loaded again until its files are checked ([afterCheck]). */
        data class Damaged(val modelId: String) : Verdict
    }

    /** Where a suspect model stands. A model with no health on record is fine. */
    enum class Health {
        /** Suspect, its files not checked yet: not loaded meanwhile. */
        CHECKING,

        /**
         * Its files do not match the catalog - or, a picked file with no hash to check them by,
         * its load ended the app twice within a day ([STRIKES]): download it, or pick it, again.
         */
        DAMAGED,
    }

    /** The marker for [modelId]'s load starting at [nowMs]. */
    fun before(modelId: String, nowMs: Long): Marker = Marker(modelId, nowMs)

    /**
     * What a [marker] found at the start says. A marker from a clock that went back since
     * counts as recent: a crash a moment ago is likelier than an unrelated kill. [lastExit]: the
     * last end of the app's process Android reports (Android 11 and later, else null) - one
     * after the load began, for a reason that is not the file's ([excused]), clears the model.
     */
    fun verdictAtStart(marker: Marker?, nowMs: Long, lastExit: SystemExit? = null): Verdict = when {
        marker == null -> Verdict.None
        nowMs - marker.startedAtMs > STALE_AFTER_MS -> Verdict.None
        excused(marker, lastExit) -> Verdict.None
        else -> Verdict.Damaged(marker.modelId)
    }

    /**
     * Whether [exit] explains the end of the load [marker] was written for: it came after the
     * load began, for a reason that is not the file's. An exit from before the load is another
     * process's - this one's end is then unknown.
     */
    fun excused(marker: Marker, exit: SystemExit?): Boolean =
        exit != null && exit.atMs >= marker.startedAtMs && exit.reason in NOT_THE_FILE

    /**
     * An unexplained end inside a load of [modelId], the [times]-th in a row, the last at [atMs]
     * (wall clock). Counts for a file without a hash only ([afterCheck]): a picked file cannot
     * be checked, so a first end - more likely the memory than the file - is not held against it.
     */
    data class Strike(val modelId: String, val atMs: Long, val times: Int) {
        fun encode(): String = "$modelId $atMs $times"

        companion object {
            /** One line, read from its end (the id may hold anything); null for a line no build can read. */
            fun decode(line: String): Strike? {
                val text = line.trim()
                val times = text.substringAfterLast(' ').toIntOrNull()?.takeIf { it > 0 } ?: return null
                val rest = text.substringBeforeLast(' ', "")
                val at = rest.substringAfterLast(' ').toLongOrNull() ?: return null
                val id = rest.substringBeforeLast(' ', "").takeIf { it.isNotBlank() } ?: return null
                return Strike(id, at, times)
            }
        }
    }

    /**
     * The strike [marker] makes, after [previous] for the same model: one more when that was
     * within [STALE_AFTER_MS] before (or a clock that went back), else the first.
     */
    fun strike(previous: Strike?, marker: Marker): Strike {
        val before = previous?.takeIf { it.modelId == marker.modelId && marker.startedAtMs - it.atMs <= STALE_AFTER_MS }?.times ?: 0
        return Strike(marker.modelId, marker.startedAtMs, before + 1)
    }

    /** How often [modelId] failed politely, with no success in between. */
    data class FailureCount(val modelId: String, val times: Int) {
        fun encode(): String = "$modelId\n$times\n"

        companion object {
            fun decode(text: String): FailureCount? {
                val lines = text.lines()
                val id = lines.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val times = lines.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: return null
                return FailureCount(id, times)
            }
        }
    }

    /** The count to keep after a failure (null: none), and what it says about the model. */
    data class Counted(val count: FailureCount?, val verdict: Verdict)

    /**
     * [modelId] failed for [problem]; [previous] is the count kept so far (one model's at a
     * time). Only [Problem.LocalModelFailed] counts: running out of memory or an old processor
     * says nothing about the file, so it neither counts nor starts the count over. A failure of
     * another model starts it over at one. [FAILURES_IN_A_ROW] of the same model make it
     * [Verdict.Damaged]. A success in between clears the count ([ModelLoadMarks.worked]).
     */
    fun afterFailure(modelId: String, problem: Problem, previous: FailureCount?): Counted {
        if (problem != Problem.LocalModelFailed) return Counted(previous, Verdict.None)
        val times = if (previous?.modelId == modelId) previous.times + 1 else 1
        val verdict = if (times >= FAILURES_IN_A_ROW) Verdict.Damaged(modelId) else Verdict.None
        return Counted(FailureCount(modelId, times), verdict)
    }

    /**
     * A suspect model's health after its files were checked: [hashMatches] null when the catalog
     * has no hash for it (a file the user picked). A match clears it; a mismatch leaves it
     * damaged. With nothing to check by, [strikes] decide - the unexplained ends inside its
     * load in a row ([strike]), or two polite failures, which count as [STRIKES]: a first is
     * let go of, the second makes it damaged.
     */
    fun afterCheck(hashMatches: Boolean?, strikes: Int = STRIKES): Health? = when (hashMatches) {
        true -> null
        false -> Health.DAMAGED
        null -> if (strikes >= STRIKES) Health.DAMAGED else null
    }

    /** What a dictation says of [modelId] once it is damaged: a file the user picked is chosen again, not downloaded. */
    fun damaged(modelId: String): Problem =
        if (modelId == WhisperModels.IMPORTED_ID) Problem.PickedModelDamaged else Problem.ModelDamaged

    /** Whether [file] is there at [sizeBytes] with the SHA-256 [sha256]. Reads the whole file: off the main thread. */
    fun matches(file: File, sizeBytes: Long, sha256: String): Boolean {
        if (!file.isFile || file.length() != sizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.equals(sha256, ignoreCase = true)
    }

    private const val BUFFER_SIZE = 256 * 1024
}

/** [modelId] is suspect or damaged ([ModelLoadGuard.Health]) and is not loaded. */
class ModelMarkedDamaged(val modelId: String) : IllegalStateException("model $modelId is marked damaged")

/**
 * [ModelLoadGuard]'s records as small files in [dir] (the app gives one in noBackupFilesDir):
 * a marker per load in progress (`loading/`), the suspect models and their [ModelLoadGuard.Health]
 * (`health`), the one model's count of polite failures (`failures`), and each model's last
 * unexplained end inside a load (`strikes`). The records are read once and then kept in memory -
 * one instance per process writes them - so asking about a model's health costs no file I/O
 * after the first call; a load or a change writes a few bytes. [lastExit] is how Android says
 * the last process ended (null below Android 11), asked once with the markers; [clock] is the
 * wall clock.
 *
 * Fails open: a file that cannot be written (storage full, the folder gone) never stops a model
 * loading - the load runs without its marker, and the health and the count are kept in memory
 * for this process. [trouble] hears what could not be written, as a class name only.
 */
class ModelLoadMarks(
    private val dir: File,
    private val trouble: (String) -> Unit = {},
    private val lastExit: () -> SystemExit? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private val markers = File(dir, "loading")
    private val healthFile = File(dir, "health")
    private val failuresFile = File(dir, "failures")
    private val strikesFile = File(dir, "strikes")

    /** [healthFile] and [failuresFile] as last read or written; null until first read. */
    private var healthKept: MutableMap<String, ModelLoadGuard.Health>? = null
    private var failuresKept: ModelLoadGuard.FailureCount? = null
    private var failuresRead = false
    private var strikesKept: MutableMap<String, ModelLoadGuard.Strike>? = null

    /**
     * The markers an earlier process left, read once - before this process writes any of its
     * own - and turned into [ModelLoadGuard.Health.CHECKING] marks and a strike each, unless
     * Android's reason for that process's end clears them ([ModelLoadGuard.excused]).
     */
    private val left: List<ModelLoadGuard.Verdict.Damaged> by lazy(lock) {
        val now = clock()
        val markerFiles = markers.listFiles().orEmpty()
        // Asked only when there is something to explain; a failure to ask explains nothing.
        val exit = if (markerFiles.isEmpty()) null else runCatching(lastExit).onFailure { troubleWith("the exit reason", it) }.getOrNull()
        val found = markerFiles.mapNotNull { file ->
            val marker = runCatching { ModelLoadGuard.Marker.decode(file.readText()) }.getOrNull()
            file.delete()
            marker?.takeIf { ModelLoadGuard.verdictAtStart(it, now, exit) is ModelLoadGuard.Verdict.Damaged }
        }.distinctBy { it.modelId }
        if (found.isNotEmpty()) {
            val health = readHealth()
            val strikes = readStrikes()
            found.forEach { marker ->
                health.putIfAbsent(marker.modelId, ModelLoadGuard.Health.CHECKING)
                strikes[marker.modelId] = ModelLoadGuard.strike(strikes[marker.modelId], marker)
            }
            writeHealth(health)
            writeStrikes(strikes)
        }
        found.map { ModelLoadGuard.Verdict.Damaged(it.modelId) }
    }

    /** The loads the last process died in (within [ModelLoadGuard.STALE_AFTER_MS]): those models are now suspect. */
    fun leftAtStart(): List<ModelLoadGuard.Verdict.Damaged> = left

    /**
     * Runs [block], the native load of [modelId], with its marker on disk. Refuses a model that
     * is suspect or damaged ([ModelMarkedDamaged]). The marker goes when [block] returns or
     * throws - only the end of the process leaves it.
     */
    fun <T> loading(modelId: String, block: () -> T): T {
        val marker = synchronized(lock) {
            left
            if (health(modelId) != null) throw ModelMarkedDamaged(modelId)
            val file = markerFor(modelId)
            // A marker that cannot be written: the model loads without one - the guard must never be what fails.
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(ModelLoadGuard.before(modelId, clock()).encode())
                file
            }.onFailure { troubleWith("the load marker", it) }.getOrNull()
        }
        try {
            return block()
        } finally {
            marker?.delete()
        }
    }

    fun health(modelId: String): ModelLoadGuard.Health? = synchronized(lock) {
        left
        readHealth()[modelId]
    }

    /** The models whose files are still to be checked - at a start, those a check that was cut off left. */
    fun checking(): List<String> = synchronized(lock) {
        left
        readHealth().filterValues { it == ModelLoadGuard.Health.CHECKING }.keys.toList()
    }

    /** [modelId] is suspect from now on, until [checked] or [clear]. */
    fun suspect(modelId: String) = synchronized(lock) {
        left
        writeHealth(readHealth().apply { put(modelId, ModelLoadGuard.Health.CHECKING) })
    }

    /**
     * The check of [modelId]'s files gave [health] ([ModelLoadGuard.afterCheck]); fine also starts
     * its failures over. Its strikes stay: a file without a hash let go of after a first end is
     * damaged at a second one within the day.
     */
    fun checked(modelId: String, health: ModelLoadGuard.Health?) = synchronized(lock) {
        if (health == null) forgetHealth(modelId) else writeHealth(readHealth().apply { put(modelId, health) })
    }

    /** [modelId] is fine again and starts afresh: downloaded anew, picked anew or deleted. */
    fun clear(modelId: String) = synchronized(lock) {
        forgetHealth(modelId)
        val strikes = readStrikes()
        if (strikes.remove(modelId) != null) writeStrikes(strikes)
    }

    /** How many unexplained ends in a row [modelId]'s loads had ([ModelLoadGuard.strike]); 0 with none. */
    fun strikes(modelId: String): Int = synchronized(lock) {
        left
        readStrikes()[modelId]?.times ?: 0
    }

    private fun forgetHealth(modelId: String) {
        left
        val health = readHealth()
        if (health.remove(modelId) != null) writeHealth(health)
        if (readFailures()?.modelId == modelId) writeFailures(null)
    }

    /**
     * [modelId] failed for [problem] ([ModelLoadGuard.afterFailure]). [ModelLoadGuard.Verdict.Damaged]
     * marks it suspect at once, so it is not loaded again, and the caller has its files checked.
     * One already suspect or damaged keeps its health: its refused load counts for nothing new.
     */
    fun failed(modelId: String, problem: Problem): ModelLoadGuard.Verdict = synchronized(lock) {
        left
        if (readHealth()[modelId] != null) return@synchronized ModelLoadGuard.Verdict.None
        val counted = ModelLoadGuard.afterFailure(modelId, problem, readFailures())
        val count = counted.count
        writeFailures(count)
        if (counted.verdict is ModelLoadGuard.Verdict.Damaged) {
            suspect(modelId)
            // Two failures in a row: as many strikes as a file without a hash is let go of for.
            writeStrikes(readStrikes().apply { put(modelId, ModelLoadGuard.Strike(modelId, clock(), ModelLoadGuard.STRIKES)) })
        }
        counted.verdict
    }

    /** [modelId] recognized: its failures start over. */
    fun worked(modelId: String) = synchronized(lock) {
        if (readFailures()?.modelId == modelId) writeFailures(null)
    }

    /** The marker file for [modelId]: its id made a plain file name, inside [dir] whatever the id holds. */
    fun markerFor(modelId: String): File = File(markers, modelId.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').ifEmpty { "_" })

    private fun readFailures(): ModelLoadGuard.FailureCount? {
        if (!failuresRead) {
            failuresKept = if (!failuresFile.isFile) null else runCatching { ModelLoadGuard.FailureCount.decode(failuresFile.readText()) }.getOrNull()
            failuresRead = true
        }
        return failuresKept
    }

    private fun writeFailures(count: ModelLoadGuard.FailureCount?) {
        failuresKept = count
        failuresRead = true
        if (count == null) failuresFile.delete() else write(failuresFile, count.encode())
    }

    /** A copy of the strikes, read from [strikesFile] the first time; a line no build can read is skipped. */
    private fun readStrikes(): MutableMap<String, ModelLoadGuard.Strike> {
        val kept = strikesKept ?: run {
            val text = if (strikesFile.isFile) runCatching { strikesFile.readText() }.getOrNull() else null
            text.orEmpty().lines().mapNotNull(ModelLoadGuard.Strike::decode).associateByTo(LinkedHashMap()) { it.modelId }
        }.also { strikesKept = it }
        return LinkedHashMap(kept)
    }

    private fun writeStrikes(strikes: Map<String, ModelLoadGuard.Strike>) {
        strikesKept = LinkedHashMap(strikes)
        if (strikes.isEmpty()) strikesFile.delete() else write(strikesFile, strikes.values.joinToString("") { it.encode() + "\n" })
    }

    /** A copy of the suspect models' health, read from [healthFile] the first time. */
    private fun readHealth(): MutableMap<String, ModelLoadGuard.Health> =
        LinkedHashMap(healthKept ?: parseHealth().also { healthKept = it })

    /** One "id health" per line; a line no build can read is skipped. */
    private fun parseHealth(): MutableMap<String, ModelLoadGuard.Health> {
        val text = (if (healthFile.isFile) runCatching { healthFile.readText() }.getOrNull() else null) ?: return mutableMapOf()
        return text.lines().mapNotNull { line ->
            val id = line.substringBeforeLast(' ', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val health = runCatching { ModelLoadGuard.Health.valueOf(line.substringAfterLast(' ').trim()) }.getOrNull()
                ?: return@mapNotNull null
            id to health
        }.toMap(LinkedHashMap())
    }

    private fun writeHealth(health: Map<String, ModelLoadGuard.Health>) {
        healthKept = LinkedHashMap(health)
        if (health.isEmpty()) healthFile.delete() else write(healthFile, health.entries.joinToString("") { "${it.key} ${it.value}\n" })
    }

    /**
     * Through a temporary sibling, so a process ending mid-write leaves the old file or the new
     * one. Never throws: what is not written stays in memory for this process ([trouble] hears it).
     */
    private fun write(file: File, text: String) {
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".new")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (e: Exception) {
            troubleWith(file.name, e)
        }
    }

    private fun troubleWith(what: String, e: Throwable) {
        runCatching { trouble("model guard: $what not written (${e.javaClass.simpleName})") }
    }
}
