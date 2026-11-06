package app.amethyst.core

import app.amethyst.core.bubble.BubbleGesture
import app.amethyst.core.bubble.BubbleGesture.Event
import app.amethyst.core.bubble.BubbleSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BubbleGestureTest {

    // A Pixel 9a: touch slop 8 dp (21 px), a dead zone of half a 56 dp bubble (74 px).
    private val gesture = BubbleGesture(touchSlop = 21f, deadZone = 74f)
    private val x = 1000f
    private val y = 1500f

    @Test
    fun `a short still touch is a tap`() {
        gesture.down(x, y, at = 0, side = BubbleSide.RIGHT)
        assertEquals(Event.None, gesture.move(x + 5, y - 3, at = 80))
        assertEquals(Event.Tap(doubleTap = false), gesture.up(x + 5, y - 3, at = 120))
    }

    @Test
    fun `a second tap right after one that started a recording is a double tap`() {
        gesture.doubleTapCommands = true
        gesture.down(x, y, at = 0, side = BubbleSide.RIGHT)
        assertEquals(Event.Tap(doubleTap = false), gesture.up(x, y, at = 100))
        gesture.tapDone(upAt = 100, startedRecording = true)
        gesture.down(x, y, at = 250, side = BubbleSide.RIGHT)
        assertEquals(Event.Tap(doubleTap = true), gesture.up(x, y, at = 330))
    }

    @Test
    fun `without voice commands two quick taps stay two taps`() {
        gesture.doubleTapCommands = false
        gesture.down(x, y, at = 0, side = BubbleSide.RIGHT)
        gesture.up(x, y, at = 100)
        gesture.tapDone(upAt = 100, startedRecording = true)
        gesture.down(x, y, at = 250, side = BubbleSide.RIGHT)
        assertEquals(Event.Tap(doubleTap = false), gesture.up(x, y, at = 330))
    }

    @Test
    fun `a tap that stopped a recording is no first half of a double tap`() {
        gesture.doubleTapCommands = true
        gesture.down(x, y, at = 0, side = BubbleSide.RIGHT)
        gesture.up(x, y, at = 100)
        gesture.tapDone(upAt = 100, startedRecording = false)
        gesture.down(x, y, at = 250, side = BubbleSide.RIGHT)
        assertEquals(Event.Tap(doubleTap = false), gesture.up(x, y, at = 330))
    }

    @Test
    fun `a slow second tap is a tap of its own`() {
        gesture.doubleTapCommands = true
        gesture.down(x, y, at = 0, side = BubbleSide.RIGHT)
        gesture.up(x, y, at = 100)
        gesture.tapDone(upAt = 100, startedRecording = true)
        gesture.down(x, y, at = 700, side = BubbleSide.RIGHT)
        assertEquals(Event.Tap(doubleTap = false), gesture.up(x, y, at = 780))
    }

    @Test
    fun `a quick drag moves the bubble and never opens the menu`() {
        gesture.down(x, y, at = 0, side = BubbleSide.RIGHT)
        assertEquals(Event.Drag(dx = -60f, dy = 10f, started = true), gesture.move(x - 60, y + 10, at = 50))
        assertEquals(Event.None, gesture.hold(at = 300))
        assertEquals(Event.Drag(dx = -300f, dy = 40f, started = false), gesture.move(x - 300, y + 40, at = 400))
        assertEquals(Event.DragEnd(dx = -310f, dy = 40f, cancelled = false), gesture.up(x - 310, y + 40, at = 450))
    }

    @Test
    fun `holding still opens the menu, and letting go in the middle closes it`() {
        gesture.down(x, y, at = 0, side = BubbleSide.RIGHT)
        assertEquals(Event.MenuOpen, gesture.hold(at = 300))
        assertEquals(Event.MenuClosed, gesture.up(x + 4, y, at = 500))
    }

    @Test
    fun `a hold timer from too early does nothing`() {
        gesture.down(x, y, at = 1000, side = BubbleSide.RIGHT)
        assertEquals(Event.None, gesture.hold(at = 1100))
        assertEquals(Event.Tap(doubleTap = false), gesture.up(x, y, at = 1150))
    }

    @Test
    fun `jitter under the touch slop keeps the hold`() {
        gesture.down(x, y, at = 0, side = BubbleSide.RIGHT)
        assertEquals(Event.None, gesture.move(x + 6, y - 8, at = 150))
        assertEquals(Event.MenuOpen, gesture.hold(at = 300))
    }

    @Test
    fun `a move that comes after the hold time opens the menu even if the timer is late`() {
        gesture.down(x, y, at = 0, side = BubbleSide.RIGHT)
        assertEquals(Event.MenuOpen, gesture.move(x + 3, y, at = 320))
        assertEquals(Event.None, gesture.hold(at = 330), "opened once")
    }

    @Test
    fun `sliding to a slot lights it up, and letting go runs it`() {
        gesture.down(x, y, at = 0, side = BubbleSide.RIGHT)
        gesture.hold(at = 300)
        val inMenu = gesture.move(x - 200, y - 60, at = 400)
        assertEquals(Event.Menu(dx = -200f, dy = -60f, slot = 1, slotChanged = true), inMenu)
        assertEquals(Event.Menu(dx = -210f, dy = -60f, slot = 1, slotChanged = false), gesture.move(x - 210, y - 60, at = 420))
        assertEquals(Event.Select(1), gesture.up(x - 210, y - 60, at = 500))
    }

    @Test
    fun `back in the middle after leaving it cancels`() {
        gesture.down(x, y, at = 0, side = BubbleSide.RIGHT)
        gesture.hold(at = 300)
        gesture.move(x - 200, y + 60, at = 400)
        assertEquals(Event.Menu(dx = -10f, dy = 5f, slot = null, slotChanged = true), gesture.move(x - 10, y + 5, at = 450))
        assertEquals(Event.MenuClosed, gesture.up(x - 10, y + 5, at = 500))
    }

    @Test
    fun `the left edge picks with the mirrored fan`() {
        gesture.down(80f, y, at = 0, side = BubbleSide.LEFT)
        gesture.hold(at = 300)
        gesture.move(280f, y + 60, at = 400)
        assertEquals(Event.Select(2), gesture.up(280f, y + 60, at = 450))
    }

    @Test
    fun `a cancelled touch runs nothing`() {
        gesture.down(x, y, at = 0, side = BubbleSide.RIGHT)
        gesture.hold(at = 300)
        gesture.move(x - 200, y - 60, at = 400)
        assertEquals(Event.MenuClosed, gesture.cancel())

        gesture.down(x, y, at = 1000, side = BubbleSide.RIGHT)
        assertEquals(Event.None, gesture.cancel(), "a press that was cancelled is no tap")

        gesture.down(x, y, at = 2000, side = BubbleSide.RIGHT)
        gesture.move(x - 100, y, at = 2050)
        assertEquals(Event.DragEnd(dx = -100f, dy = 0f, cancelled = true), gesture.cancel())
    }

    @Test
    fun `after the menu or a drag the next tap is no double tap`() {
        gesture.doubleTapCommands = true
        gesture.down(x, y, at = 0, side = BubbleSide.RIGHT)
        gesture.up(x, y, at = 100)
        gesture.tapDone(upAt = 100, startedRecording = true)
        gesture.down(x, y, at = 200, side = BubbleSide.RIGHT)
        gesture.move(x - 100, y, at = 250)
        gesture.up(x - 100, y, at = 280)
        gesture.down(x, y, at = 400, side = BubbleSide.RIGHT)
        assertEquals(Event.Tap(doubleTap = false), gesture.up(x, y, at = 450))
    }

    @Test
    fun `events after the finger lifted are ignored`() {
        gesture.down(x, y, at = 0, side = BubbleSide.RIGHT)
        gesture.up(x, y, at = 100)
        assertEquals(Event.None, gesture.move(x - 300, y, at = 150))
        assertEquals(Event.None, gesture.hold(at = 400))
        assertEquals(Event.None, gesture.up(x, y, at = 450))
        assertEquals(Event.None, gesture.cancel())
    }

    @Test
    fun `a second press while the menu is open starts over`() {
        gesture.down(x, y, at = 0, side = BubbleSide.RIGHT)
        gesture.hold(at = 300)
        // A lost up: the next down begins a new gesture instead of selecting.
        gesture.down(x, y, at = 900, side = BubbleSide.RIGHT)
        assertIs<Event.Tap>(gesture.up(x, y, at = 950))
        assertTrue(true)
    }
}
