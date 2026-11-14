package app.amethyst.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.amethyst.ui.onboarding.AccessibilityStep
import org.junit.Test

/** Back from Android's settings with the service on: the beat before the step moves on. */
class ArrivalFrames : TimelineFrames() {

    /** Of the beat's 900 ms: the burst leaving the disc, the tick half drawn, both done, at rest. */
    @Test
    fun theServiceCameOn() = frames("3-service-on", 0.2f, 0.3f, 0.45f, 1f, languages = listOf("en")) {
        // Off as the step opens, on a moment later - as it is for someone coming back.
        var on by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { on = true }
        AccessibilityStep(SetupState(accessibilityEnabled = on), actions) {}
    }

    @Test
    fun theServiceCameOnLight() = frames("3-service-on", 0.2f, 1f, dark = false) {
        var on by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { on = true }
        AccessibilityStep(SetupState(accessibilityEnabled = on), actions) {}
    }
}
