package app.amethyst.core.dictation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** How the app's process ended when it was not asked to. */
enum class ExitKind {
    /** An exception nobody caught, in the app's own (Kotlin or Java) code. */
    CRASH,

    /** A crash in native code: a speech model's runtime, the audio encoder. */
    NATIVE_CRASH,

    /** Android ended it because it stopped responding. */
    ANR,

    /** Android ended it to get memory back. */
    LOW_MEMORY,

    /** Android ended it for another reason, while it was in use. */
    OTHER,
}

/**
 * One time the app stopped unexpectedly, for Diagnostics: when, how, and [detail] - an
 * exception's class and where in the app's code it was thrown, or the reason Android gave. Never
 * an exception's message (it can quote what was heard or a server's answer) and never another
 * app's name.
 */
@Serializable
data class ExitRecord(val atMs: Long, val kind: ExitKind, val detail: String)

/**
 * An end of the process as Android reports it (`ApplicationExitInfo`, Android 11 and later):
 * its reason and status, how important the process was then, and when.
 */
data class SystemExit(val reason: Int, val importance: Int, val status: Int, val atMs: Long)

/** Makes [ExitRecord]s and keeps a list of them short and without doubles. */
object ExitRecords {

    /** The records kept: enough to see a pattern, few enough to read. */
    const val MAX = 10

    /** The app's frames a crash keeps: where in the app it went wrong. */
    const val MAX_FRAMES = 8

    /**
     * A crash Android reports within this time after one the app recorded itself is that
     * crash: the app's record, with its frames, is the one kept.
     */
    const val SAME_CRASH_MS = 10_000L

    /** The app's own code: the frames a crash keeps. */
    private const val APP_PACKAGE = "app.amethyst."

    // ApplicationExitInfo's reasons and RunningAppProcessInfo's importance, as Android defines them.
    const val REASON_UNKNOWN = 0
    const val REASON_EXIT_SELF = 1
    const val REASON_SIGNALED = 2
    const val REASON_LOW_MEMORY = 3
    const val REASON_CRASH = 4
    const val REASON_CRASH_NATIVE = 5
    const val REASON_ANR = 6
    const val REASON_INITIALIZATION_FAILURE = 7
    const val REASON_PERMISSION_CHANGE = 8
    const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
    const val REASON_USER_REQUESTED = 10
    const val REASON_USER_STOPPED = 11
    const val REASON_DEPENDENCY_DIED = 12
    const val REASON_OTHER = 13
    const val REASON_FREEZER = 14
    const val REASON_PACKAGE_STATE_CHANGE = 15
    const val REASON_PACKAGE_UPDATED = 16
    const val IMPORTANCE_CACHED = 400

    private val REASON_NAMES = mapOf(
        REASON_UNKNOWN to "UNKNOWN", REASON_EXIT_SELF to "EXIT_SELF", REASON_SIGNALED to "SIGNALED",
        REASON_LOW_MEMORY to "LOW_MEMORY", REASON_CRASH to "CRASH", REASON_CRASH_NATIVE to "CRASH_NATIVE",
        REASON_ANR to "ANR", REASON_INITIALIZATION_FAILURE to "INITIALIZATION_FAILURE",
        REASON_PERMISSION_CHANGE to "PERMISSION_CHANGE", REASON_EXCESSIVE_RESOURCE_USAGE to "EXCESSIVE_RESOURCE_USAGE",
        REASON_USER_REQUESTED to "USER_REQUESTED", REASON_USER_STOPPED to "USER_STOPPED",
        REASON_DEPENDENCY_DIED to "DEPENDENCY_DIED", REASON_OTHER to "OTHER", REASON_FREEZER to "FREEZER",
        REASON_PACKAGE_STATE_CHANGE to "PACKAGE_STATE_CHANGE", REASON_PACKAGE_UPDATED to "PACKAGE_UPDATED",
    )

