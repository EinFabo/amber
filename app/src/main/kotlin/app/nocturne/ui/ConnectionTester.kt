package app.nocturne.ui

import app.nocturne.Graph
import app.nocturne.core.audio.PcmAudio
import app.nocturne.core.engine.SelfCapturingSttEngine
import app.nocturne.core.engine.SttFailure
import app.nocturne.core.engine.TranscribeOptions
import app.nocturne.core.settings.EngineConfig
import kotlin.math.sin

/**
 * Checks that an engine's URL, model name and key actually work, without making the user
 * speak first.
 *
 * A one-second test clip carries no speech, so a correctly configured endpoint will usually
 * answer "nothing recognised" - which is a *successful* round trip. Reporting that as an
 * error would be the most confusing thing this screen could do, so it is translated back
 * into a pass.
 */
class ConnectionTester(private val graph: Graph) {

    sealed interface Result {
        data class Ok(val detail: String) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun test(config: EngineConfig): Result {
        val engine = graph.engineFactory.create(config).getOrElse { error ->
            return Result.Failed((error as? SttFailure)?.userMessage ?: error.message.orEmpty())
        }

        if (engine is SelfCapturingSttEngine) {
            return Result.Ok("Läuft auf dem Gerät - kein Verbindungstest nötig.")
        }

        return try {
            val outcome = engine.transcribe(
                testClip(),
                TranscribeOptions(languageTag = config.languageTag),
            )
            outcome.fold(
                onSuccess = { transcript ->
                    Result.Ok("Antwort nach ${transcript.latencyMs} ms: \"${transcript.text.take(40)}\"")
                },
                onFailure = { error ->
                    val failure = error as? SttFailure
                    if (failure != null && failure.userMessage.contains("nichts erkannt")) {
                        Result.Ok("Verbindung steht. Der Testclip enthält keine Sprache, " +
                            "deshalb kam kein Text zurück.")
                    } else {
                        Result.Failed(failure?.userMessage ?: error.message.orEmpty())
                    }
                },
            )
        } finally {
            runCatching { engine.close() }
        }
    }

    /** One second of a very quiet tone - enough to be valid audio, small enough to be cheap. */
    private fun testClip(): PcmAudio {
        val sampleRate = PcmAudio.DEFAULT_SAMPLE_RATE
        val samples = ShortArray(sampleRate) { i ->
            (sin(2.0 * Math.PI * 220.0 * i / sampleRate) * 600).toInt().toShort()
        }
        return PcmAudio(samples, sampleRate)
    }
}
