package app.amethyst.ui

import app.amethyst.Graph
import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.engine.OpenAiCompatibleEngine
import app.amethyst.core.engine.SelfCapturingSttEngine
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.TranscribeOptions
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.EngineConfig
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
            return Result.Ok("Runs on the device - no connection test needed.")
        }

        return try {
            val outcome = engine.transcribe(
                testClip(),
                TranscribeOptions(languageTag = config.languageTag),
            )
            val local = engine.capabilities.trustLevel == TrustLevel.ON_DEVICE
            // For an enclave engine the round trip only counts if it went over the attested key;
            // say which way it went, so a test can never pass silently as plain CLOUD.
            val enclave = (engine as? OpenAiCompatibleEngine)?.attestation()?.let { attestation ->
                if (attestation.verified) " Enclave verified: ${attestation.detail}." else ""
            }.orEmpty()
            outcome.fold(
                onSuccess = { transcript ->
                    val served = transcript.servedBy?.let { " (via $it)" }.orEmpty()
                    Result.Ok("Answer after ${transcript.latencyMs} ms$served: \"${transcript.text.take(40)}\".$enclave")
                },
                onFailure = { error ->
                    val failure = error as? SttFailure
                    if (failure != null && failure.nothingRecognized) {
                        Result.Ok(
                            if (local) {
                                "The model runs. The test clip contains no speech, so no text came " +
                                    "back, but it did compute."
                            } else {
                                "The connection works. The test clip contains no speech, " +
                                    "so no text came back.$enclave"
                            },
                        )
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
