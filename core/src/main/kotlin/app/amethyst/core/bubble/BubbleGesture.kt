package app.amethyst.core.bubble

import app.amethyst.core.commands.CommandTrigger
import kotlin.math.abs

/**
 * What a touch on the bubble means, as a pure state machine. The view feeds it the touch
 * (screen coordinates, uptime) and a timer; it answers with one [Event] per input.
 *
 * - A short still touch is a tap. A second one right after a tap that started a recording is
 *   a double tap - only while [doubleTapCommands] is on.
 * - Moving past the touch slop before the hold is a drag: the bubble follows the finger.
 * - Holding still for [holdMs] opens the quick menu; the caller lays out its fan and hands it
 *   over with [fanShown]. Sliding out of the dead zone lights the slot the finger points to
 *   (QuickMenuGeometry.Fan.slotAt); lifting runs it. Lifting in the middle - before leaving it
 *   or after coming back - only closes the menu. There is no hold-to-talk.
 * - A cancelled touch runs nothing.
 *
 * The hold is timed by the caller ([hold]) as well as checked on every move: a finger held
 * perfectly still produces no move events at all, and a timer can be late.
 */
class BubbleGesture(
    private val touchSlop: Float,
    private val deadZone: Float,
    private val holdMs: Long = HOLD_MS,
) {
    /** Whether a quick second tap turns the recording into a voice command. */
    var doubleTapCommands: Boolean = false

    sealed interface Event {
        /** Nothing to do. */
        data object None : Event

        /** A tap; [doubleTap]: the second of a double tap. Report what it did with [tapDone]. */
        data class Tap(val doubleTap: Boolean) : Event

        /** The bubble follows the finger, [dx], [dy] from where it pressed; [started] the first time. */
        data class Drag(val dx: Float, val dy: Float, val started: Boolean) : Event

        /** The drag is over - by lifting, or [cancelled] by the system. */
        data class DragEnd(val dx: Float, val dy: Float, val cancelled: Boolean) : Event

        /** The finger held still long enough: show the quick menu. */
        data object MenuOpen : Event

        /** The finger moved in the open menu; [slot] is lit (null: none), [slotChanged] when that changed. */
        data class Menu(val slot: Int?, val slotChanged: Boolean) : Event

        /** Lifted over [slot]: run it. */
        data class Select(val slot: Int) : Event

        /** The menu closes without running anything. */
        data object MenuClosed : Event
    }

    private enum class Phase { UP, PRESSED, DRAGGING, MENU }

    private var phase = Phase.UP
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var doubleTap = false
    private var slot: Int? = null

    /** The open menu's fan and which of its slots hold an action; null until [fanShown]. */
    private var fan: QuickMenuGeometry.Fan? = null
    private var selectable: List<Boolean> = emptyList()

    /** Where the finger was last seen, from where it pressed - for a drag the system cancels. */
    private var lastDx = 0f
    private var lastDy = 0f

    /** The last tap's lift, and whether that tap started a recording - for the double tap. */
    private var lastTapUpAt: Long? = null
    private var lastTapStarted = false

    /** A finger touched the bubble. A gesture still open is dropped. */
    fun down(x: Float, y: Float, at: Long): Event {
        downX = x
        downY = y
        downAt = at
        lastDx = 0f
        lastDy = 0f
        slot = null
        doubleTap = CommandTrigger.isDoubleTap(lastTapUpAt, lastTapStarted, at, doubleTapCommands)
        lastTapUpAt = null
        phase = Phase.PRESSED
        return Event.None
    }

    fun move(x: Float, y: Float, at: Long): Event {
        val dx = x - downX
        val dy = y - downY
        lastDx = dx
        lastDy = dy
        return when (phase) {
            Phase.UP -> Event.None
            Phase.PRESSED -> when {
                abs(dx) > touchSlop || abs(dy) > touchSlop -> {
                    phase = Phase.DRAGGING
                    Event.Drag(dx, dy, started = true)
                }
                at - downAt >= holdMs -> openMenu()
                else -> Event.None
            }
            Phase.DRAGGING -> Event.Drag(dx, dy, started = false)
            Phase.MENU -> {
                val now = slotAt(x, y)
                val changed = now != slot
                slot = now
                Event.Menu(now, changed)
            }
        }
    }

    /** The hold timer fired; harmless when it is stale or the finger moved meanwhile. */
    fun hold(at: Long): Event =
        if (phase == Phase.PRESSED && at - downAt >= holdMs) openMenu() else Event.None

    fun up(x: Float, y: Float, at: Long): Event {
        val event = when (phase) {
            Phase.UP -> Event.None
            Phase.PRESSED -> Event.Tap(doubleTap)
            Phase.DRAGGING -> Event.DragEnd(x - downX, y - downY, cancelled = false)
            Phase.MENU -> slotAt(x, y)?.let { Event.Select(it) } ?: Event.MenuClosed
        }
        phase = Phase.UP
        return event
    }

    /** The system took the touch away (ACTION_CANCEL): nothing runs, a drag still ends. */
    fun cancel(): Event {
        val event = when (phase) {
            Phase.UP, Phase.PRESSED -> Event.None
            Phase.DRAGGING -> Event.DragEnd(lastDx, lastDy, cancelled = true)
            Phase.MENU -> Event.MenuClosed
        }
        phase = Phase.UP
        return event
    }

    /** What the [Event.Tap] lifted at [upAt] did: only a tap that started a recording can be doubled. */
    fun tapDone(upAt: Long, startedRecording: Boolean) {
        lastTapUpAt = if (startedRecording) upAt else null
        lastTapStarted = startedRecording
    }

    /**
     * The menu that [Event.MenuOpen] asked for is laid out as [fan]; [selectable] says which
     * slots hold an action - an empty one is never picked.
     */
    fun fanShown(fan: QuickMenuGeometry.Fan, selectable: List<Boolean>) {
        if (phase != Phase.MENU) return
        this.fan = fan
        this.selectable = selectable
    }

    private fun slotAt(x: Float, y: Float): Int? =
        fan?.slotAt(x, y, deadZone) { selectable.getOrElse(it) { false } }

    private fun openMenu(): Event {
        phase = Phase.MENU
        slot = null
        fan = null
        selectable = emptyList()
        return Event.MenuOpen
    }

    companion object {
        /**
         * Long enough that a quick tap is never taken for a hold, short enough that the menu
         * comes without a noticeable wait. The system long-press timeout (400-500 ms) felt
         * sluggish for this.
         */
        const val HOLD_MS = 300L
    }
}
