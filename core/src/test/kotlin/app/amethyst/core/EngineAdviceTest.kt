package app.amethyst.core

import app.amethyst.core.engine.EngineAdvice
import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.PhoneFacts
import app.amethyst.core.engine.sherpa.ParakeetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineAdviceTest {

    private fun phone(ramMb: Long, freeMb: Long, whisperCpu: Boolean = true, onDevice: Boolean = true) =
        PhoneFacts(totalRamMb = ramMb, freeStorageMb = freeMb, whisperCpu = whisperCpu, androidOnDevice = onDevice)

    @Test
    fun `a phone with room gets parakeet`() {
        assertEquals(EngineOption.PARAKEET, EngineAdvice.recommend(phone(8_000, 4_000)).recommended)
    }

    @Test
    fun `a six gigabyte phone reports less and still gets parakeet`() {
        // totalMem leaves out what the kernel keeps: a 6 GB phone reports about 5.5 GB.
        assertEquals(EngineOption.PARAKEET, EngineAdvice.recommend(phone(5_500, 4_000)).recommended)
    }

    @Test
    fun `a four gigabyte phone gets futo 244`() {
        assertEquals(EngineOption.FUTO_244, EngineAdvice.recommend(phone(3_700, 4_000)).recommended)
    }

    @Test
    fun `a three gigabyte phone reporting less still gets futo 244`() {
        assertEquals(EngineOption.FUTO_244, EngineAdvice.recommend(phone(2_800, 4_000)).recommended)
    }

    @Test
    fun `parakeet needs its download plus the margin`() {
        val parakeetMb = ParakeetModel.TOTAL_BYTES / 1_000_000
        assertEquals(EngineOption.FUTO_244, EngineAdvice.recommend(phone(8_000, parakeetMb + 299)).recommended)
        assertEquals(EngineOption.PARAKEET, EngineAdvice.recommend(phone(8_000, parakeetMb + 300)).recommended)
    }

    @Test
    fun `no whisper cpu means no whisper option`() {
        val advice = EngineAdvice.recommend(phone(4_000, 4_000, whisperCpu = false))
        assertEquals(EngineOption.ANDROID, advice.recommended)
        val whisper = setOf(EngineOption.ACFT_LARGE, EngineOption.FUTO_244, EngineOption.FUTO_74)
        assertTrue(advice.others.none { it in whisper })
    }

    @Test
    fun `a small phone with little room gets futo 74`() {
        assertEquals(EngineOption.FUTO_74, EngineAdvice.recommend(phone(2_000, 500)).recommended)
    }

    @Test
    fun `nothing fits`() {
        assertEquals(EngineOption.ANDROID, EngineAdvice.recommend(phone(2_000, 50)).recommended)
    }

    @Test
    fun `the others list what fits, android and the server last`() {
        val advice = EngineAdvice.recommend(phone(8_000, 4_000))
        assertEquals(
            listOf(EngineOption.ACFT_LARGE, EngineOption.FUTO_244, EngineOption.FUTO_74, EngineOption.ANDROID, EngineOption.SERVER),
            advice.others,
        )
        assertFalse(advice.recommended in advice.others)
    }

    @Test
    fun `the quick setup keeps the voice on the phone where a model fits`() {
        assertTrue(EngineAdvice.keepsVoiceOnPhone(phone(8_000, 4_000, onDevice = false)))
        assertTrue(EngineAdvice.keepsVoiceOnPhone(phone(2_000, 500, onDevice = false)))
    }

    @Test
    fun `android's recognition keeps it there only where it recognizes on the phone`() {
        // The A20e: no dot product for Whisper, under 6 GB for Parakeet, Android 11 without an
        // on-device recognizer - the quick setup's pick may send the voice to Google.
        val a20e = phone(2_800, 20_000, whisperCpu = false, onDevice = false)
        assertEquals(EngineOption.ANDROID, EngineAdvice.recommend(a20e).recommended)
        assertFalse(EngineAdvice.keepsVoiceOnPhone(a20e))
        assertTrue(EngineAdvice.keepsVoiceOnPhone(a20e.copy(androidOnDevice = true)))
    }

    @Test
    fun `a phone with no recognizer of android's and no model that fits is pointed at a server`() {
        val bare = PhoneFacts(totalRamMb = 2_048, freeStorageMb = 500, whisperCpu = false, androidOnDevice = false, androidRecognizer = false)
        val advice = EngineAdvice.recommend(bare)
        assertEquals(EngineOption.SERVER, advice.recommended)
        assertFalse(EngineOption.ANDROID in advice.others)
        assertFalse(advice.recommended in advice.others)
        // Nothing recognizes, so nothing "stays on this phone".
        assertFalse(EngineAdvice.keepsVoiceOnPhone(bare))
    }

    @Test
    fun `a phone with no recognizer of android's gets the model that fits and no android option`() {
        val advice = EngineAdvice.recommend(PhoneFacts(8_000, 4_000, whisperCpu = true, androidOnDevice = false, androidRecognizer = false))
        assertEquals(EngineOption.PARAKEET, advice.recommended)
        assertFalse(EngineOption.ANDROID in advice.others)
        assertTrue(EngineOption.SERVER in advice.others)
    }
}
