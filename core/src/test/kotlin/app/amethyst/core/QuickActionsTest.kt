package app.amethyst.core

import app.amethyst.core.bubble.CleanupHere
import app.amethyst.core.bubble.QuickAction
import app.amethyst.core.bubble.QuickActions
import app.amethyst.core.bubble.QuickMenuContext
import app.amethyst.core.bubble.QuickMenuGeometry
import app.amethyst.core.bubble.QuickMenuItem
import app.amethyst.core.bubble.QuickMenuLayout
import app.amethyst.core.bubble.QuickOff
import app.amethyst.core.bubble.QuickSlot
import app.amethyst.core.bubble.QuickPhase
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppCleanup
import app.amethyst.core.settings.AppCleanupMode
import app.amethyst.core.settings.AppProfile
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.EngineConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickActionsTest {

    private val idle = QuickMenuContext(
        phase = QuickPhase.IDLE,
        hasLastDictation = true,
        fieldFocused = true,
        otherAppInFront = true,
        cleanupHere = CleanupHere.ON,
        cleanupPlanned = true,
        canUndo = true,
        command = false,
        canPause = true,
        paused = false,
    )

    private fun actions(context: QuickMenuContext) = QuickActions.slots(context).map { it?.action }
    private fun enabled(context: QuickMenuContext, action: QuickAction) =
        QuickActions.slots(context).first { it?.action == action }!!.enabled

    @Test
    fun `every state has four slots`() {
        for (phase in QuickPhase.entries) {
            assertEquals(QuickMenuGeometry.SLOTS, QuickActions.slots(idle.copy(phase = phase)).size, phase.name)
        }
    }

    @Test
    fun `idle offers the last result again, undoing it, the cleanup here and copying`() {
        assertEquals(
            listOf(QuickAction.INSERT_AGAIN, QuickAction.UNDO_LAST, QuickAction.CLEANUP_OFF_HERE, QuickAction.COPY_LAST),
            actions(idle),
        )
        assertTrue(QuickActions.slots(idle).all { it!!.enabled })
    }

    @Test
    fun `without a last dictation there is nothing to insert or copy`() {
        val none = idle.copy(hasLastDictation = false)
        assertFalse(enabled(none, QuickAction.INSERT_AGAIN))
        assertFalse(enabled(none, QuickAction.COPY_LAST))
    }

    @Test
    fun `without a field the last dictation can still be copied, not inserted`() {
        val noField = idle.copy(fieldFocused = false)
        assertFalse(enabled(noField, QuickAction.INSERT_AGAIN))
        assertTrue(enabled(noField, QuickAction.COPY_LAST))
    }

    @Test
    fun `in Amber itself there is no cleanup to switch for an app`() {
        val amber = idle.copy(otherAppInFront = false)
        assertFalse(enabled(amber, QuickAction.CLEANUP_OFF_HERE))
    }

    @Test
    fun `undo needs an insertion that can be undone and a field with the cursor`() {
        assertFalse(enabled(idle.copy(canUndo = false), QuickAction.UNDO_LAST))
        assertFalse(enabled(idle.copy(fieldFocused = false), QuickAction.UNDO_LAST))
        assertTrue(enabled(idle, QuickAction.UNDO_LAST))
    }

    @Test
    fun `the cleanup slot says what it would do`() {
        assertEquals(QuickAction.CLEANUP_ON_HERE, actions(idle.copy(cleanupHere = CleanupHere.OFF))[2])
        assertTrue(enabled(idle.copy(cleanupHere = CleanupHere.OFF), QuickAction.CLEANUP_ON_HERE))
        val noCleanup = idle.copy(cleanupHere = CleanupHere.NOT_SET_UP)
        assertEquals(QuickAction.CLEANUP_OFF_HERE, actions(noCleanup)[2])
        assertFalse(enabled(noCleanup, QuickAction.CLEANUP_OFF_HERE), "no cleanup to switch off")
    }

    @Test
    fun `listening offers cancel, no cleanup, start over and pause`() {
        val listening = idle.copy(phase = QuickPhase.RECORDING)
        assertEquals(listOf(QuickAction.CANCEL, QuickAction.SEND_WITHOUT_CLEANUP, QuickAction.START_OVER, QuickAction.PAUSE), actions(listening))
        assertTrue(enabled(listening, QuickAction.SEND_WITHOUT_CLEANUP))
        assertFalse(enabled(listening.copy(cleanupPlanned = false), QuickAction.SEND_WITHOUT_CLEANUP), "no cleanup to skip")
        assertFalse(enabled(listening.copy(command = true), QuickAction.SEND_WITHOUT_CLEANUP), "a command is never cleaned up")
    }

    @Test
    fun `working offers cancel and keeping the text as recognized`() {
        val working = idle.copy(phase = QuickPhase.WORKING)
        assertEquals(listOf(QuickAction.CANCEL, QuickAction.KEEP_AS_RECOGNIZED, null, null), actions(working))
        assertTrue(enabled(working, QuickAction.CANCEL))
        assertFalse(enabled(working.copy(cleanupPlanned = false), QuickAction.KEEP_AS_RECOGNIZED))
    }

    @Test
    fun `cancel keeps its slot from listening to working`() {
        val recording = actions(idle.copy(phase = QuickPhase.RECORDING)).indexOf(QuickAction.CANCEL)
        val working = actions(idle.copy(phase = QuickPhase.WORKING)).indexOf(QuickAction.CANCEL)
        assertEquals(recording, working)
    }

    @Test
    fun `paused, the same slot offers to go on`() {
        val paused = idle.copy(phase = QuickPhase.RECORDING, paused = true)
        assertEquals(listOf(QuickAction.CANCEL, QuickAction.SEND_WITHOUT_CLEANUP, QuickAction.START_OVER, QuickAction.RESUME), actions(paused))
        assertTrue(enabled(paused, QuickAction.RESUME))
    }

    @Test
    fun `where the recording cannot pause the slot is there but off`() {
        // Android's own recognizer holds the microphone itself; a voice command is a few words.
        val listening = idle.copy(phase = QuickPhase.RECORDING, canPause = false)
        assertFalse(enabled(listening, QuickAction.PAUSE))
    }

    @Test
    fun `an action taken from a menu of an earlier state is checked against the state now`() {
        assertTrue(QuickActions.stillValid(QuickAction.CANCEL, QuickPhase.WORKING), "cancel works while working too")
        assertTrue(QuickActions.stillValid(QuickAction.SEND_WITHOUT_CLEANUP, QuickPhase.WORKING), "the cleanup is still skipped")
        assertFalse(QuickActions.stillValid(QuickAction.START_OVER, QuickPhase.WORKING))
        assertFalse(QuickActions.stillValid(QuickAction.CANCEL, QuickPhase.IDLE), "the run ended meanwhile")
        assertFalse(QuickActions.stillValid(QuickAction.KEEP_AS_RECOGNIZED, QuickPhase.IDLE))
        assertFalse(QuickActions.stillValid(QuickAction.INSERT_AGAIN, QuickPhase.RECORDING))
        assertTrue(QuickActions.stillValid(QuickAction.COPY_LAST, QuickPhase.IDLE))
        assertFalse(QuickActions.stillValid(QuickAction.PAUSE, QuickPhase.WORKING), "the recording ended meanwhile")
        assertFalse(QuickActions.stillValid(QuickAction.RESUME, QuickPhase.IDLE))
        assertTrue(QuickActions.stillValid(QuickAction.RESUME, QuickPhase.RECORDING))
    }

    @Test
    fun `an empty slot stays empty`() {
        assertNull(QuickActions.slots(idle.copy(phase = QuickPhase.WORKING))[2])
        assertNull(QuickActions.slots(idle.copy(phase = QuickPhase.WORKING))[3])
    }

    /** Every combination of what the menu is built from, in every phase. */
    private val everyContext: List<QuickMenuContext> = buildList {
        val flags = listOf(true, false)
        for (phase in QuickPhase.entries) for (last in flags) for (field in flags) for (other in flags) for (here in CleanupHere.entries)
            for (planned in flags) for (undo in flags) for (command in flags) for (pause in flags) for (paused in flags) {
                add(QuickMenuContext(phase, last, field, other, here, planned, undo, command, pause, paused))
            }
    }

    /** The idle menu as it was before it could be changed: written out, not built by the layout. */
    private fun todaysIdle(c: QuickMenuContext): List<QuickSlot?> = listOf(
        QuickSlot(QuickAction.INSERT_AGAIN, c.hasLastDictation && c.fieldFocused),
        QuickSlot(QuickAction.UNDO_LAST, c.canUndo && c.fieldFocused),
        QuickSlot(
            if (c.cleanupHere == CleanupHere.OFF) QuickAction.CLEANUP_ON_HERE else QuickAction.CLEANUP_OFF_HERE,
            c.otherAppInFront && c.cleanupHere != CleanupHere.NOT_SET_UP,
        ),
        QuickSlot(QuickAction.COPY_LAST, c.hasLastDictation),
    )

    @Test
    fun `the default layout is today's menu in every state`() {
        for (context in everyContext) {
            val byDefault = QuickActions.slots(context)
            assertEquals(byDefault, QuickActions.slots(context, QuickMenuLayout.idle(null)), context.toString())
            if (context.phase == QuickPhase.IDLE) assertEquals(todaysIdle(context), byDefault, context.toString())
        }
    }

    @Test
    fun `a layout of one's own sets the idle places, top to bottom`() {
        val own = QuickMenuLayout.idle(listOf("open_amber", "style_here", "next_style", "app_page"))
        assertEquals(
            listOf(QuickAction.OPEN_AMBER, QuickAction.STYLE_HERE, QuickAction.NEXT_STYLE, QuickAction.APP_PAGE),
            QuickActions.slots(idle, own).map { it?.action },
        )
    }

    @Test
    fun `listening and working stay fixed whatever the layout`() {
        val own = QuickMenuLayout.idle(listOf("open_amber", null, "hide_here", "copy_last"))
        for (context in everyContext.filter { it.phase != QuickPhase.IDLE }) {
            assertEquals(QuickActions.slots(context), QuickActions.slots(context, own), context.toString())
        }
    }

    @Test
    fun `an empty place stays empty, the others keep theirs`() {
        val own = QuickMenuLayout.idle(listOf("insert_again", null, null, "copy_last"))
        val slots = QuickActions.slots(idle, own)
        assertEquals(QuickMenuGeometry.SLOTS, slots.size)
        assertEquals(listOf(QuickAction.INSERT_AGAIN, null, null, QuickAction.COPY_LAST), slots.map { it?.action })
    }

    @Test
    fun `open Amber can always run`() {
        val own = QuickMenuLayout.idle(listOf("open_amber", null, null, null))
        for (context in everyContext.filter { it.phase == QuickPhase.IDLE }) {
            assertTrue(QuickActions.slots(context, own)[0]!!.enabled, context.toString())
        }
    }

    @Test
    fun `the style actions, the app page and hiding need another app in front`() {
        val own = QuickMenuLayout.idle(listOf("style_here", "next_style", "app_page", "hide_here"))
        assertTrue(QuickActions.slots(idle, own).all { it!!.enabled })
        assertTrue(QuickActions.slots(idle.copy(otherAppInFront = false), own).none { it!!.enabled })
        // Without a cleanup the style waits for one, but changing it is never blocked.
        val bare = idle.copy(cleanupHere = CleanupHere.NOT_SET_UP, hasLastDictation = false, fieldFocused = false)
        assertTrue(QuickActions.slots(bare, own).all { it!!.enabled })
    }

    @Test
    fun `the cleanup entry shows on or off as the default one does`() {
        val own = QuickMenuLayout.idle(listOf(null, null, null, "cleanup_here"))
        assertEquals(QuickAction.CLEANUP_OFF_HERE, QuickActions.slots(idle, own)[3]!!.action)
        assertEquals(QuickAction.CLEANUP_ON_HERE, QuickActions.slots(idle.copy(cleanupHere = CleanupHere.OFF), own)[3]!!.action)
        assertFalse(QuickActions.slots(idle.copy(cleanupHere = CleanupHere.NOT_SET_UP), own)[3]!!.enabled)
    }

    @Test
    fun `the new actions are idle only`() {
        for (action in listOf(QuickAction.OPEN_AMBER, QuickAction.STYLE_HERE, QuickAction.NEXT_STYLE, QuickAction.APP_PAGE)) {
            assertTrue(QuickActions.stillValid(action, QuickPhase.IDLE), action.name)
            assertFalse(QuickActions.stillValid(action, QuickPhase.RECORDING), action.name)
            assertFalse(QuickActions.stillValid(action, QuickPhase.WORKING), action.name)
        }
    }

    @Test
    fun `every item of the editor lands in the menu as an idle action`() {
        for (item in QuickMenuItem.entries) {
            val slot = QuickActions.slots(idle, QuickMenuLayout.idle(listOf(item.id, null, null, null)))[0]
            assertTrue(slot!!.action.validIn(QuickPhase.IDLE), item.name)
        }
    }

    @Test
    fun `a kept recording puts Try again in the top place, the others stay where they are`() {
        val kept = idle.copy(keptRecording = true, hasLastDictation = false)
        assertEquals(
            listOf(QuickAction.TRY_AGAIN, QuickAction.UNDO_LAST, QuickAction.CLEANUP_OFF_HERE, QuickAction.COPY_LAST),
            actions(kept),
        )
        assertTrue(enabled(kept, QuickAction.TRY_AGAIN))
        // Even without a field: what is recognized is kept for Insert again and Copy if it cannot go in.
        assertTrue(enabled(kept.copy(fieldFocused = false), QuickAction.TRY_AGAIN))
    }

    @Test
    fun `in the user's own menu Try again takes the top place too, an empty one as well, and gives it back`() {
        val own = QuickMenuLayout.idle(listOf(null, "open_amber", "copy_last", "hide_here"))
        assertEquals(
            listOf(QuickAction.TRY_AGAIN, QuickAction.OPEN_AMBER, QuickAction.COPY_LAST, QuickAction.HIDE_HERE),
            QuickActions.slots(idle.copy(keptRecording = true), own).map { it?.action },
        )
        assertEquals(
            listOf(null, QuickAction.OPEN_AMBER, QuickAction.COPY_LAST, QuickAction.HIDE_HERE),
            QuickActions.slots(idle, own).map { it?.action },
        )
    }

    @Test
    fun `Try again is offered only while idle`() {
        for (phase in listOf(QuickPhase.RECORDING, QuickPhase.WORKING)) {
            assertFalse(QuickAction.TRY_AGAIN in actions(idle.copy(phase = phase, keptRecording = true)), phase.name)
            assertFalse(QuickActions.stillValid(QuickAction.TRY_AGAIN, phase))
        }
    }

    @Test
    fun `only a cleanup that runs gives the style its effect`() {
        assertTrue(CleanupHere.ON.runs)
        assertFalse(CleanupHere.OFF.runs)
        assertFalse(CleanupHere.NOT_SET_UP.runs)
    }

    private val tinfoil = EngineConfig.fromPreset(EnginePresets.TINFOIL)
    private val flash = CleanupChoice(tinfoil.id, "deepseek-v4-1-flash")
    private fun withCleanup(global: CleanupChoice?, vararg profiles: AppProfile) =
        AmethystSettings.defaults(TEST_PHONE).withEngine(tinfoil).copy(cleanup = global, profiles = profiles.toList())

    @Test
    fun `the cleanup here is on where one would run`() {
        assertEquals(CleanupHere.ON, QuickActions.cleanupHere(withCleanup(flash), "com.whatsapp") { true })
    }

    @Test
    fun `the cleanup here is off where the app switched it off, even with none for every app`() {
        val off = AppProfile("com.whatsapp", "WhatsApp", appCleanup = AppCleanup(AppCleanupMode.OFF))
        assertEquals(CleanupHere.OFF, QuickActions.cleanupHere(withCleanup(flash, off), "com.whatsapp") { true })
        assertEquals(CleanupHere.OFF, QuickActions.cleanupHere(withCleanup(null, off), "com.whatsapp") { true })
    }

    @Test
    fun `with no cleanup set up there is nothing to switch`() {
        assertEquals(CleanupHere.NOT_SET_UP, QuickActions.cleanupHere(withCleanup(null), "com.whatsapp") { true })
        assertEquals(CleanupHere.NOT_SET_UP, QuickActions.cleanupHere(withCleanup(flash), "com.whatsapp") { false }, "its key is gone")
        assertEquals(CleanupHere.NOT_SET_UP, QuickActions.cleanupHere(withCleanup(flash), null) { true })
    }

    @Test
    fun `a grey button has a reason, an enabled one none - in every state`() {
        // Every idle item in some layout: nine items, four places each.
        val layouts = QuickMenuItem.entries.map { it.id }.chunked(QuickMenuGeometry.SLOTS).map { QuickMenuLayout.idle(it) }
        for (context in everyContext) {
            for (slot in layouts.flatMap { QuickActions.slots(context, it) }) {
                if (slot == null) continue
                assertEquals(slot.enabled, QuickActions.whyOff(slot.action, context) == null, "${slot.action} in $context")
            }
        }
    }

    @Test
    fun `the app's own buttons say they work only in another app`() {
        val inAmber = idle.copy(otherAppInFront = false)
        for (action in listOf(QuickAction.HIDE_HERE, QuickAction.STYLE_HERE, QuickAction.NEXT_STYLE, QuickAction.APP_PAGE, QuickAction.CLEANUP_OFF_HERE)) {
            assertEquals(QuickOff.OTHER_APP_ONLY, QuickActions.whyOff(action, inAmber), action.name)
        }
    }

    @Test
    fun `without a dictation that comes first, before the missing field`() {
        val nothing = idle.copy(hasLastDictation = false, canUndo = false, fieldFocused = false)
        assertEquals(QuickOff.NO_DICTATION, QuickActions.whyOff(QuickAction.INSERT_AGAIN, nothing))
        assertEquals(QuickOff.NO_DICTATION, QuickActions.whyOff(QuickAction.UNDO_LAST, nothing))
        assertEquals(QuickOff.NO_DICTATION, QuickActions.whyOff(QuickAction.COPY_LAST, nothing))
        assertEquals(QuickOff.NO_FIELD, QuickActions.whyOff(QuickAction.INSERT_AGAIN, idle.copy(fieldFocused = false)))
        assertEquals(QuickOff.NO_FIELD, QuickActions.whyOff(QuickAction.UNDO_LAST, idle.copy(fieldFocused = false)))
    }

    @Test
    fun `undo in another app than the last insertion has nothing to undo`() {
        assertEquals(QuickOff.NOTHING_TO_UNDO, QuickActions.whyOff(QuickAction.UNDO_LAST, idle.copy(canUndo = false)))
    }

    @Test
    fun `no cleanup set up greys the cleanup switch and the buttons that skip it`() {
        assertEquals(QuickOff.NO_CLEANUP, QuickActions.whyOff(QuickAction.CLEANUP_OFF_HERE, idle.copy(cleanupHere = CleanupHere.NOT_SET_UP)))
        val recording = idle.copy(phase = QuickPhase.RECORDING, cleanupPlanned = false)
        assertEquals(QuickOff.NO_CLEANUP, QuickActions.whyOff(QuickAction.SEND_WITHOUT_CLEANUP, recording))
        assertEquals(QuickOff.NO_CLEANUP, QuickActions.whyOff(QuickAction.KEEP_AS_RECOGNIZED, recording.copy(phase = QuickPhase.WORKING)))
        assertEquals(QuickOff.NOT_FOR_COMMAND, QuickActions.whyOff(QuickAction.SEND_WITHOUT_CLEANUP, recording.copy(command = true)))
    }

    @Test
    fun `a cleanup switched off in this app is said so, not that none is set up`() {
        val recording = idle.copy(phase = QuickPhase.RECORDING, cleanupPlanned = false, cleanupHere = CleanupHere.OFF)
        assertEquals(QuickOff.CLEANUP_OFF_HERE, QuickActions.whyOff(QuickAction.SEND_WITHOUT_CLEANUP, recording))
        assertEquals(QuickOff.CLEANUP_OFF_HERE, QuickActions.whyOff(QuickAction.KEEP_AS_RECOGNIZED, recording.copy(phase = QuickPhase.WORKING)))
    }

    @Test
    fun `a recording that cannot pause says so`() {
        assertEquals(QuickOff.CANNOT_PAUSE, QuickActions.whyOff(QuickAction.PAUSE, idle.copy(phase = QuickPhase.RECORDING, canPause = false)))
        assertNull(QuickActions.whyOff(QuickAction.PAUSE, idle.copy(phase = QuickPhase.RECORDING)))
    }
}
