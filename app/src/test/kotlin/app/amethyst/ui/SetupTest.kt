package app.amethyst.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.Settings
import app.amethyst.accessibility.AmethystAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The way into Android's settings: as close to Amber's switch as the phone allows, never a crash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val service = ComponentName(app, AmethystAccessibilityService::class.java).flattenToString()

    @Before
    fun nothingResolvesUnlessSaidSo() {
        // Starting an intent no activity answers throws, as on a phone.
        shadowOf(app).checkActivities(true)
    }

    private fun settingsAnswer(action: String) = settingsAnswer(Intent(action))

    private fun settingsAnswer(intent: Intent) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.settings"
                name = "com.android.settings.Settings"
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(intent, info)
    }

    @Test
    fun `Amber's own page is asked for with the service as a flattened string`() {
        settingsAnswer("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
        assertTrue(Setup.openAccessibilitySettings(app))
        val started = shadowOf(app).nextStartedActivity
        assertEquals("android.settings.ACCESSIBILITY_DETAILS_SETTINGS", started.action)
        // AOSP's AccessibilityDetailsSettingsFragment reads it with getStringExtra.
        assertEquals(service, started.getStringExtra(Intent.EXTRA_COMPONENT_NAME))
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `without the own page the list opens with Amber's row highlighted`() {
        settingsAnswer(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        assertTrue(Setup.openAccessibilitySettings(app))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, started.action)
        assertEquals(service, started.getStringExtra(":settings:fragment_args_key"))
        assertEquals(service, started.getBundleExtra(":settings:show_fragment_args")?.getString(":settings:fragment_args_key"))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `nowhere to go is a false, not a crash`() {
        assertFalse(Setup.openAccessibilitySettings(app))
        assertFalse(Setup.openAppSettings(app))
    }

    @Test
    fun `the battery list falls back to the app info`() {
        settingsAnswer(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null)))
        assertTrue(Setup.openBatterySettings(app))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(app).nextStartedActivity.action)
    }
}
