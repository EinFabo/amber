package app.amethyst.core

import app.amethyst.core.onboarding.InstallSource
import app.amethyst.core.onboarding.PackageSource
import app.amethyst.core.onboarding.RestrictedSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestrictedSettingsTest {

    private val play = InstallSource("com.android.vending", PackageSource.STORE)

    @Test
    fun `before Android 13 nothing is locked`() {
        assertFalse(RestrictedSettings.mayBlock(32, InstallSource(null, PackageSource.UNSPECIFIED)))
        assertFalse(RestrictedSettings.mayBlock(32, null))
    }

    @Test
    fun `an install from Play is not locked`() {
        assertFalse(RestrictedSettings.mayBlock(35, play))
        // Play by name alone, as older installers report it.
        assertFalse(RestrictedSettings.mayBlock(35, InstallSource("com.android.vending", PackageSource.UNSPECIFIED)))
    }

    @Test
    fun `any installer that says it is a store is trusted`() {
        assertFalse(RestrictedSettings.mayBlock(34, InstallSource("org.example.store", PackageSource.STORE)))
    }

    @Test
    fun `an APK opened from a file or a download may be locked`() {
        assertTrue(RestrictedSettings.mayBlock(33, InstallSource("com.google.android.packageinstaller", PackageSource.DOWNLOADED_FILE)))
        assertTrue(RestrictedSettings.mayBlock(35, InstallSource("com.android.packageinstaller", PackageSource.LOCAL_FILE)))
    }

    @Test
    fun `a file install stays suspect whoever is named as installer`() {
        assertTrue(RestrictedSettings.mayBlock(35, InstallSource("com.android.vending", PackageSource.LOCAL_FILE)))
    }

    @Test
    fun `an unknown installer or none at all may be locked`() {
        assertTrue(RestrictedSettings.mayBlock(35, InstallSource(null, PackageSource.UNSPECIFIED)))
        assertTrue(RestrictedSettings.mayBlock(35, InstallSource("org.example.files", PackageSource.OTHER)))
    }

    @Test
    fun `an install source that cannot be read may be locked`() {
        assertTrue(RestrictedSettings.mayBlock(33, null))
    }
}
