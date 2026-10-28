package app.amethyst.engine

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.amethyst.core.engine.EngineCapabilities
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.ListenCallbacks
import app.amethyst.core.engine.SelfCapturingSttEngine
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.TranscribeOptions
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.text.Transcript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Android's own recognizer, preferring its offline model.
 *
 * This is the engine that makes the very first launch work: no key, no download, no server.
 * Quality is whatever the vendor ships, which is exactly why it is a starting point and not
 * the destination - but it lets the whole bubble-to-text chain be proven before any model
 * management exists.
 */
class AndroidOnDeviceEngine(private val context: Context) : SelfCapturingSttEngine {

    override val id: String = EnginePresets.ANDROID_ON_DEVICE.id
    override val displayName: String = EnginePresets.ANDROID_ON_DEVICE.displayName
    override val capabilities = EngineCapabilities(
        trustLevel = TrustLevel.ON_DEVICE,
        streaming = true,
        worksOffline = true,
        needsApiKey = false,
    )

    @Volatile
    private var recognizer: SpeechRecognizer? = null

    /**
     * Every SpeechRecognizer method checks that it was called from the main thread and throws
     * if it was not. Cancellation handlers and the controller's cleanup both run on whatever
     * thread got there first, so every call is posted instead of made directly - otherwise a
     * timed-out dictation takes the process down with it.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun requestStop() {
        val current = recognizer ?: return
        // stopListening() delivers what was heard so far; cancel() would throw it away.
        mainHandler.post { runCatching { current.stopListening() } }
    }

    override fun close() {
        val current = recognizer ?: return
        recognizer = null
        mainHandler.post { runCatching { current.destroy() } }
    }

    override suspend fun listen(
        options: TranscribeOptions,
        callbacks: ListenCallbacks,
    ): Result<Transcript> = withContext(Dispatchers.Main.immediate) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return@withContext Result.failure(
                SttFailure(
                    userMessage = "Dieses Gerät hat keinen System-Erkenner. " +
                        "Wähle eine andere Engine in den Einstellungen.",
                    technical = "SpeechRecognizer.isRecognitionAvailable == false",
                ),
            )
        }

        val startedAt = SystemClock.elapsedRealtime()

        suspendCancellableCoroutine { continuation ->
            val recognizer = createRecognizer()
            this@AndroidOnDeviceEngine.recognizer = recognizer

            // Guards against the recognizer reporting both a result and an error.
            var settled = false
            fun settle(result: Result<Transcript>) {
                if (settled) return
                settled = true
                // Listener callbacks arrive on the main thread, so this one is safe direct.
                runCatching { recognizer.destroy() }
                this@AndroidOnDeviceEngine.recognizer = null
                continuation.resume(result)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onRmsChanged(rmsdB: Float) {
                    // The documented range is roughly -2..10 dB; map it to 0f..1f.
                    callbacks.onAmplitude(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    firstResult(partialResults)?.let(callbacks.onPartial)
                }

                override fun onResults(results: Bundle?) {
                    val text = firstResult(results)
                    if (text.isNullOrBlank()) {
                        settle(
                            Result.failure(
                                SttFailure("Es wurde nichts erkannt.", "empty RESULTS_RECOGNITION"),
                            ),
                        )
                    } else {
                        settle(
                            Result.success(
                                Transcript(
                                    text = text.trim(),
                                    engineId = id,
                                    trustLevel = TrustLevel.ON_DEVICE,
                                    latencyMs = SystemClock.elapsedRealtime() - startedAt,
                                ),
                            ),
                        )
                    }
                }

                override fun onError(error: Int) {
                    settle(Result.failure(failureFor(error)))
                }
            })

            recognizer.startListening(buildIntent(options))

            continuation.invokeOnCancellation {
                // Runs on the cancelling thread - the watchdog's, not the main one.
                this@AndroidOnDeviceEngine.recognizer = null
                mainHandler.post {
                    runCatching {
                        recognizer.cancel()
                        recognizer.destroy()
                    }
                }
            }
        }
    }

    private fun createRecognizer(): SpeechRecognizer {
        val onDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        return if (onDeviceAvailable) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }

    private fun buildIntent(options: TranscribeOptions): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                options.languageTag ?: Locale.getDefault().toLanguageTag(),
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Ask for the offline model even when a network one is available, so the
            // ON_DEVICE label this engine reports stays truthful.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    companion object {

        /**
         * Asks the system recognizer to fetch the offline pack for [languageTag]. Android shows
         * its own download prompt or notification; there is no result to wait for here.
         * Returns false where the API does not exist (below Android 13).
         */
        fun requestLanguageDownload(context: Context, languageTag: String?): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return false
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            return try {
                recognizer.triggerModelDownload(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE,
                        languageTag ?: Locale.getDefault().toLanguageTag(),
                    ),
                )
                true
            } catch (e: RuntimeException) {
                false
            } finally {
                recognizer.destroy()
            }
        }
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    // ERROR_SERVER_DISCONNECTED and the two language errors are API 31+ constants. They are
    // compile-time ints, so referencing them is safe at minSdk 28 - those codes simply never
    // arrive on older versions, where the branches are dead rather than wrong.
    @SuppressLint("NewApi")
    private fun failureFor(error: Int): SttFailure {
        if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
            error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
        ) {
            return SttFailure(
                userMessage = "Diese Sprache ist offline nicht installiert.",
                technical = "SpeechRecognizer error $error",
                remedy = SttFailure.Remedy.INSTALL_LANGUAGE,
            )
        }
        val message = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Mikrofon konnte nicht gelesen werden."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "Mikrofon-Berechtigung fehlt."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Der System-Erkenner wollte online gehen und kam nicht durch."
            SpeechRecognizer.ERROR_NO_MATCH -> "Es wurde nichts erkannt."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Keine Sprache gehört."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Der Erkenner ist beschäftigt."
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                "Der System-Erkenner ist abgestürzt."
            else -> "Erkennung fehlgeschlagen."
        }
        return SttFailure(message, "SpeechRecognizer error $error")
    }
}
