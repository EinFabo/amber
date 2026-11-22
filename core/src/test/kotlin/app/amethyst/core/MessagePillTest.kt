package app.amethyst.core

import app.amethyst.core.bubble.Box
import app.amethyst.core.bubble.MessagePlacement
import app.amethyst.core.bubble.MessageQueue
import app.amethyst.core.bubble.MessageTiming
import app.amethyst.core.bubble.SafeArea
import app.amethyst.core.bubble.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessagePillTest {

    @Test
    fun `a short message stays the minimum, a long one longer, never past the maximum`() {
        assertEquals(MessageTiming.MIN_MS, MessageTiming.shownMs("Still working…"))
        val sixty = "x".repeat(60)
        assertTrue(MessageTiming.shownMs(sixty) > MessageTiming.MIN_MS)
        assertTrue(MessageTiming.shownMs(sixty) < MessageTiming.MAX_MS)
        assertEquals(MessageTiming.MAX_MS, MessageTiming.shownMs("x".repeat(500)))
    }

    @Test
    fun `more words never show for less time`() {
        val times = (0..200).map { MessageTiming.shownMs("x".repeat(it)) }
        assertEquals(times.sorted(), times)
    }

    @Test
    fun `only a message that needs more than the minimum is a long toast`() {
        assertFalse(MessageTiming.longToast("Still working…"))
        assertTrue(MessageTiming.longToast("Copied - the app did not take the text. Paste it by hand."))
    }

    // Pixel 9a: 1080 x 2424, status bar 145, gesture bar 63.
    private val screen = Screen(1080, 2424, SafeArea(top = 145, bottom = 63))
    private val gap = 42
    private val width = 800
    private val height = 120

    private fun place(keyboardTop: Int? = null, bubble: Box? = null, on: Screen = screen) =
        MessagePlacement.place(on, width, height, gap, keyboardTop, bubble)

    @Test
    fun `without a keyboard it sits above the bottom bar, centred`() {
        val (x, y) = place()
        assertEquals((1080 - width) / 2, x)
        assertEquals(2424 - 63 - gap - height, y)
    }

    @Test
    fun `with a keyboard it sits above the keys`() {
        assertEquals(1466 - gap - height, place(keyboardTop = 1466).second)
    }

    @Test
    fun `a bubble beside it does not move it`() {
        val y = 1466 - gap - height
        // The bubble at the left edge, at the same height, but clear of the pill's width.
        val bubble = Box(0, y, 120, y + 147)
        assertEquals(y, place(keyboardTop = 1466, bubble = bubble).second)
    }

    @Test
    fun `a bubble in its place puts it above the bubble`() {
        val bubble = Box(800, 1250, 947, 1397)
        assertEquals(1250 - gap - height, place(keyboardTop = 1466, bubble = bubble).second)
    }

    @Test
    fun `it never goes under the status bar`() {
        // Landscape with the keyboard nearly to the top.
        val wide = Screen(2424, 1080, SafeArea(top = 145))
        assertEquals(145 + gap, place(keyboardTop = 200, on = wide).second)
        val bubble = Box(1100, 300, 1247, 447)
        assertEquals(145 + gap, place(keyboardTop = 600, bubble = bubble, on = wide).second)
    }

    @Test
    fun `a cutout at one side keeps it centred in what is left`() {
        val cut = Screen(2424, 1080, SafeArea(left = 120, top = 0))
        assertEquals(120 + (2424 - 120 - width) / 2, place(on = cut).first)
    }

    @Test
    fun `it is no wider than its cap and keeps clear of the edges`() {
        assertEquals(900, MessagePlacement.maxWidth(screen, margin = 42, cap = 900))
        assertEquals(1080 - 2 * 42, MessagePlacement.maxWidth(screen, margin = 42, cap = 2000))
        val cut = Screen(1080, 2424, SafeArea(left = 100, right = 100))
        assertEquals(1080 - 200 - 2 * 42, MessagePlacement.maxWidth(cut, margin = 42, cap = 2000))
    }

    @Test
    fun `a keyboard reported below the screen counts as none`() {
        assertEquals(place().second, place(keyboardTop = 3000).second)
    }
}

class MessageQueueTest {

    @Test
    fun `the first message shows at once`() {
        val queue = MessageQueue()
        assertTrue(queue.add("a"))
        assertEquals("a", queue.showing)
    }

    @Test
    fun `a second message waits until the first had its time, as toasts do`() {
        val queue = MessageQueue()
        queue.add("Recognized with the phone instead.")
        assertFalse(queue.add("The text is on the clipboard. Paste it."))
        assertEquals("Recognized with the phone instead.", queue.showing)
        assertEquals("The text is on the clipboard. Paste it.", queue.next())
        assertEquals("The text is on the clipboard. Paste it.", queue.showing)
        assertNull(queue.next())
        assertNull(queue.showing)
    }

    @Test
    fun `the same text again starts its time over and is not shown twice`() {
        val queue = MessageQueue()
        queue.add("Still working…")
        assertTrue(queue.add("Still working…"))
        queue.add("b")
        assertFalse(queue.add("b"))
        assertEquals("b", queue.next())
        assertNull(queue.next())
    }

    @Test
    fun `after the last one went, the next shows at once`() {
        val queue = MessageQueue()
        queue.add("a")
        queue.next()
        assertTrue(queue.add("b"))
        assertEquals("b", queue.showing)
    }

    @Test
    fun `a pile-up keeps the newest waiting, never more than the cap`() {
        val queue = MessageQueue(cap = 2)
        queue.add("on screen")
        listOf("1", "2", "3").forEach { queue.add(it) }
        assertEquals("2", queue.next())
        assertEquals("3", queue.next())
        assertNull(queue.next())
    }

    @Test
    fun `cleared, nothing shows or waits`() {
        val queue = MessageQueue()
        queue.add("a")
        queue.add("b")
        queue.clear()
        assertNull(queue.showing)
        assertNull(queue.next())
        assertTrue(queue.add("c"))
    }
}
