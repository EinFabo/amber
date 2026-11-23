package app.amethyst.core.settings

import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.engine.ReadyFallback
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.update.AppVersion
import app.amethyst.core.update.ReleaseChannel
import app.amethyst.core.update.UpdatePolicy

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

    /** Apps with a style of their own and apps the bubble is hidden in ("6 styles · 2 hidden"); none yet at 0 and 0. */
    data class Apps(val styles: Int, val hidden: Int) : Summary

    /**
     * One app's cleanup on its page, folded to a line: the cleanup for every app ([own] false)
     * or a model of its own, with the [model]'s name - null where there is none to name: the
     * cleanup for every app is off, or the own model's service is gone.
     */
    data class AppCleanupModel(val own: Boolean, val model: String?) : Summary


    /** A fallback for a recognition on the phone, which no server can fail. */
    data object NotNeeded : Summary

    /** Who steps in: [engine], or Parakeet then Whisper when null; [prepared] in advance. */
    data class Fallback(val engine: EngineConfig?, val prepared: Boolean) : Summary
    data class StopsAfter(val seconds: Double) : Summary
    data object RunsUntilTap : Summary

    /**
     * What is done to the text by rules, in the order it happens: the [first] few things and
     * whether there are [more] ("Filler words, commas, spaces ..."); nothing ("as recognized")
     * when empty.
     */
    data class Text(val first: List<Part>, val more: Boolean) : Summary {

        /** The Text page's switches, top to bottom. */
        enum class Part { FILLERS, COMMAS, SPACES, CAPITALS, PUNCTUATION, CLIPBOARD }
    }

    /** [open] things that keep the microphone available in the background; all set at 0. */
    data class Reliability(val open: Int) : Summary

    /** The last [count] dictations; nothing dictated yet at 0. */
    data class Diagnostics(val count: Int) : Summary

    /** The row on top of the Settings page: [open] things on the Reliability page need a look. */
    data class ReliabilityOpen(val open: Int) : Summary

    /** Light, dark, or as the phone is set. */
    data class Theme(val mode: ThemeMode) : Summary

    /** How long the history keeps a dictation; [secure]: off by the security mode. */
    data class History(val keep: app.amethyst.core.history.HistoryKeep, val secure: Boolean) : Summary

    /** What is kept: the security mode when it is on ([secure]), otherwise how long the history keeps a dictation. */
    data class Privacy(val keep: app.amethyst.core.history.HistoryKeep, val secure: Boolean) : Summary

    /**
     * The bubble when it is on: whether it shows [onlyWithField] or always, and in how many apps
     * it is [hidden] (none: not said).
     */
    data class Bubble(val onlyWithField: Boolean, val hidden: Int) : Summary

    /** How many own words there are ([OwnWords]); none yet at 0. */
    data class OwnWords(val count: Int) : Summary

    /** The bubble's size in a word, not in dp ([SettingsSummaries.bubbleSize]). */
    data class BubbleSize(val size: Size) : Summary {
        enum class Size { SMALL, MEDIUM, LARGE, EXTRA_LARGE }
    }

    /** A folded server on a model list: "3 models". */
    data class Models(val count: Int) : Summary

    /** The "About Amber" row: the installed [version], and what is known of a [newer] one. */
    data class About(val version: String, val newer: Newer) : Summary {

        /** Whether a release newer than the installed Amber is out, as far as this build knows. */
        sealed interface Newer {

            /** Not known: a build that never asks, the check switched off, or no check that got an answer yet. */
            data object Unknown : Newer

            /** The last check found nothing newer. */
            data object None : Newer

            /** [version] is out. */
            data class Out(val version: String) : Newer
        }
    }
}

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

    fun ownWords(settings: AmethystSettings): Summary = Summary.OwnWords(settings.words.own.size)

    /** Off, or where it shows and how many apps it stays out of - "On" alone said nothing: it nearly always is. */
    fun bubble(bubble: BubbleSettings): Summary =
        if (!bubble.enabled) Summary.Off else Summary.Bubble(bubble.onlyWithTextField, bubble.hiddenIn.distinct().size)

    /**
     * The bubble's size slider (44 to 96 dp) in words: up to 52 small, up to 68 medium - the
     * default 56 among them - up to 84 large, then extra large. The preview beside it shows it.
     */
    fun bubbleSize(sizeDp: Int): Summary = Summary.BubbleSize(
        when {
            sizeDp <= 52 -> Summary.BubbleSize.Size.SMALL
            sizeDp <= 68 -> Summary.BubbleSize.Size.MEDIUM
            sizeDp <= 84 -> Summary.BubbleSize.Size.LARGE
            else -> Summary.BubbleSize.Size.EXTRA_LARGE
        },
    )

    fun history(settings: AmethystSettings): Summary = Summary.History(settings.privacy.history, settings.privacy.securityMode)

    fun privacy(settings: AmethystSettings): Summary = Summary.Privacy(settings.privacy.history, settings.privacy.securityMode)

    fun commands(settings: AmethystSettings): Summary = when {
        !settings.commands.enabled -> Summary.Off
        !settings.bubble.enabled -> Summary.OnButBubbleOff
        else -> Summary.On
    }

    /** The Apps row: how many apps have a style, in how many the bubble is hidden ([AppRows.counts]). */
    fun apps(profiles: List<AppProfile>, hiddenIn: List<String>): Summary =
        AppRows.counts(profiles, hiddenIn).let { (styles, hidden) -> Summary.Apps(styles, hidden) }

    /** [own], one app's choice of cleanup, in a line: the default and what that is, off, or its own model. */
    fun appCleanup(settings: AmethystSettings, own: AppCleanup): Summary {
        fun model(choice: CleanupChoice?) = (cleanup(settings, choice) as? Summary.Name)?.name
        return when (own.mode) {
            AppCleanupMode.DEFAULT -> Summary.AppCleanupModel(own = false, model(settings.cleanup))
            AppCleanupMode.OFF -> Summary.Off
            AppCleanupMode.MODEL -> Summary.AppCleanupModel(own = true, model(own.choice))
        }
    }

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

    /**
     * After how long a silence it stops, or that only a tap ends it - how a recording ends is
     * what its page is about; what becomes of the words is the Text page's ([text]).
     */
    fun recording(capture: CaptureSettings): Summary =
        if (capture.autoStopOnSilence) Summary.StopsAfter(capture.silenceTimeoutMs / 1000.0) else Summary.RunsUntilTap

    /**
     * What the Text page has switched on, in its order: the rules for the words (in the capture
     * settings since before the page) and those for inserting.
     */
    fun text(capture: CaptureSettings, insertion: InsertionSettings): Summary {
        val on = listOfNotNull(
            Summary.Text.Part.FILLERS.takeIf { capture.stripFillers },
            Summary.Text.Part.COMMAS.takeIf { capture.germanCommas },
            Summary.Text.Part.SPACES.takeIf { insertion.autoSpace },
            Summary.Text.Part.CAPITALS.takeIf { insertion.autoCapitalize },
            Summary.Text.Part.PUNCTUATION.takeIf { insertion.gluePunctuation },
            Summary.Text.Part.CLIPBOARD.takeIf { insertion.allowClipboard },
        )
        return Summary.Text(on.take(TEXT_PARTS), more = on.size > TEXT_PARTS)
    }

    /** How many things the Text row names: what fits a line on a phone, in German too. */
    const val TEXT_PARTS = 3

    /**
     * The Reliability row's state - none while the row on top of the page says what is open
     * ([Attention.Reliability]): said once, where it is in sight, not again at the bottom.
     */
    fun reliability(open: Int, attention: Attention? = null): Summary? =
        Summary.Reliability(open).takeUnless { attention is Attention.Reliability }

    fun diagnostics(count: Int): Summary = Summary.Diagnostics(count)

    fun models(count: Int): Summary = Summary.Models(count)

    /**
     * The installed version and, if known, whether a newer one is out. Known only where the
     * Updates page would say so ([UpdatePolicy.available]): on a build that checks, while the
     * check is on - a release found earlier stays out after a check that failed, but "nothing
     * newer" needs the last check to have got its answer. [channel] is null until known.
     */
    fun about(installed: String, channel: ReleaseChannel?, updates: UpdateSettings): Summary {
        val newer = when {
            channel == null || !channel.checksForUpdates || !updates.enabled -> Summary.About.Newer.Unknown
            else -> UpdatePolicy.available(channel, updates, installed)?.let { Summary.About.Newer.Out(it.version) }
                ?: Summary.About.Newer.None.takeIf {
                    updates.lastCheckedAt > 0L && !updates.lastCheckFailed &&
                        AppVersion.parse(installed) != null && AppVersion.parse(updates.latestVersion) != null
                }
                ?: Summary.About.Newer.Unknown
        }
        return Summary.About(installed, newer)
    }

    /** A display name without its bracketed note: "Tinfoil (Enclave)" becomes "Tinfoil". */
    fun shortName(displayName: String): String = displayName.substringBefore(" (").ifBlank { displayName }
}
