package app.amethyst.core.engine.whisper

import app.amethyst.core.audio.PcmAudio
import kotlin.math.ceil

/** Turns captured PCM into what whisper.cpp expects, and picks its decoding parameters. */
object WhisperInput {

    const val SAMPLE_RATE = 16_000

    /** Whisper's encoder sees 30 s at most, as 1500 positions: one per 320 samples. */
    const val FULL_AUDIO_CTX = 1500
    private const val SAMPLES_PER_CTX = SAMPLE_RATE * 30 / FULL_AUDIO_CTX

    /**
     * Context positions added on top of the audio itself. The ACFT fine-tune was trained to
     * cope with a shortened context, but cutting it exactly at the last sample clips the final
     * word; a little slack costs almost nothing.
     */
    private const val AUDIO_CTX_SLACK = 64

    /** Below this, a shortened context starts to hurt more than it saves. */
    private const val MIN_AUDIO_CTX = 256

    /**
     * whisper.cpp silently returns no text for input under one second, so short utterances
     * ("Ja.", "Okay.") are padded with silence instead of being lost.
     */
    const val MIN_SAMPLES = SAMPLE_RATE * 1_200 / 1_000

    /** Mono float samples in -1..1 at 16 kHz, padded to [MIN_SAMPLES]. */
    fun prepare(audio: PcmAudio): FloatArray {
        val mono = toMono(audio)
        val resampled = if (audio.sampleRate == SAMPLE_RATE) {
            mono
        } else {
            resample(mono, audio.sampleRate, SAMPLE_RATE)
        }
        val out = FloatArray(maxOf(resampled.size, MIN_SAMPLES))
        resampled.copyInto(out)
        return out
    }

    /**
     * How much of the encoder context to use. The FUTO models are fine-tuned so that a short
     * dictation does not have to be padded out to 30 s - that is where most of their speed
     * comes from. Anything longer than one window uses the full context, because whisper.cpp
     * then slides over the audio window by window.
     */
    fun audioCtxFor(sampleCount: Int): Int {
        val needed = ceil(sampleCount.toDouble() / SAMPLES_PER_CTX).toInt() + AUDIO_CTX_SLACK
        return if (needed >= FULL_AUDIO_CTX) 0 else needed.coerceAtLeast(MIN_AUDIO_CTX)
    }

    /**
     * whisper.cpp wants bare language codes ("de"), settings hold BCP-47 ("de-DE") or a list
     * of them ("de,en"), and no tag means detect any language.
     *
     * One language is forced. Several are handed over as "de,en", and native code detects per
     * dictation which of them was spoken. Forcing a language the speaker is not using makes
     * Whisper translate into it instead of transcribing - English speech comes out German.
     */
    fun languageFor(tag: String?): String {
        val codes = languageCodes(tag)
        return if (codes.isEmpty()) "auto" else codes.joinToString(",")
    }

    /** The distinct primary subtags in [tag], in order: "de-DE, en_US" -> [de, en]. */
    fun languageCodes(tag: String?): List<String> =
        tag.orEmpty()
            .split(',')
            .map { it.trim().substringBefore('-').substringBefore('_').lowercase() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun toMono(audio: PcmAudio): FloatArray {
        val samples = audio.samples
        if (audio.channels == 1) {
            return FloatArray(samples.size) { samples[it] / 32768f }
        }
        return FloatArray(audio.frameCount) { frame ->
            (samples[frame * 2] + samples[frame * 2 + 1]) / 65536f
        }
    }

    /** Linear interpolation. Capture already runs at 16 kHz; this only guards other rates. */
    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (input.isEmpty()) return input
        val outLength = (input.size.toLong() * to / from).toInt()
        val step = from.toDouble() / to
        return FloatArray(outLength) { i ->
            val position = i * step
            val index = position.toInt()
            val next = minOf(index + 1, input.lastIndex)
            val fraction = (position - index).toFloat()
            input[index] * (1 - fraction) + input[next] * fraction
        }
    }
}

/** Cleans what whisper.cpp returns before it reaches the normalizer and the text field. */
object WhisperOutput {

    /** Sound annotations such as "[Musik]", "(Lachen)" or "*Applaus*". */
    private val annotation = Regex("""\[[^\]]*]|\([^)]*\)|\*[^*]*\*|♪+""")

    /**
     * Lines Whisper is known to invent on silence or noise, because they were burned into its
     * training data from subtitle files. Matched as whole outputs only, so a user who really
     * dictates one of these words keeps it.
     */
    private val hallucinations = listOf(
        Regex("""(?i)^untertitel (im auftrag des zdf|der amara\.org-community|von stephanie geiges).*$"""),
        Regex("""(?i)^untertitelung (des zdf|aufgrund der amara\.org-community).*$"""),
        Regex("""(?i)^(vielen dank|danke) fürs zuschauen[.!]?$"""),
        Regex("""(?i)^(thanks for watching|thank you for watching)[.!]?$"""),
    )

    fun clean(raw: String): String {
        val text = raw.replace(annotation, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return if (hallucinations.any { it.matches(text) }) "" else text
    }
}
