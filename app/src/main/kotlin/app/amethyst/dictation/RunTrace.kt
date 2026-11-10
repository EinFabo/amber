package app.amethyst.dictation

import android.app.ActivityManager
import android.os.SystemClock
import app.amethyst.core.engine.EngineRouting
import java.io.File

/**
 * Where the time of one dictation went, for the diagnostics card.
 *
 * Exists because "slow from the bubble, fine in the app" has several possible causes - the
 * microphone route, a model that had to be loaded first, the wait for the proxy window, or
 * Android scheduling a background process onto its slow cores - and only numbers from the
 * device can tell them apart.
 */
internal class RunTrace(val route: String) {

    private val startedAt = SystemClock.elapsedRealtime()
    private var recordingFrom = 0L
    private var recordingTo = 0L
    private var transcribingFrom = 0L
    private var transcribingTo = 0L
    private var insertingFrom = 0L
    private var insertingTo = 0L
    private var waitedForFocus = 0L

    var modelWarm: Boolean? = null

    /** Which speech recognition heard the run, and whether the app's own setting chose it. */
    var routing: EngineRouting.Decision? = null

    /** How the whisper thread was scheduled during this run; see NativeWhisperRuntime. */
    var whisperScheduling: String? = null

    /** Where whisper.cpp spent the run: language detection, encoder and decoder runs, fallbacks (WhisperTimings). */
    var whisperTimings: String? = null

    /** How many parts the recording was recognized in; 0 means in one piece. */
    var chunks = 0

    /** What the cleanup model did, when one is chosen. */
    var cleanup: String? = null

    /** Why the cleanup went the way it did, when there is a story; may name the app. */
    var cleanupNote: String? = null

    /** The enclave that answered, when the engine names one (Tinfoil). */
    var servedBy: String? = null

    /** How the recording went up to a server - format and size, never the address. */
    var upload: String? = null

    /** Whether the server connection opened during the recording was ready for the upload. */
    var connection: String? = null
    private var cpuGroup: String? = null
    private var importance: Int? = null

    /** Set when a local model took over from an unreachable server. */
    var fallback: String? = null

    /** What the fallback prepared on the phone during the recording came to, if one was. */
    var prepared: String? = null

    /** The cleanup model that changed the text, for the cost estimate. */
    var cleanupModel: String? = null

    /** The engine whose server ran [cleanupModel] - the cleanup is priced by its provider. */
    var cleanupEngineId: String? = null

    /** Whether the field was looked for again when Android said none had focus, and how that went. */
    var refind: String? = null

    /** Why the recording ended, when it was not a tap or a pause ("ended at the longest recording, 300 s"). */
    var ended: String? = null

    /** How long the recording was paused, and the line saying so ("paused 4.2 s"). */
    var pausedMs = 0L
    var paused: String? = null

    /** How long the user spoke - the paused time not counted. */
    val recordingMs: Long get() = if (recordingTo > 0) (recordingTo - recordingFrom - pausedMs).coerceAtLeast(0) else 0

    /** From the stop until now: what the user has waited for the text. */
    val sinceStopMs: Long get() = if (recordingTo > 0) now() - recordingTo else 0

    fun recordingStarts() { recordingFrom = now() }
    fun recordingEnds() { recordingTo = now() }

    /** Also samples how Android is scheduling the process at the moment the work starts. */
    fun transcribingStarts() {
        transcribingFrom = now()
        cpuGroup = readCpuGroup()
        importance = runCatching {
            ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState).importance
        }.getOrNull()
    }

    fun transcribingEnds() { transcribingTo = now() }
    fun focusWait(ms: Long) { waitedForFocus = ms }
    fun insertingStarts() { insertingFrom = now() }
    fun insertingEnds() { insertingTo = now() }

    /**
     * One line for the diagnostics card and logcat. [namesApps] false leaves out what names
     * the app the text went to: a release build's logcat gets no app names.
     */
    fun summary(namesApps: Boolean = true): String = buildList {
        add("route $route")
        routing?.line(namesApps)?.let(::add)
        if (recordingFrom > 0) add("start ${ms(recordingFrom - startedAt)}")
        if (recordingTo > 0) add("recording ${ms(recordingMs)}")
        ended?.let(::add)
        paused?.let(::add)
        if (transcribingTo > 0) {
            val warm = when (modelWarm) {
                true -> ", model was loaded"
                false -> ", model had to load"
                null -> ""
            }
            // With chunks this is only the wait after the stop - the rest ran while speaking.
            val parts = if (chunks > 0) " after stop, $chunks chunks" else ""
            add("recognition ${ms(transcribingTo - transcribingFrom)}$parts$warm")
        }
        if (waitedForFocus > 0) add("focus wait ${ms(waitedForFocus)}")
        if (insertingTo > 0) add("insert ${ms(insertingTo - insertingFrom)}")
        refind?.let(::add)
        fallback?.let(::add)
        prepared?.let(::add)
        cleanupNote?.let { add(if (namesApps) it else "cleanup routed by the app's style") }
        cleanup?.let(::add)
        upload?.let(::add)
        connection?.let(::add)
        servedBy?.let { add("enclave $it") }
        // The whisper thread's own group says more than the caller's, when there is one.
        whisperScheduling?.let(::add) ?: cpuGroup?.let { add("CPU group $it") }
        whisperTimings?.let(::add)
        importance?.let { add("importance $it") }
    }.joinToString(" · ")

    private fun now() = SystemClock.elapsedRealtime()

    private fun ms(value: Long): String =
        if (value >= 1_000) "%.1f s".format(value / 1000.0) else "$value ms"

    /**
     * "/top-app", "/foreground", "/background" or "/restricted": the cpuset Android put this
     * thread in. Background sets are usually the small cores only.
     */
    private fun readCpuGroup(): String? = runCatching {
        File("/proc/thread-self/cpuset").readText().trim().ifEmpty { null }
    }.getOrNull()
}
