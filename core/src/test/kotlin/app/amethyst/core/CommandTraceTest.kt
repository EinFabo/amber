package app.amethyst.core

import app.amethyst.core.commands.CommandTrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandTraceTest {

    @Test
    fun `keeps the lines in the order they were noted`() {
        val trace = CommandTrace()
        trace.note("gate: passed")
        trace.note("picked: RecyclerView")
        trace.addAll(listOf("step 1: moved", "step 2: moved"))
        assertEquals(listOf("gate: passed", "picked: RecyclerView", "step 1: moved", "step 2: moved"), trace.lines)
    }

    @Test
    fun `starts empty and drops blank notes`() {
        val trace = CommandTrace()
        assertTrue(trace.lines.isEmpty())
        trace.note("   ")
        trace.note("")
        assertTrue(trace.lines.isEmpty())
    }

    @Test
    fun `a long line is cut to the limit and says so`() {
        val trace = CommandTrace(maxChars = 20)
        trace.note("x".repeat(500))
        val line = trace.lines.single()
        assertEquals(20, line.length)
        assertTrue(line.endsWith("…"), line)
    }

    @Test
    fun `a line break inside a note keeps it one line`() {
        val trace = CommandTrace()
        trace.note("first\nsecond\r\nthird")
        assertEquals(listOf("first second third"), trace.lines)
    }

    @Test
    fun `up to the cap every line is kept`() {
        val trace = CommandTrace(maxLines = 10)
        repeat(10) { trace.note("step $it") }
        assertEquals((0 until 10).map { "step $it" }, trace.lines)
    }

    @Test
    fun `past the cap the first and the last lines stay and the middle is counted`() {
        // The first lines say what was understood, the last ones why the run stopped.
        val trace = CommandTrace(maxLines = 10)
        repeat(25) { trace.note("step $it") }
        val lines = trace.lines
        assertEquals(10, lines.size)
        assertEquals((0 until 5).map { "step $it" }, lines.take(5))
        assertEquals("… 16 lines left out", lines[5])
        assertEquals((21 until 25).map { "step $it" }, lines.drop(6))
    }

    @Test
    fun `the default bounds are forty lines of at most 160 characters`() {
        val trace = CommandTrace()
        repeat(1_000) { trace.note("y".repeat(400)) }
        assertEquals(CommandTrace.MAX_LINES, trace.lines.size)
        assertEquals(40, CommandTrace.MAX_LINES)
        assertEquals(160, CommandTrace.MAX_CHARS)
        assertTrue(trace.lines.all { it.length <= CommandTrace.MAX_CHARS })
    }
}
