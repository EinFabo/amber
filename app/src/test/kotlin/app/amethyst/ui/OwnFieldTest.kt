package app.amethyst.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Amber's own field tells the service of its focus - the only word of it below Android 13. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class OwnFieldTest {

    private val reported = MutableStateFlow(false)
    private val focus = FocusRequester()
    private var shown by mutableStateOf(true)
    private val elsewhere = FocusRequester()

    private fun start() = Robolectric.buildActivity(ComponentActivity::class.java).setup().also { controller ->
        controller.get().setContent {
            CompositionLocalProvider(LocalOwnField provides reported) {
                Column {
                    if (shown) {
                        BasicTextField("", {}, Modifier.reportsOwnField().focusRequester(focus))
                    }
                    // A field that does not report: focus moves there.
                    BasicTextField("", {}, Modifier.focusRequester(elsewhere))
                }
            }
        }
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `focus is reported, and taken back when the field loses it or leaves the screen`() {
        start()
        assertFalse("no focus yet", reported.value)
        focus.requestFocus()
        idle()
        assertTrue(reported.value)
        elsewhere.requestFocus()
        idle()
        assertFalse("focus moved on", reported.value)
        focus.requestFocus()
        idle()
        assertTrue(reported.value)
        shown = false
        idle()
        assertFalse("the field left the screen", reported.value)
    }

    @Test
    fun `leaving the screen counts as losing the focus, coming back as regaining it`() {
        val controller = start()
        focus.requestFocus()
        idle()
        assertTrue(reported.value)
        controller.pause()
        idle()
        assertFalse("another app in front", reported.value)
        controller.resume()
        idle()
        assertTrue("back, the field still focused", reported.value)
    }
}
