package app.amethyst.bubble

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * The bubble's haptics, played on the vibrator directly.
 *
 * Not `View.performHapticFeedback`: Android files that as touch feedback (usage TOUCH) and drops
 * it entirely while the system's "touch feedback" is off - seen on a Pixel 9a (Android 17) as
 * `ignored_for_settings | usage: TOUCH` for every buzz, with Amber's own haptics switch on.
 * A plain `vibrate(effect)` does not help from Android 13 on: a short predefined effect without
 * attributes is re-filed as TOUCH too (seen the same way). So from Android 13 every buzz carries
 * usage ACCESSIBILITY - Amber is an accessibility service - which has its own intensity and
 * stays on with touch feedback off. Below 13 a plain vibration is not filtered by that setting.
 * Amber's own haptics switch still decides first.
 */
internal class Haptics(context: Context) {

    enum class Kind {
        /** A light tick: a slot changes, back to the centre. */
        TICK,
        /** A click: the menu opens, a drag picks something up. */
        CLICK,
        /** Something was done: a dictation is in, a menu action ran. */
        CONFIRM,
        /** Refused or failed. */
        REJECT,
    }

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()?.takeIf { it.hasVibrator() }

    fun play(kind: Kind) {
        val target = vibrator ?: return
        runCatching {
            val attributes = ACCESSIBILITY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && attributes != null) {
                target.vibrate(effect(kind), attributes)
            } else {
                target.vibrate(effect(kind))
            }
        }
            .onFailure { Log.w(TAG, "could not vibrate", it) }
    }

    private fun effect(kind: Kind): VibrationEffect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(
                when (kind) {
                    Kind.TICK -> VibrationEffect.EFFECT_TICK
                    Kind.CLICK -> VibrationEffect.EFFECT_CLICK
                    Kind.CONFIRM -> VibrationEffect.EFFECT_HEAVY_CLICK
                    Kind.REJECT -> VibrationEffect.EFFECT_DOUBLE_CLICK
                },
            )
        } else {
            when (kind) {
                Kind.TICK -> VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE)
                Kind.CLICK -> VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
                Kind.CONFIRM -> VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
                Kind.REJECT -> VibrationEffect.createWaveform(longArrayOf(0, 20, 60, 20), -1)
            }
        }

    private companion object {
        const val TAG = "Amber"

        val ACCESSIBILITY: VibrationAttributes? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY)
            } else {
                null
            }
    }
}