    /** Ends somebody asked for: the user (force stop, swiped away), an update, a permission taken back. */
    private val ASKED_FOR = setOf(
        REASON_EXIT_SELF, REASON_PERMISSION_CHANGE, REASON_USER_REQUESTED, REASON_USER_STOPPED,
        REASON_PACKAGE_STATE_CHANGE, REASON_PACKAGE_UPDATED,
    )

    /**
     * [t] as a crash at [nowMs]: the classes of it and its causes, and at most [MAX_FRAMES] of
     * the app's own frames - from [t], or from the first cause that has any. Not a message.
     */
    fun fromThrowable(t: Throwable, nowMs: Long): ExitRecord {
        val chain = causes(t)
        val classes = chain.joinToString(", caused by ") { it.javaClass.name }
        val frames = chain.asSequence()
            .map { e -> e.stackTrace.filter { it.className.startsWith(APP_PACKAGE) } }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
            .take(MAX_FRAMES)
            .map { "at ${it.className}.${it.methodName}(${it.fileName ?: "?"}:${it.lineNumber})" }
        return ExitRecord(nowMs, ExitKind.CRASH, (listOf(classes) + frames).joinToString("\n"))
    }

    /** One line for a failure that was caught: [t]'s class and the first of the app's frames. */
    fun oneLine(t: Throwable): String = fromThrowable(t, 0).detail.lines().take(2).joinToString(" ")

    /** [t] and its causes, each once. */
    private fun causes(t: Throwable): List<Throwable> {
        val seen = mutableListOf<Throwable>()
        var next: Throwable? = t
        while (next != null && seen.none { it === next } && seen.size < MAX_CAUSES) {
            seen += next
            next = next.cause
        }
        return seen
    }

    private const val MAX_CAUSES = 5

    /**
     * [exit] as a record, or null for an end that was asked for ([ASKED_FOR]) - and for Android
     * tidying up a process nobody was using ([IMPORTANCE_CACHED] and beyond), which is routine.
     * A crash and an ANR count whatever the app was doing.
     */
    fun fromSystem(exit: SystemExit): ExitRecord? {
        if (exit.reason in ASKED_FOR) return null
        val kind = when (exit.reason) {
            REASON_CRASH -> ExitKind.CRASH
            REASON_CRASH_NATIVE -> ExitKind.NATIVE_CRASH
            REASON_ANR -> ExitKind.ANR
            REASON_LOW_MEMORY -> ExitKind.LOW_MEMORY
            else -> ExitKind.OTHER
        }
        val always = kind == ExitKind.CRASH || kind == ExitKind.NATIVE_CRASH || kind == ExitKind.ANR
        if (!always && exit.importance >= IMPORTANCE_CACHED) return null
        val name = REASON_NAMES[exit.reason] ?: "reason ${exit.reason}"
        val signal = if (exit.reason == REASON_SIGNALED) " (signal ${exit.status})" else ""
        return ExitRecord(exit.atMs, kind, "Android: $name$signal, importance ${exit.importance}")
    }

    /**
     * [old] and [new] together, newest first, at most [MAX]: a record of the same time and kind
     * once, and a crash Android reports within [SAME_CRASH_MS] after one in [old] not again.
     */
    fun merge(old: List<ExitRecord>, new: List<ExitRecord>): List<ExitRecord> {
        val fresh = new.filterNot { n ->
            old.any { o -> o.kind == n.kind && (o.atMs == n.atMs || (n.kind == ExitKind.CRASH && n.atMs - o.atMs in 0..SAME_CRASH_MS)) }
        }
        return (old + fresh)
            .distinctBy { it.atMs to it.kind }
            .sortedByDescending { it.atMs }
            .take(MAX)
    }

    /** How a copied report names [kind] - English, as all of Diagnostics' copy. */
    fun reportName(kind: ExitKind): String = when (kind) {
        ExitKind.CRASH -> "crash"
        ExitKind.NATIVE_CRASH -> "native crash"
        ExitKind.ANR -> "not responding (ANR)"
        ExitKind.LOW_MEMORY -> "out of memory"
        ExitKind.OTHER -> "ended by Android"
    }
}

