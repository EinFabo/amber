package app.amethyst.core

import app.amethyst.core.bubble.BubbleAccess
import app.amethyst.core.bubble.BubbleAccess.Action
import app.amethyst.core.bubble.BubbleAccess.Phase
import app.amethyst.core.bubble.BubbleAccess.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BubbleAccessTest {

    private fun node(
        phase: Phase,
        command: Boolean = false,
        failure: String? = null,
        commandsOn: Boolean = false,
        textFieldFocused: Boolean = true,
        canHide: Boolean = false,
    ) = BubbleAccess.node(phase, command, failure, commandsOn, textFieldFocused, canHide)

    @Test
    fun `each phase names its state`() {
        assertEquals(Status.READY, node(Phase.READY).status)
        assertEquals(Status.LISTENING, node(Phase.LISTENING).status)
        assertEquals(Status.LISTENING_FOR_COMMAND, node(Phase.LISTENING, command = true).status)
        assertEquals(Status.PROCESSING, node(Phase.WORKING).status)
        assertEquals(Status.DONE, node(Phase.DONE).status)
        assertEquals(Status.FAILED, node(Phase.FAILED).status)
    }

    @Test
    fun `at rest the double tap starts a dictation, while listening it stops it`() {
        assertEquals(Action.START_DICTATION, node(Phase.READY).click)
        assertEquals(Action.STOP_DICTATION, node(Phase.LISTENING).click)
        assertEquals(listOf(Action.START_DICTATION), node(Phase.READY).actions)
        assertEquals(listOf(Action.STOP_DICTATION), node(Phase.LISTENING).actions)
    }

    @Test
    fun `after a result or a failure a double tap starts the next run`() {
        assertEquals(Action.START_DICTATION, node(Phase.DONE).click)
        assertEquals(Action.START_DICTATION, node(Phase.FAILED).click)
    }

    @Test
    fun `a dictation being written has nothing to double tap for`() {
        val working = node(Phase.WORKING)
        assertNull(working.click)
        assertEquals(emptyList(), working.actions)
    }

    @Test
    fun `a command being carried out can be cancelled, as by a tap`() {
        assertEquals(Action.CANCEL_COMMAND, node(Phase.WORKING, command = true, commandsOn = true).click)
    }

    @Test
    fun `with commands on and a text field the voice command is an extra action`() {
        assertEquals(listOf(Action.START_DICTATION, Action.VOICE_COMMAND), node(Phase.READY, commandsOn = true).actions)
        // The double tap's second half: the recording turns into a command.
        assertEquals(listOf(Action.STOP_DICTATION, Action.VOICE_COMMAND), node(Phase.LISTENING, commandsOn = true).actions)
    }

    @Test
    fun `without a text field the tap itself starts a command, so no second entry for it`() {
        val ready = node(Phase.READY, commandsOn = true, textFieldFocused = false)
        assertEquals(Action.START_COMMAND, ready.click)
        assertEquals(listOf(Action.START_COMMAND), ready.actions)
        val listening = node(Phase.LISTENING, command = true, commandsOn = true, textFieldFocused = false)
        assertEquals(Action.STOP_LISTENING, listening.click)
        assertEquals(listOf(Action.STOP_LISTENING), listening.actions)
    }

    @Test
    fun `commands off never offers a command`() {
        assertEquals(Action.START_DICTATION, node(Phase.READY, textFieldFocused = false).click)
        assertFalse(Action.VOICE_COMMAND in node(Phase.READY, textFieldFocused = false).actions)
        assertFalse(Action.VOICE_COMMAND in node(Phase.LISTENING).actions)
    }

    @Test
    fun `hide in this app only at rest, as the drag to the hide target`() {
        assertEquals(listOf(Action.START_DICTATION, Action.HIDE_HERE), node(Phase.READY, canHide = true).actions)
        for (phase in listOf(Phase.LISTENING, Phase.WORKING, Phase.DONE, Phase.FAILED)) {
            assertFalse(Action.HIDE_HERE in node(phase, canHide = true).actions, "$phase")
        }
        assertFalse(Action.HIDE_HERE in node(Phase.READY, canHide = false).actions)
    }

    @Test
    fun `a failure carries its short reason, other states none`() {
        assertEquals("Timed out", node(Phase.FAILED, failure = "Timed out - the dictation was cancelled.").reason)
        assertNull(node(Phase.FAILED).reason)
        assertNull(node(Phase.READY, failure = "Timed out").reason)
    }

    @Test
    fun `start, result and failure are said aloud`() {
        assertTrue(BubbleAccess.announces(Phase.READY, Phase.LISTENING))
        assertTrue(BubbleAccess.announces(Phase.WORKING, Phase.DONE))
        assertTrue(BubbleAccess.announces(Phase.LISTENING, Phase.FAILED))
        assertTrue(BubbleAccess.announces(Phase.READY, Phase.FAILED))
        // A new run right from a result.
        assertTrue(BubbleAccess.announces(Phase.DONE, Phase.LISTENING))
    }

    @Test
    fun `the way through and back to rest is quiet`() {
        assertFalse(BubbleAccess.announces(Phase.LISTENING, Phase.WORKING))
        assertFalse(BubbleAccess.announces(Phase.DONE, Phase.READY))
        assertFalse(BubbleAccess.announces(Phase.FAILED, Phase.READY))
        assertFalse(BubbleAccess.announces(Phase.LISTENING, Phase.READY))
        // Amplitude and progress updates keep the phase: nothing to say.
        assertFalse(BubbleAccess.announces(Phase.LISTENING, Phase.LISTENING))
        assertFalse(BubbleAccess.announces(Phase.WORKING, Phase.WORKING))
    }

    @Test
    fun `the short reason is the first plain part of the message`() {
        assertEquals("Microphone permission is missing", BubbleAccess.shortReason("Microphone permission is missing. Please grant it in Amber."))
        assertEquals("Unexpected error", BubbleAccess.shortReason("Unexpected error: java.io.IOException"))
        assertEquals("Voice commands are off", BubbleAccess.shortReason("Voice commands are off."))
        assertEquals("Which one?", BubbleAccess.shortReason("Which one? Signal, Slack - say the full name."))
        assertEquals("Not enough storage", BubbleAccess.shortReason("Not enough storage: 500 MB needed, 20 MB free."))
        assertEquals("Version 1.5 failed", BubbleAccess.shortReason("Version 1.5 failed"))
    }

    @Test
    fun `quoted words never reach the reason`() {
        // The two messages that quote the dictated text; their toasts use a brief without it.
        val notInserted = BubbleAccess.shortReason("No text field has focus any more - not inserted: “my secret words”")
        assertEquals("No text field has focus any more", notInserted)
        val notTaken = BubbleAccess.shortReason("No text field accepted the text: “my secret words” [commit=false]")
        assertEquals("No text field accepted the text", notTaken)
        assertEquals("No app called", BubbleAccess.shortReason("No app called “secret”."))
        assertEquals("Heard", BubbleAccess.shortReason("Heard \"secret\" twice"))
        assertEquals("Heard", BubbleAccess.shortReason("Heard „secret“ twice"))
        assertNull(BubbleAccess.shortReason("“secret” was not understood"))
    }

    @Test
    fun `an apostrophe is no quote`() {
        assertEquals("Amber's accessibility service is off", BubbleAccess.shortReason("Amber's accessibility service is off."))
    }

    @Test
    fun `a long reason is cut at a word`() {
        val long = "word ".repeat(40).trim()
        val reason = BubbleAccess.shortReason(long)!!
        assertTrue(reason.length <= BubbleAccess.MAX_REASON, reason)
        assertTrue(reason.endsWith("word…"), reason)
    }

    @Test
    fun `nothing left is no reason`() {
        assertNull(BubbleAccess.shortReason(""))
        assertNull(BubbleAccess.shortReason("   "))
        assertNull(BubbleAccess.shortReason(" - something"))
    }
}
