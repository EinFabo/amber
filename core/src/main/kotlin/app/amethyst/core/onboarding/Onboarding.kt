package app.amethyst.core.onboarding

import app.amethyst.core.settings.AmethystSettings

/** The introduction's screens, in the order they are shown. */
enum class OnboardingStep { WELCOME, ACCESSIBILITY, MICROPHONE, ENGINE, CLEANUP, APP_STYLES, BUBBLE, RECORDING, RELIABILITY, DONE }

/**
 * Which screens the introduction shows and how it moves between them. The styles step only
 * makes sense with a cleanup model to apply them, so it comes and goes with the cleanup choice -
 * also while the user is in the middle of the flow.
 */
object Onboarding {

    fun steps(settings: AmethystSettings): List<OnboardingStep> =
        OnboardingStep.entries.filter { it != OnboardingStep.APP_STYLES || settings.cleanup != null }

    fun next(current: OnboardingStep, settings: AmethystSettings): OnboardingStep? =
        steps(settings).firstOrNull { it > current }

    fun previous(current: OnboardingStep, settings: AmethystSettings): OnboardingStep? =
        steps(settings).lastOrNull { it < current }

    /** 1-based position and total, for "Step 4 of 9". */
    fun position(current: OnboardingStep, settings: AmethystSettings): Pair<Int, Int> {
        val steps = steps(settings)
        return (steps.count { it <= current }).coerceAtLeast(1) to steps.size
    }

    /** "Skip" keeps a step's default; the permissions and the engine are not skipped. */
    fun skippable(step: OnboardingStep): Boolean = step >= OnboardingStep.CLEANUP && step != OnboardingStep.DONE
}
