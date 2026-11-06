package app.amethyst.core

import app.amethyst.core.bubble.BubbleSpring
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BubbleSpringTest {

    private val spring = BubbleSpring()

    /** Runs the spring at 60 frames a second until it rests; the positions on the way. */
    private fun run(from: Double, to: Double, velocity: Double = 0.0, maxFrames: Int = 600): List<Double> {
        var motion = BubbleSpring.Motion(from, velocity)
        val path = mutableListOf(from)
        repeat(maxFrames) {
            motion = spring.step(motion, to, 1.0 / 60)
            path += motion.position
            if (spring.atRest(motion, to)) return path
        }
        return path
    }

    @Test
    fun `it comes to rest at the target`() {
        val path = run(from = 100.0, to = 933.0)
        assertEquals(933.0, path.last(), 0.5)
        assertTrue(path.size < 60, "settles within a second, took ${path.size} frames")
    }

    @Test
    fun `it overshoots only a little`() {
        val path = run(from = 100.0, to = 933.0, velocity = 3000.0)
        val beyond = path.max() - 933.0
        assertTrue(beyond > 0.0, "slightly damped: a fling carries it a bit past the edge")
        assertTrue(beyond < 60.0, "but not far: $beyond px")
    }

    @Test
    fun `a fling away from the target still comes back`() {
        val path = run(from = 500.0, to = 0.0, velocity = 2000.0)
        assertEquals(0.0, path.last(), 0.5)
    }

    @Test
    fun `a long frame does not make it explode`() {
        val motion = spring.step(BubbleSpring.Motion(0.0, 0.0), 1000.0, 0.5)
        assertTrue(motion.position in -200.0..1200.0, "position ${motion.position}")
    }

    @Test
    fun `at the target and still it is at rest`() {
        assertTrue(spring.atRest(BubbleSpring.Motion(10.2, 3.0), 10.0))
    }
}
