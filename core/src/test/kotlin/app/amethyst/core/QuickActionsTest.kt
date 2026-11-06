package app.amethyst.core

import app.amethyst.core.bubble.CleanupHere
import app.amethyst.core.bubble.QuickAction
import app.amethyst.core.bubble.QuickActions
import app.amethyst.core.bubble.QuickMenuContext
import app.amethyst.core.bubble.QuickMenuGeometry
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
        command = false,
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
    fun `idle offers the last result, the cleanup here and hiding`() {
        assertEquals(
            listOf(QuickAction.CLEANUP_OFF_HERE, QuickAction.INSERT_AGAIN, QuickAction.COPY_LAST, QuickAction.HIDE_HERE),
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
    fun `in Amber itself there is nothing to hide the bubble in or switch the cleanup for`() {
        val amber = idle.copy(otherAppInFront = false)
        assertFalse(enabled(amber, QuickAction.HIDE_HERE))
        assertFalse(enabled(amber, QuickAction.CLEANUP_OFF_HERE))
    }

    @Test
    fun `the cleanup slot says what it would do`() {
        assertEquals(QuickAction.CLEANUP_ON_HERE, actions(idle.copy(cleanupHere = CleanupHere.OFF))[0])
        assertTrue(enabled(idle.copy(cleanupHere = CleanupHere.OFF), QuickAction.CLEANUP_ON_HERE))
        val noCleanup = idle.copy(cleanupHere = CleanupHere.NOT_SET_UP)
        assertEquals(QuickAction.CLEANUP_OFF_HERE, actions(noCleanup)[0])
        assertFalse(enabled(noCleanup, QuickAction.CLEANUP_OFF_HERE), "no cleanup to switch off")
    }

    @Test
    fun `listening offers cancel, send as spoken and start over`() {
        val listening = idle.copy(phase = QuickPhase.RECORDING)
        assertEquals(listOf(QuickAction.START_OVER, QuickAction.CANCEL, QuickAction.SEND_AS_SPOKEN, null), actions(listening))
        assertTrue(enabled(listening, QuickAction.SEND_AS_SPOKEN))
        assertFalse(enabled(listening.copy(cleanupPlanned = false), QuickAction.SEND_AS_SPOKEN), "no cleanup to skip")
        assertFalse(enabled(listening.copy(command = true), QuickAction.SEND_AS_SPOKEN), "a command is never cleaned up")
    }

    @Test
    fun `working offers cancel and keeping the text as recognized`() {
        val working = idle.copy(phase = QuickPhase.WORKING)
        assertEquals(listOf(null, QuickAction.CANCEL, QuickAction.KEEP_AS_HEARD, null), actions(working))
        assertTrue(enabled(working, QuickAction.CANCEL))
        assertFalse(enabled(working.copy(cleanupPlanned = false), QuickAction.KEEP_AS_HEARD))
    }

    @Test
    fun `cancel keeps its slot from listening to working`() {
        val recording = actions(idle.copy(phase = QuickPhase.RECORDING)).indexOf(QuickAction.CANCEL)
        val working = actions(idle.copy(phase = QuickPhase.WORKING)).indexOf(QuickAction.CANCEL)
        assertEquals(recording, working)
    }

    @Test
    fun `an action taken from a menu of an earlier state is checked against the state now`() {
        assertTrue(QuickActions.stillValid(QuickAction.CANCEL, QuickPhase.WORKING), "cancel works while working too")
        assertTrue(QuickActions.stillValid(QuickAction.SEND_AS_SPOKEN, QuickPhase.WORKING), "the cleanup is still skipped")
        assertFalse(QuickActions.stillValid(QuickAction.START_OVER, QuickPhase.WORKING))
        assertFalse(QuickActions.stillValid(QuickAction.CANCEL, QuickPhase.IDLE), "the run ended meanwhile")
        assertFalse(QuickActions.stillValid(QuickAction.KEEP_AS_HEARD, QuickPhase.IDLE))
        assertFalse(QuickActions.stillValid(QuickAction.INSERT_AGAIN, QuickPhase.RECORDING))
        assertTrue(QuickActions.stillValid(QuickAction.COPY_LAST, QuickPhase.IDLE))
    }

    @Test
    fun `labels are short enough for a thumb's glance`() {
        for (action in QuickAction.entries) assertTrue(action.label.length <= 16, action.label)
    }

    @Test
    fun `an empty slot stays empty`() {
        assertNull(QuickActions.slots(idle.copy(phase = QuickPhase.WORKING))[0])
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
}
