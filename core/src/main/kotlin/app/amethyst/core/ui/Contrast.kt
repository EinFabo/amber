package app.amethyst.core.ui

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * How well one colour reads on another, as WCAG 2 measures it: from 1 (the same colour) to 21
 * (black on white). Colours are 0xAARRGGBB, as Android packs them.
 */
object Contrast {

    /** WCAG AA for body text. */
    const val AA_TEXT = 4.5

    /** WCAG AA for large text and for what marks a control: an icon, a border, a switch. */
    const val AA_LARGE = 3.0

    /** The ratio of two opaque colours; the order does not matter. */
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** [foreground] as it shows on [background]: its own alpha mixed in, the result opaque. */
    fun over(foreground: Int, background: Int): Int {
        val alpha = (foreground ushr 24 and 0xFF) / 255.0
        fun mix(shift: Int): Int {
            val f = foreground ushr shift and 0xFF
            val b = background ushr shift and 0xFF
            return Math.round(f * alpha + b * (1 - alpha)).toInt()
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** Relative luminance, 0 (black) to 1 (white); alpha is ignored. */
    fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = (argb ushr shift and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
