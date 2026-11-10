package app.amethyst.core.bubble

import app.amethyst.core.engine.CleanupRouting
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppCleanupMode
import app.amethyst.core.settings.AppProfiles

/**
 * Where a dictation is, as far as the quick menu cares. Done and Failed count as idle: they
 * show for a second or three, and their result is offered in idle anyway (the last dictation).
 */
enum class QuickPhase {
    IDLE,

    /** Starting or listening: the microphone is (about to be) open. */
    RECORDING,

    /** The words are being recognized, cleaned up or carried out. */
    WORKING,
}

/** The cleanup in the app in front, for the switch in the menu. */
enum class CleanupHere {
    /** A cleanup runs here: the menu offers to switch it off. */
    ON,

    /** Switched off for this app: the menu offers to switch it on again. */
    OFF,

    /** No cleanup would run here anyway: nothing to switch off. */
    NOT_SET_UP,
    ;

    /** A cleanup rewrites a dictation here - and with it, the app's style takes effect. */
    val runs: Boolean get() = this == ON
}

/**
 * What the quick menu can do. The name each button shows (verb first, short) is in the app's
 * strings (bubble/QuickMenuView.kt).
 */
enum class QuickAction(private val phases: Set<QuickPhase>) {
    INSERT_AGAIN(setOf(QuickPhase.IDLE)),

    /** Takes the last dictation out of the field again, while it still stands before the cursor. */
    UNDO_LAST(setOf(QuickPhase.IDLE)),
    COPY_LAST(setOf(QuickPhase.IDLE)),
    CLEANUP_OFF_HERE(setOf(QuickPhase.IDLE)),
    CLEANUP_ON_HERE(setOf(QuickPhase.IDLE)),
    HIDE_HERE(setOf(QuickPhase.IDLE)),

    /** Opens Amber's main screen. */
    OPEN_AMBER(setOf(QuickPhase.IDLE)),

    /** Opens the "Style here" window over the app in front: its style and own instruction. */
    STYLE_HERE(setOf(QuickPhase.IDLE)),

    /** The app's style one step on ([AppProfiles.nextStyle]). */
    NEXT_STYLE(setOf(QuickPhase.IDLE)),

    /** Opens the app's page in Amber: style, cleanup, speech recognition. */
    APP_PAGE(setOf(QuickPhase.IDLE)),

    /** Throws the run away, recording or not. */
    CANCEL(setOf(QuickPhase.RECORDING, QuickPhase.WORKING)),

    /** Stops the recording; the words go in as recognized, without the cleanup. */
    SEND_WITHOUT_CLEANUP(setOf(QuickPhase.RECORDING, QuickPhase.WORKING)),

    /** Throws the recording away and starts a new one. */
    START_OVER(setOf(QuickPhase.RECORDING)),

    /** The text goes in as recognized; a cleanup on its way is dropped. */
    KEEP_AS_RECOGNIZED(setOf(QuickPhase.WORKING)),

    /** Nothing heard is kept until [RESUME]; the microphone stays open. A tap still sends. */
    PAUSE(setOf(QuickPhase.RECORDING)),
    RESUME(setOf(QuickPhase.RECORDING)),
    ;

    fun validIn(phase: QuickPhase): Boolean = phase in phases
}

/** One slot of the menu; a disabled one is shown greyed and does nothing, never moved. */
data class QuickSlot(val action: QuickAction, val enabled: Boolean)

/** What the menu is built from, taken once when it opens. */
data class QuickMenuContext(
    val phase: QuickPhase,
    /** This process has a dictation that went in somewhere (any app); see DictationLog.lastDictation. */
    val hasLastDictation: Boolean,
    /** A field that takes dictation has focus - not a password field. */
    val fieldFocused: Boolean,
    /** An app other than Amber is in front: the bubble can be hidden there, its cleanup switched. */
    val otherAppInFront: Boolean,
    val cleanupHere: CleanupHere,
    /** The run in progress would be cleaned up (or the next one, while idle). */
    val cleanupPlanned: Boolean,
    /** The last insertion can be undone in the app in front ([app.amethyst.core.insert.UndoLast]). */
    val canUndo: Boolean = false,
    /** The run in progress is a voice command, which is never cleaned up. */
    val command: Boolean,
    /**
     * The recording in progress can pause: Amber's own recorder holds the microphone (not
     * Android's recognizer), and it is a dictation, not a command of a few words.
     */
    val canPause: Boolean = false,
    /** The recording in progress is paused. */
    val paused: Boolean = false,
)

