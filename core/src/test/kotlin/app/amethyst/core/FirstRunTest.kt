package app.amethyst.core

import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.PhoneFacts
import app.amethyst.core.engine.whisper.WhisperModels
import app.amethyst.core.onboarding.FirstRun
import app.amethyst.core.onboarding.PendingVerdict
import app.amethyst.core.onboarding.QuickPlan
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppProfile
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.SettingsCodec
import app.amethyst.core.settings.WritingStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FirstRunTest {

    private val fresh = AmethystSettings.defaults(TEST_PHONE)
    private val android = EnginePresets.ANDROID_ON_DEVICE.id
    private val parakeet = EnginePresets.LOCAL_PARAKEET.id
    private val apps = setOf("com.whatsapp", "com.google.android.gm", "org.example.other")
    private val viaAndroid = QuickPlan(EngineOption.ANDROID, EngineOption.PARAKEET)
    private val nothingReady: (EngineOption) -> Boolean = { false }

    private fun phone(
        ramMb: Long = 8_000,
        freeMb: Long = 20_000,
        whisperCpu: Boolean = true,
        onDevice: Boolean = true,
        recognizer: Boolean = true,
    ) = PhoneFacts(totalRamMb = ramMb, freeStorageMb = freeMb, whisperCpu = whisperCpu, androidOnDevice = onDevice, androidRecognizer = recognizer)

    @Test
    fun `a phone with room dictates with android until parakeet is there`() {
        assertEquals(viaAndroid, FirstRun.plan(phone(), nothingReady))
        assertEquals(EngineOption.PARAKEET, viaAndroid.download)
    }

    @Test
    fun `a smaller phone waits for the whisper that fits it`() {
        assertEquals(QuickPlan(EngineOption.ANDROID, EngineOption.FUTO_244), FirstRun.plan(phone(ramMb = 3_700), nothingReady))
        assertEquals(QuickPlan(EngineOption.ANDROID, EngineOption.FUTO_74), FirstRun.plan(phone(ramMb = 1_900), nothingReady))
    }

    @Test
    fun `a model already on the phone is used at once`() {
        val plan = FirstRun.plan(phone()) { it == EngineOption.PARAKEET }
        assertEquals(QuickPlan(EngineOption.PARAKEET, null), plan)
        assertEquals(EngineOption.PARAKEET, plan.download)
    }

    @Test
    fun `a phone nothing fits on stays with android and downloads nothing`() {
        val plan = FirstRun.plan(phone(freeMb = 100), nothingReady)
        assertEquals(QuickPlan(EngineOption.ANDROID, null), plan)
        assertNull(plan.download)
        // An old CPU without Whisper's instructions and too little memory for Parakeet.
        assertEquals(QuickPlan(EngineOption.ANDROID, null), FirstRun.plan(phone(ramMb = 3_700, whisperCpu = false), nothingReady))
    }

    @Test
    fun `android does not stand in where it would send the voice away or is missing`() {
        assertEquals(QuickPlan(EngineOption.PARAKEET, null), FirstRun.plan(phone(onDevice = false), nothingReady))
        assertEquals(QuickPlan(EngineOption.PARAKEET, null), FirstRun.plan(phone(recognizer = false), nothingReady))
    }

    @Test
    fun `quick keeps android active, remembers what waits and leaves cleanup off`() {
        val after = FirstRun.quick(fresh, viaAndroid, apps, TEST_PHONE)
        assertEquals(android, after.activeEngineId)
        assertEquals(EngineOption.PARAKEET, FirstRun.pending(after))
        assertNull(after.cleanup)
        assertFalse(after.onboardingDone)
        assertEquals(listOf("com.whatsapp", "com.google.android.gm"), after.profiles.map { it.packageName })
        assertTrue(after.firstRun.appStylesGiven)
    }

    @Test
    fun `quick without a stand-in makes the recommended one active with its model`() {
        val after = FirstRun.quick(fresh, QuickPlan(EngineOption.FUTO_244, null), apps, TEST_PHONE)
        assertEquals(EnginePresets.LOCAL_WHISPER.id, after.activeEngineId)
        assertEquals(WhisperModels.FUTO_MULTILINGUAL_244.id, after.activeEngine?.modelId)
        assertNull(FirstRun.pending(after))
    }

    @Test
    fun `skip is quick that also ends the introduction`() {
        val after = FirstRun.quick(fresh, viaAndroid, apps, TEST_PHONE, finish = true)
        assertTrue(after.onboardingDone)
        assertEquals(after.copy(onboardingDone = false), FirstRun.quick(fresh, viaAndroid, apps, TEST_PHONE))
    }

    @Test
    fun `a replay changes nothing`() {
        val set = fresh
            .withEngine(EngineConfig.fromPreset(EnginePresets.GROQ))
            .copy(
                activeEngineId = EnginePresets.GROQ.id,
                cleanup = CleanupChoice(EnginePresets.GROQ.id, "llama"),
                profiles = listOf(AppProfile("com.whatsapp", "WhatsApp", WritingStyle.FORMAL)),
                onboardingDone = true,
            )
        assertSame(set, FirstRun.quick(set, viaAndroid, apps, TEST_PHONE))
        assertSame(set, FirstRun.quick(set, viaAndroid, apps, TEST_PHONE, finish = true))
        assertSame(set, FirstRun.appStyles(set, apps))
        // Also for someone who replays while still on Android's recognition.
        val onAndroid = fresh.copy(onboardingDone = true)
        assertSame(onAndroid, FirstRun.quick(onAndroid, viaAndroid, apps, TEST_PHONE))
    }

    @Test
    fun `a recognition chosen step by step is not replaced by quick`() {
        val chosen = FirstRun.use(fresh, EngineOption.FUTO_74, TEST_PHONE)
        val after = FirstRun.quick(chosen, viaAndroid, apps, TEST_PHONE)
        assertEquals(EnginePresets.LOCAL_WHISPER.id, after.activeEngineId)
        assertNull(FirstRun.pending(after))
    }

    @Test
    fun `app styles are given once, so one switched off stays off`() {
        val given = FirstRun.appStyles(fresh, apps)
        val without = given.copy(profiles = given.profiles.filterNot { it.packageName == "com.whatsapp" })
        assertSame(without, FirstRun.appStyles(without, apps))
        assertEquals(without.profiles, FirstRun.quick(without, viaAndroid, apps, TEST_PHONE).profiles)
    }

    @Test
    fun `a style the user has is kept as it is`() {
        val own = fresh.copy(profiles = listOf(AppProfile("com.whatsapp", "WhatsApp", WritingStyle.FORMAL, instruction = "sign")))
        val after = FirstRun.appStyles(own, apps)
        assertEquals(own.profiles.single(), after.profiles.first())
        assertEquals(listOf("com.whatsapp", "com.google.android.gm"), after.profiles.map { it.packageName })
    }

    @Test
    fun `the waiting recognition takes over when its model is there`() {
        val waiting = FirstRun.quick(fresh, viaAndroid, apps, TEST_PHONE)
        assertEquals(PendingVerdict.WAIT, FirstRun.verdict(waiting, ready = false))
        assertSame(waiting, FirstRun.settle(waiting, ready = false, TEST_PHONE))
        assertEquals(PendingVerdict.SWITCH, FirstRun.verdict(waiting, ready = true))
        val switched = FirstRun.settle(waiting, ready = true, TEST_PHONE)
        assertEquals(parakeet, switched.activeEngineId)
        assertTrue(switched.engines.any { it.id == parakeet })
        assertTrue(switched.engines.any { it.id == android })
        assertNull(switched.firstRun.pendingEngine)
        assertEquals(PendingVerdict.NONE, FirstRun.verdict(switched, ready = true))
    }

    @Test
    fun `a waiting whisper switches with its own model`() {
        val waiting = FirstRun.quick(fresh, QuickPlan(EngineOption.ANDROID, EngineOption.FUTO_244), apps, TEST_PHONE)
        val switched = FirstRun.settle(waiting, ready = true, TEST_PHONE)
        assertEquals(WhisperModels.FUTO_MULTILINGUAL_244.id, switched.activeEngine?.modelId)
    }

    @Test
    fun `a recognition the user chose meanwhile stays, and the wait ends`() {
        val waiting = FirstRun.quick(fresh, viaAndroid, apps, TEST_PHONE)
        val chosen = waiting.withEngine(EngineConfig.fromPreset(EnginePresets.GROQ)).copy(activeEngineId = EnginePresets.GROQ.id)
        assertEquals(PendingVerdict.DROP, FirstRun.verdict(chosen, ready = true))
        val settled = FirstRun.settle(chosen, ready = true, TEST_PHONE)
        assertEquals(EnginePresets.GROQ.id, settled.activeEngineId)
        assertNull(settled.firstRun.pendingEngine)
        assertFalse(FirstRun.stillWanted(settled, EngineOption.PARAKEET))
    }

    @Test
    fun `the download goes on for someone who chose the waiting recognition themselves`() {
        val waiting = FirstRun.quick(fresh, viaAndroid, apps, TEST_PHONE)
        val chosen = FirstRun.use(waiting, EngineOption.PARAKEET, TEST_PHONE)
        assertEquals(PendingVerdict.DROP, FirstRun.verdict(chosen, ready = false))
        assertTrue(FirstRun.stillWanted(chosen, EngineOption.PARAKEET))
        assertFalse(FirstRun.stillWanted(chosen, EngineOption.FUTO_244))
    }

    @Test
    fun `a waiting name this build does not know is dropped`() {
        val odd = fresh.copy(firstRun = fresh.firstRun.copy(pendingEngine = "GONE"))
        assertNull(FirstRun.pending(odd))
        assertEquals(PendingVerdict.DROP, FirstRun.verdict(odd, ready = false))
        assertNull(FirstRun.settle(odd, ready = false, TEST_PHONE).firstRun.pendingEngine)
        // Android needs no model, so it never waits.
        assertNull(FirstRun.pending(fresh.copy(firstRun = fresh.firstRun.copy(pendingEngine = "ANDROID"))))
    }

    @Test
    fun `what waits survives being stored, and older settings have nothing waiting`() {
        val waiting = FirstRun.quick(fresh, viaAndroid, apps, TEST_PHONE)
        assertEquals(waiting, SettingsCodec.decode(SettingsCodec.encode(waiting), TEST_PHONE))
        val old = """{"activeEngineId":"android-on-device","engines":[],"onboardingDone":true,"schemaVersion":${AmethystSettings.SCHEMA_VERSION}}"""
        val decoded = SettingsCodec.decode(old, TEST_PHONE)
        assertNull(decoded.firstRun.pendingEngine)
        assertFalse(decoded.firstRun.appStylesGiven)
    }

    @Test
    fun `a new install starts without app styles`() {
        assertTrue(fresh.profiles.isEmpty())
    }
}
