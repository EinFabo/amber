package app.amethyst.core

import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.BubbleSettings
import app.amethyst.core.settings.CaptureSettings
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.CommandSettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.FallbackMode
import app.amethyst.core.settings.FallbackSettings
import app.amethyst.core.settings.InsertionSettings
import app.amethyst.core.settings.InsertPart
import app.amethyst.core.settings.SettingsSummaries
import app.amethyst.core.settings.Summary
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
        assertEquals(Summary.Name("Gemma 4 31B"), SettingsSummaries.cleanup(settings, CleanupChoice(tinfoil.id, "gemma4-31b")))
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
    fun `recording says when it stops`() {
        assertEquals(Summary.StopsAfter(0.9), SettingsSummaries.recording(CaptureSettings(autoStopOnSilence = true, silenceTimeoutMs = 900)))
        assertEquals(Summary.StopsAfter(1.5), SettingsSummaries.recording(CaptureSettings(autoStopOnSilence = true, silenceTimeoutMs = 1_500)))
        assertEquals(Summary.RunsUntilTap, SettingsSummaries.recording(CaptureSettings()))
    }

    @Test
    fun `inserting lists what is added, or nothing`() {
        assertEquals(
            Summary.Inserting(listOf(InsertPart.SPACES, InsertPart.CAPITALS, InsertPart.PUNCTUATION)),
            SettingsSummaries.inserting(InsertionSettings()),
        )
        assertEquals(
            Summary.Inserting(listOf(InsertPart.CAPITALS, InsertPart.CLIPBOARD)),
            SettingsSummaries.inserting(InsertionSettings(autoSpace = false, gluePunctuation = false, allowClipboard = true)),
        )
        assertEquals(
            Summary.Inserting(emptyList()),
            SettingsSummaries.inserting(InsertionSettings(autoSpace = false, autoCapitalize = false, gluePunctuation = false)),
        )
    }

    @Test
    fun `the folded advanced row names what needs a look before how much it holds`() {
        assertEquals(Summary.MoreSettings(4), SettingsSummaries.advanced(hidden = 4, reliabilityOpen = 0))
        assertEquals(Summary.ReliabilityOpen(1), SettingsSummaries.advanced(hidden = 4, reliabilityOpen = 1))
    }

    @Test
    fun `on and off are just that`() {
        assertEquals(Summary.On, SettingsSummaries.onOff(true))
        assertEquals(Summary.Off, SettingsSummaries.onOff(false))
    }
}
