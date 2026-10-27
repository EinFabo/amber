package app.nocturne.core

import app.nocturne.core.capture.PcmAccumulator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmAccumulatorTest {

    @Test
    fun `an empty accumulator builds empty audio`() {
        val audio = PcmAccumulator().build()
        assertEquals(0, audio.samples.size)
        assertEquals(0, audio.durationMs)
    }

    @Test
    fun `concatenates frames in order`() {
        val accumulator = PcmAccumulator()
        accumulator.append(shortArrayOf(1, 2, 3))
        accumulator.append(shortArrayOf(4, 5))

        assertTrue(shortArrayOf(1, 2, 3, 4, 5).contentEquals(accumulator.build().samples))
        assertEquals(5, accumulator.sampleCount)
    }

    @Test
    fun `keeps only the samples a short read delivered`() {
        val accumulator = PcmAccumulator()
        val frame = ShortArray(320) { 7 }
        // The final read of a stream is almost always shorter than the buffer.
        accumulator.append(frame, length = 100)

        assertEquals(100, accumulator.sampleCount)
        assertEquals(100, accumulator.build().samples.size)
    }

    @Test
    fun `ignores empty and negative reads`() {
        val accumulator = PcmAccumulator()
        accumulator.append(ShortArray(10), length = 0)
        accumulator.append(ShortArray(10), length = -3)
        assertEquals(0, accumulator.sampleCount)
    }

    @Test
    fun `never reads past the end of a frame`() {
        val accumulator = PcmAccumulator()
        accumulator.append(ShortArray(8) { 1 }, length = 99)
        assertEquals(8, accumulator.sampleCount)
    }

    @Test
    fun `derives duration from the sample rate`() {
        val accumulator = PcmAccumulator(sampleRate = 16_000)
        accumulator.append(ShortArray(8_000))
        assertEquals(500, accumulator.durationMs)
    }

    @Test
    fun `reset clears everything`() {
        val accumulator = PcmAccumulator()
        accumulator.append(shortArrayOf(1, 2, 3))
        accumulator.reset()
        assertEquals(0, accumulator.sampleCount)
        assertEquals(0, accumulator.build().samples.size)
    }

    @Test
    fun `loudness is silent at zero and full at the ceiling`() {
        assertEquals(0f, PcmAccumulator.normalizeLoudness(0.0))
        assertEquals(0f, PcmAccumulator.normalizeLoudness(1.0))
        assertEquals(1f, PcmAccumulator.normalizeLoudness(32_768.0))
        assertEquals(1f, PcmAccumulator.normalizeLoudness(99_999.0), "must clamp")
    }

    @Test
    fun `normal speech lands in the upper half, not near zero`() {
        // A linear scale would put a 2000-RMS frame at 6% and the ring would look dead.
        val speech = PcmAccumulator.normalizeLoudness(2_000.0)
        assertTrue(speech > 0.6f, "expected a visible ring, got $speech")
        assertTrue(speech < 1f)
    }

    @Test
    fun `louder frames always map higher`() {
        val quiet = PcmAccumulator.normalizeLoudness(200.0)
        val mid = PcmAccumulator.normalizeLoudness(2_000.0)
        val loud = PcmAccumulator.normalizeLoudness(20_000.0)
        assertTrue(quiet < mid && mid < loud, "$quiet / $mid / $loud")
    }
}
