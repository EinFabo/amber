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
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import app.amethyst.core.engine.EngineCapabilities
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.LanguagePack
import app.amethyst.core.engine.ListenCallbacks
import app.amethyst.core.engine.ListenStop
import app.amethyst.core.engine.SelfCapturingSttEngine
import app.amethyst.core.engine.Problem
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.TranscribeOptions
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.text.Transcript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    /** This listening's stop: kept until the recognizer listens, and waited for no longer than it says. */
    private val stop = ListenStop()

    /** Settles the listening when the recognizer has not answered a stop in time; set while it listens. */
    @Volatile
    private var answerOverdue: (() -> Unit)? = null

    private val giveUpWaiting = Runnable { answerOverdue?.invoke() }

    override fun requestStop() {
        // Before the recognizer listens it would drop the stop: kept, and passed on when it does.
        if (stop.stop()) mainHandler.post(::stopListening)
    }

    /**
     * Main thread. stopListening() delivers what was heard so far; cancel() would throw it
     * away. The recognizer has no time limit of its own for that answer, so it gets one here.
     */
    private fun stopListening() {
        val current = recognizer ?: return
        runCatching { current.stopListening() }
        mainHandler.postDelayed(giveUpWaiting, ListenStop.ANSWER_WITHIN_MS)
    }

    override fun close() {
        mainHandler.removeCallbacks(giveUpWaiting)
        answerOverdue = null
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
                    problem = Problem.NoSystemRecognizer,
                    technical = "SpeechRecognizer.isRecognitionAvailable == false",
                ),
            )
        }

        val startedAt = SystemClock.elapsedRealtime()

        suspendCancellableCoroutine { continuation ->
            val recognizer = createRecognizer()
            this@AndroidOnDeviceEngine.recognizer = recognizer

            // Guards against the recognizer reporting both a result and an error, and against
            // either coming after the wait for them ran out.
            fun settle(result: Result<Transcript>) {
                if (!stop.settle()) return
                mainHandler.removeCallbacks(giveUpWaiting)
                answerOverdue = null
                // Listener callbacks arrive on the main thread, so this one is safe direct.
                runCatching { recognizer.destroy() }
                this@AndroidOnDeviceEngine.recognizer = null
                continuation.resume(result)
            }

            fun heard(text: String) = Transcript(
                text = text.trim(),
                engineId = id,
                trustLevel = usedTrust,
                latencyMs = SystemClock.elapsedRealtime() - startedAt,
            )

            // Asked to stop, the recognizer answered with neither a result nor an error: the
            // last interim text is what was heard. The log never gets the words.
            answerOverdue = {
                val text = stop.heard
                val instead = if (text != null) "taking its last interim text" else "nothing heard"
                Log.w(TAG, "the system recognizer did not answer the stop within ${ListenStop.ANSWER_WITHIN_MS} ms - $instead")
                settle(
                    if (text != null) {
                        Result.success(heard(text))
                    } else {
                        Result.failure(
                            SttFailure(Problem.NoSpeechHeard, "no answer within ${ListenStop.ANSWER_WITHIN_MS} ms of the stop, no interim text"),
                        )
                    },
                )
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = callbacks.onReady()
                override fun onBeginningOfSpeech() = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onRmsChanged(rmsdB: Float) {
                    // The documented range is roughly -2..10 dB; map it to 0f..1f.
                    callbacks.onAmplitude(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = firstResult(partialResults) ?: return
                    stop.partial(partial)
                    callbacks.onPartial(partial)
                }

                override fun onResults(results: Bundle?) {
                    val text = firstResult(results)?.takeIf { it.isNotBlank() } ?: stop.heardOnStop
                    if (text == null) {
                        settle(
                            Result.failure(
                                SttFailure(Problem.NothingRecognized, "empty RESULTS_RECOGNITION"),
                            ),
                        )
                    } else {
                        settle(Result.success(heard(text)))
                    }
                }

                override fun onError(error: Int) {
                    // Asked to stop, the words the bubble showed are what was said, whatever the
                    // recognizer makes of the end ([ListenStop.heardOnStop]).
                    val text = stop.heardOnStop
                    if (text != null) {
                        Log.w(TAG, "the system recognizer answered the stop with error $error - taking its last interim text")
                        settle(Result.success(heard(text)))
                    } else {
                        settle(Result.failure(failureFor(error)))
                    }
                }
            })

            recognizer.startListening(buildIntent(options))
            // A stop that came before this moment is due now.
            if (stop.listening()) stopListening()

            continuation.invokeOnCancellation {
                // Runs on the cancelling thread - the watchdog's, not the main one.
                stop.settle()
                mainHandler.removeCallbacks(giveUpWaiting)
                answerOverdue = null
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

    /**
     * What the last recognizer guaranteed. Only the on-device recognizer (Android 13+) keeps
     * the audio on the phone; the general one merely prefers offline and may send it to the
     * recognition service's servers, so its text is labelled as cloud.
     */
    @Volatile
    private var usedTrust: TrustLevel = TrustLevel.ON_DEVICE

    private fun createRecognizer(): SpeechRecognizer {
        val onDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        usedTrust = if (onDeviceAvailable) TrustLevel.ON_DEVICE else TrustLevel.CLOUD
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
            // The on-device recognizer (Android 13+) is asked to stay offline, so the ON_DEVICE
            // label it reports stays truthful. The phone's default recognizer is labelled CLOUD
            // (createRecognizer) and the introduction says it may send the voice to its maker:
            // asked to stay offline as well, it refused every dictation on a phone without its
            // offline pack ("language isn't installed for offline use" on the A20e, Android 11),
            // and below Android 13 there is no way to fetch that pack for the user.
            if (usedTrust == TrustLevel.ON_DEVICE) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    companion object {
        private const val TAG = "Embersay"

        /**
         * Whether this phone has a recognizer that keeps the voice on the device (Android 13+).
         * Set once at start; the settings label the Android engine by it.
         */
        @Volatile
        var onDeviceSpeech: Boolean = true
            private set

        fun detectOnDeviceSpeech(context: Context) {
            onDeviceSpeech = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        }

        /** How long Android may take to say which language packs it has; a recognizer that does not answer says nothing. */
        private const val SUPPORT_ANSWER_MS = 5_000L

        /**
         * Whether the on-device recognizer has the offline pack for [languageTag], is fetching
         * it, could fetch it, or has none for it ([LanguagePack.of]). Null below Android 13,
         * where there is no on-device recognizer to ask, and where it does not answer.
         */
        suspend fun languagePack(context: Context, languageTag: String): LanguagePack? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            // Every SpeechRecognizer call checks that it comes from the main thread.
            return withContext(Dispatchers.Main) {
                if (!runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)) return@withContext null
                withTimeoutOrNull(SUPPORT_ANSWER_MS) { askSupport(context, languageTag) }
            }
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private suspend fun askSupport(context: Context, languageTag: String): LanguagePack? = suspendCancellableCoroutine { continuation ->
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val main = Handler(Looper.getMainLooper())
            var destroyed = false
            // Main thread only: the answer comes on the main executor, a cancellation is posted there.
            fun destroy() {
                if (destroyed) return
                destroyed = true
                runCatching { recognizer.destroy() }
            }
            fun answer(pack: LanguagePack?) {
                destroy()
                if (continuation.isActive) continuation.resume(pack)
            }
            continuation.invokeOnCancellation { main.post(::destroy) }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            val asked = runCatching {
                recognizer.checkRecognitionSupport(
                    intent,
                    context.mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) = answer(
                            LanguagePack.of(
                                languageTag,
                                installed = support.installedOnDeviceLanguages,
                                pending = support.pendingOnDeviceLanguages,
                                supported = support.supportedOnDeviceLanguages,
                            ),
                        )

                        override fun onError(error: Int) {
                            Log.i(TAG, "the on-device recognizer did not say which language packs it has (error $error)")
                            answer(null)
                        }
                    },
                )
            }
            if (asked.isFailure) answer(null)
        }

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
                problem = Problem.LanguageNotInstalled,
                technical = "SpeechRecognizer error $error",
                remedy = SttFailure.Remedy.INSTALL_LANGUAGE,
            )
        }
        val problem = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> Problem.MicrophoneUnreadable
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Problem.MicrophonePermissionMissing
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> Problem.RecognizerOffline
            SpeechRecognizer.ERROR_NO_MATCH -> Problem.NothingRecognized
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Problem.NoSpeechHeard
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Problem.RecognizerBusy
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> Problem.RecognizerCrashed
            else -> Problem.RecognitionFailed
        }
        return SttFailure(problem, "SpeechRecognizer error $error")
    }
}
