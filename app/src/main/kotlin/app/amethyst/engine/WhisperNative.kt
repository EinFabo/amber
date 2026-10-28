package app.amethyst.engine

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import app.amethyst.core.engine.whisper.WhisperRuntime
import app.amethyst.core.engine.whisper.WhisperRuntimeException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The JNI surface of libamethyst_whisper.so. Implemented in src/main/cpp/whisper_jni.cpp. */
internal object WhisperNative {

    /** Null when the library loaded; otherwise why it did not (e.g. an x86 emulator). */
    val loadError: Throwable? = runCatching { System.loadLibrary("amethyst_whisper") }
        .exceptionOrNull()

    /** [threads] sizes the CPU thread pool, which is created on the first load only. */
    external fun load(path: String, threads: Int): Long
    external fun free(handle: Long)
    external fun transcribe(
        handle: Long,
        samples: FloatArray,
        language: String,
        audioCtx: Int,
        threads: Int,
    ): ByteArray
    external fun abort()

    /** Kernel thread ids of the pool's workers; empty before the first load. */
    external fun workerTids(): IntArray
}

/**
 * Keeps one whisper.cpp context alive across dictations. Loading reads ~80 MB and builds the
 * graph; paying that on every bubble tap would add most of a second to each dictation.
 *
 * The context is reloaded when the file behind the path changes, so installing a new model
 * never leaves the old one answering.
 *
 * All native work runs on one dedicated thread ([dispatcher]). Through the bubble the process
 * is only a bound accessibility service - Android binds keyboards "like the top app", but not
 * accessibility services - so without help recognition runs on small cores at low clock and
 * took several times longer than in the app's own window. Three things push back: the thread
 * runs at display priority, whisper.cpp's worker threads are persistent and inherit that
 * priority (see patch-whisper.cmake), and while a transcription runs a performance hint
 * session (ADPF) tells the system the work is late, which raises clocks and moves it to big
 * cores on devices that support it.
 */
class NativeWhisperRuntime(context: Context) : WhisperRuntime {

    private val lock = Any()
    private var handle = 0L
    @Volatile
    private var loadedPath: String? = null
    private var loadedStamp = 0L

    @Volatile
    private var inferenceTid = 0

    @Volatile
    private var priorityApplied: Int? = null

    private val executor = Executors.newSingleThreadExecutor { work ->
        Thread({
            inferenceTid = Process.myTid()
            priorityApplied = raisePriority()
            work.run()
        }, "whisper")
    }

    /** The one thread whisper.cpp is called from; its priority is inherited by the pool. */
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private val booster = Booster(context)

    override fun ensureLoaded(modelPath: String) {
        synchronized(lock) {
            WhisperNative.loadError?.let { error ->
                throw WhisperRuntimeException("native library unavailable: ${error.message}")
            }
            val file = File(modelPath)
            if (!file.isFile) throw WhisperRuntimeException("model file missing: $modelPath")
            val stamp = file.lastModified() * 31 + file.length()
            if (handle != 0L && loadedPath == modelPath && loadedStamp == stamp) return

            releaseLocked()
            val started = System.currentTimeMillis()
            handle = WhisperNative.load(modelPath, defaultThreads())
            loadedPath = modelPath
            loadedStamp = stamp
            Log.i(TAG, "whisper context ready in ${System.currentTimeMillis() - started} ms")
        }
    }

    override fun transcribe(
        samples: FloatArray,
        language: String,
        audioCtx: Int,
        threads: Int,
    ): String = synchronized(lock) {
        if (handle == 0L) throw WhisperRuntimeException("model not loaded")
        val started = System.currentTimeMillis()
        val bytes = booster.during(listOf(inferenceTid) + WhisperNative.workerTids().toList()) {
            WhisperNative.transcribe(handle, samples, language, audioCtx, threads)
        }
        Log.i(
            TAG,
            "whisper: ${samples.size / 16} ms audio, ctx=$audioCtx, lang=$language, " +
                "$threads threads -> ${System.currentTimeMillis() - started} ms (${schedulingSummary()})",
        )
        // Decoded here, not in native code: a token boundary can split a multi-byte
        // character, and this replaces the broken bytes instead of aborting.
        String(bytes, Charsets.UTF_8)
    }

