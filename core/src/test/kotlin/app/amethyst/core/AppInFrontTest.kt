package app.amethyst.core

import app.amethyst.core.apps.AppInFront
import app.amethyst.core.bubble.SystemShade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppInFrontTest {

    private val own = "app.amethyst"
    private val main = "app.amethyst.ui.MainActivity"
    private val rule = AppInFront(ownPackage = own, ownMainScreen = main)

    @Test
    fun `an app's window change puts it in front`() {
        assertEquals("com.whatsapp", rule.afterWindowChange("com.whatsapp", "com.whatsapp.Conversation", fromKeyboard = false))
    }

    @Test
    fun `the keyboard and the system bars are not the app the user is in`() {
        assertNull(rule.afterWindowChange("com.google.android.inputmethod.latin", "android.inputmethodservice.SoftInputWindow", fromKeyboard = true))
        assertNull(rule.afterWindowChange(SystemShade.SYSTEM_UI, "android.widget.FrameLayout", fromKeyboard = false))
    }

    @Test
    fun `only sayEmber's main screen counts of its own windows - not an overlay, a toast or the microphone helper`() {
        assertEquals(own, rule.afterWindowChange(own, main, fromKeyboard = false))
        assertNull(rule.afterWindowChange(own, "android.widget.FrameLayout", fromKeyboard = false))
        assertNull(rule.afterWindowChange(own, "app.amethyst.capture.MicProxyActivity", fromKeyboard = false))
        assertNull(rule.afterWindowChange(own, null, fromKeyboard = false))
    }

    @Test
    fun `a home screen is in front, but it is never an app seen in front`() {
        val home = "com.google.android.apps.nexuslauncher"
        assertEquals(home, rule.afterWindowChange(home, "com.android.launcher3.Launcher", fromKeyboard = false))
        assertFalse(rule.worthNoting(home) { it == home })
        assertTrue(rule.worthNoting("org.thoughtcrime.securesms") { it == home })
    }

    @Test
    fun `sayEmber itself is never an app seen in front, and the home check is not asked for it`() {
        var asked = false
        assertFalse(rule.worthNoting(own) { asked = true; false })
        assertFalse(asked)
    }

    @Test
    fun `the quick menu asks the focused application window, not the last window event`() {
        val windows = listOf(
            window(application = false, focused = true, owner = SystemShade.SYSTEM_UI),
            window(application = true, focused = false, owner = "com.google.android.apps.nexuslauncher"),
            window(application = true, focused = true, owner = "com.whatsapp"),
        )
        assertEquals("com.whatsapp", rule.focused(windows))
    }

    @Test
    fun `no focused application window, or one without content, says nothing`() {
        assertNull(rule.focused(listOf(window(application = true, focused = false, owner = "com.whatsapp"))))
        assertNull(rule.focused(listOf(window(application = true, focused = true, owner = null))))
        assertNull(rule.focused(emptyList()))
    }

    @Test
    fun `only the focused window's owner is asked - a cross-process call`() {
        val asked = mutableListOf<String>()
        val windows = listOf("a", "b", "c").map { pkg ->
            AppInFront.Window(application = true, focused = pkg == "b", owner = { asked += pkg; pkg })
        }
        assertEquals("b", rule.focused(windows))
        assertEquals(listOf("b"), asked)
    }

    private fun window(application: Boolean, focused: Boolean, owner: String?) =
        AppInFront.Window(application = application, focused = focused, owner = { owner })
}
