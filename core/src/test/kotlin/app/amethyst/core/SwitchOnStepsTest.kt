package app.amethyst.core

import app.amethyst.core.onboarding.BatteryPage
import app.amethyst.core.onboarding.SettingsMaker
import app.amethyst.core.onboarding.SwitchOnStep
import app.amethyst.core.onboarding.SwitchOnPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

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

    @Test
    fun `battery from Android 12 is app info, battery, unrestricted on every maker`() {
        for (sdk in listOf(31, 34, 35)) {
            for (maker in SettingsMaker.entries) {
                assertEquals(
                    listOf(SwitchOnStep.APP_INFO, SwitchOnStep.TAP_BATTERY, SwitchOnStep.UNRESTRICTED),
                    SwitchOnPlan.battery(maker, sdk),
                )
            }
            assertEquals(BatteryPage.APP_INFO, SwitchOnPlan.batteryPage(sdk))
        }
    }

    @Test
    fun `before Android 12 the way is the battery optimization list, as each maker words it`() {
        // Android 9 to 11 (the A20e): the app info's battery page has no "Unrestricted".
        for (sdk in listOf(28, 29, 30)) {
            val aosp = listOf(SwitchOnStep.BATTERY_OPTIMIZATION, SwitchOnStep.ALL_APPS, SwitchOnStep.DONT_OPTIMIZE)
            assertEquals(aosp, SwitchOnPlan.battery(SettingsMaker.AOSP, sdk))
            assertEquals(aosp, SwitchOnPlan.battery(SettingsMaker.XIAOMI, sdk))
            // One UI calls the list "Optimize battery usage", its filter "All", and has a switch per app.
            assertEquals(
                listOf(SwitchOnStep.OPTIMIZE_BATTERY_USAGE, SwitchOnStep.ALL_SAMSUNG, SwitchOnStep.SWITCH_OFF_OPTIMIZING),
                SwitchOnPlan.battery(SettingsMaker.SAMSUNG, sdk),
            )
            assertEquals(BatteryPage.OPTIMIZATION_LIST, SwitchOnPlan.batteryPage(sdk))
        }
    }

    @Test
    fun `the maker's extra step is never in the numbered battery steps`() {
        for (sdk in 28..35) {
            for (maker in SettingsMaker.entries) {
                val steps = SwitchOnPlan.battery(maker, sdk)
                assertFalse(SwitchOnStep.NEVER_SLEEPING in steps || SwitchOnStep.AUTOSTART in steps, "$maker on $sdk")
            }
        }
    }

    @Test
    fun `Samsung has never sleeping apps and Xiaomi autostart as an extra step`() {
        assertEquals(SwitchOnStep.NEVER_SLEEPING, SwitchOnPlan.batteryExtra(SettingsMaker.SAMSUNG))
        assertEquals(SwitchOnStep.AUTOSTART, SwitchOnPlan.batteryExtra(SettingsMaker.XIAOMI))
        assertNull(SwitchOnPlan.batteryExtra(SettingsMaker.AOSP))
    }

    @Test
    fun `the extra step shows once the battery is unrestricted, until it is marked done`() {
        // Before: the numbered steps come first, and the return after "Unrestricted" ends them.
        assertNull(SwitchOnPlan.batteryExtraShown(SettingsMaker.SAMSUNG, batteryUnrestricted = false, done = false))
        assertEquals(SwitchOnStep.NEVER_SLEEPING, SwitchOnPlan.batteryExtraShown(SettingsMaker.SAMSUNG, batteryUnrestricted = true, done = false))
        assertEquals(SwitchOnStep.AUTOSTART, SwitchOnPlan.batteryExtraShown(SettingsMaker.XIAOMI, batteryUnrestricted = true, done = false))
        // "Done" hides it for good.
        assertNull(SwitchOnPlan.batteryExtraShown(SettingsMaker.SAMSUNG, batteryUnrestricted = true, done = true))
        assertNull(SwitchOnPlan.batteryExtraShown(SettingsMaker.XIAOMI, batteryUnrestricted = true, done = true))
        // Other phones have none.
        assertNull(SwitchOnPlan.batteryExtraShown(SettingsMaker.AOSP, batteryUnrestricted = true, done = false))
    }
}
