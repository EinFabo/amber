package app.amethyst.core

import app.amethyst.core.engine.whisper.WhisperTimings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * whisper.cpp's own timings for one transcription, as the JNI bridge hands them over: how the
 * language was chosen, then whisper_print_timings' lines before the run (whose fallback counts
 * whisper.cpp never resets) and after it. For Diagnostics: how much a second encoder pass for
 * the language detection and the decoder's fallbacks cost on the phone.
 */
class WhisperTimingsTest {

    private fun printed(failP: Int, failH: Int, encode: String = "0.00 ms /     1 runs", decode: String = "0.00 ms /     1 runs") = """
        |
        |whisper_print_timings:     load time =   812.40 ms
        |whisper_print_timings:     fallbacks = ${"%3d".format(failP)} p / ${"%3d".format(failH)} h
        |whisper_print_timings:      mel time =    31.20 ms
        |whisper_print_timings:   sample time =   118.75 ms /   212 runs (     0.56 ms per run)
        |whisper_print_timings:   encode time = $encode (   700.25 ms per run)
        |whisper_print_timings:   decode time = $decode (    15.00 ms per run)
        |whisper_print_timings:   batchd time =    90.10 ms /     3 runs (    30.03 ms per run)
        |whisper_print_timings:   prompt time =     0.00 ms /     1 runs (     0.00 ms per run)
        |whisper_print_timings:    total time =  2350.00 ms
        |""".trimMargin()

    private fun report(detection: String, before: String, after: String) = "detection: $detection\n--- before\n$before\n--- after\n$after"

    private val dictation = report(
        "among 812",
        printed(2, 1),
        printed(3, 1, encode = "1400.50 ms /     2 runs", decode = "  570.00 ms /    38 runs"),
    )

    @Test
    fun `one dictation in a language list - detected, then transcribed`() {
        val timings = WhisperTimings.parse(dictation)!!

        assertEquals(1, timings.calls)
        assertEquals(1, timings.detectedAmong)
        assertEquals(812L, timings.detectMs)
        assertEquals(WhisperTimings.Stage(1400.5, 2), timings.encode)
        assertEquals(WhisperTimings.Stage(570.0, 38), timings.decode)
        assertEquals(WhisperTimings.Stage(90.1, 3), timings.batchd)
        assertEquals(WhisperTimings.Stage(0.0, 0), timings.prompt, "no time means no run, whatever the count says")
        assertEquals(WhisperTimings.Stage(118.75, 212), timings.sample)
        assertEquals(31.2, timings.melMs)
        assertEquals(1, timings.fallbacksLogprob, "only this run's, not the model's whole life")
        assertEquals(0, timings.fallbacksEntropy)
        assertEquals(2350.0, timings.totalMs)
        assertEquals(
            "whisper: language detected among the list in 812 ms, encode 2× 1.4 s, decode 38× 570 ms, batchd 3× 90 ms, " +
                "sample 119 ms, mel 31 ms, fallbacks 1 logprob / 0 entropy, total 2.4 s",
            timings.summary(),
        )
    }

    @Test
    fun `a fixed language detects nothing, and whisper's own auto says so`() {
        val fixed = WhisperTimings.parse(report("none", printed(0, 0), printed(0, 0, encode = "700.00 ms /     1 runs")))!!
        assertEquals(0, fixed.detectedAmong + fixed.detectedAuto)
        assertEquals("whisper: language fixed, encode 1× 700 ms", fixed.summary().substringBefore(", batchd"))

        val auto = WhisperTimings.parse(report("auto", printed(0, 0), printed(0, 0, encode = "1400.00 ms /     2 runs")))!!
        assertEquals(1, auto.detectedAuto)
        assertEquals("whisper: language auto-detected by whisper, encode 2× 1.4 s", auto.summary().substringBefore(", batchd"))
    }

    @Test
    fun `several calls in one dictation add up - chunks, or a command's language passes`() {
        val pass = WhisperTimings.parse(report("none", printed(0, 0), printed(0, 0, encode = "300.00 ms /     1 runs")))!!
        val both = WhisperTimings.parse(dictation)!! + pass

        assertEquals(2, both.calls)
        assertEquals(WhisperTimings.Stage(1700.5, 3), both.encode)
        assertEquals(1, both.detectedAmong)
        assertEquals(
            "whisper 2 calls: language detected among the list 1× in 812 ms, encode 3× 1.7 s",
            both.summary().substringBefore(", decode"),
        )
    }

    @Test
    fun `a report that is not one gives nothing rather than wrong numbers`() {
        assertNull(WhisperTimings.parse(""))
        assertNull(WhisperTimings.parse("garbage"))
        assertNull(WhisperTimings.parse(report("among 5", "", "")))
    }
}
