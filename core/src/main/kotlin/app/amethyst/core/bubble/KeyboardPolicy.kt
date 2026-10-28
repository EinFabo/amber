package app.amethyst.core.bubble

/**
 * Decides where the keyboard's top edge is, from what Android reports about its window.
 *
 * Android's report is unreliable in ways that made the bubble jump around:
 *
 * - **Read mid-animation.** The keyboard window is reported while it is still sliding in or
 *   out, translated partly below the screen (top 2362 on a 2400 px screen). Taken at face
 *   value the bubble moved too little, or dropped back down after it had already moved up.
 *   A window that reaches below the screen is therefore treated as *moving*, never as the
 *   keyboard's position, and is read again a moment later.
 * - **Frame, not keys.** Some keyboards lay their window over most of the screen and draw
 *   only the lower part. Such a frame says "a keyboard is up", not where it starts; the
 *   caller passes the drawn area instead when it can find it ([Reading.Unmeasurable] if not).
 * - **Late or missing.** The window appears some time after the field takes focus, and a few
 *   phones never report it. A focused field therefore counts as "keyboard coming" and the
 *   bubble goes where the keyboard was last time, at the same moment the keyboard starts
 *   moving.
 *
 * Pure and clock-driven, so every one of these cases is a unit test rather than a guess.
 */
