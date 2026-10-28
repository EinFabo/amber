package app.amethyst.core

import app.amethyst.core.engine.whisper.ModelSlots
import app.amethyst.core.engine.whisper.ModelSlots.SlotState
import app.amethyst.core.engine.whisper.WhisperModelInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class ModelSlotsTest {

    private val info = WhisperModelInfo(vocabularySize = 51_865, audioLayers = 6, textLayers = 6, audioState = 512)

    private fun TestScope.slots(onDisk: MutableMap<String, SlotState> = mutableMapOf()) = ModelSlots(
        scope = this,
        dispatcher = StandardTestDispatcher(testScheduler),
        ids = listOf("a", "b"),
        inspect = { id -> onDisk[id] ?: SlotState.Missing },
    )

    @Test
    fun `a finished download marks the slot ready`() = runTest {
        val slots = slots()
        slots.run("a", "Downloading") { report, _ ->
            report(50, 100)
            Result.success(info to 100L)
        }
        advanceUntilIdle()
        assertEquals(SlotState.Ready(info, 100L), slots.state("a"))
    }

    /**
     * The 0.3.0 softlock: a blocking download cannot be interrupted, so after cancel() the
     * job finished as "not active" and never wrote a state - the row stayed on "Downloading" and
     * refused a new start.
     */
    @Test
    fun `cancelling frees the slot at once and a late result does not bring it back`() = runTest {
        val slots = slots()
        val release = CompletableDeferred<Unit>()
        slots.run("a", "Downloading") { report, isCancelled ->
            report(10, 100)
            release.await()
            // The blocking copy notices the cancel only at its next chunk.
            if (isCancelled()) Result.failure(InterruptedException("cancelled")) else Result.success(info to 100L)
        }
        advanceUntilIdle()
        assertIs<SlotState.Busy>(slots.state("a"))

        slots.cancel("a")
        assertEquals(SlotState.Missing, slots.state("a"), "the button must answer immediately")

        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(SlotState.Missing, slots.state("a"))

        slots.run("a", "Downloading") { _, _ -> Result.success(info to 100L) }
        advanceUntilIdle()
        assertEquals(SlotState.Ready(info, 100L), slots.state("a"), "a new start must be possible")
    }

    @Test
    fun `cancelling a replacement keeps the model that was there before`() = runTest {
        val onDisk = mutableMapOf<String, SlotState>("a" to SlotState.Ready(info, 80L))
        val slots = slots(onDisk)
        slots.run("a", "Kopiert") { _, _ -> CompletableDeferred<Result<Pair<WhisperModelInfo, Long>>>().await() }
        advanceUntilIdle()

        slots.cancel("a")
        assertEquals(SlotState.Ready(info, 80L), slots.state("a"))
    }

    @Test
    fun `a failure is shown and can be retried`() = runTest {
        val slots = slots()
        slots.run("b", "Downloading") { _, _ -> Result.failure(IllegalStateException("The server answered 503.")) }
        advanceUntilIdle()
        assertEquals(SlotState.Failed("The server answered 503."), slots.state("b"))

        slots.run("b", "Downloading") { _, _ -> Result.success(info to 1L) }
        advanceUntilIdle()
        assertIs<SlotState.Ready>(slots.state("b"))
    }

    @Test
    fun `a thrown exception ends in a failed state, never in busy`() = runTest {
        val slots = slots()
        slots.run("a", "Downloading") { _, _ -> throw java.io.IOException("unexpected end of stream") }
        advanceUntilIdle()
        assertIs<SlotState.Failed>(slots.state("a"))
    }
}
