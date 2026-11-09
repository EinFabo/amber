package app.amethyst.core

import app.amethyst.core.bubble.LevelMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LevelMeterTest {

    /** Follows [target] in frames of [frameMs] for [forMs], starting at [from]. */
    private fun LevelMeter.run(target: Float, from: Long, forMs: Long, frameMs: Long = 16): Long {
        var t = from
        while (t < from + forMs) {
            t += frameMs
            follow(target, t)
        }
        return t
    }

    @Test
    fun `a word shows within a few frames`() {
        val meter = LevelMeter()
        meter.follow(0f, 0)
        meter.run(0.8f, from = 0, forMs = 100)
        assertTrue(meter.level > 0.7f, "level ${meter.level}")
    }

    @Test
    fun `a short gap between words is a dip, not a drop to nothing`() {
        val meter = LevelMeter()
        val t = meter.run(0.8f, from = 0, forMs = 300)
        // Android's recognizer reports silence between words: 120 ms of it.
        meter.run(0f, from = t, forMs = 120)
        assertTrue(meter.level > 0.5f, "level ${meter.level}")
    }

    @Test
    fun `silence brings it down`() {
        val meter = LevelMeter()
        val t = meter.run(0.8f, from = 0, forMs = 300)
        meter.run(0f, from = t, forMs = 1000)
        assertTrue(meter.level < 0.05f, "level ${meter.level}")
        meter.run(0f, from = t + 1000, forMs = 1000)
        assertTrue(meter.settledAt(0f), "level ${meter.level}")
    }

    @Test
    fun `the same voice looks the same however often it is reported`() {
        // Amber's recorder draws 60 frames a second; a recognizer may report 10 times a second.
        val often = LevelMeter().apply { follow(0f, 0); run(0.6f, from = 0, forMs = 200, frameMs = 16) }
        val rarely = LevelMeter().apply { follow(0f, 0); run(0.6f, from = 0, forMs = 200, frameMs = 100) }
        assertEquals(often.level, rarely.level, 0.02f)
    }

    @Test
    fun `a late frame counts as a short step, and reset starts from silence`() {
        val meter = LevelMeter()
        meter.follow(0f, 0)
        meter.follow(1f, 5_000)
        assertTrue(meter.level < 1f)
        meter.reset()
        assertEquals(0f, meter.level)
        assertTrue(meter.settledAt(0f))
    }
}
