package app.amethyst.core

import app.amethyst.core.insert.InsertionStrategy
import app.amethyst.core.insert.StrategyMemory
import kotlin.test.Test
import kotlin.test.assertEquals

class StrategyMemoryTest {

    private val whatsapp = "com.whatsapp"

    @Test
    fun `unknown app gets the default probe order`() {
        assertEquals(StrategyMemory.DEFAULT_ORDER, StrategyMemory().order(whatsapp))
    }

    @Test
    fun `a successful strategy moves to the front for that app only`() {
        val memory = StrategyMemory()
        memory.recordSuccess(whatsapp, InsertionStrategy.PASTE)

        assertEquals(
            listOf(
                InsertionStrategy.PASTE,
                InsertionStrategy.COMMIT_TEXT,
                InsertionStrategy.SET_TEXT,
                InsertionStrategy.CLIPBOARD_ONLY,
            ),
            memory.order(whatsapp),
        )
        assertEquals(StrategyMemory.DEFAULT_ORDER, memory.order("com.android.chrome"))
    }

    @Test
    fun `a failure forgets the remembered strategy so it is probed again`() {
        val memory = StrategyMemory(mapOf(whatsapp to InsertionStrategy.SET_TEXT))
        memory.recordFailure(whatsapp, InsertionStrategy.SET_TEXT)
        assertEquals(StrategyMemory.DEFAULT_ORDER, memory.order(whatsapp))
    }

    @Test
    fun `a failure of some other strategy keeps the preference`() {
        val memory = StrategyMemory(mapOf(whatsapp to InsertionStrategy.PASTE))
        memory.recordFailure(whatsapp, InsertionStrategy.SET_TEXT)
        assertEquals(InsertionStrategy.PASTE, memory.order(whatsapp).first())
    }

    @Test
    fun `clipboard fallback is never remembered as a preference`() {
        val memory = StrategyMemory(mapOf(whatsapp to InsertionStrategy.SET_TEXT))
        memory.recordSuccess(whatsapp, InsertionStrategy.CLIPBOARD_ONLY)
        assertEquals(StrategyMemory.DEFAULT_ORDER, memory.order(whatsapp))
        assertEquals(emptyMap(), memory.snapshot())
    }

    @Test
    fun `snapshot round-trips through the constructor`() {
        val memory = StrategyMemory()
        memory.recordSuccess(whatsapp, InsertionStrategy.PASTE)
        memory.recordSuccess("org.mozilla.firefox", InsertionStrategy.SET_TEXT)

        val restored = StrategyMemory(memory.snapshot())
        assertEquals(InsertionStrategy.PASTE, restored.order(whatsapp).first())
        assertEquals(InsertionStrategy.SET_TEXT, restored.order("org.mozilla.firefox").first())
    }
}
