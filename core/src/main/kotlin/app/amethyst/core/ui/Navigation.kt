package app.amethyst.core.ui

/** One place in the app's screen stack ([Navigation]). */
sealed interface Screen {
    /** The introduction; its step is kept beside the stack. */
    data object Onboarding : Screen
    data object Home : Screen
    data object Settings : Screen
    data class Page(val page: SettingsPage) : Screen
    data class EngineDetail(val engineId: String) : Screen
    data class AppStyle(val packageName: String) : Screen
}

/**
 * The app's screens as a stack: Home → Settings → a page → an engine, and back the same way.
 *
 * The stack is saved as text ([save], [restore]) so a rotation, split screen, a new font size
 * or Android ending the app in the background brings it back where it was, not on Home. Each
 * place has a [keys] entry under which its own state (scroll position, typed text) is kept while
 * a screen above covers it, and dropped once it is taken off the stack ([forgotten]).
 */
object Navigation {

    /** A fresh install starts in the introduction instead of Home. */
    fun start(introduce: Boolean): List<Screen> = listOf(if (introduce) Screen.Onboarding else Screen.Home)

    /**
     * [next] on top. A second tap on a row that already opened its page - before the page
     * covered the row - opens nothing more: otherwise it would take two Backs to get out.
     */
    fun open(stack: List<Screen>, next: Screen): List<Screen> = if (stack.lastOrNull() == next) stack else stack + next

    /** One step back; the last screen stays - leaving it is Android's Back, out of the app. */
    fun back(stack: List<Screen>): List<Screen> = if (stack.size > 1) stack.dropLast(1) else stack

    /**
     * The quick menu's "App page": that page, with the way back through the app styles and
     * Settings. Never out of the introduction - it is finished first, so the stack stays.
     */
    fun toAppPage(stack: List<Screen>, packageName: String): List<Screen> =
        if (Screen.Onboarding in stack) {
            stack
        } else {
            listOf(Screen.Home, Screen.Settings, Screen.Page(SettingsPage.APP_STYLES), Screen.AppStyle(packageName))
        }

    /**
     * Whether going from [from] to [to] is a way back - the way that was come, a step or more
     * towards Home. Anything else (a page opened, the introduction finished, the stack replaced)
     * is a way forward; the screens slide by it.
     */
    fun isBack(from: List<Screen>, to: List<Screen>): Boolean =
        to.size < from.size && from.subList(0, to.size) == to

    /** The stack as text, for Android's saved state. */
    fun save(stack: List<Screen>): List<String> = stack.map(::name)

    /**
     * The stack [save] wrote, or null when it cannot be read whole - then the app starts as it
     * would anyway ([start]) instead of failing. Every stack starts at Home or in the introduction.
     */
    fun restore(saved: List<String>?): List<Screen>? {
        if (saved.isNullOrEmpty()) return null
        val stack = saved.map { parse(it) ?: return null }
        return stack.takeIf { it.first() == Screen.Home || it.first() == Screen.Onboarding }
    }

    /**
     * One key per place, under which the place keeps its own state. Its position is part of it,
     * so the same page twice in the stack keeps two states, and a page opened again after it was
     * left starts fresh.
     */
    fun keys(stack: List<Screen>): List<String> = stack.mapIndexed { index, screen -> "$index:${name(screen)}" }

    /** The key of the screen shown ([keys]). */
    fun topKey(stack: List<Screen>): String = "${stack.lastIndex}:${name(stack.last())}"

    /** The keys of the places [after] no longer has: their state can go. */
    fun forgotten(before: List<Screen>, after: List<Screen>): List<String> = keys(before) - keys(after).toSet()

    private fun name(screen: Screen): String = when (screen) {
        Screen.Onboarding -> ONBOARDING
        Screen.Home -> HOME
        Screen.Settings -> SETTINGS
        is Screen.Page -> "$PAGE/${screen.page.name}"
        is Screen.EngineDetail -> "$ENGINE/${screen.engineId}"
        is Screen.AppStyle -> "$APP/${screen.packageName}"
    }

    private fun parse(name: String): Screen? {
        when (name) {
            ONBOARDING -> return Screen.Onboarding
            HOME -> return Screen.Home
            SETTINGS -> return Screen.Settings
        }
        if ('/' !in name) return null
        // Only the first slash divides: an engine's id may hold one of its own.
        val kind = name.substringBefore('/')
        val value = name.substringAfter('/').ifEmpty { return null }
        return when (kind) {
            PAGE -> SettingsPage.entries.firstOrNull { it.name == value }?.let(Screen::Page)
            ENGINE -> Screen.EngineDetail(value)
            APP -> Screen.AppStyle(value)
            else -> null
        }
    }

    private const val ONBOARDING = "onboarding"
    private const val HOME = "home"
    private const val SETTINGS = "settings"
    private const val PAGE = "page"
    private const val ENGINE = "engine"
    private const val APP = "app"
}
