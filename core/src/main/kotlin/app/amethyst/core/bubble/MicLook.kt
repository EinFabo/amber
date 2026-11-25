package app.amethyst.core.bubble

/**
 * How the bubble looks while a dictation starts: asked for the microphone is not yet listening.
 * Android's speech recognition takes a moment to open, and words said before it does are lost -
 * the bubble shows the flames only once the microphone is really open.
 */
enum class MicLook {
    OPENING,
    LISTENING,
    ;

    companion object {
        fun of(open: Boolean): MicLook = if (open) LISTENING else OPENING
    }
}
