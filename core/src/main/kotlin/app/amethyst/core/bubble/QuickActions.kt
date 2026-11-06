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
}

/** What the quick menu can do. [label]: the name the menu shows, verb first. */
enum class QuickAction(val label: String, private val phases: Set<QuickPhase>) {
    INSERT_AGAIN("Insert again", setOf(QuickPhase.IDLE)),
    COPY_LAST("Copy last", setOf(QuickPhase.IDLE)),
    CLEANUP_OFF_HERE("Cleanup off here", setOf(QuickPhase.IDLE)),
    CLEANUP_ON_HERE("Cleanup on here", setOf(QuickPhase.IDLE)),
    HIDE_HERE("Hide here", setOf(QuickPhase.IDLE)),

    /** Throws the run away, recording or not. */
    CANCEL("Cancel", setOf(QuickPhase.RECORDING, QuickPhase.WORKING)),

    /** Stops the recording; the words go in as recognized, without the cleanup. */
    SEND_AS_SPOKEN("Send as spoken", setOf(QuickPhase.RECORDING, QuickPhase.WORKING)),

    /** Throws the recording away and starts a new one. */
    START_OVER("Start over", setOf(QuickPhase.RECORDING)),

    /** The text goes in as recognized; a cleanup on its way is dropped. */
    KEEP_AS_HEARD("Keep as heard", setOf(QuickPhase.WORKING)),
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
    /** The run in progress is a voice command, which is never cleaned up. */
    val command: Boolean,
)

/**
 * Which action sits in which slot of the quick menu ([QuickMenuGeometry]: 0 up, 1 up-inward,
 * 2 down-inward, 3 down). The most used ones go to the two inner slots, the easiest thumb
 * moves; a slot keeps its place whether its action can run or not, and an unused slot stays
 * empty, so a place never moves under the thumb.
 */
object QuickActions {

    fun slots(context: QuickMenuContext): List<QuickSlot?> = when (context.phase) {
        QuickPhase.IDLE -> {
            val cleanup = if (context.cleanupHere == CleanupHere.OFF) QuickAction.CLEANUP_ON_HERE else QuickAction.CLEANUP_OFF_HERE
            listOf(
                QuickSlot(cleanup, context.otherAppInFront && context.cleanupHere != CleanupHere.NOT_SET_UP),
                QuickSlot(QuickAction.INSERT_AGAIN, context.hasLastDictation && context.fieldFocused),
                QuickSlot(QuickAction.COPY_LAST, context.hasLastDictation),
                QuickSlot(QuickAction.HIDE_HERE, context.otherAppInFront),
            )
        }
        QuickPhase.RECORDING -> listOf(
            QuickSlot(QuickAction.START_OVER, true),
            QuickSlot(QuickAction.CANCEL, true),
            QuickSlot(QuickAction.SEND_AS_SPOKEN, context.cleanupPlanned && !context.command),
            null,
        )
        QuickPhase.WORKING -> listOf(
            null,
            QuickSlot(QuickAction.CANCEL, true),
            QuickSlot(QuickAction.KEEP_AS_HEARD, context.cleanupPlanned && !context.command),
            null,
        )
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
