package app.amethyst.core.insert

/** How text gets into the focused field of a foreign app. */
enum class InsertionStrategy {
    /**
     * Type into the field through the accessibility service's own input connection, the way a
     * keyboard does (Android 13+). Inserts at the cursor, keeps undo, works in WebViews, and
     * never touches the clipboard.
     */
    COMMIT_TEXT,

    /** Read the field, splice at the cursor, write it back with ACTION_SET_TEXT. */
    SET_TEXT,

    /** Put the text on the clipboard and fire ACTION_PASTE at the focused node. */
    PASTE,

    /** Clipboard only, with a hint to the user. Last resort, but never fails. */
    CLIPBOARD_ONLY,
}

/**
 * Remembers which insertion strategy actually worked, per target app.
 *
 * ACTION_SET_TEXT is unreliable in WebViews, games and some cross-platform toolkits, and
 * which apps break is not knowable in advance. So instead of guessing globally, Amethyst
 * probes once per app and then goes straight to the method that worked - the user sees one
 * hiccup in an app, never a recurring one.
 */
class StrategyMemory(initial: Map<String, InsertionStrategy> = emptyMap()) {

    private val preferred = initial.toMutableMap()

    /** Strategies to try for [packageName], best first. */
    fun order(packageName: String): List<InsertionStrategy> {
        val known = preferred[packageName] ?: return DEFAULT_ORDER
        return listOf(known) + DEFAULT_ORDER.filter { it != known }
    }

    fun recordSuccess(packageName: String, strategy: InsertionStrategy) {
        // Clipboard-only is a fallback, not a preference: never lock an app onto it, or a
        // single bad moment would permanently downgrade that app.
        if (strategy == InsertionStrategy.CLIPBOARD_ONLY) {
            preferred.remove(packageName)
        } else {
            preferred[packageName] = strategy
        }
    }

    fun recordFailure(packageName: String, strategy: InsertionStrategy) {
        if (preferred[packageName] == strategy) preferred.remove(packageName)
    }

    fun forget(packageName: String) {
        preferred.remove(packageName)
    }

    fun snapshot(): Map<String, InsertionStrategy> = preferred.toMap()

    companion object {
        val DEFAULT_ORDER: List<InsertionStrategy> = listOf(
            InsertionStrategy.COMMIT_TEXT,
            InsertionStrategy.SET_TEXT,
            InsertionStrategy.PASTE,
            InsertionStrategy.CLIPBOARD_ONLY,
        )
    }
}
