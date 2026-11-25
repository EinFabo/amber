package app.amethyst.core

import app.amethyst.core.onboarding.SettingsMaker
import app.amethyst.core.onboarding.SwitchOnStep
import app.amethyst.core.onboarding.SwitchOnPlan
import kotlin.test.Test
import kotlin.test.assertEquals

class SwitchOnStepsTest {

    @Test
    fun `the maker is read from the manufacturer in any case`() {
        assertEquals(SettingsMaker.SAMSUNG, SettingsMaker.of("samsung"))
        assertEquals(SettingsMaker.SAMSUNG, SettingsMaker.of("SAMSUNG"))
        assertEquals(SettingsMaker.XIAOMI, SettingsMaker.of("Xiaomi"))
        assertEquals(SettingsMaker.XIAOMI, SettingsMaker.of("REDMI"))
        assertEquals(SettingsMaker.XIAOMI, SettingsMaker.of("poco"))
        assertEquals(SettingsMaker.AOSP, SettingsMaker.of("Google"))
        assertEquals(SettingsMaker.AOSP, SettingsMaker.of(""))
    }

    @Test
    fun `Samsung starts at Installed apps and Xiaomi at Downloaded apps`() {
        assertEquals(SwitchOnStep.INSTALLED_APPS, SwitchOnPlan.accessibility(SettingsMaker.SAMSUNG).first())
        assertEquals(SwitchOnStep.DOWNLOADED_APPS, SwitchOnPlan.accessibility(SettingsMaker.XIAOMI).first())
    }

    @Test
    fun `AOSP goes straight to the service`() {
        assertEquals(
            listOf(SwitchOnStep.TAP_SERVICE, SwitchOnStep.TURN_ON, SwitchOnStep.ALLOW),
            SwitchOnPlan.accessibility(SettingsMaker.AOSP),
        )
    }

    @Test
    fun `every maker ends the accessibility steps the same way`() {
        for (maker in SettingsMaker.entries) {
            assertEquals(
                listOf(SwitchOnStep.TAP_SERVICE, SwitchOnStep.TURN_ON, SwitchOnStep.ALLOW),
                SwitchOnPlan.accessibility(maker).takeLast(3),
            )
        }
    }
}
