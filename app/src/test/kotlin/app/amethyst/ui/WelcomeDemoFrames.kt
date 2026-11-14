package app.amethyst.ui

import app.amethyst.ui.onboarding.WelcomeStep
import org.junit.Test

/** The welcome step's demo, moment by moment through its six seconds. */
class WelcomeDemoFrames : TimelineFrames() {

    /**
     * The chat before anything; the keyboard up and the bubble there, tapped; listening; the
     * second tap; working while the sentence goes in; the burst; at rest.
     */
    @Test
    fun demo() = frames("4-demo", 0f, 0.15f, 0.34f, 0.5f, 0.61f, 0.8f, 0.9f, 1f) { WelcomeStep({}, {}) }

    @Test
    fun demoLight() = frames("4-demo", 0.34f, 0.5f, 0.8f, 0.9f, dark = false, languages = listOf("de")) { WelcomeStep({}, {}) }
}
