package app.amethyst.core

import app.amethyst.core.engine.RecognitionEstimate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecognitionEstimateTest {

    @Test
    fun `starts from the engine's typical rate and learns this phone's`() {
        val estimate = RecognitionEstimate(learningRate = 0.5)
        assertEquals(1_350, estimate.expectedMs("futo", audioMs = 10_000, typicalRate = 0.12, overheadMs = 150))

        // This phone needed 2.15 s for 10 s of audio: rate 0.2.
        estimate.learn("futo", audioMs = 10_000, tookMs = 2_150, overheadMs = 150)
        assertEquals(2_150, estimate.expectedMs("futo", 10_000, typicalRate = 0.12, overheadMs = 150))

        // Half way towards the next observation (rate 0.1): 0.15.
        estimate.learn("futo", audioMs = 10_000, tookMs = 1_150, overheadMs = 150)
        assertEquals(1_650, estimate.expectedMs("futo", 10_000, typicalRate = 0.12, overheadMs = 150))
    }

    @Test
    fun `each engine and model learns separately`() {
        val estimate = RecognitionEstimate()
        estimate.learn("turbo", audioMs = 10_000, tookMs = 35_000, overheadMs = 0)
        assertEquals(1_200, estimate.expectedMs("futo", 10_000, typicalRate = 0.12, overheadMs = 0))
    }

    @Test
    fun `the ring runs linearly, then creeps, and never shows done by itself`() {
        assertEquals(0.5f, RecognitionEstimate.progress(500f, 1_000f))
        assertEquals(0.9f, RecognitionEstimate.progress(900f, 1_000f))
        val late = RecognitionEstimate.progress(5_000f, 1_000f)
        assertTrue(late > 0.95f && late < 0.98f, "late = $late")
        assertEquals(0f, RecognitionEstimate.progress(100f, 0f))
    }
}
