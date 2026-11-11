package app.amethyst.ui

import android.content.Context
import app.amethyst.Graph
import app.amethyst.R
import app.amethyst.Texts
import app.amethyst.core.audio.PcmAudio
import app.amethyst.core.engine.ConfigIssue
import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.OpenAiCompatibleEngine
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.engine.Servers
import app.amethyst.core.engine.SelfCapturingSttEngine
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.TranscribeOptions
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.text.TextCleanup
import app.amethyst.core.settings.LanguageList
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
class ConnectionTester(private val graph: Graph, private val context: Context) {

    sealed interface Result {
        data class Ok(val detail: String) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun test(config: EngineConfig): Result {
        if (!Servers.recognizes(config)) return testCleanup(config)
        val engine = graph.engineFactory.create(config).getOrElse { error ->
            return Result.Failed(Texts.failure(context, error))
        }

        if (engine is SelfCapturingSttEngine) {
            return Result.Ok(context.getString(R.string.test_runs_on_phone))
        }

        return try {
            val outcome = engine.transcribe(
                testClip(),
                TranscribeOptions(languageTag = LanguageList.forDictation(config)),
            )
            val local = engine.capabilities.trustLevel == TrustLevel.ON_DEVICE
            // For an enclave engine the round trip only counts if it went over the attested key;
            // say which way it went, so a test can never pass silently as plain CLOUD.
            val enclave = (engine as? OpenAiCompatibleEngine)?.attestation()?.let { attestation ->
                if (attestation.verified) context.getString(R.string.test_enclave_verified, attestation.detail) else null
            }
            fun withEnclave(text: String) = listOfNotNull(text, enclave).joinToString(" ")
            outcome.fold(
                onSuccess = { transcript ->
                    val heard = transcript.text.take(40)
                    val answer = transcript.servedBy?.let { context.getString(R.string.test_answer_via, transcript.latencyMs, heard, it) }
                        ?: context.getString(R.string.test_answer, transcript.latencyMs, heard)
                    Result.Ok(withEnclave(answer))
                },
                onFailure = { error ->
                    val failure = error as? SttFailure
                    if (failure != null && failure.nothingRecognized) {
                        Result.Ok(
                            if (local) {
                                context.getString(R.string.test_model_runs)
                            } else {
                                withEnclave(context.getString(R.string.test_connection_works))
                            },
                        )
                    } else {
                        Result.Failed(Texts.failure(context, error))
                    }
                },
            )
        } finally {
            runCatching { engine.close() }
        }
    }

    /**
     * A server for the cleanup only (Claude): two words tidied by its recommended model, or the
     * one its cleanup uses - a round trip that costs a fraction of a cent.
     */
    private suspend fun testCleanup(config: EngineConfig): Result {
        val settings = graph.settings.value.withEngine(config)
        val provider = ServerProviders.forUrl(config.baseUrl)
        val model = settings.cleanup?.takeIf { it.engineId == config.id }?.model
            ?: provider.cleanupModels.firstOrNull { it.recommended }?.id
            ?: return Result.Failed(Texts.issue(context, ConfigIssue.NoSpeechRecognition(provider.name)))
        val cleanup = graph.engineFactory.cleanupFor(CleanupChoice(config.id, model), settings)
            ?: return Result.Failed(
                EngineConfigValidator.validate(config, graph.secretStore.has(config.apiKeyAlias))
                    .firstOrNull { !EngineConfigValidator.onlyAboutSpeech(it) }
                    ?.let { Texts.issue(context, it) }
                    ?: context.getString(R.string.problem_recognition_failed),
            )
        return cleanup.clean(TextCleanup.TEST_DICTATION).fold(
            onSuccess = { Result.Ok(context.getString(R.string.test_cleanup_works, it.latencyMs, it.text)) },
            onFailure = { Result.Failed(Texts.failure(context, it)) },
        )
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
