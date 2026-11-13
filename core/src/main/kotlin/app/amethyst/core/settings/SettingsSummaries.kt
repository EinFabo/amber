package app.amethyst.core.settings

import app.amethyst.core.engine.ReadyFallback
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.privacy.TrustLevel

/**
 * A settings row's one-line state, as a value: the app words it in the phone's language
 * (ui/Texts.kt). Names - of a model, of a speech recognition - are shown as they are.
 */
sealed interface Summary {
    data object On : Summary
    data object Off : Summary
    data object NoneSelected : Summary

    /** A model's name, shown as it is. */
    data class Name(val name: String) : Summary

    /** "Tinfoil · Enclave": the speech recognition and where it sends the voice. */
    data class Recognition(val engine: EngineConfig, val place: TrustLevel?) : Summary

    /** Voice commands are given through the bubble, so "on" without it says so. */
    data object OnButBubbleOff : Summary

    /** Apps with their own style; none yet at 0. */
    data class Apps(val count: Int) : Summary

    /** A fallback for a recognition on the phone, which no server can fail. */
    data object NotNeeded : Summary

    /** Who steps in: [engine], or Parakeet then Whisper when null; [prepared] in advance. */
    data class Fallback(val engine: EngineConfig?, val prepared: Boolean) : Summary
    data class StopsAfter(val seconds: Double) : Summary
    data object RunsUntilTap : Summary

    /** What is added to the text on its way in; nothing ("as recognized") when empty. */
    data class Inserting(val parts: List<InsertPart>) : Summary

    /** [open] things that keep the microphone available in the background; all set at 0. */
    data class Reliability(val open: Int) : Summary

    /** The last [count] dictations; nothing dictated yet at 0. */
    data class Diagnostics(val count: Int) : Summary

    /**
     * The folded "Advanced" row: what it holds - the [first] few rows by their titles, and
     * whether there are [more] ("When the server fails, Recording, Inserting ...")...
     */
    data class MoreSettings(val first: List<SettingsItem>, val more: Boolean) : Summary

    /** ...or what in it needs a look. */
    data class ReliabilityOpen(val open: Int) : Summary

    /** Light, dark, or as the phone is set. */
    data class Theme(val mode: ThemeMode) : Summary

    /** A folded server on a model list: "3 models". */
    data class Models(val count: Int) : Summary
}

enum class InsertPart { SPACES, CAPITALS, PUNCTUATION, CLIPBOARD }

/**
 * The settings list's one-line states. Each says what a page is set to in a few words - never
 * why: a row is read at a glance, and its page explains.
 */
object SettingsSummaries {

    fun recognition(engine: EngineConfig?, trust: TrustLevel?): Summary =
        if (engine == null) Summary.NoneSelected else Summary.Recognition(engine, trust)

    /**
     * The cleanup model's name - "DeepSeek V4.1 Flash" - or off, also when its engine is gone.
     * [choice] is the cleanup for every app unless one app's own is given.
     */
    fun cleanup(settings: AmethystSettings, choice: CleanupChoice? = settings.cleanup): Summary {
        if (choice == null) return Summary.Off
        val engine = settings.engines.firstOrNull { it.id == choice.engineId } ?: return Summary.Off
        return Summary.Name(ServerProviders.forEngine(engine, settings).cleanupModel(choice.model)?.label ?: choice.model)
    }

    fun onOff(on: Boolean): Summary = if (on) Summary.On else Summary.Off

    fun commands(settings: AmethystSettings): Summary = when {
        !settings.commands.enabled -> Summary.Off
        !settings.bubble.enabled -> Summary.OnButBubbleOff
        else -> Summary.On
    }

    fun appStyles(count: Int): Summary = Summary.Apps(count)

    fun appearance(ui: UiSettings): Summary = theme(ui.theme)

    fun theme(mode: ThemeMode): Summary = Summary.Theme(mode)

    /** Who takes over a failed server dictation; with [recognition] on the phone, no server can fail. */
    fun fallback(settings: AmethystSettings, recognition: TrustLevel?): Summary {
        if (recognition == TrustLevel.ON_DEVICE) return Summary.NotNeeded
        val engine = when (settings.fallback.mode) {
            FallbackMode.AUTOMATIC -> null
            FallbackMode.OFF -> return Summary.Off
            FallbackMode.ENGINE -> settings.engines.firstOrNull { it.id == settings.fallback.engineId } ?: return Summary.Off
        }
        val prepared = settings.fallback.prepare && ReadyFallback.preparable(settings.fallback, settings.engines)
        return Summary.Fallback(engine, prepared)
    }

    /** After how long a silence it stops, or that only a tap ends it. */
    fun recording(capture: CaptureSettings): Summary =
        if (capture.autoStopOnSilence) Summary.StopsAfter(capture.silenceTimeoutMs / 1000.0) else Summary.RunsUntilTap

    fun inserting(insertion: InsertionSettings): Summary =
        Summary.Inserting(
            listOfNotNull(
                InsertPart.SPACES.takeIf { insertion.autoSpace },
                InsertPart.CAPITALS.takeIf { insertion.autoCapitalize },
                InsertPart.PUNCTUATION.takeIf { insertion.gluePunctuation },
                InsertPart.CLIPBOARD.takeIf { insertion.allowClipboard },
            ),
        )

    fun reliability(open: Int): Summary = Summary.Reliability(open)

    fun diagnostics(count: Int): Summary = Summary.Diagnostics(count)

    /** [hidden]: the rows the switch would show ([SettingsLayout.hiddenRows]), so the two never differ. */
    fun advanced(hidden: List<SettingsItem>, reliabilityOpen: Int): Summary =
        if (reliabilityOpen > 0) {
            Summary.ReliabilityOpen(reliabilityOpen)
        } else {
            Summary.MoreSettings(hidden.take(ADVANCED_TITLES), more = hidden.size > ADVANCED_TITLES)
        }

    /** How many titles the folded "Advanced settings" row names: what fits a line on a phone. */
    const val ADVANCED_TITLES = 3

    fun models(count: Int): Summary = Summary.Models(count)

    /** A display name without its bracketed note: "Tinfoil (Enclave)" becomes "Tinfoil". */
    fun shortName(displayName: String): String = displayName.substringBefore(" (").ifBlank { displayName }
}