    /** True when [modelPath] is in memory right now - read without the lock, for diagnostics. */
    fun isLoaded(modelPath: String): Boolean = loadedPath == modelPath

    /**
     * How the inference thread is being scheduled, for the diagnostics card: its cpuset
     * ("/top-app", "/foreground", ...), the priority it got and whether the hint session runs.
     */
    fun schedulingSummary(): String = buildList {
        val tid = inferenceTid
        if (tid != 0) {
            runCatching { File("/proc/self/task/$tid/cpuset").readText().trim() }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.let { add("CPU-Gruppe $it") }
        }
        priorityApplied?.let { add("Prio $it") }
        add("Pool ${WhisperNative.workerTids().size + 1}")
        add(if (booster.lastRunBoosted) "Boost an" else "Boost aus")
    }.joinToString(", ")

    /** Not under [lock]: it has to reach a transcription that is holding it. */
    override fun abort() {
        if (WhisperNative.loadError == null) WhisperNative.abort()
    }

    /** Frees the ~150 MB the context holds. The next dictation loads it again. */
    fun release() {
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        if (handle != 0L) {
            WhisperNative.free(handle)
            Log.i(TAG, "whisper context released")
        }
        handle = 0L
        loadedPath = null
        loadedStamp = 0L
    }

    /** The highest priority Android lets an app thread take, tried from the top down. */
    private fun raisePriority(): Int? {
        for (priority in intArrayOf(Process.THREAD_PRIORITY_URGENT_DISPLAY, Process.THREAD_PRIORITY_DISPLAY)) {
            val applied = runCatching { Process.setThreadPriority(priority) }.isSuccess
            if (applied) return priority
        }
        Log.w(TAG, "could not raise the whisper thread priority")
        return null
    }

    companion object {
        private const val TAG = "Amber"

        /**
         * Big cores do the work; using every core drags the little ones in and is slower on
         * most big.LITTLE phones.
         */
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }
}

/**
 * Asks for CPU while a transcription runs, through a performance hint session (Android 12+).
 *
 * The session is told about a short target and then, every few milliseconds, how long the
 * work has been running - always over target, which is the truth for one long job measured
 * against a frame-sized deadline. Android answers by raising clocks and preferring big cores.
 * When the job ends the reports stop and the boost decays by itself. On devices without
 * support the session is null and nothing happens.
 */
private class Booster(context: Context) {

    private val manager: PerformanceHintManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { context.getSystemService(PerformanceHintManager::class.java) }.getOrNull()
        } else {
            null
        }

    private var session: PerformanceHintManager.Session? = null
    private var sessionTids: List<Int> = emptyList()

    private val ticker = Executors.newSingleThreadScheduledExecutor { work -> Thread(work, "whisper-boost") }

    /** Whether the last transcription ran with a hint session, for the diagnostics card. */
    @Volatile
    var lastRunBoosted = false
        private set

    fun <T> during(tids: List<Int>, work: () -> T): T {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return work()
        val current = sessionFor(tids.filter { it > 0 })
        lastRunBoosted = current != null
        if (current == null) return work()

        val started = System.nanoTime()
        val reports: ScheduledFuture<*> = ticker.scheduleAtFixedRate(
            { runCatching { current.reportActualWorkDuration(System.nanoTime() - started) } },
            0,
            REPORT_EVERY_MS,
            TimeUnit.MILLISECONDS,
        )
        return try {
            work()
        } finally {
            reports.cancel(false)
        }
    }

    /** One session for the life of the thread pool; recreated only if the threads changed. */
    private fun sessionFor(tids: List<Int>): PerformanceHintManager.Session? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || tids.isEmpty()) return null
        if (session != null && tids == sessionTids) return session
        session?.close()
        session = runCatching { manager?.createHintSession(tids.toIntArray(), TARGET_NANOS) }
            .onFailure { Log.w("Amber", "no performance hint session", it) }
            .getOrNull()
        sessionTids = tids
        if (session == null) Log.i("Amber", "performance hints not supported on this device")
        return session
    }

    private companion object {
        const val TARGET_NANOS = 16_000_000L
        const val REPORT_EVERY_MS = 20L
    }
}
