package app.amethyst.ui

import app.amethyst.ui.onboarding.AccessibilityStep
import org.junit.Test

/** The accessibility step's pictures of Android's settings, one moment per tap and at rest. */
class WhereToTapFrames : TimelineFrames() {

    /** Before the settings open: the row, the switch, "Allow" - then all three done. */
    @Test
    fun switchOn() = frames("2-switch-on", 0.17f, 0.5f, 0.83f, 1f) { AccessibilityStep(SetupState(), actions) {} }

    @Test
    fun switchOnLight() = frames("2-switch-on", 0.17f, 0.83f, dark = false) { AccessibilityStep(SetupState(), actions) {} }

    /** Back without the service on: the picture in place of the numbered steps. */
    @Test
    fun switchOnAgain() = frames("2-switch-on-again", 0.5f, 1f) { AccessibilityStep(SetupState(accessibilityTried = true), actions) {} }

    /** The same on an install Android locked: ⋮, then its menu's item. */
    @Test
    fun restricted() = frames("2-restricted", 0.25f, 0.6f, 0.75f, 1f) {
        AccessibilityStep(SetupState(accessibilityTried = true, restrictedSettings = true), actions) {}
    }

    @Test
    fun restrictedLight() = frames("2-restricted", 0.75f, dark = false) {
        AccessibilityStep(SetupState(accessibilityTried = true, restrictedSettings = true), actions) {}
    }
}
