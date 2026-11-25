package app.amethyst.core

import app.amethyst.core.bubble.MicLook
import kotlin.test.Test
import kotlin.test.assertEquals

class MicLookTest {

    @Test
    fun `before the microphone is open the bubble is opening, not listening`() {
        assertEquals(MicLook.OPENING, MicLook.of(open = false))
    }

    @Test
    fun `once the microphone is open the bubble listens`() {
        assertEquals(MicLook.LISTENING, MicLook.of(open = true))
    }
}
