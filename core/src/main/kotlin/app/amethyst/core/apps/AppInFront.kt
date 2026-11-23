package app.amethyst.core.apps

import app.amethyst.core.bubble.SystemShade

/**
 * Which app the user is in, told from what the accessibility service sees. Each rule here broke
 * once on a phone before it was written down:
 *
 * - The keyboard and SystemUI send window changes too; they are not the app the user is in.
 * - Neither are Embersay's own overlays - the hide target, a toast, the microphone helper:
 *   taken for the app in front, they switched off drag-to-hide for the next drag and let the
 *   bubble back into hidden apps (rc2). Only Embersay's main screen counts.
 * - A home screen is in front between apps, but nothing is dictated there: it never goes on
 *   [AppsSeenInFront].
 * - The last window event can be stale: back from the recent apps into an app, Pixel's launcher
 *   sent the last one, and the quick menu offered a style for the launcher (rc3). The quick menu
 *   asks the windows themselves which one has the focus ([focused]).
 */
class AppInFront(
    private val ownPackage: String,
    /** The class name of Embersay's main screen, the one window of its own that is an app in front. */
    private val ownMainScreen: String,
) {

    /** One window as the service reports it, [owner] asked only for the focused application window. */
    class Window(
        /** AccessibilityWindowInfo.TYPE_APPLICATION: an app's own window, not a bar, the keyboard or an overlay. */
        val application: Boolean,
        val focused: Boolean,
        /** The package of the window's content; a cross-process call, so only asked last. */
        val owner: () -> String?,
    )

    /**
     * The app a window change from [pkg] (its window [className]) puts in front, or null when
     * the window is not an app the user is in: the keyboard ([fromKeyboard]), SystemUI, or one
     * of Embersay's overlays.
     */
    fun afterWindowChange(pkg: String, className: String?, fromKeyboard: Boolean): String? {
        val ownOverlay = pkg == ownPackage && className != ownMainScreen
        return pkg.takeIf { it != SystemShade.SYSTEM_UI && !fromKeyboard && !ownOverlay }
    }

    /**
     * Whether [pkg], seen in front, is an app for [AppsSeenInFront]: never Embersay, never a home
     * screen. [isHome] asks the package manager, so only when the package is not Embersay's.
     */
    fun worthNoting(pkg: String, isHome: (String) -> Boolean): Boolean = pkg != ownPackage && !isHome(pkg)

    /** The app whose application window has the focus right now, or null when none has. */
    fun focused(windows: List<Window>): String? =
        windows.firstOrNull { it.application && it.focused }?.owner?.invoke()
}
