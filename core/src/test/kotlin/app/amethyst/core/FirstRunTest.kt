package app.amethyst.core

import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.PhoneFacts
import app.amethyst.core.engine.whisper.WhisperModels
import app.amethyst.core.onboarding.FirstRun
import app.amethyst.core.onboarding.PendingStage
import app.amethyst.core.onboarding.PendingVerdict
import app.amethyst.core.onboarding.QuickPlan
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppEngine
import app.amethyst.core.settings.AppEngineMode
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
    private val whisper = EnginePresets.LOCAL_WHISPER.id
    private val apps = setOf("com.whatsapp", "com.google.android.gm", "org.example.other")
    private val viaSmall = QuickPlan(EngineOption.FUTO_74, EngineOption.PARAKEET, nowMissing = true)
    private val nothingReady: (EngineOption) -> Boolean = { false }

    /** Settings from 0.33: Android's recognition stands in while Parakeet downloads. */
    private val viaAndroid = fresh.copy(firstRun = fresh.firstRun.copy(appStylesGiven = true, pendingEngine = EngineOption.PARAKEET.name))

    private fun phone(
        ramMb: Long = 8_000,
        freeMb: Long = 20_000,
        whisperCpu: Boolean = true,
        onDevice: Boolean = true,
        recognizer: Boolean = true,
    ) = PhoneFacts(totalRamMb = ramMb, freeStorageMb = freeMb, whisperCpu = whisperCpu, androidOnDevice = onDevice, androidRecognizer = recognizer)

    private fun option(settings: AmethystSettings) = settings.activeEngine?.let(app.amethyst.core.onboarding.EngineNames::optionOf)

    @Test
    fun `a phone with room dictates with the small model until parakeet is there`() {
        assertEquals(viaSmall, FirstRun.plan(phone(), nothingReady))
        assertEquals(listOf(EngineOption.FUTO_74, EngineOption.PARAKEET), viaSmall.downloads)
        // Android's recognition is no stand-in any more, wherever it keeps the voice.
        assertEquals(viaSmall, FirstRun.plan(phone(onDevice = false), nothingReady))
        assertEquals(viaSmall, FirstRun.plan(phone(recognizer = false), nothingReady))
    }

    @Test
    fun `a big whisper gets the small model in front of it too`() {
        assertEquals(QuickPlan(EngineOption.FUTO_74, EngineOption.FUTO_244, nowMissing = true), FirstRun.plan(phone(ramMb = 3_700), nothingReady))
    }

    @Test
    fun `a phone too small for parakeet gets the small model and nothing extra`() {
        val plan = FirstRun.plan(phone(ramMb = 1_900), nothingReady)
        assertEquals(QuickPlan(EngineOption.FUTO_74, null, nowMissing = true), plan)
        assertEquals(listOf(EngineOption.FUTO_74), plan.downloads)
        val after = FirstRun.quick(fresh, plan, apps, TEST_PHONE)
        assertEquals(EngineOption.FUTO_74, FirstRun.wanted(after))
        assertEquals(PendingStage.OWN, FirstRun.stage(after))
        assertNull(after.firstRun.standIn)
        // Once its model is there, nothing is left - and nothing is deleted.
        val there = FirstRun.settle(after, ready = true, TEST_PHONE)
        assertNull(FirstRun.wanted(there))
        assertNull(FirstRun.leftover(there))
    }

    @Test
    fun `a model already on the phone is used at once`() {
        val plan = FirstRun.plan(phone()) { it == EngineOption.PARAKEET }
        assertEquals(QuickPlan(EngineOption.PARAKEET, null), plan)
        assertEquals(emptyList(), plan.downloads)
    }

    @Test
    fun `a small model already on the phone stands in without a download of its own`() {
        val plan = FirstRun.plan(phone()) { it == EngineOption.FUTO_74 }
        assertEquals(QuickPlan(EngineOption.FUTO_74, EngineOption.PARAKEET), plan)
        val after = FirstRun.quick(fresh, plan, apps, TEST_PHONE)
        assertEquals(EngineOption.PARAKEET, FirstRun.wanted(after))
        assertEquals(PendingStage.SMALL_STANDS_IN, FirstRun.stage(after))
    }

    @Test
    fun `a phone nothing fits on stays with android and downloads nothing`() {
        val plan = FirstRun.plan(phone(freeMb = 100), nothingReady)
        assertEquals(QuickPlan(EngineOption.ANDROID, null), plan)
        assertEquals(emptyList(), plan.downloads)
        // An old CPU without Whisper's instructions and too little memory for Parakeet.
        assertEquals(QuickPlan(EngineOption.ANDROID, null), FirstRun.plan(phone(ramMb = 3_700, whisperCpu = false), nothingReady))
    }

    @Test
    fun `without room or a cpu for the small model the recommended one is active at once`() {
        val atOnce = QuickPlan(EngineOption.PARAKEET, null, nowMissing = true)
        // Parakeet does not need Whisper's instructions; the small model does.
        assertEquals(atOnce, FirstRun.plan(phone(whisperCpu = false), nothingReady))
        // Room for Parakeet, not for both.
        val tight = (EngineOption.PARAKEET.downloadBytes + EngineOption.FUTO_74.downloadBytes / 2) / 1_000_000 + 300
        assertEquals(atOnce, FirstRun.plan(phone(freeMb = tight), nothingReady))
        assertEquals(listOf(EngineOption.PARAKEET), atOnce.downloads)
    }

    @Test
    fun `quick makes the small model active, parakeet waits, and both are written down in order`() {
        val after = FirstRun.quick(fresh, viaSmall, apps, TEST_PHONE)
        assertEquals(whisper, after.activeEngineId)
        assertEquals(WhisperModels.FUTO_MULTILINGUAL_74.id, after.activeEngine?.modelId)
        assertEquals(EngineOption.PARAKEET, FirstRun.pending(after))
        assertEquals(EngineOption.FUTO_74, FirstRun.wanted(after))
        assertEquals(EngineOption.FUTO_74, FirstRun.smallFirst(after))
        assertTrue(FirstRun.anyNetwork(after, EngineOption.FUTO_74))
        assertFalse(FirstRun.anyNetwork(after, EngineOption.PARAKEET))
        assertEquals(PendingStage.SMALL_FIRST, FirstRun.stage(after))
        assertNull(after.cleanup)
        assertFalse(after.onboardingDone)
        assertEquals(listOf("com.whatsapp", "com.google.android.gm"), after.profiles.map { it.packageName })
        assertTrue(after.firstRun.appStylesGiven)
        assertEquals(after, SettingsCodec.decode(SettingsCodec.encode(after), TEST_PHONE))
    }

    @Test
    fun `the small model stands in once it is there, then parakeet takes over and the small one goes`() {
        val start = FirstRun.quick(fresh, viaSmall, apps, TEST_PHONE)
        assertEquals(PendingVerdict.WAIT, FirstRun.verdict(start, ready = false))
        assertSame(start, FirstRun.settle(start, ready = false, TEST_PHONE))

        assertEquals(PendingVerdict.STANDS_IN, FirstRun.verdict(start, ready = true))
        val small = FirstRun.settle(start, ready = true, TEST_PHONE)
        assertEquals(whisper, small.activeEngineId)
        assertEquals(EngineOption.PARAKEET, FirstRun.wanted(small))
        assertFalse(FirstRun.anyNetwork(small, EngineOption.PARAKEET))
        assertEquals(PendingStage.SMALL_STANDS_IN, FirstRun.stage(small))
        assertNull(FirstRun.leftover(small))

        assertEquals(PendingVerdict.WAIT, FirstRun.verdict(small, ready = false))
        assertEquals(PendingVerdict.SWITCH, FirstRun.verdict(small, ready = true))
        val switched = FirstRun.settle(small, ready = true, TEST_PHONE)
        assertEquals(parakeet, switched.activeEngineId)
        assertNull(FirstRun.wanted(switched))
        assertNull(FirstRun.stage(switched))
        assertEquals(EngineOption.FUTO_74, FirstRun.leftover(switched))

        val tidy = FirstRun.tidy(switched)
        assertNull(tidy.firstRun.standIn)
        assertNull(FirstRun.leftover(tidy))
        // Settings no longer offer a Whisper whose model is gone.
        assertFalse(tidy.engines.any { it.id == whisper })
        assertTrue(tidy.engines.any { it.id == android })
        assertSame(tidy, FirstRun.tidy(tidy))
    }

    @Test
    fun `a big whisper takes over the same recognition with its own model`() {
        val start = FirstRun.quick(fresh, QuickPlan(EngineOption.FUTO_74, EngineOption.FUTO_244, nowMissing = true), apps, TEST_PHONE)
        val small = FirstRun.settle(start, ready = true, TEST_PHONE)
        val switched = FirstRun.settle(small, ready = true, TEST_PHONE)
        assertEquals(EngineOption.FUTO_244, option(switched))
        assertEquals(EngineOption.FUTO_74, FirstRun.leftover(switched))
        // The small model's file goes; the recognition stays, with the big model.
        val tidy = FirstRun.tidy(switched)
        assertEquals(WhisperModels.FUTO_MULTILINGUAL_244.id, tidy.activeEngine?.modelId)
    }

    @Test
    fun `the small model failing keeps it first, and parakeet waits behind it`() {
        val start = FirstRun.quick(fresh, viaSmall, apps, TEST_PHONE)
        // A failed download is no verdict: the model is still not there.
        assertSame(start, FirstRun.settle(start, ready = false, TEST_PHONE))
        assertEquals(EngineOption.FUTO_74, FirstRun.wanted(start))
        assertEquals(PendingStage.SMALL_FIRST, FirstRun.stage(start))
    }

    @Test
    fun `parakeet failing leaves the small model hearing the dictations`() {
        val small = FirstRun.settle(FirstRun.quick(fresh, viaSmall, apps, TEST_PHONE), ready = true, TEST_PHONE)
        assertSame(small, FirstRun.settle(small, ready = false, TEST_PHONE))
        assertEquals(whisper, small.activeEngineId)
        assertEquals(EngineOption.PARAKEET, FirstRun.wanted(small))
        assertNull(FirstRun.leftover(small))
    }

    @Test
    fun `another recognition chosen while the small model downloads ends both, and the small one goes`() {
        val start = FirstRun.quick(fresh, viaSmall, apps, TEST_PHONE).withEngine(EngineConfig.fromPreset(EnginePresets.GROQ))
        val groq = FirstRun.chosen(start, EnginePresets.GROQ.id)
        assertNull(FirstRun.wanted(groq))
        assertEquals(EngineOption.FUTO_74, FirstRun.leftover(groq))
        // The same change made any other way.
        val other = start.copy(activeEngineId = EnginePresets.GROQ.id)
        assertEquals(PendingVerdict.DROP, FirstRun.verdict(other, ready = false))
        val settled = FirstRun.settle(other, ready = false, TEST_PHONE)
        assertNull(FirstRun.wanted(settled))
        assertEquals(EngineOption.FUTO_74, FirstRun.leftover(settled))
        assertEquals(EnginePresets.GROQ.id, FirstRun.tidy(settled).activeEngineId)
    }

    @Test
    fun `another recognition chosen while parakeet downloads ends the wait, and the small one goes`() {
        val small = FirstRun.settle(FirstRun.quick(fresh, viaSmall, apps, TEST_PHONE), ready = true, TEST_PHONE)
        val chosen = FirstRun.chosen(small, android)
        assertEquals(android, chosen.activeEngineId)
        assertNull(FirstRun.wanted(chosen))
        assertEquals(EngineOption.FUTO_74, FirstRun.leftover(chosen))
    }

    @Test
    fun `the small model chosen by the user is theirs, stays active and is never deleted`() {
        val start = FirstRun.quick(fresh, viaSmall, apps, TEST_PHONE)
        val small = FirstRun.settle(start, ready = true, TEST_PHONE)
        // While it downloads, while parakeet does; in Settings and in the introduction.
        for (chosen in listOf(FirstRun.chosen(start, whisper), FirstRun.chosen(small, whisper), FirstRun.use(small, EngineOption.FUTO_74, TEST_PHONE))) {
            assertEquals(whisper, chosen.activeEngineId)
            assertNull(FirstRun.pending(chosen))
            assertNull(chosen.firstRun.standIn)
            assertNull(FirstRun.leftover(FirstRun.settle(chosen, ready = true, TEST_PHONE)))
        }
        // Its own model stays wanted while it is on its way.
        assertEquals(EngineOption.FUTO_74, FirstRun.wanted(FirstRun.chosen(start, whisper)))
        assertEquals(PendingStage.OWN, FirstRun.stage(FirstRun.chosen(start, whisper)))
    }

    @Test
    fun `parakeet chosen by the user keeps its download, and the small one goes once it is there`() {
        val start = FirstRun.quick(fresh, viaSmall, apps, TEST_PHONE).withEngine(EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET))
        for (chosen in listOf(FirstRun.chosen(start, parakeet), FirstRun.use(start, EngineOption.PARAKEET, TEST_PHONE))) {
            assertEquals(parakeet, chosen.activeEngineId)
            assertEquals(EngineOption.PARAKEET, FirstRun.wanted(chosen))
            // Kept while parakeet is not there yet.
            assertNull(FirstRun.leftover(chosen))
            val there = FirstRun.settle(chosen, ready = true, TEST_PHONE)
            assertNull(FirstRun.wanted(there))
            assertEquals(EngineOption.FUTO_74, FirstRun.leftover(there))
        }
    }

    @Test
    fun `the small model an app or the fallback uses is theirs - not deleted, the mark gone`() {
        val switched = FirstRun.settle(FirstRun.settle(FirstRun.quick(fresh, viaSmall, apps, TEST_PHONE), true, TEST_PHONE), true, TEST_PHONE)
        val byApp = switched.copy(profiles = switched.profiles.map { it.copy(appEngine = AppEngine(AppEngineMode.ENGINE, whisper)) })
        val byFallback = switched.copy(fallback = switched.fallback.copy(engineId = whisper))
        for (used in listOf(byApp, byFallback)) {
            // Its file stays: nothing to delete.
            assertNull(FirstRun.leftover(used))
            val tidy = FirstRun.tidy(used)
            assertTrue(tidy.engines.any { it.id == whisper })
            assertNull(tidy.firstRun.standIn)
            assertSame(tidy, FirstRun.tidy(tidy))
        }
    }

    @Test
    fun `while parakeet is on its way an app using the small model changes nothing yet`() {
        val small = FirstRun.settle(FirstRun.quick(fresh, viaSmall, apps, TEST_PHONE), ready = true, TEST_PHONE)
        val byApp = small.copy(profiles = small.profiles.map { it.copy(appEngine = AppEngine(AppEngineMode.ENGINE, whisper)) })
        // The mark stays, so Parakeet still takes over when it is there.
        assertSame(byApp, FirstRun.tidy(byApp))
        assertEquals(PendingVerdict.SWITCH, FirstRun.verdict(byApp, ready = true))
    }

    @Test
    fun `a whisper that has the big model by now is no use of the small one`() {
        val start = FirstRun.quick(fresh, QuickPlan(EngineOption.FUTO_74, EngineOption.FUTO_244, nowMissing = true), apps, TEST_PHONE)
        val switched = FirstRun.settle(FirstRun.settle(start, ready = true, TEST_PHONE), ready = true, TEST_PHONE)
        val byApp = switched.copy(profiles = switched.profiles.map { it.copy(appEngine = AppEngine(AppEngineMode.ENGINE, whisper)) })
        assertEquals(EngineOption.FUTO_74, FirstRun.leftover(byApp))
    }

    @Test
    fun `a recognition made active before its model is there has the model written down as wanted`() {
        val after = FirstRun.quick(fresh, QuickPlan(EngineOption.PARAKEET, null, nowMissing = true), apps, TEST_PHONE, finish = true)
        assertEquals(parakeet, after.activeEngineId)
        assertNull(FirstRun.pending(after))
        assertEquals(EngineOption.PARAKEET, FirstRun.wanted(after))
        assertEquals(PendingStage.OWN, FirstRun.stage(after))
        assertEquals(after, SettingsCodec.decode(SettingsCodec.encode(after), TEST_PHONE))

        assertEquals(PendingVerdict.WAIT, FirstRun.verdict(after, ready = false))
        assertSame(after, FirstRun.settle(after, ready = false, TEST_PHONE))
        assertEquals(PendingVerdict.DONE, FirstRun.verdict(after, ready = true))
        val there = FirstRun.settle(after, ready = true, TEST_PHONE)
        assertEquals(parakeet, there.activeEngineId)
        assertNull(FirstRun.wanted(there))
        assertEquals(PendingVerdict.NONE, FirstRun.verdict(there, ready = true))
    }

    @Test
    fun `a wanted model is dropped when another recognition is chosen`() {
        val wanted = FirstRun.quick(fresh, QuickPlan(EngineOption.FUTO_244, null, nowMissing = true), apps, TEST_PHONE)
        assertEquals(EngineOption.FUTO_244, FirstRun.wanted(wanted))
        // Another recognition, and another Whisper model of the same one.
        val groq = wanted.withEngine(EngineConfig.fromPreset(EnginePresets.GROQ)).copy(activeEngineId = EnginePresets.GROQ.id)
        val otherModel = wanted.withEngine(wanted.activeEngine!!.copy(modelId = WhisperModels.FUTO_MULTILINGUAL_74.id))
        for (chosen in listOf(groq, otherModel)) {
            assertEquals(PendingVerdict.DROP, FirstRun.verdict(chosen, ready = false))
            val settled = FirstRun.settle(chosen, ready = false, TEST_PHONE)
            assertNull(FirstRun.wanted(settled))
            assertEquals(chosen.activeEngine, settled.activeEngine)
            assertFalse(FirstRun.stillWanted(settled, EngineOption.FUTO_244))
        }
    }

    @Test
    fun `a model that is on the phone already is not written down`() {
        val after = FirstRun.quick(fresh, QuickPlan(EngineOption.PARAKEET, null), apps, TEST_PHONE)
        assertEquals(parakeet, after.activeEngineId)
        assertNull(FirstRun.wanted(after))
    }

    @Test
    fun `skip is quick that also ends the introduction`() {
        val after = FirstRun.quick(fresh, viaSmall, apps, TEST_PHONE, finish = true)
        assertTrue(after.onboardingDone)
        assertEquals(after.copy(onboardingDone = false), FirstRun.quick(fresh, viaSmall, apps, TEST_PHONE))
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
        assertSame(set, FirstRun.quick(set, viaSmall, apps, TEST_PHONE))
        assertSame(set, FirstRun.quick(set, viaSmall, apps, TEST_PHONE, finish = true))
        assertSame(set, FirstRun.appStyles(set, apps))
        // Also for someone who replays while still on Android's recognition: no small model either.
        val onAndroid = fresh.copy(onboardingDone = true)
        assertSame(onAndroid, FirstRun.quick(onAndroid, viaSmall, apps, TEST_PHONE))
        assertNull(FirstRun.wanted(onAndroid))
    }

    @Test
    fun `a recognition chosen step by step is not replaced by quick`() {
        val chosen = FirstRun.use(fresh, EngineOption.FUTO_244, TEST_PHONE)
        val after = FirstRun.quick(chosen, viaSmall, apps, TEST_PHONE)
        assertEquals(EngineOption.FUTO_244, option(after))
        assertNull(FirstRun.pending(after))
        assertNull(after.firstRun.standIn)
    }

    @Test
    fun `app styles are given once, so one switched off stays off`() {
        val given = FirstRun.appStyles(fresh, apps)
        val without = given.copy(profiles = given.profiles.filterNot { it.packageName == "com.whatsapp" })
        assertSame(without, FirstRun.appStyles(without, apps))
        assertEquals(without.profiles, FirstRun.quick(without, viaSmall, apps, TEST_PHONE).profiles)
    }

    @Test
    fun `a style the user has is kept as it is`() {
        val own = fresh.copy(profiles = listOf(AppProfile("com.whatsapp", "WhatsApp", WritingStyle.FORMAL, instruction = "sign")))
        val after = FirstRun.appStyles(own, apps)
        assertEquals(own.profiles.single(), after.profiles.first())
        assertEquals(listOf("com.whatsapp", "com.google.android.gm"), after.profiles.map { it.packageName })
    }

    @Test
    fun `settings from 0_33 - the recognition waiting behind android's takes over when its model is there`() {
        assertEquals(EngineOption.PARAKEET, FirstRun.wanted(viaAndroid))
        assertEquals(PendingStage.ANDROID_STANDS_IN, FirstRun.stage(viaAndroid))
        assertFalse(FirstRun.anyNetwork(viaAndroid, EngineOption.PARAKEET))
        assertEquals(PendingVerdict.WAIT, FirstRun.verdict(viaAndroid, ready = false))
        assertSame(viaAndroid, FirstRun.settle(viaAndroid, ready = false, TEST_PHONE))
        assertEquals(PendingVerdict.SWITCH, FirstRun.verdict(viaAndroid, ready = true))
        val switched = FirstRun.settle(viaAndroid, ready = true, TEST_PHONE)
        assertEquals(parakeet, switched.activeEngineId)
        assertTrue(switched.engines.any { it.id == parakeet })
        assertTrue(switched.engines.any { it.id == android })
        assertNull(switched.firstRun.pendingEngine)
        assertEquals(PendingVerdict.NONE, FirstRun.verdict(switched, ready = true))
        // Android's recognition is never deleted.
        assertNull(FirstRun.leftover(switched))
    }

    @Test
    fun `settings from 0_33 - a recognition the user chose meanwhile stays, and the wait ends`() {
        val chosen = viaAndroid.withEngine(EngineConfig.fromPreset(EnginePresets.GROQ)).copy(activeEngineId = EnginePresets.GROQ.id)
        assertEquals(PendingVerdict.DROP, FirstRun.verdict(chosen, ready = true))
        val settled = FirstRun.settle(chosen, ready = true, TEST_PHONE)
        assertEquals(EnginePresets.GROQ.id, settled.activeEngineId)
        assertNull(settled.firstRun.pendingEngine)
        assertFalse(FirstRun.stillWanted(settled, EngineOption.PARAKEET))
    }

    @Test
    fun `the download goes on for someone who chose the waiting recognition themselves`() {
        // Made active without a word about what waits - not by one of the user's own choices.
        for (waiting in listOf(viaAndroid, FirstRun.settle(FirstRun.quick(fresh, viaSmall, apps, TEST_PHONE), true, TEST_PHONE))) {
            val chosen = waiting.withEngine(EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET)).copy(activeEngineId = parakeet)
            assertEquals(PendingVerdict.DROP, FirstRun.verdict(chosen, ready = false))
            assertTrue(FirstRun.stillWanted(chosen, EngineOption.PARAKEET))
            assertFalse(FirstRun.stillWanted(chosen, EngineOption.FUTO_244))
            // No longer waiting, but written down still - a restart takes it up again.
            val settled = FirstRun.settle(chosen, ready = false, TEST_PHONE)
            assertNull(FirstRun.pending(settled))
            assertEquals(EngineOption.PARAKEET, FirstRun.wanted(settled))
            assertNull(FirstRun.wanted(FirstRun.settle(chosen, ready = true, TEST_PHONE)))
        }
    }

    @Test
    fun `the same for someone who made parakeet active while the small model was still on its way`() {
        val start = FirstRun.quick(fresh, viaSmall, apps, TEST_PHONE)
        val chosen = start.withEngine(EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET)).copy(activeEngineId = parakeet)
        assertEquals(PendingVerdict.DROP, FirstRun.verdict(chosen, ready = true))
        val settled = FirstRun.settle(chosen, ready = true, TEST_PHONE)
        assertEquals(EngineOption.PARAKEET, FirstRun.wanted(settled))
        assertEquals(PendingStage.OWN, FirstRun.stage(settled))
    }

    @Test
    fun `choosing android oneself ends the wait, in the introduction and in settings`() {
        for (waiting in listOf(viaAndroid, FirstRun.quick(fresh, viaSmall, apps, TEST_PHONE))) {
            for (chosen in listOf(FirstRun.use(waiting, EngineOption.ANDROID, TEST_PHONE), FirstRun.chosen(waiting, android))) {
                assertEquals(android, chosen.activeEngineId)
                assertNull(FirstRun.pending(chosen))
                assertNull(FirstRun.wanted(chosen))
                assertEquals(PendingVerdict.NONE, FirstRun.verdict(chosen, ready = true))
                assertSame(chosen, FirstRun.settle(chosen, ready = true, TEST_PHONE))
                assertFalse(FirstRun.stillWanted(chosen, EngineOption.PARAKEET))
            }
        }
    }

    @Test
    fun `any other choice of one's own ends the wait too`() {
        val waiting = viaAndroid.withEngine(EngineConfig.fromPreset(EnginePresets.GROQ))
        val groq = FirstRun.chosen(waiting, EnginePresets.GROQ.id)
        assertEquals(EnginePresets.GROQ.id, groq.activeEngineId)
        assertNull(FirstRun.wanted(groq))

        val small = FirstRun.use(waiting, EngineOption.FUTO_74, TEST_PHONE)
        assertNull(FirstRun.pending(small))
        assertEquals(EngineOption.FUTO_74, FirstRun.wanted(small))
    }

    @Test
    fun `choosing the waiting recognition oneself keeps its model wanted`() {
        val waiting = viaAndroid.withEngine(EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET))
        for (chosen in listOf(FirstRun.use(waiting, EngineOption.PARAKEET, TEST_PHONE), FirstRun.chosen(waiting, parakeet))) {
            assertEquals(parakeet, chosen.activeEngineId)
            assertNull(FirstRun.pending(chosen))
            assertEquals(EngineOption.PARAKEET, FirstRun.wanted(chosen))
            assertEquals(PendingVerdict.WAIT, FirstRun.verdict(chosen, ready = false))
        }
    }

    @Test
    fun `a choice in settings does not write a model down by itself`() {
        val withParakeet = fresh.withEngine(EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET))
        val chosen = FirstRun.chosen(withParakeet, parakeet)
        assertEquals(parakeet, chosen.activeEngineId)
        assertNull(FirstRun.wanted(chosen))
        // And choosing another recognition drops a model wanted for the one before.
        val wanted = FirstRun.quick(fresh, QuickPlan(EngineOption.PARAKEET, null, nowMissing = true), apps, TEST_PHONE)
        assertNull(FirstRun.wanted(FirstRun.chosen(wanted, android)))
    }

    @Test
    fun `a waiting name this build does not know is dropped`() {
        val odd = fresh.copy(firstRun = fresh.firstRun.copy(pendingEngine = "GONE"))
        assertNull(FirstRun.pending(odd))
        assertEquals(PendingVerdict.DROP, FirstRun.verdict(odd, ready = false))
        assertNull(FirstRun.settle(odd, ready = false, TEST_PHONE).firstRun.pendingEngine)
        // Android needs no model, so it never waits.
        assertNull(FirstRun.pending(fresh.copy(firstRun = fresh.firstRun.copy(pendingEngine = "ANDROID"))))
        val oddWanted = fresh.copy(firstRun = fresh.firstRun.copy(wantedModel = "GONE"))
        assertNull(FirstRun.wanted(oddWanted))
        assertNull(FirstRun.settle(oddWanted, ready = false, TEST_PHONE).firstRun.wantedModel)
        // A stand-in this build does not know is never deleted.
        assertNull(FirstRun.leftover(fresh.copy(firstRun = fresh.firstRun.copy(standIn = "GONE"))))
    }

    @Test
    fun `what waits survives being stored, and older settings have nothing waiting`() {
        val small = FirstRun.settle(FirstRun.quick(fresh, viaSmall, apps, TEST_PHONE), true, TEST_PHONE)
        assertEquals(small, SettingsCodec.decode(SettingsCodec.encode(small), TEST_PHONE))
        val old = """{"activeEngineId":"android-on-device","engines":[],"onboardingDone":true,"schemaVersion":${AmethystSettings.SCHEMA_VERSION}}"""
        val decoded = SettingsCodec.decode(old, TEST_PHONE)
        assertNull(decoded.firstRun.pendingEngine)
        assertNull(decoded.firstRun.wantedModel)
        assertNull(decoded.firstRun.standIn)
        assertFalse(decoded.firstRun.appStylesGiven)
    }

    @Test
    fun `a new install starts without app styles`() {
        assertTrue(fresh.profiles.isEmpty())
    }
}
