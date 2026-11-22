package app.amethyst.core.bubble

/**
 * How long a short message over another app stays. Android drops a toast of an app in the
 * background whose notifications are off (the default from Android 13 until the user allows
 * them), so while the accessibility service runs Amber shows its own small message instead,
 * and that one needs a time of its own: long enough to read, by its length, but gone before
 * it is in the way.
 */
object MessageTiming {
    const val MIN_MS = 2_500L
    const val MAX_MS = 6_000L

    /** A moment to notice it, then about sixteen characters a second. */
    private const val BASE_MS = 1_500L
    private const val PER_CHAR_MS = 60L

    fun shownMs(text: String): Long = (BASE_MS + PER_CHAR_MS * text.length).coerceIn(MIN_MS, MAX_MS)

    /**
     * Without the service it is a plain toast, which knows two lengths only: the short one
     * (about two seconds) for what is read at a glance, the long one for the rest.
     */
    fun longToast(text: String): Boolean = shownMs(text) > MIN_MS
}

/**
 * Where the message sits: centred, just above the keys - or above the bottom bar with no
 * keyboard - where the eyes are while dictating, and above the bubble when the bubble is in
 * that place. Never under the status bar. Pure, so every case is a test.
 */
object MessagePlacement {

    /** As wide as the text needs, up to [cap], and [margin] clear of either edge. */
    fun maxWidth(screen: Screen, margin: Int, cap: Int): Int =
        minOf(cap, screen.width - screen.safe.left - screen.safe.right - 2 * margin).coerceAtLeast(0)

    /**
     * The top-left corner of a message [width] x [height], [gap] clear of the keys of a docked
     * keyboard at [keyboardTop], the bottom bar, the [bubble] (its frame, null when not shown)
     * and the status bar.
     */
    fun place(screen: Screen, width: Int, height: Int, gap: Int, keyboardTop: Int?, bubble: Box?): Pair<Int, Int> {
        val left = screen.safe.left
        val usable = screen.width - left - screen.safe.right
        val x = left + (usable - width) / 2
        val top = screen.safe.top + gap
        val bottom = screen.height - screen.safe.bottom
        val floor = if (keyboardTop != null) minOf(keyboardTop, bottom) else bottom
        var y = floor - gap - height
        if (bubble != null && bubble.overlaps(Box(x, y - gap, x + width, y + height + gap))) {
            y = bubble.top - gap - height
        }
        return x to y.coerceAtLeast(top)
    }
}

/**
 * One message on screen at a time, the next waiting until it had its time. Toasts queue like
 * this, and two messages often come within a moment: "X didn't answer, recognized with Y
 * instead", then "the text is on the clipboard". Swapped at once, the first - which says who
 * heard the words - was gone before it could be read. Not thread-safe: the pill's main thread.
 */
class MessageQueue(
    /** Android keeps about five toasts of an app; past that the oldest waiting one goes, the newest says how things stand. */
    private val cap: Int = 4,
) {
    /** The message on screen, null when none is. */
    var showing: String? = null
        private set

    private val waiting = ArrayDeque<String>()

    /**
     * True when [text] goes on screen now - nothing showed, or it is the text on screen again,
     * whose time starts over. Otherwise it waits its turn; a text already waiting is not added twice.
     */
    fun add(text: String): Boolean {
        if (showing == null || showing == text) {
            showing = text
            return true
        }
        if (text !in waiting) {
            waiting.addLast(text)
            if (waiting.size > cap) waiting.removeFirst()
        }
        return false
    }

    /** The one on screen had its time: the next to show, or null when none waits. */
    fun next(): String? {
        showing = waiting.removeFirstOrNull()
        return showing
    }

    fun clear() {
        showing = null
        waiting.clear()
    }
}
