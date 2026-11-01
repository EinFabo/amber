package app.amethyst.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * The launcher's apps ([Launcher.apps]), read once and kept for the process: a voice command,
 * its app search and the app pickers all use this one list. Reading it asks the package manager
 * for every launcher activity and its label - tens to hundreds of milliseconds on a phone - so
 * it is never read on the main thread.
 *
 * An app installed, removed, updated, switched on or off, or a new phone language (the labels
 * follow it) drops the list; the next use reads it afresh.
 */
class LauncherList(context: Context) {

    private val context = context.applicationContext

    @Volatile
    private var cached: List<InstalledApp>? = null

    /** Counts the drops, so a read that began before one is not kept as fresh. */
    private val generation = AtomicInteger()

    private val lock = Any()

    /** False when the change broadcasts could not be had: then nothing is kept. */
    private var watching = true

    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = invalidate()
    }

    init {
        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        // System broadcasts: they arrive although nothing else may send to this receiver.
        runCatching {
            ContextCompat.registerReceiver(this.context, changes, packages, ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(this.context, changes, IntentFilter(Intent.ACTION_LOCALE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure {
            // Without the broadcasts the list would go stale: read it every time, as before.
            Log.w(TAG, "no package broadcasts - the launcher list is read on every use", it)
            watching = false
        }
    }

    /** The list, read on the IO pool when it is not at hand. */
    suspend fun apps(): List<InstalledApp> = cached ?: withContext(Dispatchers.IO) { appsBlocking() }

    /**
     * The list, read on the calling thread when it is not at hand - only for callers already on
     * a background thread, such as the command rules' app lookup.
     */
    fun appsBlocking(): List<InstalledApp> {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val before = generation.get()
            val read = Launcher.apps(context)
            if (watching && generation.get() == before) cached = read
            return read
        }
    }

    /** Drops the list; the next use reads it afresh. */
    fun invalidate() {
        generation.incrementAndGet()
        cached = null
    }

    private companion object {
        const val TAG = "Amber"
    }
}
