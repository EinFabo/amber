package app.amethyst.core

import app.amethyst.core.capture.RecordingLimit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecordingLimitTest {

    @Test
    fun `the sign shows in the last 15 s of five minutes and burns down to the end`() {
        assertNull(RecordingLimit.left(0, 300_000))
        assertNull(RecordingLimit.left(284_999, 300_000))
        assertEquals(1f, RecordingLimit.left(285_000, 300_000))
        assertEquals(0.5f, RecordingLimit.left(292_500, 300_000))
        assertEquals(0f, RecordingLimit.left(300_000, 300_000))
        assertEquals(0f, RecordingLimit.left(301_000, 300_000), "past the limit it stays empty")
    }

    @Test
    fun `a short limit gets a quarter of itself, not half the recording`() {
        assertNull(RecordingLimit.left(22_000, 30_000))
        assertEquals(1f, RecordingLimit.left(22_500, 30_000))
        assertEquals(0f, RecordingLimit.left(30_000, 30_000))
    }

    @Test
    fun `no limit, no sign`() {
        assertNull(RecordingLimit.left(1_000_000, Int.MAX_VALUE))
        assertNull(RecordingLimit.left(0, 0))
    }
}
