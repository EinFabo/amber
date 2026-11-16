package app.amethyst.core

import app.amethyst.core.capture.StartToneMute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StartToneMuteTest {

    /** 30 ms frames at 16 kHz. */
    private fun frame() = ShortArray(480) { 1_000 }

    @Test
    fun `the first moments are silenced, frame by frame, and nothing after them`() {
        val mute = StartToneMute(16_000, 250)
        // 250 ms are 4000 samples: eight whole frames of 480, then 160 of the ninth.
        repeat(8) {
            val f = frame()
            mute.apply(f, f.size)
            assertTrue(f.all { it == 0.toShort() }, "frame $it")
        }
        val ninth = frame()
        mute.apply(ninth, ninth.size)
        assertTrue(ninth.take(160).all { it == 0.toShort() })
        assertTrue(ninth.drop(160).all { it == 1_000.toShort() })
        val tenth = frame()
        mute.apply(tenth, tenth.size)
        assertTrue(tenth.all { it == 1_000.toShort() })
    }

    @Test
    fun `a short read silences only what was read, and a failed read changes nothing`() {
        val mute = StartToneMute(16_000, 250)
        val f = frame()
        mute.apply(f, 100)
        assertEquals(100, f.count { it == 0.toShort() })
        mute.apply(f, -3)
        assertEquals(100, f.count { it == 0.toShort() })
    }

    @Test
    fun `without the tone nothing is silenced`() {
        val mute = StartToneMute(16_000, 0)
        val f = frame()
        mute.apply(f, f.size)
        assertTrue(f.all { it == 1_000.toShort() })
    }
}
