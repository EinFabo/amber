package app.amethyst.core.engine.whisper

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One state per model slot, and at most one download or copy per slot.
 *
 * The rule that matters is cancellation: the copy underneath is blocking I/O and only
 * notices a cancel at its next chunk, possibly seconds later. So [cancel] restores the slot
 * immediately, and a result that arrives afterwards from that old job is ignored - otherwise
 * the slot either sticks on "busy" or a cancelled download flips back to "failed".
 */
class ModelSlots(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    ids: List<String>,
    /** What is on disk for a slot right now, used at start and after a cancel. */
    private val inspect: (String) -> SlotState,
) {

    sealed interface SlotState {
        data object Missing : SlotState

        /** [total] is null when an imported file does not report its size. */
        data class Busy(val label: String, val received: Long, val total: Long?) : SlotState {
            val fraction: Float? get() = total?.takeIf { it > 0 }?.let { received.toFloat() / it }
        }

        data class Ready(val info: WhisperModelInfo, val sizeBytes: Long) : SlotState

        data class Failed(val message: String) : SlotState
    }

    private val _states = MutableStateFlow(ids.associateWith(inspect))
    val states: StateFlow<Map<String, SlotState>> = _states.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    fun state(id: String): SlotState = _states.value[id] ?: SlotState.Missing

    fun set(id: String, state: SlotState) {
        _states.update { it + (id to state) }
    }

    /**
     * Runs [block] for [id] unless something already runs there. The block reports progress
     * and must poll `isCancelled`; it returns the installed model and its size in bytes.
     */
    fun run(
        id: String,
        label: String,
        block: suspend (
            report: (received: Long, total: Long?) -> Unit,
            isCancelled: () -> Boolean,
        ) -> Result<Pair<WhisperModelInfo, Long>>,
    ) = synchronized(jobs) {
        if (jobs[id]?.isActive == true) return@synchronized
        set(id, SlotState.Busy(label, 0, null))

        lateinit var job: Job
        job = scope.launch(dispatcher) {
            var lastStep = -1L
            val result = try {
                block(
                    { received, total ->
                        // One update per percent (or per MB without a total), not per chunk.
                        val step = if (total != null && total > 0) received * 100 / total else received shr 20
                        if (step != lastStep && current(id, job)) {
                            lastStep = step
                            set(id, SlotState.Busy(label, received, total))
                        }
                    },
                    { !job.isActive },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }

            if (!current(id, job) || !job.isActive) return@launch
            set(
                id,
                result.fold(
                    onSuccess = { (info, size) -> SlotState.Ready(info, size) },
                    onFailure = { error -> SlotState.Failed(error.message ?: "Unbekannter Fehler") },
                ),
            )
            synchronized(jobs) { if (jobs[id] === job) jobs.remove(id) }
        }
        jobs[id] = job
    }

    /** Stops whatever runs for [id] and shows what is on disk, without waiting for the I/O. */
    fun cancel(id: String) {
        val job = synchronized(jobs) { jobs.remove(id) } ?: return
        job.cancel()
        set(id, inspect(id))
    }

    private fun current(id: String, job: Job): Boolean = synchronized(jobs) { jobs[id] === job }
}
