package app.amethyst.dictation

import android.app.ActivityManager
import android.os.SystemClock
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

    /** How the whisper thread was scheduled during this run; see NativeWhisperRuntime. */
    var whisperScheduling: String? = null
    private var cpuGroup: String? = null
    private var importance: Int? = null

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

    fun summary(): String = buildList {
        add("Weg $route")
        if (recordingFrom > 0) add("Start ${ms(recordingFrom - startedAt)}")
        if (recordingTo > 0) add("Aufnahme ${ms(recordingTo - recordingFrom)}")
        if (transcribingTo > 0) {
            val warm = when (modelWarm) {
                true -> ", Modell war geladen"
                false -> ", Modell musste laden"
                null -> ""
            }
            add("Erkennung ${ms(transcribingTo - transcribingFrom)}$warm")
        }
        if (waitedForFocus > 0) add("Warten auf Fokus ${ms(waitedForFocus)}")
        if (insertingTo > 0) add("Einfügen ${ms(insertingTo - insertingFrom)}")
        // The whisper thread's own group says more than the caller's, when there is one.
        whisperScheduling?.let(::add) ?: cpuGroup?.let { add("CPU-Gruppe $it") }
        importance?.let { add("Priorität $it") }
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
