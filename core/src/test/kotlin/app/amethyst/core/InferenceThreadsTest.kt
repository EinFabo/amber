package app.amethyst.core

import app.amethyst.core.engine.InferenceThreads
import app.amethyst.core.engine.InferenceThreads.Thermal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InferenceThreadsTest {

    @Test
    fun `a cool phone gets up to four threads and the performance hint`() {
        assertEquals(InferenceThreads.Plan(4, true), InferenceThreads.choose(8, Thermal.NORMAL, saver = false))
        assertEquals(InferenceThreads.Plan(4, true), InferenceThreads.choose(6, Thermal.LIGHT, saver = false))
        assertEquals(InferenceThreads.Plan(3, true), InferenceThreads.choose(3, Thermal.NORMAL, saver = false))
    }

    @Test
    fun `a warm phone gets fewer threads and no hint`() {
        for (thermal in listOf(Thermal.MODERATE, Thermal.SEVERE, Thermal.CRITICAL)) {
            val plan = InferenceThreads.choose(8, thermal, saver = false)
            assertEquals(2, plan.threads)
            assertFalse(plan.performanceHint)
        }
    }

    @Test
    fun `battery saver alone gets fewer threads and no hint`() {
        assertEquals(InferenceThreads.Plan(2, false), InferenceThreads.choose(8, Thermal.NORMAL, saver = true))
    }

    @Test
    fun `at least two threads, never more than the cores, never fewer than one`() {
        assertEquals(2, InferenceThreads.choose(4, Thermal.SEVERE, saver = false).threads)
        assertEquals(1, InferenceThreads.choose(1, Thermal.SEVERE, saver = true).threads)
        assertEquals(1, InferenceThreads.choose(0, Thermal.NORMAL, saver = false).threads)
        assertTrue(InferenceThreads.choose(2, Thermal.SEVERE, saver = true).threads == 2)
    }
}
