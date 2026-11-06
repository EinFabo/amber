package app.amethyst.core.onboarding

import app.amethyst.core.bubble.BubbleEvent
import app.amethyst.core.bubble.BubbleSide

/** The introduction's three small tries with the real flame, in the order they are shown. */
enum class PracticeTask { TAP, HOLD, DRAG }

enum class TaskState { OPEN, DONE, SKIPPED }

/** Whether a dictation could run now, as far as the recognition goes. */
enum class Recognition {
    READY,

    /** The model is on its way (or waiting for Wi-Fi): a tap only says so and records nothing. */
    DOWNLOADING,

    /** No engine, or its model is not on the phone. */
    NONE,
    ;

    companion object {
        /** [needsModel]: the engine runs on a model Amber downloads; a server or Android's recognizer needs none. */
        fun of(engineChosen: Boolean, downloading: Boolean, needsModel: Boolean, modelReady: Boolean): Recognition = when {
            !engineChosen -> NONE
            downloading -> DOWNLOADING
            needsModel && !modelReady -> NONE
            else -> READY
        }
    }
}

/** What the practice step knows about the phone right now. */
data class PracticeReadiness(
    /** Amber's accessibility service is on: without it there is no flame. */
    val serviceOn: Boolean,
    /** The flame is switched on in Amber's settings. */
    val bubbleOn: Boolean,
    val microphone: Boolean,
    val recognition: Recognition,
    /**
     * There is a last dictation for the quick menu's "Copy last" - the same one it goes by
     * ([app.amethyst.core.dictation.DictationLog.lastDictation]).
     */
    val hasLastDictation: Boolean,
)

/** Why a task cannot happen right now - each gets a plain hint instead of a dead task. */
enum class PracticeBlock {
    /** No flame at all: the accessibility service is off. */
    NO_SERVICE,

    /** No flame at all: switched off in Settings → Bubble. */
    BUBBLE_OFF,
    NO_MICROPHONE,
    DOWNLOADING,
    NO_RECOGNITION,

    /** The quick menu in Amber's own screen has only "Insert again" and "Copy last": both need a dictation. */
    NEEDS_DICTATION,
}

/**
 * How far the practice is: which tasks Amber saw happen, which were skipped. A task ticks when
 * its event comes, in any order - a skipped one too, if it happens after all. The drag counts
 * once the flame docks on the other side than [startSide], the side it had when the step
 * opened; a step opened again starts over from where the flame is then.
 */
data class Practice(
    val startSide: BubbleSide,
    val done: Set<PracticeTask> = emptySet(),
    val skipped: Set<PracticeTask> = emptySet(),
) {

    fun state(task: PracticeTask): TaskState = when (task) {
        in done -> TaskState.DONE
        in skipped -> TaskState.SKIPPED
        else -> TaskState.OPEN
    }

    fun on(event: BubbleEvent): Practice = when (event) {
        BubbleEvent.DictationInserted -> tick(PracticeTask.TAP)
        is BubbleEvent.QuickActionRun -> tick(PracticeTask.HOLD)
        is BubbleEvent.Docked -> if (event.side != startSide) tick(PracticeTask.DRAG) else this
    }

    fun skip(task: PracticeTask): Practice = if (task in done) this else copy(skipped = skipped + task)

    /** The first task still open, the one the step points at; null once nothing is left. */
    val current: PracticeTask? get() = PracticeTask.entries.firstOrNull { state(it) == TaskState.OPEN }

    /** Nothing left to do: each task done or skipped. */
    val finished: Boolean get() = current == null

    /** Every task done - the success line. */
    val allDone: Boolean get() = done.size == PracticeTask.entries.size

    private fun tick(task: PracticeTask) = copy(done = done + task, skipped = skipped - task)

    /** For the saved-state bundle: side, done, skipped. */
    fun save(): List<String> = listOf(startSide.name, done.joinToString(",") { it.name }, skipped.joinToString(",") { it.name })

    companion object {

        fun start(side: BubbleSide) = Practice(side)

        /** Null when [saved] is not what [save] wrote; then the step starts over. */
        fun restore(saved: List<String>): Practice? {
            val side = saved.getOrNull(0)?.let { name -> BubbleSide.entries.firstOrNull { it.name == name } } ?: return null
            fun tasks(at: Int) = saved.getOrNull(at).orEmpty().split(",")
                .mapNotNull { name -> PracticeTask.entries.firstOrNull { it.name == name } }.toSet()
            return Practice(side, tasks(1), tasks(2) - tasks(1))
        }

        /** What keeps the flame off the screen, so no task can happen; null when it can show. */
        fun stepBlock(readiness: PracticeReadiness): PracticeBlock? = when {
            !readiness.serviceOn -> PracticeBlock.NO_SERVICE
            !readiness.bubbleOn -> PracticeBlock.BUBBLE_OFF
            else -> null
        }

        /**
         * What stands in the way of [task] now; null when it can be done, or already was. The
         * drag only needs the flame; the tap a microphone and a recognition; the hold a
         * dictation first, since in Amber's own screen nothing else in the quick menu works -
         * whether none could be made yet or the tap was skipped.
         */
        fun blocked(task: PracticeTask, readiness: PracticeReadiness, practice: Practice): PracticeBlock? {
            if (practice.state(task) == TaskState.DONE) return null
            stepBlock(readiness)?.let { return it }
            val dictation = when {
                !readiness.microphone -> PracticeBlock.NO_MICROPHONE
                readiness.recognition == Recognition.DOWNLOADING -> PracticeBlock.DOWNLOADING
                readiness.recognition == Recognition.NONE -> PracticeBlock.NO_RECOGNITION
                else -> null
            }
            return when (task) {
                PracticeTask.TAP -> dictation
                PracticeTask.HOLD -> PracticeBlock.NEEDS_DICTATION.takeIf { !readiness.hasLastDictation && practice.state(PracticeTask.TAP) != TaskState.DONE }
                PracticeTask.DRAG -> null
            }
        }
    }
}
