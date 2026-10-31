package app.amethyst.core.commands

import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.engine.SttEngine
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.TranscribeOptions
import app.amethyst.core.engine.whisper.WhisperInput
import app.amethyst.core.text.Transcript
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Which language a voice command is recognized in. A command is a second of audio, too little
 * for Whisper to tell German from English: on "Öffne WhatsApp" the English app name tipped it
 * to English, and the German words came out as "If now what's up?". So an engine with a list
 * of languages hears a command in one language at a time - the phone's language first, then
 * the rest of the list in its order - and the rules get the first words they understand. Each
 * pass carries a short vocabulary of commands in its language as the prompt.
 *
 * Diagnostics get one line per pass ("heard as de: …") and which one was taken; logcat none.
 */
object CommandLanguages {

    /**
     * The languages to try, in order: the phone's first when the list has it, then the list's
     * others, at most [maxAttempts]. One language, "auto" or none is one attempt, as set.
     */
    fun attempts(languageTag: String?, phoneLanguage: String?, maxAttempts: Int = Int.MAX_VALUE): List<String?> {
        val codes = WhisperInput.languageCodes(languageTag)
        if (codes.size <= 1) return listOf(languageTag)
        val phone = WhisperInput.languageCodes(phoneLanguage).firstOrNull()
        val ordered = if (phone != null && phone in codes) listOf(phone) + (codes - phone) else codes
        return ordered.take(maxAttempts.coerceAtLeast(1))
    }

    /**
     * Commands in their words, as speech engines write them: the prompt for a pass in that
     * language. A direction comes with its opposite, so neither is pulled toward; no swipe,
     * which an echo could turn into an archived mail; and no run of words across two phrases
     * is itself a command ("Go back. Home screen." held "go back home"), else [isEcho] would
     * swallow it. "Öffne" goes last: anything after it would read as an app's name.
     */
    private val PROMPTS = mapOf(
        "de" to "Zurück. Lauter. Scroll runter. Taschenlampe an. Leiser. Scroll hoch. Taschenlampe aus. Startbildschirm. Öffne WhatsApp.",
        "en" to "Go back. Louder. Scroll down. Flashlight on. Quieter. Scroll up. Flashlight off. Home screen. Open WhatsApp.",
    )

    /** The command vocabulary for a pass in [language] ("de", "de-DE"); null for any other or none. */
    fun promptFor(language: String?): String? =
        WhisperInput.languageCodes(language).singleOrNull()?.let(PROMPTS::get)

    /**
     * Whether [heard] holds [prompt] read back - Whisper's known answer to audio it hears
     * nothing in, and what bleeds into the words it does hear: all of it a run of the prompt
     * across two of its phrases, or anywhere in it two or more whole phrases one right after the
     * other in the prompt's order, some left out between them or not ("Kopieren. Zurück.
     * Lauter."). Several commands run in one go now, so that must never run them. One phrase
     * alone is what the user may well have said ("Zurück"), and so is part of one ("WhatsApp");
     * with "und" between them, or the other way round, they were said.
     */
    fun isEcho(heard: String, prompt: String?): Boolean {
        if (prompt == null) return false
        val words = VoiceCommands.normalize(heard).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val phrases = prompt.split('.').map(VoiceCommands::normalize).filter { it.isNotEmpty() }.map { it.split(' ') }
        // Each word of the prompt with the number of the phrase it belongs to.
        val promptWords = phrases.flatMapIndexed { phrase, text -> text.map { it to phrase } }
        val whole = (0..promptWords.size - words.size).any { start ->
            val run = promptWords.subList(start, start + words.size)
            run.map { it.first } == words && run.first().second != run.last().second
        }
        return whole || words.indices.any { start -> phrasesInOrder(words, start, phrases, after = -1) >= 2 }
    }

