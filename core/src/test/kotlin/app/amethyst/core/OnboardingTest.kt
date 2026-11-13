package app.amethyst.core

import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.onboarding.Onboarding
import app.amethyst.core.onboarding.OnboardingPath
import app.amethyst.core.onboarding.OnboardingStep
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.CleanupChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingTest {

    private val plain = AmethystSettings.defaults(TEST_PHONE)
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
    fun `the flame is tried right after it is explained, then comes reliability`() {
        assertEquals(OnboardingStep.PRACTICE, Onboarding.next(OnboardingStep.BUBBLE, plain))
        assertEquals(OnboardingStep.RELIABILITY, Onboarding.next(OnboardingStep.PRACTICE, plain))
        assertEquals(OnboardingStep.BUBBLE, Onboarding.previous(OnboardingStep.PRACTICE, withCleanup))
        assertEquals(7 to 9, Onboarding.position(OnboardingStep.PRACTICE, plain))
        assertEquals(8 to 10, Onboarding.position(OnboardingStep.PRACTICE, withCleanup))
    }

    @Test
    fun `auto-pause is off and no longer asked`() {
        assertTrue(OnboardingStep.entries.none { it.name == "RECORDING" })
    }

    @Test
    fun `skip is offered from the cleanup on`() {
        assertFalse(Onboarding.skippable(OnboardingStep.MICROPHONE))
        assertTrue(Onboarding.skippable(OnboardingStep.CLEANUP))
        assertTrue(Onboarding.skippable(OnboardingStep.PRACTICE))
        assertTrue(Onboarding.skippable(OnboardingStep.RELIABILITY))
        assertFalse(Onboarding.skippable(OnboardingStep.DONE))
    }

    @Test
    fun `the quick way asks for the two permissions, tries the bubble and is done`() {
        val quick = listOf(OnboardingStep.WELCOME, OnboardingStep.ACCESSIBILITY, OnboardingStep.MICROPHONE, OnboardingStep.PRACTICE, OnboardingStep.DONE)
        assertEquals(quick, Onboarding.steps(plain, OnboardingPath.QUICK))
        // A cleanup someone has on a replay brings no styles step into it.
        assertEquals(quick, Onboarding.steps(withCleanup, OnboardingPath.QUICK))
        assertEquals(OnboardingStep.PRACTICE, Onboarding.next(OnboardingStep.MICROPHONE, plain, OnboardingPath.QUICK))
        assertEquals(OnboardingStep.DONE, Onboarding.next(OnboardingStep.PRACTICE, plain, OnboardingPath.QUICK))
        assertEquals(OnboardingStep.MICROPHONE, Onboarding.previous(OnboardingStep.PRACTICE, plain, OnboardingPath.QUICK))
        assertNull(Onboarding.next(OnboardingStep.DONE, plain, OnboardingPath.QUICK))
        assertEquals(2 to 5, Onboarding.position(OnboardingStep.ACCESSIBILITY, plain, OnboardingPath.QUICK))
        assertEquals(5 to 5, Onboarding.position(OnboardingStep.DONE, plain, OnboardingPath.QUICK))
    }

    @Test
    fun `step by step is the introduction as it was`() {
        assertEquals(Onboarding.steps(plain), Onboarding.steps(plain, OnboardingPath.DEEP))
        assertEquals(Onboarding.steps(withCleanup), Onboarding.steps(withCleanup, OnboardingPath.DEEP))
        assertEquals(OnboardingStep.entries, Onboarding.steps(withCleanup, OnboardingPath.DEEP))
    }

    @Test
    fun `a later step is forward, an earlier one back - also across the steps a way leaves out`() {
        assertTrue(Onboarding.forward(OnboardingStep.WELCOME, OnboardingStep.ACCESSIBILITY))
        assertTrue(Onboarding.forward(OnboardingStep.MICROPHONE, OnboardingStep.PRACTICE))
        assertFalse(Onboarding.forward(OnboardingStep.PRACTICE, OnboardingStep.MICROPHONE))
        // The practice step's way back to the service it needs.
        assertFalse(Onboarding.forward(OnboardingStep.PRACTICE, OnboardingStep.ACCESSIBILITY))
        assertFalse(Onboarding.forward(OnboardingStep.DONE, OnboardingStep.WELCOME))
    }

    @Test
    fun `the welcome step has no number - the way is chosen there`() {
        assertNull(Onboarding.position(OnboardingStep.WELCOME, plain))
        assertNull(Onboarding.position(OnboardingStep.WELCOME, plain, OnboardingPath.QUICK))
        assertEquals(2 to 9, Onboarding.position(OnboardingStep.ACCESSIBILITY, plain))
    }
}
