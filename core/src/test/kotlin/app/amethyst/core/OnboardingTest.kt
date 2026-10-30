package app.amethyst.core

import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.onboarding.Onboarding
import app.amethyst.core.onboarding.OnboardingStep
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.CleanupChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingTest {

    private val plain = AmethystSettings.defaults()
    private val withCleanup = plain.copy(cleanup = CleanupChoice(EnginePresets.TINFOIL.id, "deepseek-v4-1-flash"))

    @Test
    fun `without cleanup the styles step is left out`() {
        assertEquals(OnboardingStep.entries - OnboardingStep.APP_STYLES, Onboarding.steps(plain))
        assertEquals(4 to 9, Onboarding.position(OnboardingStep.ENGINE, plain))
    }

    @Test
    fun `with cleanup the styles step follows it`() {
        assertEquals(OnboardingStep.APP_STYLES, Onboarding.next(OnboardingStep.CLEANUP, withCleanup))
        assertEquals(10 to 10, Onboarding.position(OnboardingStep.DONE, withCleanup))
    }

    @Test
    fun `turning cleanup off again skips the styles step`() {
        assertEquals(OnboardingStep.BUBBLE, Onboarding.next(OnboardingStep.CLEANUP, plain))
        assertEquals(OnboardingStep.CLEANUP, Onboarding.previous(OnboardingStep.BUBBLE, plain))
    }

    @Test
    fun `a styles step left behind by cleanup turned off still finds its neighbours`() {
        assertEquals(OnboardingStep.BUBBLE, Onboarding.next(OnboardingStep.APP_STYLES, plain))
        assertEquals(OnboardingStep.CLEANUP, Onboarding.previous(OnboardingStep.APP_STYLES, plain))
    }

    @Test
    fun `the ends have no neighbour`() {
        assertNull(Onboarding.previous(OnboardingStep.WELCOME, plain))
        assertNull(Onboarding.next(OnboardingStep.DONE, plain))
    }

    @Test
    fun `skip is offered from the cleanup on`() {
        assertFalse(Onboarding.skippable(OnboardingStep.MICROPHONE))
        assertTrue(Onboarding.skippable(OnboardingStep.CLEANUP))
        assertTrue(Onboarding.skippable(OnboardingStep.RELIABILITY))
        assertFalse(Onboarding.skippable(OnboardingStep.DONE))
    }
}
