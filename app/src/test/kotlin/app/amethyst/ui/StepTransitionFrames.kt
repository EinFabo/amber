package app.amethyst.ui

import android.animation.ValueAnimator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.amethyst.core.onboarding.OnboardingStep
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.ui.onboarding.OnboardingScreen
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/** One step giving way to the next and to the one before: on its way in (the test's clock needs some 130 ms to get going), nearly there, and at rest. */
class StepTransitionFrames : TimelineFrames() {

    @Test
    fun aStepComesInFromTheSideItLiesOn() {
        assertTrue("these pictures need animations on", ValueAnimator.areAnimatorsEnabled())
        // Frames only as the clock passes, 16 ms apart: so a moment of the transition can be held.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        var step by mutableStateOf(OnboardingStep.WELCOME)
        val activity = page {
            OnboardingScreen(
                step = step, settings = AmethystSettings.defaults(listOf("en-US")), setup = SetupState(), actions = actions,
                content = content(), onStep = {}, onFinish = {},
            )
        }
        pass(1_500)
        for ((to, name) in listOf(OnboardingStep.ACCESSIBILITY to "forward", OnboardingStep.WELCOME to "back")) {
            step = to
            var at = 0L
            for (ms in listOf(180L, 240L, 600L)) {
                pass(ms - at)
                at = ms
                save(activity, "1-step-$name-${"%03d".format(ms)}ms")
            }
        }
    }
}