/**
 * The [ExitRecord]s kept in [file] (the app gives `exits.json` in noBackupFilesDir), and how far
 * Android's own list of exits was read, so each of those is added once. Read when first asked,
 * then kept in [records]; every change writes the few bytes again. One instance per process.
 *
 * Fails open: a file it cannot read starts an empty list, one it cannot write keeps the change in
 * memory - neither ever stops the app, least of all while it crashes. [trouble] hears what went
 * wrong, as a class name only.
 */
class ExitLog(
    private val file: File,
    private val trouble: (String) -> Unit = {},
) {
    @Serializable
    private data class Stored(val readUpToMs: Long = 0, val records: List<ExitRecord> = emptyList())

    private val lock = Any()

    /** The file as last read or written; null until first asked - building this reads nothing. */
    private var kept: Stored? = null
    private val _records = MutableStateFlow<List<ExitRecord>>(emptyList())
    private val shown = _records.asStateFlow()

    /** Newest first, at most [ExitRecords.MAX]. Reads the file the first time; the same flow every time. */
    val records: StateFlow<List<ExitRecord>>
        get() {
            synchronized(lock) { stored() }
            return shown
        }

    /** Under [lock]. */
    private fun stored(): Stored = kept ?: read().also {
        kept = it
        _records.value = it.records
    }

    /** Writes [t] as a crash at once, on the crashing thread: the process ends right after. */
    fun crashed(t: Throwable, nowMs: Long) {
        try {
            change { it.copy(records = ExitRecords.merge(it.records, listOf(ExitRecords.fromThrowable(t, nowMs)))) }
        } catch (e: Throwable) {
            // Nothing may stop the crash going on to Android.
        }
    }

    /**
     * At a start: Android's list of the app's exits ([SystemExit]); those after the last read
     * become records ([ExitRecords.fromSystem]), and the list counts as read up to its newest.
     */
    fun foundAtStart(exits: List<SystemExit>) {
        change { now ->
            val unread = exits.filter { it.atMs > now.readUpToMs }
            if (unread.isEmpty()) return@change now
            Stored(
                readUpToMs = unread.maxOf { it.atMs },
                records = ExitRecords.merge(now.records, unread.mapNotNull(ExitRecords::fromSystem)),
            )
        }
    }

    /** Diagnostics cleared: the records go; what was read of Android's list stays read. */
    fun clear() = change { it.copy(records = emptyList()) }

    /** Diagnostics' Undo after [clear]: [cleared] back beside whatever came since. */
    fun restore(cleared: List<ExitRecord>) = change { it.copy(records = ExitRecords.merge(it.records, cleared)) }

    /**
     * Records every uncaught exception before it goes on to the handler that was there before -
     * Android's, which ends the process. [clock] is the wall clock.
     */
    fun installCrashHandler(clock: () -> Long = System::currentTimeMillis) {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            crashed(error, runCatching(clock).getOrDefault(0L))
            before?.uncaughtException(thread, error)
        }
    }

    private fun change(transform: (Stored) -> Stored) = synchronized(lock) {
        val now = stored()
        val next = transform(now)
        if (next == now) return@synchronized
        kept = next
        _records.value = next.records
        write(next)
    }

    private fun read(): Stored = try {
        if (file.exists()) JSON.decodeFromString(Stored.serializer(), file.readText()) else Stored()
    } catch (e: Exception) {
        trouble("exit records not read: ${e.javaClass.simpleName}")
        Stored()
    }

    private fun write(next: Stored) {
        try {
            file.parentFile?.mkdirs()
            val part = File(file.path + ".part")
            part.writeText(JSON.encodeToString(Stored.serializer(), next))
            if (!part.renameTo(file)) {
                file.writeText(part.readText())
                part.delete()
            }
        } catch (e: Exception) {
            trouble("exit records not written: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
