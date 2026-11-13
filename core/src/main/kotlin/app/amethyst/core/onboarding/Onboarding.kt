package app.amethyst.core.onboarding

import app.amethyst.core.settings.AmethystSettings

/**
 * The introduction's screens, in the order they are shown. How a recording ends is no longer
 * asked: it runs until the second tap ([app.amethyst.core.settings.CaptureSettings.autoStopOnSilence]).
 * PRACTICE tries the real flame right after BUBBLE explained it ([Practice]).
 */
enum class OnboardingStep { WELCOME, ACCESSIBILITY, MICROPHONE, ENGINE, CLEANUP, APP_STYLES, BUBBLE, PRACTICE, RELIABILITY, DONE }

/**
 * The way through the introduction, chosen on the welcome step. Its third choice, skipping
 * it, is no path: it leaves for the app at once with what [FirstRun] decides.
 */
enum class OnboardingPath {
    /** The two permissions and a try; everything else is decided for the phone ([FirstRun]). */
    QUICK,

    /** Every choice asked, one per screen. */
    DEEP,
}

/**
 * Which screens the introduction shows and how it moves between them. The styles step only
 * makes sense with a cleanup model to apply them, so it comes and goes with the cleanup choice -
 * also while the user is in the middle of the flow.
 */
object Onboarding {

    /**
     * The quick way: what Android has to be asked for, the bubble tried once, and what was
     * decided. The try stays in because it is the first time the bubble shows, and it can be
     * skipped with one tap.
     */
    private val QUICK = listOf(
        OnboardingStep.WELCOME, OnboardingStep.ACCESSIBILITY, OnboardingStep.MICROPHONE, OnboardingStep.PRACTICE, OnboardingStep.DONE,
    )

    fun steps(settings: AmethystSettings, path: OnboardingPath = OnboardingPath.DEEP): List<OnboardingStep> = when (path) {
        OnboardingPath.QUICK -> QUICK
        OnboardingPath.DEEP -> OnboardingStep.entries.filter { it != OnboardingStep.APP_STYLES || settings.cleanup != null }
    }

    fun next(current: OnboardingStep, settings: AmethystSettings, path: OnboardingPath = OnboardingPath.DEEP): OnboardingStep? =
        steps(settings, path).firstOrNull { it > current }

    fun previous(current: OnboardingStep, settings: AmethystSettings, path: OnboardingPath = OnboardingPath.DEEP): OnboardingStep? =
        steps(settings, path).lastOrNull { it < current }

    /** Whether going from [from] to [to] leads on through the introduction, rather than back. */
    fun forward(from: OnboardingStep, to: OnboardingStep): Boolean = to >= from

    /**
     * 1-based position and total, for "Step 4 of 10" - null on the welcome step, where the
     * way and with it the number of steps is still to be chosen.
     */
    fun position(current: OnboardingStep, settings: AmethystSettings, path: OnboardingPath = OnboardingPath.DEEP): Pair<Int, Int>? {
        if (current == OnboardingStep.WELCOME) return null
        val steps = steps(settings, path)
        return (steps.count { it <= current }).coerceAtLeast(1) to steps.size
    }

    /** "Skip" keeps a step's default; the permissions and the engine are not skipped. */
    fun skippable(step: OnboardingStep): Boolean = step >= OnboardingStep.CLEANUP && step != OnboardingStep.DONE
}
