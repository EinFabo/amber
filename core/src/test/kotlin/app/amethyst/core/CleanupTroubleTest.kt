package app.amethyst.core

import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.Problem
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppCleanup
import app.amethyst.core.settings.AppCleanupMode
import app.amethyst.core.settings.AppProfile
import app.amethyst.core.settings.Attention
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.ReliabilityItem
import app.amethyst.core.settings.SettingsAttention
import app.amethyst.core.settings.UpdateSettings
import app.amethyst.core.text.CleanupTrouble
import app.amethyst.core.text.CleanupTrouble.Cause
import app.amethyst.core.text.CleanupTrouble.Open
import app.amethyst.core.update.ReleaseChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CleanupTroubleTest {

    private val trouble = CleanupTrouble()
    private val llama = CleanupChoice("groq", "llama")
    private val llama9 = CleanupChoice("groq", "llama-9")

    @Test
    fun `a refused key, no credit or an unknown model need the user`() {
        assertEquals(Cause.KEY_REFUSED, CleanupTrouble.causeOf(Problem.KeyRejected))
        assertEquals(Cause.KEY_REFUSED, CleanupTrouble.causeOf(Problem.CleanupFailed(401)))
        assertEquals(Cause.KEY_REFUSED, CleanupTrouble.causeOf(Problem.CleanupFailed(403)))
        assertEquals(Cause.NO_CREDIT, CleanupTrouble.causeOf(Problem.CleanupFailed(402)))
        assertEquals(Cause.NO_CREDIT, CleanupTrouble.causeOf(Problem.NoCredit("Groq")))
        assertEquals(Cause.UNKNOWN_MODEL, CleanupTrouble.causeOf(Problem.CleanupModelUnknown("llama-9")))
    }

    @Test
    fun `a timeout, a refusal of the content or a server hiccup stay silent`() {
        for (problem in listOf(
            Problem.NoAnswerInTime("Groq"), Problem.CleanupDeclined("Claude"), Problem.CleanupCutOff,
            Problem.CleanupChangedTooMuch("negation dropped"), Problem.CleanupFailed(500), Problem.CleanupUnreadable,
            Problem.NoConnection("Groq"), Problem.RateLimited,
        )) {
            assertNull(CleanupTrouble.causeOf(problem), problem.toString())
            assertNull(trouble.failed(problem, "Groq", llama))
        }
        assertNull(trouble.open.value)
    }

    @Test
    fun `each cause is said once per session, the row stays until a cleanup succeeds`() {
        assertEquals(Open(Cause.KEY_REFUSED, "Groq", llama), trouble.failed(Problem.CleanupFailed(401), "Groq", llama))
        assertNull(trouble.failed(Problem.CleanupFailed(401), "Groq", llama))
        assertEquals(Open(Cause.KEY_REFUSED, "Groq", llama), trouble.open.value)
        // Another cause is said once too.
        assertEquals(Open(Cause.UNKNOWN_MODEL, "llama-9", llama9), trouble.failed(Problem.CleanupModelUnknown("llama-9"), "Groq", llama9))
        assertEquals(Open(Cause.UNKNOWN_MODEL, "llama-9", llama9), trouble.open.value)
        trouble.succeeded()
        assertNull(trouble.open.value)
        // Failing again: the row comes back, the toast does not - once per session.
        assertNull(trouble.failed(Problem.CleanupFailed(401), "Groq", llama))
        assertEquals(Open(Cause.KEY_REFUSED, "Groq", llama), trouble.open.value)
    }

    @Test
    fun `the Settings row says the cleanup trouble after reliability, before a release`() {
        val open = Open(Cause.NO_CREDIT, "Groq", llama)
        val found = UpdateSettings(latestVersion = "0.40.0")
        assertEquals(Attention.Cleanup(open), SettingsAttention.of(emptyList(), ReleaseChannel.GITHUB, found, "0.34.0", cleanup = open))
        assertEquals(
            Attention.Reliability(listOf(ReliabilityItem.BATTERY)),
            SettingsAttention.of(listOf(ReliabilityItem.BATTERY), ReleaseChannel.GITHUB, found, "0.34.0", cleanup = open),
        )
        assertEquals(Attention.Update("0.40.0"), SettingsAttention.of(emptyList(), ReleaseChannel.GITHUB, found, "0.34.0", cleanup = null))
    }

    private val groq = EngineConfig(id = "groq", kind = EngineKind.OPENAI_COMPATIBLE, displayName = "Groq")
    private val settings = AmethystSettings(engines = listOf(groq), cleanup = llama)

    @Test
    fun `the trouble goes when the cleanup is turned off or another service or model takes its place`() {
        trouble.failed(Problem.KeyRejected, "Groq", llama)
        trouble.settingsChanged(settings)
        assertEquals(llama, trouble.open.value?.choice)
        trouble.settingsChanged(settings.copy(cleanup = llama9))
        assertNull(trouble.open.value)

        trouble.failed(Problem.KeyRejected, "Groq", llama)
        trouble.settingsChanged(settings.copy(cleanup = null))
        assertNull(trouble.open.value)

        trouble.failed(Problem.KeyRejected, "Groq", llama)
        trouble.settingsChanged(settings.copy(cleanup = CleanupChoice("openai", "llama")))
        assertNull(trouble.open.value)
    }

    @Test
    fun `an app's own model keeps it, the service gone does not`() {
        val app = AppProfile(packageName = "com.whatsapp", label = "WhatsApp", appCleanup = AppCleanup(AppCleanupMode.MODEL, llama))
        val open = Open(Cause.KEY_REFUSED, "Groq", llama)
        assertTrue(CleanupTrouble.stillApplies(open, settings))
        assertTrue(CleanupTrouble.stillApplies(open, settings.copy(cleanup = null, profiles = listOf(app))))
        assertFalse(CleanupTrouble.stillApplies(open, settings.copy(cleanup = null, profiles = listOf(app.copy(appCleanup = AppCleanup(AppCleanupMode.OFF, llama))))))
        assertFalse(CleanupTrouble.stillApplies(open, settings.copy(engines = emptyList())))
    }

    @Test
    fun `saving that service's key again clears it, and a new failure is said again`() {
        assertEquals(Open(Cause.KEY_REFUSED, "Groq", llama), trouble.failed(Problem.KeyRejected, "Groq", llama))
        trouble.keySaved(listOf("openai"))
        assertEquals(llama, trouble.open.value?.choice)
        trouble.keySaved(listOf("groq"))
        assertNull(trouble.open.value)
        assertEquals(Open(Cause.KEY_REFUSED, "Groq", llama), trouble.failed(Problem.KeyRejected, "Groq", llama))
    }
}
