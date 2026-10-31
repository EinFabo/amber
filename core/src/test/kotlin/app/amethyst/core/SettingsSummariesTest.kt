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
import app.amethyst.core.settings.SettingsSummaries
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsSummariesTest {

    private val tinfoil = EngineConfig.fromPreset(EnginePresets.TINFOIL)
    private val groq = EngineConfig.fromPreset(EnginePresets.GROQ)
    private val parakeet = EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET)
    private val settings = AmethystSettings.defaults().withEngine(tinfoil).withEngine(groq).withEngine(parakeet)

    @Test
    fun `recognition names the engine without its note and where the voice goes`() {
        assertEquals("Tinfoil · Enclave", SettingsSummaries.recognition(tinfoil, TrustLevel.ENCLAVE_VERIFIED))
        assertEquals("Parakeet TDT · On device", SettingsSummaries.recognition(parakeet, TrustLevel.ON_DEVICE))
        assertEquals("Groq · Cloud", SettingsSummaries.recognition(groq, TrustLevel.CLOUD))
        assertEquals("None selected", SettingsSummaries.recognition(null, null))
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
        assertEquals("DeepSeek V4.1 Flash", SettingsSummaries.cleanup(on))
        assertEquals("Off", SettingsSummaries.cleanup(settings))
    }

    @Test
    fun `cleanup on an unknown model shows the model as typed, on a removed engine it is off`() {
        assertEquals("llama3.2", SettingsSummaries.cleanup(settings.copy(cleanup = CleanupChoice(groq.id, "llama3.2"))))
        assertEquals("Off", SettingsSummaries.cleanup(settings.copy(cleanup = CleanupChoice("gone", "deepseek-v4-1-flash"))))
    }

    @Test
    fun `an app's own cleanup choice reads the same way`() {
        assertEquals("Gemma 4 31B", SettingsSummaries.cleanup(settings, CleanupChoice(tinfoil.id, "gemma4-31b")))
    }

    @Test
    fun `voice commands say when the bubble they need is off`() {
        assertEquals("Off", SettingsSummaries.commands(settings))
        val on = settings.copy(commands = CommandSettings(enabled = true))
        assertEquals("On", SettingsSummaries.commands(on))
        assertEquals("On · the bubble is off", SettingsSummaries.commands(on.copy(bubble = BubbleSettings(enabled = false))))
    }

    @Test
    fun `app styles count the apps`() {
        assertEquals("None yet", SettingsSummaries.appStyles(0))
        assertEquals("1 app", SettingsSummaries.appStyles(1))
        assertEquals("6 apps", SettingsSummaries.appStyles(6))
    }

    @Test
    fun `the fallback names who steps in, and is not needed for a phone engine`() {
        assertEquals("Parakeet, then Whisper", SettingsSummaries.fallback(settings, TrustLevel.CLOUD))
        assertEquals("Off", SettingsSummaries.fallback(settings.copy(fallback = FallbackSettings(FallbackMode.OFF)), TrustLevel.CLOUD))
        assertEquals("Groq", SettingsSummaries.fallback(settings.copy(fallback = FallbackSettings(FallbackMode.ENGINE, groq.id)), TrustLevel.ENCLAVE_VERIFIED))
        assertEquals("Off", SettingsSummaries.fallback(settings.copy(fallback = FallbackSettings(FallbackMode.ENGINE, "gone")), TrustLevel.CLOUD))
        assertEquals("Not needed", SettingsSummaries.fallback(settings, TrustLevel.ON_DEVICE))
    }

    @Test
    fun `recording says when it stops`() {
        assertEquals("Stops after 0.9 s silence", SettingsSummaries.recording(CaptureSettings(silenceTimeoutMs = 900)))
        assertEquals("Stops after 1.5 s silence", SettingsSummaries.recording(CaptureSettings(silenceTimeoutMs = 1_500)))
        assertEquals("Runs until you tap", SettingsSummaries.recording(CaptureSettings(autoStopOnSilence = false)))
    }

    @Test
    fun `inserting lists what is added, or nothing`() {
        assertEquals("Spaces, capitals, punctuation", SettingsSummaries.inserting(InsertionSettings()))
        assertEquals("Capitals, clipboard", SettingsSummaries.inserting(InsertionSettings(autoSpace = false, gluePunctuation = false, allowClipboard = true)))
        assertEquals(
            "As recognized",
            SettingsSummaries.inserting(InsertionSettings(autoSpace = false, autoCapitalize = false, gluePunctuation = false)),
        )
    }

    @Test
    fun `reliability, diagnostics and the folded advanced row count in words`() {
        assertEquals("All set", SettingsSummaries.reliability(0))
        assertEquals("1 thing open", SettingsSummaries.reliability(1))
        assertEquals("2 things open", SettingsSummaries.reliability(2))
        assertEquals("Nothing dictated yet", SettingsSummaries.diagnostics(0))
        assertEquals("Last 1 dictation", SettingsSummaries.diagnostics(1))
        assertEquals("Last 12 dictations", SettingsSummaries.diagnostics(12))
        assertEquals("4 more settings", SettingsSummaries.advanced(hidden = 4, reliabilityOpen = 0))
        assertEquals("Reliability: 1 thing open", SettingsSummaries.advanced(hidden = 4, reliabilityOpen = 1))
    }

    @Test
    fun `a folded engine counts its models`() {
        assertEquals("1 model", SettingsSummaries.models(1))
        assertEquals("3 models", SettingsSummaries.models(3))
    }

    @Test
    fun `on and off are just that`() {
        assertEquals("On", SettingsSummaries.onOff(true))
        assertEquals("Off", SettingsSummaries.onOff(false))
    }
}
