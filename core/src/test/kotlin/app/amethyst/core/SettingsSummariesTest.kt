package app.amethyst.core

import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppCleanup
import app.amethyst.core.settings.AppCleanupMode
import app.amethyst.core.settings.AppProfile
import app.amethyst.core.settings.BubbleSettings
import app.amethyst.core.settings.CaptureSettings
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.CommandSettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.FallbackMode
import app.amethyst.core.settings.FallbackSettings
import app.amethyst.core.settings.InsertionSettings
import app.amethyst.core.settings.SettingsSummaries
import app.amethyst.core.settings.Summary
import app.amethyst.core.settings.UpdateSettings
import app.amethyst.core.update.ReleaseChannel
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsSummariesTest {

    private val tinfoil = EngineConfig.fromPreset(EnginePresets.TINFOIL)
    private val groq = EngineConfig.fromPreset(EnginePresets.GROQ)
    private val parakeet = EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET)
    private val settings = AmethystSettings.defaults(TEST_PHONE).withEngine(tinfoil).withEngine(groq).withEngine(parakeet)

    @Test
    fun `recognition names the engine and where the voice goes`() {
        assertEquals(Summary.Recognition(tinfoil, TrustLevel.ENCLAVE_VERIFIED), SettingsSummaries.recognition(tinfoil, TrustLevel.ENCLAVE_VERIFIED))
        assertEquals(Summary.NoneSelected, SettingsSummaries.recognition(null, null))
    }

    @Test
    fun `a short name drops the bracketed note and keeps a plain name`() {
        assertEquals("Whisper local", SettingsSummaries.shortName("Whisper local (FUTO)"))
        assertEquals("Own server", SettingsSummaries.shortName("Own server"))
        assertEquals("(odd)", SettingsSummaries.shortName("(odd)"))
    }

    @Test
    fun `cleanup is the model's name, or off`() {
        val on = settings.copy(cleanup = CleanupChoice(tinfoil.id, "deepseek-v4-1-flash"))
        assertEquals(Summary.Name("DeepSeek V4.1 Flash"), SettingsSummaries.cleanup(on))
        assertEquals(Summary.Off, SettingsSummaries.cleanup(settings))
    }

    @Test
    fun `cleanup on an unknown model shows the model as typed, on a removed engine it is off`() {
        assertEquals(Summary.Name("llama3.2"), SettingsSummaries.cleanup(settings.copy(cleanup = CleanupChoice(groq.id, "llama3.2"))))
        assertEquals(Summary.Off, SettingsSummaries.cleanup(settings.copy(cleanup = CleanupChoice("gone", "deepseek-v4-1-flash"))))
    }

    @Test
    fun `an app's own cleanup choice reads the same way`() {
        assertEquals(Summary.Name("Gemma 4"), SettingsSummaries.cleanup(settings, CleanupChoice(tinfoil.id, "gemma4-31b")))
    }

    @Test
    fun `apps counts the styles and the apps the bubble is hidden in`() {
        val styled = listOf(AppProfile("com.whatsapp", "WhatsApp"), AppProfile("com.google.android.keep", "Keep"))
        assertEquals(Summary.Apps(styles = 2, hidden = 1), SettingsSummaries.apps(styled, listOf("com.game")))
        // An app with both counts in both: the two lists are counted apart.
        assertEquals(Summary.Apps(styles = 2, hidden = 2), SettingsSummaries.apps(styled, listOf("com.game", "com.whatsapp")))
        assertEquals(Summary.Apps(styles = 0, hidden = 1), SettingsSummaries.apps(emptyList(), listOf("com.game")))
        assertEquals(Summary.Apps(styles = 0, hidden = 0), SettingsSummaries.apps(emptyList(), emptyList()))
    }

    @Test
    fun `an app's folded cleanup row says the default and what it is, off, or its own model`() {
        val on = settings.copy(cleanup = CleanupChoice(tinfoil.id, "deepseek-v4-1-flash"))
        assertEquals(Summary.AppCleanupModel(own = false, "DeepSeek V4.1 Flash"), SettingsSummaries.appCleanup(on, AppCleanup()))
        // The cleanup for every app is off: the default has no model to name.
        assertEquals(Summary.AppCleanupModel(own = false, null), SettingsSummaries.appCleanup(settings, AppCleanup()))
        assertEquals(Summary.Off, SettingsSummaries.appCleanup(on, AppCleanup(AppCleanupMode.OFF, CleanupChoice(tinfoil.id, "gemma4-31b"))))
        assertEquals(
            Summary.AppCleanupModel(own = true, "Gemma 4"),
            SettingsSummaries.appCleanup(settings, AppCleanup(AppCleanupMode.MODEL, CleanupChoice(tinfoil.id, "gemma4-31b"))),
        )
        // Its service removed, or none picked yet: an own model without a name.
        assertEquals(Summary.AppCleanupModel(own = true, null), SettingsSummaries.appCleanup(on, AppCleanup(AppCleanupMode.MODEL, CleanupChoice("gone", "x"))))
        assertEquals(Summary.AppCleanupModel(own = true, null), SettingsSummaries.appCleanup(on, AppCleanup(AppCleanupMode.MODEL)))
    }

    @Test
    fun `voice commands say when the bubble they need is off`() {
        assertEquals(Summary.Off, SettingsSummaries.commands(settings))
        val on = settings.copy(commands = CommandSettings(enabled = true))
        assertEquals(Summary.On, SettingsSummaries.commands(on))
        assertEquals(Summary.OnButBubbleOff, SettingsSummaries.commands(on.copy(bubble = BubbleSettings(enabled = false))))
    }

    @Test
    fun `the fallback names who steps in, and is not needed for a phone engine`() {
        assertEquals(Summary.Fallback(null, prepared = false), SettingsSummaries.fallback(settings, TrustLevel.CLOUD))
        assertEquals(Summary.Off, SettingsSummaries.fallback(settings.copy(fallback = FallbackSettings(FallbackMode.OFF)), TrustLevel.CLOUD))
        assertEquals(Summary.Fallback(groq, prepared = false), SettingsSummaries.fallback(settings.copy(fallback = FallbackSettings(FallbackMode.ENGINE, groq.id)), TrustLevel.ENCLAVE_VERIFIED))
        assertEquals(Summary.Off, SettingsSummaries.fallback(settings.copy(fallback = FallbackSettings(FallbackMode.ENGINE, "gone")), TrustLevel.CLOUD))
        assertEquals(Summary.NotNeeded, SettingsSummaries.fallback(settings, TrustLevel.ON_DEVICE))
    }

    @Test
    fun `a fallback prepared on the phone says so, one on a server cannot be`() {
        assertEquals(Summary.Fallback(null, prepared = true), SettingsSummaries.fallback(settings.copy(fallback = FallbackSettings(prepare = true)), TrustLevel.CLOUD))
        assertEquals(Summary.Fallback(groq, prepared = false), SettingsSummaries.fallback(settings.copy(fallback = FallbackSettings(FallbackMode.ENGINE, groq.id, prepare = true)), TrustLevel.ENCLAVE_VERIFIED))
        assertEquals(Summary.Off, SettingsSummaries.fallback(settings.copy(fallback = FallbackSettings(FallbackMode.OFF, prepare = true)), TrustLevel.CLOUD))
    }

    @Test
    fun `the own words row counts them`() {
        val settings = AmethystSettings.defaults(TEST_PHONE)
        assertEquals(Summary.OwnWords(0), SettingsSummaries.ownWords(settings))
        assertEquals(Summary.OwnWords(2), SettingsSummaries.ownWords(settings.copy(words = settings.words.copy(own = listOf("Anna", "Amber")))))
    }

    @Test
    fun `the bubble row says where it shows and how many apps it stays out of`() {
        assertEquals(Summary.Off, SettingsSummaries.bubble(BubbleSettings(enabled = false)))
        assertEquals(Summary.Bubble(onlyWithField = true, hidden = 0), SettingsSummaries.bubble(BubbleSettings()))
        assertEquals(
            Summary.Bubble(onlyWithField = false, hidden = 2),
            SettingsSummaries.bubble(BubbleSettings(onlyWithTextField = false, hiddenIn = listOf("a", "b", "a"))),
        )
    }

    @Test
    fun `recording says when it stops`() {
        assertEquals(Summary.StopsAfter(0.9), SettingsSummaries.recording(CaptureSettings(autoStopOnSilence = true, silenceTimeoutMs = 900)))
        assertEquals(Summary.StopsAfter(1.5), SettingsSummaries.recording(CaptureSettings(autoStopOnSilence = true, silenceTimeoutMs = 1_500)))
        assertEquals(Summary.RunsUntilTap, SettingsSummaries.recording(CaptureSettings()))
    }

    private val noWordRules = CaptureSettings(stripFillers = false, germanCommas = false)

    @Test
    fun `text names the first things done to it, in the page's order, and says there are more`() {
        // A new install: both word rules, the three for inserting and the clipboard.
        assertEquals(
            Summary.Text(listOf(Summary.Text.Part.FILLERS, Summary.Text.Part.COMMAS, Summary.Text.Part.SPACES), more = true),
            SettingsSummaries.text(CaptureSettings(), InsertionSettings()),
        )
        assertEquals(
            Summary.Text(listOf(Summary.Text.Part.COMMAS, Summary.Text.Part.SPACES, Summary.Text.Part.CAPITALS), more = true),
            SettingsSummaries.text(CaptureSettings(stripFillers = false), InsertionSettings(allowClipboard = true)),
        )
    }

    @Test
    fun `text with a few things on names them all`() {
        assertEquals(
            Summary.Text(listOf(Summary.Text.Part.SPACES, Summary.Text.Part.CAPITALS, Summary.Text.Part.PUNCTUATION), more = false),
            SettingsSummaries.text(noWordRules, InsertionSettings(allowClipboard = false)),
        )
        assertEquals(
            Summary.Text(listOf(Summary.Text.Part.FILLERS, Summary.Text.Part.CLIPBOARD), more = false),
            SettingsSummaries.text(
                CaptureSettings(germanCommas = false),
                InsertionSettings(autoSpace = false, autoCapitalize = false, gluePunctuation = false, allowClipboard = true),
            ),
        )
    }

    @Test
    fun `text with every rule off goes in as recognized`() {
        assertEquals(
            Summary.Text(emptyList(), more = false),
            SettingsSummaries.text(noWordRules, InsertionSettings(autoSpace = false, autoCapitalize = false, gluePunctuation = false, allowClipboard = false)),
        )
    }

    @Test
    fun `the word rules are the text row's, not the recording row's`() {
        val silent = CaptureSettings(autoStopOnSilence = true, silenceTimeoutMs = 900)
        assertEquals(SettingsSummaries.recording(silent), SettingsSummaries.recording(silent.copy(stripFillers = false, germanCommas = false)))
    }

    @Test
    fun `on and off are just that`() {
        assertEquals(Summary.On, SettingsSummaries.onOff(true))
        assertEquals(Summary.Off, SettingsSummaries.onOff(false))
    }

    private val checked = UpdateSettings(lastCheckedAt = 1_000L, latestVersion = "0.33.0")

    @Test
    fun `about says the version, and a newer one that is out`() {
        assertEquals(Summary.About("0.32.0", Summary.About.Newer.Out("0.33.0")), SettingsSummaries.about("0.32.0", ReleaseChannel.GITHUB, checked))
        // Dismissed on Home, it is still out.
        assertEquals(
            Summary.About("0.32.0", Summary.About.Newer.Out("0.33.0")),
            SettingsSummaries.about("0.32.0", ReleaseChannel.GITHUB, checked.copy(dismissedVersion = "0.33.0")),
        )
        // Found by an earlier check: the failed one after it knows nothing new.
        assertEquals(
            Summary.About("0.32.0", Summary.About.Newer.Out("0.33.0")),
            SettingsSummaries.about("0.32.0", ReleaseChannel.GITHUB, checked.copy(lastCheckFailed = true)),
        )
    }

    @Test
    fun `about says there is nothing newer once a check got that answer`() {
        assertEquals(Summary.About("0.33.0", Summary.About.Newer.None), SettingsSummaries.about("0.33.0", ReleaseChannel.GITHUB, checked))
        assertEquals(Summary.About("0.34.0-debug", Summary.About.Newer.None), SettingsSummaries.about("0.34.0-debug", ReleaseChannel.GITHUB, checked))
    }

    @Test
    fun `about says only the version where nothing is known`() {
        fun about(installed: String, channel: ReleaseChannel?, updates: UpdateSettings) =
            SettingsSummaries.about(installed, channel, updates)
        // A build that gets its updates from where it was installed, and one not known yet.
        assertEquals(Summary.About("0.32.0", Summary.About.Newer.Unknown), about("0.32.0", ReleaseChannel.OTHER, checked))
        assertEquals(Summary.About("0.32.0", Summary.About.Newer.Unknown), about("0.32.0", null, checked))
        // The check switched off.
        assertEquals(Summary.About("0.32.0", Summary.About.Newer.Unknown), about("0.32.0", ReleaseChannel.GITHUB, checked.copy(enabled = false)))
        // Never checked, or the last check failed with nothing newer stored.
        assertEquals(Summary.About("0.33.0", Summary.About.Newer.Unknown), about("0.33.0", ReleaseChannel.GITHUB, UpdateSettings()))
        assertEquals(Summary.About("0.33.0", Summary.About.Newer.Unknown), about("0.33.0", ReleaseChannel.GITHUB, checked.copy(lastCheckFailed = true)))
        // A version that cannot be read is not called up to date.
        assertEquals(Summary.About("", Summary.About.Newer.Unknown), about("", ReleaseChannel.GITHUB, checked))
        assertEquals(Summary.About("0.33.0", Summary.About.Newer.Unknown), about("0.33.0", ReleaseChannel.GITHUB, checked.copy(latestVersion = "soon")))
    }

    @Test
    fun `the bubble's size is a word, the default medium`() {
        assertEquals(Summary.BubbleSize(Summary.BubbleSize.Size.SMALL), SettingsSummaries.bubbleSize(44))
        assertEquals(Summary.BubbleSize(Summary.BubbleSize.Size.SMALL), SettingsSummaries.bubbleSize(52))
        assertEquals(Summary.BubbleSize(Summary.BubbleSize.Size.MEDIUM), SettingsSummaries.bubbleSize(BubbleSettings().sizeDp))
        assertEquals(Summary.BubbleSize(Summary.BubbleSize.Size.MEDIUM), SettingsSummaries.bubbleSize(68))
        assertEquals(Summary.BubbleSize(Summary.BubbleSize.Size.LARGE), SettingsSummaries.bubbleSize(72))
        assertEquals(Summary.BubbleSize(Summary.BubbleSize.Size.EXTRA_LARGE), SettingsSummaries.bubbleSize(96))
    }

    @Test
    fun `the privacy row names the security mode when it is on, the history otherwise`() {
        val settings = AmethystSettings.defaults(TEST_PHONE)
        assertEquals(Summary.Privacy(settings.privacy.history, secure = false), SettingsSummaries.privacy(settings))
        val secure = settings.copy(privacy = settings.privacy.copy(securityMode = true))
        assertEquals(true, (SettingsSummaries.privacy(secure) as Summary.Privacy).secure)
    }
}
