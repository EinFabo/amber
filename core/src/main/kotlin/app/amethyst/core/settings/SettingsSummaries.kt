package app.amethyst.core.settings

import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.privacy.TrustLevel
import java.util.Locale

/**
 * The settings list's one-line states. Each says what a page is set to in a few words - never
 * why: a row is read at a glance, and its page explains.
 */
object SettingsSummaries {

    private const val ON = "On"
    private const val OFF = "Off"

    /** "Tinfoil · Enclave": the engine without its bracketed note, and where it sends the voice. */
    fun recognition(engine: EngineConfig?, trust: TrustLevel?): String =
        if (engine == null) "None selected"
        else listOfNotNull(shortName(engine.displayName), trust?.let(::place)).joinToString(" · ")

    /**
     * The cleanup model's name - "DeepSeek V4.1 Flash" - or off, also when its engine is gone.
     * [choice] is the cleanup for every app unless one app's own is given.
     */
    fun cleanup(settings: AmethystSettings, choice: CleanupChoice? = settings.cleanup): String {
        if (choice == null) return OFF
        val engine = settings.engines.firstOrNull { it.id == choice.engineId } ?: return OFF
        return ServerProviders.forUrl(engine.baseUrl).cleanupModel(choice.model)?.label ?: choice.model
    }

    fun onOff(on: Boolean): String = if (on) ON else OFF

    /** Voice commands are given through the bubble, so "on" without it says so. */
    fun commands(settings: AmethystSettings): String = when {
        !settings.commands.enabled -> OFF
        !settings.bubble.enabled -> "$ON · the bubble is off"
        else -> ON
    }

    fun appStyles(count: Int): String = if (count == 0) "None yet" else count(count, "app", "apps")

    /** Who takes over a failed server dictation; with [recognition] on the phone, no server can fail. */
    fun fallback(settings: AmethystSettings, recognition: TrustLevel?): String {
        if (recognition == TrustLevel.ON_DEVICE) return "Not needed"
        return when (settings.fallback.mode) {
            FallbackMode.AUTOMATIC -> "Parakeet, then Whisper"
            FallbackMode.OFF -> OFF
            FallbackMode.ENGINE -> settings.engines.firstOrNull { it.id == settings.fallback.engineId }
                ?.let { shortName(it.displayName) } ?: OFF
        }
    }

    /** "Stops after 0.9 s silence", or that only a tap ends it. */
    fun recording(capture: CaptureSettings): String =
        if (capture.autoStopOnSilence) "Stops after %.1f s silence".format(Locale.US, capture.silenceTimeoutMs / 1000.0)
        else "Runs until you tap"

    /** What is added to the text on its way in - "Spaces, capitals, punctuation" - or nothing. */
    fun inserting(insertion: InsertionSettings): String =
        listOfNotNull(
            "spaces".takeIf { insertion.autoSpace },
            "capitals".takeIf { insertion.autoCapitalize },
            "punctuation".takeIf { insertion.gluePunctuation },
            "clipboard".takeIf { insertion.allowClipboard },
        ).joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { "As recognized" }

    /** [open] of the things that keep the microphone available in the background. */
    fun reliability(open: Int): String = if (open == 0) "All set" else "${count(open, "thing", "things")} open"

    fun diagnostics(count: Int): String = if (count == 0) "Nothing dictated yet" else "Last ${count(count, "dictation", "dictations")}"

    /** The folded "Advanced" row: how much it holds, or what in it needs a look. */
    fun advanced(hidden: Int, reliabilityOpen: Int): String =
        if (reliabilityOpen > 0) "Reliability: ${reliability(reliabilityOpen)}" else "$hidden more settings"

    /** A folded engine on a model list: "3 models". */
    fun models(count: Int): String = count(count, "model", "models")

    /** A display name without its bracketed note: "Tinfoil (Enclave)" becomes "Tinfoil". */
    fun shortName(displayName: String): String = displayName.substringBefore(" (").ifBlank { displayName }

    /** Where the voice goes, in a word or two. */
    fun place(trust: TrustLevel): String = when (trust) {
        TrustLevel.ON_DEVICE -> "On device"
        TrustLevel.ENCLAVE_VERIFIED -> "Enclave"
        TrustLevel.CLOUD -> "Cloud"
    }

    private fun count(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
}