class KeyboardPolicy(
    /** Typical top edge of a docked keyboard as a fraction of the screen, before one is seen. */
    private val defaultTopFraction: Float = 0.58f,
    /**
     * Where the keyboard settled last time, kept across restarts. Without it the first field
     * after an update or a reboot moved the bubble to an estimate and then a few pixels
     * again to the real edge - up, and back down.
     */
    remembered: Settled? = null,
) {

    /** A keyboard's top edge, measured on a screen of [screenHeight]. */
    data class Settled(val top: Int, val screenHeight: Int)

    sealed interface Reading {
        /** No keyboard window on screen. */
        data object Absent : Reading

        /** The keyboard's window, or the part of it that is drawn, in screen pixels. */
        data class Window(val top: Int, val bottom: Int) : Reading

        /** A keyboard window is there but where its keys start could not be found. */
        data object Unmeasurable : Reading
    }

    /**
     * [top] is where the bubble has to stay above, or null for "no keyboard". [recheckInMs]
     * asks for another reading after that delay while something is still settling.
     */
    data class Decision(val top: Int?, val recheckInMs: Long?, val why: String)

    private var focused = false
    private var focusedAt = 0L

    /** A settled keyboard was seen since the current field took focus. */
    private var seenThisFocus = false

    /** This phone reports keyboard windows at all. Until then, missing proves nothing. */
    private var phoneReportsKeyboard = false

    private var lastSettledTop: Int? = remembered?.top

    /** Screen height [lastSettledTop] was measured at; after a rotation it does not apply. */
    private var settledAtHeight = remembered?.screenHeight ?: 0
    private var current: Int? = null

    /** What to keep for the next start; null until a keyboard has settled once. */
    val settled: Settled? get() = lastSettledTop?.let { Settled(it, settledAtHeight) }

    /** The keyboard is up and has come to rest - so the next move below the edge is a close. */
    private var resting = false

    /** It started sliding away; the next missing report is a close, not a gap. */
    private var leaving = false

    /** When the keyboard was first seen moving, so a window parked off screen is not polled forever. */
    private var movingSince: Long? = null

    /** When a keyboard that was up stopped being reported; a short gap is not a close. */
    private var missingSince: Long? = null

    fun onFocusChanged(isFocused: Boolean, now: Long, screenHeight: Int): Decision {
        if (isFocused != focused) {
            focused = isFocused
            focusedAt = now
            seenThisFocus = false
            leaving = false
        }
        // Moving at once is the point: the bubble and the keyboard should travel together.
        return if (focused) {
            decide(predicted(screenHeight), RECHECK_MS, "field focused, keyboard expected")
        } else {
            decide(current, RECHECK_MS, "focus gone, waiting for keyboard")
        }
    }

    fun onReading(reading: Reading, now: Long, screenHeight: Int): Decision {
        // Bounds that make no sense are treated like a keyboard in motion: looked at again,
        // but only for as long as an animation can take.
        val moving = reading is Reading.Window &&
            (reading.bottom > screenHeight + TOLERANCE_PX || reading.bottom <= reading.top || reading.top < 0)
        if (!moving) movingSince = null
        if (reading != Reading.Absent) missingSince = null
        return read(reading, now, screenHeight)
    }

    private fun read(reading: Reading, now: Long, screenHeight: Int): Decision = when (reading) {
        is Reading.Window -> {
            phoneReportsKeyboard = true
            val sane = reading.bottom > reading.top && reading.top >= 0
            when {
                !sane -> {
                    val since = movingSince ?: now.also { movingSince = it }
                    decide(current, if (now - since < MAX_MOVING_MS) RECHECK_MS else null, "implausible keyboard bounds")
                }

                reading.bottom > screenHeight + TOLERANCE_PX -> {
                    val since = movingSince ?: now.also { movingSince = it }
                    val recheck = if (now - since < MAX_MOVING_MS) RECHECK_MS else null
                    val top = current
                    if (resting && top != null && reading.top > top) {
                        // A keyboard that was at rest and now reaches below the edge is going
                        // away. Android reports that once, then nothing until it is gone - so
                        // the bubble goes down now, together with it, not half a second later.
                        resting = false
                        leaving = true
                        decide(null, recheck, "keyboard sliding away (bottom ${reading.bottom})")
                    } else {
                        // Sliding in. Hold position and look again shortly - but only for as
                        // long as an animation can take.
                        decide(top ?: if (focused && !leaving) predicted(screenHeight) else null, recheck, "keyboard moving (bottom ${reading.bottom})")
                    }
                }

                reading.bottom - reading.top > screenHeight * TALL_FRAME -> {
                    seenThisFocus = true
                    decide(predicted(screenHeight), null, "keyboard frame nearly screen-high")
                }

                else -> {
                    seenThisFocus = true
                    resting = true
                    leaving = false
                    lastSettledTop = reading.top
                    settledAtHeight = screenHeight
                    decide(reading.top, null, "keyboard settled at ${reading.top}")
                }
            }
        }

        Reading.Unmeasurable -> {
            phoneReportsKeyboard = true
            seenThisFocus = true
            decide(predicted(screenHeight), null, "keyboard up, height unknown")
        }

        Reading.Absent -> when {
            leaving -> {
                leaving = false
                resting = false
                decide(null, null, "keyboard closed")
            }
            !focused -> decide(null, null, "no keyboard").also { resting = false }
            // It was up for this field and is not reported now. Android drops the keyboard
            // window from the list for a moment now and then (seen on a device: up at +66 ms,
            // missing at +156 ms, field still focused) - only a gap that lasts is a close.
            seenThisFocus -> {
                val since = missingSince ?: now.also { missingSince = it }
                if (now - since < CLOSE_CONFIRM_MS) {
                    decide(current, RECHECK_MS, "keyboard briefly unreported")
                } else {
                    resting = false
                    decide(null, null, "keyboard closed")
                }
            }
            now - focusedAt < ARRIVAL_WINDOW_MS -> decide(predicted(screenHeight), RECHECK_MS, "keyboard still on its way")
            // This phone never shows its keyboard window; trust the focus alone.
            !phoneReportsKeyboard -> decide(predicted(screenHeight), null, "keyboard window never reported, estimated")
            // Long enough that a keyboard would have arrived: there is none for this field.
            else -> decide(null, null, "field without keyboard")
        }
    }

    private fun predicted(screenHeight: Int): Int =
        lastSettledTop?.takeIf { settledAtHeight == screenHeight }
            ?: (screenHeight * defaultTopFraction).toInt()

    private fun decide(top: Int?, recheckInMs: Long?, why: String): Decision {
        current = top
        return Decision(top, recheckInMs, why)
    }

    companion object {
        /** How soon to look again while the keyboard is still moving. */
        const val RECHECK_MS = 60L

        /** Longer than any keyboard animation; past this a window off screen is parked there. */
        const val MAX_MOVING_MS = 2_000L

        /** How long a keyboard that was up must stay unreported to count as closed. */
        const val CLOSE_CONFIRM_MS = 250L

        /** A keyboard appears within this after focus, or not at all. */
        const val ARRIVAL_WINDOW_MS = 1_500L

        /** A keyboard window taller than this share of the screen is a frame, not keys. */
        const val TALL_FRAME = 0.8f

        /** Rounding in window bounds; a settled keyboard ends at the screen edge. */
        const val TOLERANCE_PX = 4
    }
}
