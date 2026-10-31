package app.amethyst.core

import app.amethyst.core.commands.CommandOutcome
import app.amethyst.core.commands.RepeatRun
import app.amethyst.core.commands.Repetition
import app.amethyst.core.commands.Step
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RepetitionTest {

    private class Script(vararg results: Step) {
        val left = results.toMutableList()
        var steps = 0
        var pauses = 0
        suspend fun run(times: Int) = Repetition.run(times, pause = { pauses++ }) {
            steps++
            left.removeFirstOrNull() ?: Step.Done
        }
    }

    @Test
    fun `every step runs, with a pause between steps only`() = runTest {
        val script = Script()
        assertEquals(RepeatRun(asked = 5, done = 5, stop = null), script.run(5))
        assertEquals(5, script.steps)
        assertEquals(4, script.pauses)
    }

    @Test
    fun `a failed step stops the rest`() = runTest {
        val script = Script(Step.Done, Step.Failed("refused"))
        assertEquals(RepeatRun(asked = 10, done = 1, stop = Step.Failed("refused")), script.run(10))
        assertEquals(2, script.steps)
    }

    @Test
    fun `a step that changed nothing ends the run - the end of the list`() = runTest {
        val script = Script(Step.Done, Step.Done, Step.Done, Step.Unchanged)
        assertEquals(RepeatRun(asked = 30, done = 3, stop = Step.Unchanged), script.run(30))
        assertEquals(4, script.steps)
    }

    @Test
    fun `never more than the cap, whatever is asked`() = runTest {
        val script = Script()
        assertEquals(Repetition.MAX_TO_END, script.run(500).done)
    }

    @Test
    fun `outcomes in words`() {
        assertEquals(CommandOutcome.Done("Scrolled down"), RepeatRun(1, 1, null).outcome("Scrolled down", "nothing more to scroll"))
        assertEquals(CommandOutcome.Done("Scrolled down 5 times"), RepeatRun(5, 5, null).outcome("Scrolled down", "nothing more to scroll"))
        assertEquals(
            CommandOutcome.Done("Scrolled down 3 times - nothing more to scroll"),
            RepeatRun(10, 3, Step.Unchanged).outcome("Scrolled down", "nothing more to scroll"),
        )
        assertEquals(
            CommandOutcome.Failed("Nothing more to scroll"),
            RepeatRun(10, 0, Step.Unchanged).outcome("Scrolled down", "nothing more to scroll"),
        )
        assertEquals(CommandOutcome.Failed("Android refused."), RepeatRun(4, 0, Step.Failed("Android refused.")).outcome("Back", "-"))
        assertEquals(
            CommandOutcome.Failed("Back 2 of 4 times, then: Android refused."),
            RepeatRun(4, 2, Step.Failed("Android refused.")).outcome("Back", "-"),
        )
    }
}