    /** How many whole [phrases] follow each other in [words] from [at] on, each later in the prompt than the one before. */
    private fun phrasesInOrder(words: List<String>, at: Int, phrases: List<List<String>>, after: Int): Int =
        phrases.withIndex()
            .filter { (i, phrase) -> i > after && words.size - at >= phrase.size && words.subList(at, at + phrase.size) == phrase }
            .maxOfOrNull { (i, phrase) -> 1 + phrasesInOrder(words, at + phrase.size, phrases, i) } ?: 0

    /** What came of a command's recognition. */
    class Recognition(
        /** The words for the rules - the first they understand, else the first heard - or why there are none. */
        val result: Result<Transcript>,
        /** How many passes ran: a recognition-time estimate learns only from one. */
        val passes: Int,
    )

    /**
     * Recognizes a command's [audio] with [engine], language by language where the engine takes
     * one per request ([app.amethyst.core.engine.EngineCapabilities.commandLanguageAttempts]),
     * with the command vocabulary as prompt where it takes one, and stops at the first words
     * the rules surely understand ([VoiceCommands.parseStrict], the installed apps from
     * [appLabels], read on [readOn]). An engine that takes neither gets the one plain pass it
     * always had. Each pass is [note]d for Diagnostics as it ends, so a tap that stops the next
     * one still leaves it there.
     */
    suspend fun recognize(
        engine: SttEngine,
        audio: PcmAudio,
        options: TranscribeOptions,
        phoneLanguage: String?,
        appLabels: () -> List<String>,
        readOn: CoroutineContext = EmptyCoroutineContext,
        note: (String) -> Unit = {},
    ): Recognition {
        // Strictly: a misheard "öffne" is assumed only for the words that stand, never from a
        // later pass - "Lösche WhatsApp" refused in German must not open it as "Lush WhatsApp".
        suspend fun understood(text: String) =
            withContext(readOn) { VoiceCommands.parseStrict(text, appLabels) is Understanding.Understood }
        val caps = engine.capabilities
        if (caps.commandLanguageAttempts <= 1 && !caps.commandPrompt) {
            return Recognition(engine.transcribe(audio, options), passes = 1)
        }
        val languages = attempts(options.languageTag, phoneLanguage, caps.commandLanguageAttempts)
        var firstWords: Pair<String?, Transcript>? = null
        var firstNothing: Throwable? = null
        var passes = 0
        for (language in languages) {
            val prompt = if (caps.commandPrompt) promptFor(language) else null
            val result = engine.transcribe(audio, options.copy(languageTag = language, precedingText = prompt))
            passes++
            val label = "heard as ${language ?: "auto"}"
            val error = result.exceptionOrNull()
            if (error != null && (error as? SttFailure)?.nothingRecognized != true) {
                // The engine itself failed: another language would fail the same way.
                note("$label: failed (${technical(error)}) - no further attempt")
                if (firstWords == null) return Recognition(result, passes)
                break
            }
            if (error != null) {
                note("$label: nothing (${technical(error)})")
                firstNothing = firstNothing ?: error
                continue
            }
            val transcript = result.getOrThrow()
            val text = transcript.text.trim()
            if (isEcho(text, prompt)) {
                note("$label: \"$text\" - the prompt read back, taken as nothing heard")
                firstNothing = firstNothing ?: SttFailure(SttFailure.NOTHING_RECOGNIZED, "only the command prompt came back")
                continue
            }
            if (understood(text)) {
                note("$label: \"$text\" - understood")
                if (passes > 1) note("taken: ${language ?: "auto"}, the first the rules understand")
                return Recognition(Result.success(transcript), passes)
            }
            note("$label: \"$text\" - not understood")
            if (firstWords == null) firstWords = language to transcript
        }
        val words = firstWords
        if (words != null) {
            if (passes > 1) note("taken: ${words.first ?: "auto"} - none understood, so the first words heard stand")
            return Recognition(Result.success(words.second), passes)
        }
        if (passes > 1) note("taken: none - nothing heard in any language")
        return Recognition(
            Result.failure(firstNothing ?: SttFailure(SttFailure.NOTHING_RECOGNIZED, "no language to try")),
            passes,
        )
    }

    private fun technical(error: Throwable): String = (error as? SttFailure)?.technical ?: error.toString()
}
