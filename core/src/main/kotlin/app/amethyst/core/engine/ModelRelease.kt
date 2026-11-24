package app.amethyst.core.engine

/**
 * When the speech model on the phone - up to 900 MB of Whisper, 670 MB of Parakeet - is given
 * back after a dictation.
 *
 * It stays three idle minutes, so the next dictation does not read it from flash again. Up to
 * Android 13 the system also warns of real memory pressure (onTrimMemory's RUNNING_CRITICAL),
 * and the model goes then. From Android 14 that warning no longer comes: a process holding a
 * model nobody uses would simply be killed - and with it the accessibility service. So there
 * Amber looks at memory itself after each dictation and lets go sooner when it is short. With
 * room to spare nothing changes: the next dictation finds the model loaded, as before.
 *
 * "Short" is what Android says (lowMemory), a low-RAM phone, or a phone of 6 GB or less with
 * little room above the threshold. availMem alone says little: cached processes count as used
 * and are ended on demand, so on an 8 GB Pixel it read 531-731 MB after every dictation, and
 * the room rule alone let the model go 30 s later each time - the next dictation then read it
 * again for 3.5-3.9 s (Pixel 9a, 2026-11-23).
 */
object ModelRelease {

    /** How long a loaded model may stay idle. */
    enum class Wait(val ms: Long) {
        /** Room to spare: the usual idle time. */
        IDLE(3 * 60 * 1000L),

        /** Tight: a dictation right after this one still finds the model, a pause does not. */
        SOON(30 * 1000L),

        /** Android says memory is low: now. */
        NOW(0L),
    }

    /** What to do on an onTrimMemory call. */
    enum class Trim {
        /** Free the models (once no dictation uses them). */
        RELEASE,

        /** Look at memory again, as after a dictation; only ever brings the release forward. */
        REVIEW,
        NONE,
    }

    /** ActivityManager's view of memory, as much of it as the decision needs. */
    data class Memory(
        val sdk: Int,
        /** ActivityManager.isLowRamDevice: an Android Go phone, 2 GB or less. */
        val lowRamDevice: Boolean,
        /** MemoryInfo.lowMemory: the system considers itself low on memory now. */
        val lowMemory: Boolean,
        val availBytes: Long,
        /** MemoryInfo.threshold: below this the system starts killing background processes. */
        val thresholdBytes: Long,
        /** MemoryInfo.totalMem: what the kernel can hand out, a little less than the phone's RAM; 0 unknown. */
        val totalBytes: Long,
    )

    /** Android 14: the release can no longer wait for RUNNING_CRITICAL. */
    const val ANDROID_14 = 34

    /**
     * Room above the threshold besides the model itself: a margin for whatever the user opens
     * next. Without at least the model's size and this, the process holding the model is among
     * the first the system would end.
     */
    const val MARGIN_BYTES = 256L * 1_000_000

    /**
     * Up to here a phone is small enough for the room rule: 6 GB phones report about 5.6-5.9 GB
     * (the kernel's share is not counted), 8 GB ones about 7.4 GB.
     */
    const val SMALL_PHONE_BYTES = 6_500L * 1_000_000

    // ComponentCallbacks2's levels; fixed in the platform API.
    const val TRIM_RUNNING_CRITICAL = 15
    const val TRIM_UI_HIDDEN = 20
    const val TRIM_BACKGROUND = 40
    const val TRIM_COMPLETE = 80

    /**
     * After a dictation: how long the [modelBytes] loaded now may stay. [memory] null when
     * Android did not say - then as always.
     */
    fun afterDictation(memory: Memory?, modelBytes: Long): Wait {
        if (memory == null || modelBytes <= 0 || memory.sdk < ANDROID_14) return Wait.IDLE
        if (memory.lowMemory || memory.availBytes <= memory.thresholdBytes) return Wait.NOW
        if (memory.lowRamDevice) return Wait.SOON
        // A phone that does not say what it has is taken for a small one.
        if (memory.totalBytes > SMALL_PHONE_BYTES) return Wait.IDLE
        val room = memory.availBytes - memory.thresholdBytes
        return if (room < modelBytes + MARGIN_BYTES) Wait.SOON else Wait.IDLE
    }

    /**
     * A model loading because a dictation is about to start: never freed at once - the
     * dictation needs it in a moment, and reading it twice costs more than holding it.
     */
    fun beforeDictation(memory: Memory?, modelBytes: Long): Wait =
        afterDictation(memory, modelBytes).let { if (it == Wait.NOW) Wait.SOON else it }

    /**
     * Real memory pressure frees the models on every Android. From Android 14, where that call
     * no longer comes, the screens going away (UI_HIDDEN, BACKGROUND) is a moment to look at
     * memory again; before, those only mean the screens went away - the bubble still dictates.
     */
    fun onTrim(level: Int, sdk: Int): Trim = when {
        level == TRIM_RUNNING_CRITICAL || level >= TRIM_COMPLETE -> Trim.RELEASE
        sdk >= ANDROID_14 && level >= TRIM_UI_HIDDEN -> Trim.REVIEW
        else -> Trim.NONE
    }
}
