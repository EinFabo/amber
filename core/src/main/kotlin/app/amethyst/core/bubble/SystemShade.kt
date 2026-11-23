package app.amethyst.core.bubble

/**
 * Whether the notification shade or quick settings are pulled down, told from the windows the
 * accessibility service sees. The bubble is an accessibility overlay, which lies above every
 * window - the shade included - so it has to step aside by itself.
 *
 * The shade is a system window of SystemUI that covers the screen from the top: on Android 12+
 * the "notification shade" window, before that the status bar's own window grown to full
 * height. Collapsed, the status bar is only a strip at the top, and the shade window is not on
 * the screen at all. Pulled down, it takes the input focus (it has to, for Back and a reply
 * typed into a notification). Requiring the focus keeps out SystemUI's other big windows that
 * sit there without being in use - Android's chat bubbles, which lie over the whole screen in
 * a window of their own while collapsed. A volume panel or a dialog from a quick settings tile
 * covers neither the top nor half the screen.
 */
object SystemShade {

    const val SYSTEM_UI = "com.android.systemui"

    /** One window as the service reports it, [owner] asked only when the rest already fits. */
    class Window(
        /** AccessibilityWindowInfo.TYPE_SYSTEM: the bars, the shade, SystemUI's dialogs. */
        val system: Boolean,
        val bounds: Box,
        /** Focused or active: the user is in it. */
        val inUse: Boolean,
        /** The package of the window's content; a cross-process call, so only asked last. */
        val owner: () -> String?,
    )

    fun open(windows: List<Window>, screen: Screen): Boolean = windows.any { window ->
        window.system && window.inUse && coversTop(window.bounds, screen) && window.owner() == SYSTEM_UI
    }

    /**
     * From the top - at most the status bar down - to at least half the screen's height. A
     * shade on its way down already has its full window, so the bubble goes as soon as the
     * pull starts.
     */
    private fun coversTop(bounds: Box, screen: Screen): Boolean =
        bounds.top <= screen.safe.top && bounds.height * 2 >= screen.height && bounds.width * 2 >= screen.width
}
