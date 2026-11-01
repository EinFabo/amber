package app.amethyst.core.engine.whisper

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Where whisper.cpp spent one transcription - or several added up, the chunks of a dictation or
 * a command's language passes - for the dictation's Diagnostics: whether the language was
 * detected (a second encoder pass when the language list has several), the encoder and decoder
 * runs, and the decoder's fallbacks (a retry at a higher temperature after a failed decode).
 * Measured to decide whether reusing the detection's encoder output and fewer retries pay off;
 * nothing here changes how Whisper runs.
 */
data class WhisperTimings(
    /** How many transcriptions this adds up. */
    val calls: Int,
    /** Calls whose language was picked among the list's by the JNI bridge (detect_among). */
    val detectedAmong: Int,
    /** Wall time of those detections. */
    val detectMs: Long,
    /** Calls left to whisper.cpp's own "auto", which detects inside whisper_full. */
    val detectedAuto: Int,
    val encode: Stage,
    val decode: Stage,
    /** Batched decoding, several decoders at once (best of n). */
    val batchd: Stage,
    val prompt: Stage,
    val sample: Stage,
    val melMs: Double,
    /** Decodes that failed the log-probability threshold. */
    val fallbacksLogprob: Int,
    /** Decodes that failed the entropy (repetition) threshold. */
    val fallbacksEntropy: Int,
    /** From the start of the language detection to the end of the transcription. */
    val totalMs: Double,
) {

    /** Time spent in one kind of step, and how many times it ran. */
    data class Stage(val ms: Double, val runs: Int) {
        operator fun plus(other: Stage) = Stage(ms + other.ms, runs + other.runs)
    }

    operator fun plus(other: WhisperTimings) = WhisperTimings(
        calls = calls + other.calls,
        detectedAmong = detectedAmong + other.detectedAmong,
        detectMs = detectMs + other.detectMs,
        detectedAuto = detectedAuto + other.detectedAuto,
        encode = encode + other.encode,
        decode = decode + other.decode,
        batchd = batchd + other.batchd,
        prompt = prompt + other.prompt,
        sample = sample + other.sample,
        melMs = melMs + other.melMs,
        fallbacksLogprob = fallbacksLogprob + other.fallbacksLogprob,
        fallbacksEntropy = fallbacksEntropy + other.fallbacksEntropy,
        totalMs = totalMs + other.totalMs,
    )

    /** One line, numbers only: "whisper: language fixed, encode 1× 700 ms, decode 38× 570 ms, …". */
    fun summary(): String = buildList {
        val several = calls > 1
        val language = buildList {
            if (detectedAmong > 0) add("detected among the list ${if (several) "$detectedAmong× " else ""}in ${ms(detectMs.toDouble())}")
            if (detectedAuto > 0) add("auto-detected by whisper" + if (several) " $detectedAuto×" else "")
        }
        add("language " + language.ifEmpty { listOf("fixed") }.joinToString(", "))
        listOf("encode" to encode, "decode" to decode, "batchd" to batchd, "prompt" to prompt).forEach { (name, stage) ->
            if (stage.runs > 0) add("$name ${stage.runs}× ${ms(stage.ms)}")
        }
        if (sample.runs > 0) add("sample ${ms(sample.ms)}")
        if (melMs > 0) add("mel ${ms(melMs)}")
        add("fallbacks $fallbacksLogprob logprob / $fallbacksEntropy entropy")
        add("total ${ms(totalMs)}")
    }.joinToString(", ", prefix = if (calls > 1) "whisper $calls calls: " else "whisper: ")

    companion object {

        private val STAGE = Regex("""(\w+) time =\s*([\d.]+) ms /\s*(\d+) runs""")
        private val SINGLE = Regex("""(mel|total) time =\s*([\d.]+) ms""")
        private val FALLBACKS = Regex("""fallbacks =\s*(\d+) p /\s*(\d+) h""")

        /**
         * Reads the bridge's report: a "detection: among <ms>", "detection: auto" or
         * "detection: none" line, then whisper_print_timings' lines under "--- before" and
         * "--- after" the run. Its timings were reset at the start; its fallback counts never
         * are, so this run's are the difference. Null for anything else.
         */
        fun parse(report: String): WhisperTimings? {
            val detection = report.lineSequence().firstOrNull()?.removePrefix("detection: ")?.trim() ?: return null
            val before = report.substringAfter("\n--- before\n", "").substringBefore("\n--- after\n")
            val after = report.substringAfter("\n--- after\n", "").takeIf { it.isNotBlank() } ?: return null
            val stages = STAGE.findAll(after).associate { match ->
                val ms = match.groupValues[2].toDoubleOrNull() ?: 0.0
                // whisper.cpp prints at least "1 runs" so as not to divide by zero.
                match.groupValues[1] to Stage(ms, if (ms > 0) match.groupValues[3].toInt() else 0)
            }
            val encode = stages["encode"] ?: return null
            val singles = SINGLE.findAll(after).associate { it.groupValues[1] to (it.groupValues[2].toDoubleOrNull() ?: 0.0) }
            fun fallbacks(text: String) = FALLBACKS.find(text)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() } ?: (0 to 0)
            val (pBefore, hBefore) = fallbacks(before)
            val (pAfter, hAfter) = fallbacks(after)
            val among = detection.startsWith("among")
            val none = Stage(0.0, 0)
            return WhisperTimings(
                calls = 1,
                detectedAmong = if (among) 1 else 0,
                detectMs = if (among) detection.removePrefix("among").trim().toLongOrNull() ?: 0L else 0L,
                detectedAuto = if (detection == "auto") 1 else 0,
                encode = encode,
                decode = stages["decode"] ?: none,
                batchd = stages["batchd"] ?: none,
                prompt = stages["prompt"] ?: none,
                sample = stages["sample"] ?: none,
                melMs = singles["mel"] ?: 0.0,
                fallbacksLogprob = (pAfter - pBefore).coerceAtLeast(0),
                fallbacksEntropy = (hAfter - hBefore).coerceAtLeast(0),
                totalMs = singles["total"] ?: 0.0,
            )
        }

        private fun ms(value: Double): String =
            if (value >= 1_000) String.format(Locale.ROOT, "%.1f s", value / 1000.0) else "${value.roundToLong()} ms"
    }
}
