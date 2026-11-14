package app.amethyst.ui

import androidx.compose.runtime.CompositionLocalProvider
import app.amethyst.core.bubble.BubbleEvent
import app.amethyst.core.bubble.BubbleSide
import app.amethyst.core.bubble.QuickAction
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.onboarding.Practice
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.engine.ParakeetState
import app.amethyst.ui.onboarding.PracticeStep
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/** The practice step's moments: a dictation landing, all three done, and the pointer after a quiet while. */
class PracticeFrames : TimelineFrames() {

    private val ready = SetupState(microphoneGranted = true, accessibilityEnabled = true)

    private val settings = AmethystSettings.defaults(listOf("en-US"))
        .withEngine(EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET))
        .copy(activeEngineId = EnginePresets.LOCAL_PARAKEET.id)

    private fun tried(vararg events: BubbleEvent) = content(bubbleEvents = flowOf(*events), parakeet = ParakeetState.Ready(670_000_000))

    /** The field's glow rising and fading, the tick's burst between them. */
    @Test
    fun aDictationLands() = frames("5-landed", 0.1f, 0.5f, 0.8f, 1f, languages = listOf("en")) {
        PracticeStep(settings, ready, actions, tried(BubbleEvent.DictationInserted), {}, {})
    }

    @Test
    fun aDictationLandsLight() = frames("5-landed", 0.1f, 0.5f, dark = false, languages = listOf("de")) {
        PracticeStep(settings, ready, actions, tried(BubbleEvent.DictationInserted), {}, {})
    }

    /** The last line coming in. */
    @Test
    fun allDone() = frames("5-all-done", 0.3f, 1f) {
        PracticeStep(
            settings, ready, actions,
            tried(BubbleEvent.DictationInserted, BubbleEvent.QuickActionRun(QuickAction.COPY_LAST), BubbleEvent.Docked(BubbleSide.LEFT)), {}, {},
        )
    }

    /**
     * Nothing tried for a while: the line at the bubble's edge, once on either side. The quiet
     * is waited out for real - a coroutine's delay does not go by the test's clock.
     */
    @Test
    fun quietForAWhile() {
        for ((language, side, dark) in listOf(Triple("en", BubbleSide.RIGHT, true), Triple("de", BubbleSide.LEFT, false))) {
            RuntimeEnvironment.setQualifiers("+$language")
            val activity = page(dark) {
                CompositionLocalProvider(LocalTimelineAt provides 0.5f) {
                    PracticeStep(settings.copy(bubble = settings.bubble.copy(side = side)), ready, actions, tried(), {}, {})
                }
            }
            pass(1_500)
            Thread.sleep(Practice.QUIET_MS + 300)
            pass(500)
            save(activity, "5-quiet-${side.name.lowercase()}${if (dark) "" else "-light"}-$language")
        }
    }
}