/**
 * Which action sits in which slot of the quick menu ([QuickMenuGeometry]: slot 0 at the top,
 * slot 3 at the bottom, on both edges). Cancel keeps the top slot from listening to working;
 * a slot keeps its place whether its action can run or not, and an unused slot stays empty,
 * so a place never moves under the thumb.
 */
object QuickActions {

    /**
     * The four slots for [context]. While idle they follow [idleLayout] ([QuickMenuLayout.idle]:
     * the user's own order, or today's four); listening and working are fixed.
     */
    fun slots(context: QuickMenuContext, idleLayout: List<QuickMenuItem?> = QuickMenuLayout.DEFAULT_IDLE): List<QuickSlot?> = when (context.phase) {
        QuickPhase.IDLE -> List(QuickMenuGeometry.SLOTS) { i -> idleLayout.getOrNull(i)?.let { idleSlot(it, context) } }
        QuickPhase.RECORDING -> listOf(
            QuickSlot(QuickAction.CANCEL, true),
            QuickSlot(QuickAction.SEND_WITHOUT_CLEANUP, context.cleanupPlanned && !context.command),
            QuickSlot(QuickAction.START_OVER, true),
            if (context.paused) QuickSlot(QuickAction.RESUME, true) else QuickSlot(QuickAction.PAUSE, context.canPause),
        )
        QuickPhase.WORKING -> listOf(
            QuickSlot(QuickAction.CANCEL, true),
            QuickSlot(QuickAction.KEEP_AS_RECOGNIZED, context.cleanupPlanned && !context.command),
            null,
            null,
        )
    }

    /** What [item] offers while idle, and whether it can run in [context]. */
    private fun idleSlot(item: QuickMenuItem, context: QuickMenuContext): QuickSlot {
        val otherApp = context.otherAppInFront
        return when (item) {
            QuickMenuItem.INSERT_AGAIN -> QuickSlot(QuickAction.INSERT_AGAIN, context.hasLastDictation && context.fieldFocused)
            QuickMenuItem.UNDO_LAST -> QuickSlot(QuickAction.UNDO_LAST, context.canUndo && context.fieldFocused)
            QuickMenuItem.CLEANUP_HERE -> QuickSlot(
                if (context.cleanupHere == CleanupHere.OFF) QuickAction.CLEANUP_ON_HERE else QuickAction.CLEANUP_OFF_HERE,
                otherApp && context.cleanupHere != CleanupHere.NOT_SET_UP,
            )
            QuickMenuItem.COPY_LAST -> QuickSlot(QuickAction.COPY_LAST, context.hasLastDictation)
            QuickMenuItem.HIDE_HERE -> QuickSlot(QuickAction.HIDE_HERE, otherApp)
            QuickMenuItem.OPEN_AMBER -> QuickSlot(QuickAction.OPEN_AMBER, true)
            // A style belongs to an app: in Amber itself there is none to change.
            QuickMenuItem.STYLE_HERE -> QuickSlot(QuickAction.STYLE_HERE, otherApp)
            QuickMenuItem.NEXT_STYLE -> QuickSlot(QuickAction.NEXT_STYLE, otherApp)
            QuickMenuItem.APP_PAGE -> QuickSlot(QuickAction.APP_PAGE, otherApp)
        }
    }

    /**
     * The menu's list is taken when it opens and not rebuilt under the finger; on release the
     * action is checked against the state [now] - a run that ended meanwhile is not cancelled
     * into the next one.
     */
    fun stillValid(action: QuickAction, now: QuickPhase): Boolean = action.validIn(now)

    /**
     * The cleanup in [packageName] for the menu's switch: off where the app switched it off,
     * on where one would run ([CleanupRouting]), else there is nothing to switch.
     */
    fun cleanupHere(settings: AmethystSettings, packageName: String?, hasKey: (String?) -> Boolean): CleanupHere {
        if (packageName == null) return CleanupHere.NOT_SET_UP
        if (AppProfiles.forPackage(settings.profiles, packageName)?.appCleanup?.mode == AppCleanupMode.OFF) return CleanupHere.OFF
        val decision = CleanupRouting.decide(settings, packageName, fellBackFrom = null, hasKey = hasKey)
        return if (decision.choice != null) CleanupHere.ON else CleanupHere.NOT_SET_UP
    }
}
