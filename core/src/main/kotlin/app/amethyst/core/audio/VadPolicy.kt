package app.amethyst.core.audio

/**
 * Decides when a dictation has ended, from per-frame loudness alone.
 *
 * This is deliberately a plain state machine over RMS values rather than a neural VAD:
 * it needs no model file, so it works on the very first launch, and it is unit-testable
 * off-device. Stage 3 swaps in Silero VAD behind the same decision contract.
 */
class VadPolicy(private val config: VadConfig = VadConfig()) {

    private var framesSeen = 0
    private var noiseSum = 0.0
    private var speechMs = 0
    private var silenceMs = 0
    private var totalMs = 0

    /** Running estimate of the room's noise floor, from the first [VadConfig.calibrationFrames]. */
    var noiseFloor: Double = 0.0
        private set

    /** True once at least one frame crossed the speech threshold. */
    var speechDetected: Boolean = false
        private set

    /** The loudness a frame must exceed to count as speech. */
    val speechThreshold: Double
        get() = maxOf(config.absoluteFloor, noiseFloor * config.speechFactor)

    fun reset() {
        framesSeen = 0
        noiseSum = 0.0
        speechMs = 0
        silenceMs = 0
        totalMs = 0
        noiseFloor = 0.0
        speechDetected = false
    }

    /**
     * Feeds one frame of [VadConfig.frameMs] length and returns what the recorder should do.
     */
    fun onFrame(rms: Double): VadDecision {
        framesSeen++
        totalMs += config.frameMs

        if (framesSeen <= config.calibrationFrames) {
            noiseSum += rms
            noiseFloor = noiseSum / framesSeen
            // Still learning the room; never stop during calibration.
            return VadDecision.Continue
        }

        if (rms > speechThreshold) {
            speechDetected = true
            speechMs += config.frameMs
            silenceMs = 0
        } else if (speechDetected) {
            silenceMs += config.frameMs
        }

        if (totalMs >= config.maxDurationMs) {
            return if (speechMs >= config.minSpeechMs) {
                VadDecision.Stop(StopReason.MAX_DURATION)
            } else {
                VadDecision.Discard(DiscardReason.NO_SPEECH)
            }
        }

        if (!speechDetected && totalMs >= config.noSpeechTimeoutMs) {
            return VadDecision.Discard(DiscardReason.NO_SPEECH)
        }

        if (speechDetected && silenceMs >= config.silenceTimeoutMs) {
            return if (speechMs >= config.minSpeechMs) {
                VadDecision.Stop(StopReason.SILENCE)
            } else {
                VadDecision.Discard(DiscardReason.TOO_SHORT)
            }
        }

        return VadDecision.Continue
    }

    /**
     * What a user-triggered stop should do: transcribe only if enough speech was captured,
     * so a stray tap does not fire an empty request at a paid API.
     */
    fun onManualStop(): VadDecision =
        if (speechMs >= config.minSpeechMs) {
            VadDecision.Stop(StopReason.MANUAL)
        } else {
            VadDecision.Discard(if (speechDetected) DiscardReason.TOO_SHORT else DiscardReason.NO_SPEECH)
        }
}

data class VadConfig(
    val frameMs: Int = 20,
    /** Audio kept from before the trigger, so the first syllable is not clipped. */
    val preRollMs: Int = 300,
    val minSpeechMs: Int = 350,
    val silenceTimeoutMs: Int = 900,
    val maxDurationMs: Int = 120_000,
    /** Give up if nothing at all was said - the bubble was tapped by accident. */
    val noSpeechTimeoutMs: Int = 4_000,
    val calibrationFrames: Int = 15,
    val speechFactor: Double = 3.0,
    /** Floor for near-silent rooms, where noiseFloor * speechFactor would trigger on nothing. */
    val absoluteFloor: Double = 120.0,
)

sealed interface VadDecision {
    /** Keep recording. */
    data object Continue : VadDecision

    /** Stop and send the buffer to the engine. */
    data class Stop(val reason: StopReason) : VadDecision

    /** Stop and throw the buffer away. */
    data class Discard(val reason: DiscardReason) : VadDecision
}

enum class StopReason { SILENCE, MAX_DURATION, MANUAL }

enum class DiscardReason { NO_SPEECH, TOO_SHORT }
