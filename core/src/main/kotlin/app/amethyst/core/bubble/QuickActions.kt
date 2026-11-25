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

    /**
     * Recognizes the recording of a dictation that failed once more, with the speech recognition
     * set now ([app.amethyst.core.dictation.KeptDictation]); never one of the places to choose.
     */
    TRY_AGAIN(setOf(QuickPhase.IDLE)),

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

/**
 * Why a grey button of the quick menu cannot run, said when the thumb lets go on it - a buzz
 * alone left people guessing ([QuickActions.whyOff]). The words are in the app's strings.
 */
enum class QuickOff {
    /** Hiding, the cleanup switch and the styles belong to an app, and sayEmber is in front. */
    OTHER_APP_ONLY,

    /** Nothing dictated since sayEmber started, so nothing to insert or copy. */
    NO_DICTATION,

    /** There is a dictation, but the last insertion is not in the app in front. */
    NOTHING_TO_UNDO,

    /** Inserting and undoing need a text field with the cursor in it. */
    NO_FIELD,

    /** No cleanup would run here, so there is nothing to switch off or skip. */
    NO_CLEANUP,

    /** The cleanup is switched off for the app in front, so there is none to skip. */
    CLEANUP_OFF_HERE,

    /** A voice command is never cleaned up. */
    NOT_FOR_COMMAND,

    /** Android's speech recognition holds the microphone, or the run is a command. */
    CANNOT_PAUSE,
}

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
    /** A failed dictation's recording is kept for "Try again" ([app.amethyst.core.dictation.KeptDictation]). */
    val keptRecording: Boolean = false,
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
     * the user's own order, or today's four); listening and working are fixed. While a failed
     * dictation's recording is kept, "Try again" takes the top place - whatever the layout puts
     * there, the user's own as today's - and gives it back once the recording is gone; the other
     * places stay where they are. Today's top place is "Insert again", which has nothing new to
     * insert after a recognition that failed.
     */
    fun slots(context: QuickMenuContext, idleLayout: List<QuickMenuItem?> = QuickMenuLayout.DEFAULT_IDLE): List<QuickSlot?> = when (context.phase) {
        QuickPhase.IDLE -> List(QuickMenuGeometry.SLOTS) { i ->
            if (i == TRY_AGAIN_PLACE && context.keptRecording) QuickSlot(QuickAction.TRY_AGAIN, true)
            else idleLayout.getOrNull(i)?.let { idleSlot(it, context) }
        }
        QuickPhase.RECORDING -> listOf(
            slot(QuickAction.CANCEL, context),
            slot(QuickAction.SEND_WITHOUT_CLEANUP, context),
            slot(QuickAction.START_OVER, context),
            slot(if (context.paused) QuickAction.RESUME else QuickAction.PAUSE, context),
        )
        QuickPhase.WORKING -> listOf(
            slot(QuickAction.CANCEL, context),
            slot(QuickAction.KEEP_AS_RECOGNIZED, context),
            null,
            null,
        )
    }

    /** What [item] offers while idle, and whether it can run in [context]. */
    private fun idleSlot(item: QuickMenuItem, context: QuickMenuContext): QuickSlot = slot(
        when (item) {
            QuickMenuItem.INSERT_AGAIN -> QuickAction.INSERT_AGAIN
            QuickMenuItem.UNDO_LAST -> QuickAction.UNDO_LAST
            QuickMenuItem.CLEANUP_HERE -> if (context.cleanupHere == CleanupHere.OFF) QuickAction.CLEANUP_ON_HERE else QuickAction.CLEANUP_OFF_HERE
            QuickMenuItem.COPY_LAST -> QuickAction.COPY_LAST
            QuickMenuItem.HIDE_HERE -> QuickAction.HIDE_HERE
            QuickMenuItem.OPEN_AMBER -> QuickAction.OPEN_AMBER
            QuickMenuItem.STYLE_HERE -> QuickAction.STYLE_HERE
            QuickMenuItem.NEXT_STYLE -> QuickAction.NEXT_STYLE
            QuickMenuItem.APP_PAGE -> QuickAction.APP_PAGE
        },
        context,
    )

    /** [action] greyed exactly when there is a reason it cannot run ([whyOff]). */
    private fun slot(action: QuickAction, context: QuickMenuContext) = QuickSlot(action, whyOff(action, context) == null)

    /**
     * Why [action] cannot run in [context], or null when it can - the one rule for greying a
     * button and for what the message says when the thumb lets go on a grey one. Where two
     * reasons hold, the one to fix first: without any dictation, a text field would not help.
     */
    fun whyOff(action: QuickAction, context: QuickMenuContext): QuickOff? = when (action) {
        QuickAction.INSERT_AGAIN -> when {
            !context.hasLastDictation -> QuickOff.NO_DICTATION
            !context.fieldFocused -> QuickOff.NO_FIELD
            else -> null
        }
        QuickAction.UNDO_LAST -> when {
            !context.hasLastDictation && !context.canUndo -> QuickOff.NO_DICTATION
            !context.canUndo -> QuickOff.NOTHING_TO_UNDO
            !context.fieldFocused -> QuickOff.NO_FIELD
            else -> null
        }
        QuickAction.COPY_LAST -> if (context.hasLastDictation) null else QuickOff.NO_DICTATION
        QuickAction.CLEANUP_OFF_HERE, QuickAction.CLEANUP_ON_HERE -> when {
            !context.otherAppInFront -> QuickOff.OTHER_APP_ONLY
            context.cleanupHere == CleanupHere.NOT_SET_UP -> QuickOff.NO_CLEANUP
            else -> null
        }
        // A style belongs to an app: in Amber itself there is none to change.
        QuickAction.HIDE_HERE, QuickAction.STYLE_HERE, QuickAction.NEXT_STYLE, QuickAction.APP_PAGE ->
            if (context.otherAppInFront) null else QuickOff.OTHER_APP_ONLY
        QuickAction.SEND_WITHOUT_CLEANUP, QuickAction.KEEP_AS_RECOGNIZED -> when {
            context.command -> QuickOff.NOT_FOR_COMMAND
            // Switched off here with the menu's own switch: "none set up" would be wrong.
            !context.cleanupPlanned && context.cleanupHere == CleanupHere.OFF -> QuickOff.CLEANUP_OFF_HERE
            !context.cleanupPlanned -> QuickOff.NO_CLEANUP
            else -> null
        }
        QuickAction.PAUSE -> if (context.canPause) null else QuickOff.CANNOT_PAUSE
        QuickAction.OPEN_AMBER, QuickAction.TRY_AGAIN, QuickAction.CANCEL, QuickAction.START_OVER, QuickAction.RESUME -> null
    }

    /**
     * The menu's list is taken when it opens and not rebuilt under the finger; on release the
     * action is checked against the state [now] - a run that ended meanwhile is not cancelled
     * into the next one.
     */
    fun stillValid(action: QuickAction, now: QuickPhase): Boolean = action.validIn(now)

    /** The idle place "Try again" takes while a recording is kept: the top one, first under the thumb. */
    const val TRY_AGAIN_PLACE = 0

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
