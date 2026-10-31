package app.amethyst.core

import app.amethyst.core.commands.Command
import app.amethyst.core.commands.CommandChain
import app.amethyst.core.commands.CommandOutcome
import app.amethyst.core.commands.CommandTrace
import app.amethyst.core.commands.Direction
import app.amethyst.core.commands.GlobalAction
import app.amethyst.core.commands.SettingsPage
import app.amethyst.core.commands.TextEdit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Several commands said in one go run one after another: a pause between them, longer where the
 * screen changes, and the first that is not done stops the rest.
 */
class CommandChainTest {

    private val selectAll = Command.Edit(TextEdit.SELECT_ALL)
    private val copy = Command.Edit(TextEdit.COPY)
    private val back2 = Command.Navigate(GlobalAction.BACK, 2)
    private val whatsapp = Command.OpenApp("whatsapp")

    /** Runs [commands] with [answers] per command; records what ran and every pause. */
    private class Harness(private val answers: Map<Command, CommandOutcome>) {
        val ran = mutableListOf<Command>()
        val pauses = mutableListOf<Long>()
        val trace = CommandTrace()

        suspend fun run(vararg commands: Command) = CommandChain.run(commands.toList(), trace, pause = { pauses += it }) { command ->
            ran += command
            trace.note("executor: ${command::class.simpleName}")
            answers[command] ?: CommandOutcome.Done("Did it")
        }
    }

    @Test
    fun `one command runs as it always did - no heading, no pause, its own outcome`() = runTest {
        val harness = Harness(mapOf(copy to CommandOutcome.Done("Copied")))
        val run = harness.run(copy)
        assertEquals(CommandOutcome.Done("Copied"), run.outcome)
        assertEquals(listOf("executor: Edit"), harness.trace.lines)
        assertEquals(emptyList(), harness.pauses)
        assertEquals("outcome: done", run.line)

        val refused = Harness(mapOf(copy to CommandOutcome.Refused("Unlock the phone first."))).run(copy)
        assertEquals(CommandOutcome.Refused("Unlock the phone first."), refused.outcome)
        assertEquals("outcome: refused", refused.line)
    }

    @Test
    fun `steps run in order, each under its heading, and the toast says what each did`() = runTest {
        val harness = Harness(mapOf(selectAll to CommandOutcome.Done("Selected all"), copy to CommandOutcome.Done("Copied")))
        val run = harness.run(selectAll, copy)
        assertEquals(listOf<Command>(selectAll, copy), harness.ran)
        assertEquals(CommandOutcome.Done("Selected all, copied"), run.outcome)
        assertEquals(
            listOf("step 1/2: Edit SELECT_ALL", "executor: Edit", "step 2/2: Edit COPY", "executor: Edit"),
            harness.trace.lines,
        )
        assertEquals("outcome: done, all 2 steps", run.line)
    }

    @Test
    fun `the pause is longer after the screen changed`() = runTest {
        val harness = Harness(mapOf(back2 to CommandOutcome.Done("Back 2 times"), whatsapp to CommandOutcome.Done("Opened WhatsApp")))
        val run = harness.run(back2, whatsapp, Command.Scroll(Direction.DOWN), copy)
        assertEquals(listOf(CommandChain.NAVIGATION_SETTLE_MS, CommandChain.OPEN_SETTLE_MS, CommandChain.PAUSE_MS), harness.pauses)
        assertEquals(CommandOutcome.Done("Back 2 times, opened WhatsApp, did it, did it"), run.outcome)
    }

    @Test
    fun `a pause after each kind of command`() {
        assertEquals(CommandChain.OPEN_SETTLE_MS, CommandChain.pauseAfter(whatsapp))
        assertEquals(CommandChain.OPEN_SETTLE_MS, CommandChain.pauseAfter(Command.OpenSettings(SettingsPage.WIFI)))
        assertEquals(CommandChain.NAVIGATION_SETTLE_MS, CommandChain.pauseAfter(Command.Navigate(GlobalAction.HOME)))
        assertEquals(CommandChain.PAUSE_MS, CommandChain.pauseAfter(selectAll))
        assertEquals(CommandChain.PAUSE_MS, CommandChain.pauseAfter(Command.Scroll(Direction.UP, 3)))
        assertEquals(250L, CommandChain.PAUSE_MS)
    }

    @Test
    fun `the first step not done stops the chain and is named`() = runTest {
        val harness = Harness(
            mapOf(
                selectAll to CommandOutcome.Done("Selected all"),
                copy to CommandOutcome.Failed("Nothing is selected - say “select all” first."),
            ),
        )
        val run = harness.run(selectAll, copy, whatsapp)
        assertEquals(listOf<Command>(selectAll, copy), harness.ran, "the third never runs")
        assertEquals(
            CommandOutcome.Failed("Selected all, then step 2 of 3 failed: Nothing is selected - say “select all” first."),
            run.outcome,
        )
        assertEquals("outcome: failed at step 2/3 (Edit COPY)", run.line)
        assertEquals(listOf(CommandChain.PAUSE_MS), harness.pauses)
    }

    @Test
    fun `a refusal at the first step stays a refusal`() = runTest {
        val harness = Harness(mapOf(back2 to CommandOutcome.Refused("Unlock the phone first.")))
        val run = harness.run(back2, whatsapp)
        assertEquals(listOf<Command>(back2), harness.ran)
        val outcome = assertIs<CommandOutcome.Refused>(run.outcome)
        assertEquals("Step 1 of 2 refused: Unlock the phone first.", outcome.message)
        assertEquals("outcome: refused at step 1/2 (Navigate BACK ×2)", run.line)
    }

    @Test
    fun `a tap between two steps stops the chain at once`() = runTest {
        val ran = mutableListOf<Command>()
        assertFailsWith<CancellationException> {
            CommandChain.run(listOf(selectAll, copy), CommandTrace(), pause = { throw CancellationException("stopped by a tap") }) {
                ran += it
                CommandOutcome.Done("Done")
            }
        }
        assertEquals(listOf<Command>(selectAll), ran)
    }
}
