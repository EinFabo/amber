package app.amethyst.data

import android.content.Context
import app.amethyst.core.privacy.SecurityMode
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.stats.DictationStat
import app.amethyst.core.stats.StatsLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The statistics ledger plus what the screens read from it. In noBackupFilesDir: the numbers
 * stay on this phone, out of cloud backups. All file work runs one at a time off the main
 * thread, so the first load and a dictation's line never interleave.
 *
 * [settings] are the settings as they are now, null until read: a line is written without its
 * app whenever they say so at the moment it is written ([SecurityMode.statToKeep]) - also for a
 * dictation that began before the security mode went on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsStore(context: Context, private val scope: CoroutineScope, private val settings: () -> AmethystSettings?) {

    private val ledger = StatsLedger(File(context.noBackupFilesDir, "stats/dictations.jsonl"))
    private val io = Dispatchers.IO.limitedParallelism(1)

    private val _all = MutableStateFlow<List<DictationStat>>(emptyList())
    val all: StateFlow<List<DictationStat>> = _all

    init {
        scope.launch(io) { _all.value = ledger.all() }
    }

    fun dayOf(atMillis: Long): Long = ledger.dayOf(atMillis)

    fun millisUntilNextDay(atMillis: Long): Long = ledger.millisUntilNextDay(atMillis)

    fun record(stat: DictationStat) {
        scope.launch(io) {
            val kept = SecurityMode.statToKeep(settings(), stat)
            ledger.record(kept)
            _all.value = _all.value + kept
        }
    }

    fun clear() {
        scope.launch(io) {
            ledger.clear()
            _all.value = emptyList()
        }
    }

    /** The file written again without the app each dictation went into ([SecurityMode.withoutApps]). */
    fun forgetApps() {
        scope.launch(io) { rewriteWithoutApps() }
    }

    /**
     * The same, only when a line still names an app ([SecurityMode.namesApps]): at a start with
     * the security mode on ([SecurityMode.repairsAtStart]), for what an earlier switch left.
     */
    fun forgetAppsIfNamed(before: suspend (List<DictationStat>) -> Unit) {
        scope.launch(io) {
            val stats = try {
                ledger.all()
            } catch (e: Exception) {
                android.util.Log.w("Embersay", "statistics not read: ${e.javaClass.simpleName}")
                emptyList()
            }
            if (SecurityMode.namesApps(stats)) {
                // What they show before the names go - the nudge toward a real app remembers its end.
                before(stats)
                rewriteWithoutApps()
                android.util.Log.i("Embersay", "security mode: app names left in the statistics removed")
            }
        }
    }

    private fun rewriteWithoutApps() {
        try {
            _all.value = ledger.rewrite(SecurityMode::withoutApps)
        } catch (e: Exception) {
            android.util.Log.w("Embersay", "statistics not written again: ${e.javaClass.simpleName}")
        }
    }
}
