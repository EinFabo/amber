package app.amethyst.data

import android.content.Context
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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsStore(context: Context, private val scope: CoroutineScope) {

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
            ledger.record(stat)
            _all.value = _all.value + stat
        }
    }

    fun clear() {
        scope.launch(io) {
            ledger.clear()
            _all.value = emptyList()
        }
    }
}
