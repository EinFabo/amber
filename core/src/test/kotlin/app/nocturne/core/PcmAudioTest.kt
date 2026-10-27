package app.nocturne.core

import app.nocturne.core.audio.PcmAudio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmAudioTest {

    @Test
    fun `wav container has a valid RIFF header`() {
        val audio = PcmAudio(ShortArray(16_000) { 0 }, sampleRate = 16_000)
        val wav = audio.toWav()

        assertEquals(PcmAudio.WAV_HEADER_SIZE + 32_000, wav.size)
        assertEquals("RIFF", wav.decodeToString(0, 4))
        assertEquals("WAVE", wav.decodeToString(8, 12))
        assertEquals("fmt ", wav.decodeToString(12, 16))
        assertEquals("data", wav.decodeToString(36, 40))
        assertEquals(16_000, readLe32(wav, 24), "sample rate")
        assertEquals(32_000, readLe32(wav, 40), "data chunk size")
        assertEquals(36 + 32_000, readLe32(wav, 4), "riff chunk size")
    }

    @Test
    fun `samples survive the round trip little-endian`() {
        val audio = PcmAudio(shortArrayOf(0, 1, -1, 32_767, -32_768))
        val wav = audio.toWav()
        val payload = wav.copyOfRange(PcmAudio.WAV_HEADER_SIZE, wav.size)
        val decoded = ShortArray(payload.size / 2) { i ->
            ((payload[i * 2].toInt() and 0xFF) or (payload[i * 2 + 1].toInt() shl 8)).toShort()
        }
        assertTrue(shortArrayOf(0, 1, -1, 32_767, -32_768).contentEquals(decoded))
    }

    @Test
    fun `duration is derived from sample rate`() {
        assertEquals(1_000, PcmAudio(ShortArray(16_000)).durationMs)
        assertEquals(500, PcmAudio(ShortArray(8_000)).durationMs)
    }

    @Test
    fun `rms is zero for silence and positive for a tone`() {
        assertEquals(0.0, PcmAudio(ShortArray(100)).rms())
        assertTrue(PcmAudio(ShortArray(100) { 1_000 }).rms() > 900.0)
    }

    private fun readLe32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)
}
