package app.amethyst.core

import app.amethyst.core.capture.LoudnessLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoudnessLogTest {

    @Test
    fun `the loudness log counts rate and quantiles`() {
        val log = LoudnessLog()
        assertNull(log.summary())
        for (i in 0..100) log.add(i / 100f, nowMs = 1_000 + i * 20L)
        assertEquals("50/s · p10 0.10 · p50 0.50 · p90 0.90 · max 1.00", log.summary())
    }
}
