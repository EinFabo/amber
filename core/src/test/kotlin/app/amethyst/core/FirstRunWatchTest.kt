package app.amethyst.core

import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.onboarding.FirstRun
import app.amethyst.core.onboarding.FirstRunWatch
import app.amethyst.core.onboarding.ModelFetch
import app.amethyst.core.onboarding.QuickPlan
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.EngineConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The watch over the models the first run still fetches, with the downloads faked. */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstRunWatchTest {

    private val fresh = AmethystSettings.defaults(TEST_PHONE)
    private val android = EnginePresets.ANDROID_ON_DEVICE.id
    private val parakeet = EnginePresets.LOCAL_PARAKEET.id
    private val whisper = EnginePresets.LOCAL_WHISPER.id

    /** Quick setup on a phone with room: the small model first, Parakeet after it. */
    private val viaSmall = FirstRun.quick(fresh, QuickPlan(EngineOption.FUTO_74, EngineOption.PARAKEET, nowMissing = true), emptySet(), TEST_PHONE, finish = true)

    /** Settings from 0.33: Android's recognition stands in while Parakeet downloads. */
    private val waiting = fresh.copy(onboardingDone = true, firstRun = fresh.firstRun.copy(pendingEngine = EngineOption.PARAKEET.name))

    private val activeAtOnce = FirstRun.quick(fresh, QuickPlan(EngineOption.PARAKEET, null, nowMissing = true), emptySet(), TEST_PHONE, finish = true)

    private class Downloads : ModelFetch {
        val onPhone = MutableStateFlow(emptySet<EngineOption>())
        val calls = mutableListOf<String>()

        override fun ready(option: EngineOption): Flow<Boolean> = onPhone.map { option in it }

        override suspend fun fetch(option: EngineOption, mobileData: Boolean) {
            calls += if (mobileData) "fetch ${option.name} on any network" else "fetch ${option.name}"
        }

        override suspend fun letGo(option: EngineOption) {
            calls += "let go ${option.name}"
        }

        override suspend fun delete(option: EngineOption) {
            calls += "delete ${option.name}"
            onPhone.update { it - option }
        }

        fun arrives(option: EngineOption) = onPhone.update { it + option }
    }

    private class Phone(scope: TestScope, start: AmethystSettings, onPhone: Set<EngineOption> = emptySet()) {
        val stored = MutableStateFlow(start)
        val downloads = Downloads().also { it.onPhone.value = onPhone }
        private val watch = FirstRunWatch(stored, { transform -> stored.update(transform) }, downloads, { TEST_PHONE })
        val job: Job = scope.launch { watch.run() }
    }

    private fun watched(start: AmethystSettings, onPhone: Set<EngineOption> = emptySet(), body: suspend TestScope.(Phone) -> Unit) = runTest {
        val phone = Phone(this, start, onPhone)
        advanceUntilIdle()
        body(phone)
        phone.job.cancel()
    }

    @Test
    fun `nothing waiting asks nothing of the downloads`() = watched(fresh) { phone ->
        assertEquals(emptyList(), phone.downloads.calls)
        assertEquals(fresh, phone.stored.value)
    }

    /** On Wi-Fi: the small model in seconds, Parakeet after it, the switch, the small one deleted. */
    @Test
    fun `the small model first, then parakeet takes over and the small one is deleted`() = watched(viaSmall) { phone ->
        assertEquals(listOf("fetch FUTO_74 on any network"), phone.downloads.calls)
        assertEquals(whisper, phone.stored.value.activeEngineId)

        phone.downloads.arrives(EngineOption.FUTO_74)
        advanceUntilIdle()
        // The small one hears the dictations now; Parakeet's download comes next, on Wi-Fi only.
        assertEquals(whisper, phone.stored.value.activeEngineId)
        assertEquals(EngineOption.PARAKEET, FirstRun.wanted(phone.stored.value))
        assertEquals(listOf("fetch FUTO_74 on any network", "fetch PARAKEET"), phone.downloads.calls)

        phone.downloads.arrives(EngineOption.PARAKEET)
        advanceUntilIdle()
        assertEquals(parakeet, phone.stored.value.activeEngineId)
        assertNull(FirstRun.wanted(phone.stored.value))
        assertNull(phone.stored.value.firstRun.standIn)
        assertFalse(phone.stored.value.engines.any { it.id == whisper })
        assertEquals(listOf("fetch FUTO_74 on any network", "fetch PARAKEET", "delete FUTO_74"), phone.downloads.calls)
    }

    /**
     * On mobile data the fake holds nothing back; what matters is what is asked: the small model
     * on any network, Parakeet on Wi-Fi only - it stays held while the small one hears the dictations.
     */
    @Test
    fun `on mobile data the small model comes anyway and parakeet waits for wi-fi`() = watched(viaSmall) { phone ->
        phone.downloads.arrives(EngineOption.FUTO_74)
        advanceUntilIdle()
        assertEquals(listOf("fetch FUTO_74 on any network", "fetch PARAKEET"), phone.downloads.calls)
        // Hours on mobile data: nothing changes, nothing is deleted.
        advanceUntilIdle()
        assertEquals(whisper, phone.stored.value.activeEngineId)
        assertEquals(EngineOption.PARAKEET, FirstRun.wanted(phone.stored.value))
    }

    @Test
    fun `the small model failing holds parakeet back, and the next start fetches it again`() = runTest {
        val first = Phone(this, viaSmall)
        advanceUntilIdle()
        // It never arrives: Parakeet is not asked for, the small one stays wanted.
        assertEquals(listOf("fetch FUTO_74 on any network"), first.downloads.calls)
        assertEquals(EngineOption.FUTO_74, FirstRun.wanted(first.stored.value))
        first.job.cancel()

        val next = Phone(this, first.stored.value)
        advanceUntilIdle()
        assertEquals(listOf("fetch FUTO_74 on any network"), next.downloads.calls)
        next.job.cancel()
    }

    @Test
    fun `parakeet failing leaves the small model active and is fetched again at the next start`() = runTest {
        val first = Phone(this, viaSmall)
        first.downloads.arrives(EngineOption.FUTO_74)
        advanceUntilIdle()
        first.job.cancel()

        val next = Phone(this, first.stored.value, onPhone = setOf(EngineOption.FUTO_74))
        advanceUntilIdle()
        assertEquals(listOf("fetch PARAKEET"), next.downloads.calls)
        assertEquals(whisper, next.stored.value.activeEngineId)
        next.downloads.arrives(EngineOption.PARAKEET)
        advanceUntilIdle()
        assertEquals(parakeet, next.stored.value.activeEngineId)
        assertEquals(listOf("fetch PARAKEET", "delete FUTO_74"), next.downloads.calls)
        next.job.cancel()
    }

    /** The process dies after the small model is there (Skip, no service): the next one goes on with Parakeet. */
    @Test
    fun `a restart between the two downloads goes on with the second`() = runTest {
        val first = Phone(this, viaSmall)
        first.downloads.arrives(EngineOption.FUTO_74)
        advanceUntilIdle()
        val between = first.stored.value
        first.job.cancel()

        val next = Phone(this, between, onPhone = setOf(EngineOption.FUTO_74))
        advanceUntilIdle()
        assertEquals(listOf("fetch PARAKEET"), next.downloads.calls)
        next.job.cancel()
    }

    @Test
    fun `a restart while the small model downloads fetches it again over any network`() = runTest {
        val first = Phone(this, viaSmall)
        advanceUntilIdle()
        first.job.cancel()
        val next = Phone(this, first.stored.value)
        advanceUntilIdle()
        assertEquals(listOf("fetch FUTO_74 on any network"), next.downloads.calls)
        next.downloads.arrives(EngineOption.FUTO_74)
        advanceUntilIdle()
        assertEquals(listOf("fetch FUTO_74 on any network", "fetch PARAKEET"), next.downloads.calls)
        next.job.cancel()
    }

    @Test
    fun `another recognition chosen while the small model downloads lets it go and deletes it`() = watched(viaSmall) { phone ->
        phone.stored.update { FirstRun.chosen(it.withEngine(EngineConfig.fromPreset(EnginePresets.GROQ)), EnginePresets.GROQ.id) }
        advanceUntilIdle()
        assertEquals(EnginePresets.GROQ.id, phone.stored.value.activeEngineId)
        assertNull(FirstRun.wanted(phone.stored.value))
        assertEquals(listOf("fetch FUTO_74 on any network", "let go FUTO_74", "delete FUTO_74"), phone.downloads.calls)
        // Neither arriving later changes anything.
        phone.downloads.arrives(EngineOption.FUTO_74)
        phone.downloads.arrives(EngineOption.PARAKEET)
        advanceUntilIdle()
        assertEquals(EnginePresets.GROQ.id, phone.stored.value.activeEngineId)
    }

    @Test
    fun `another recognition chosen while parakeet downloads stays, and the small one is deleted`() = watched(viaSmall) { phone ->
        phone.downloads.arrives(EngineOption.FUTO_74)
        advanceUntilIdle()
        phone.stored.update { FirstRun.chosen(it, android) }
        advanceUntilIdle()
        assertEquals(android, phone.stored.value.activeEngineId)
        assertEquals(listOf("fetch FUTO_74 on any network", "fetch PARAKEET", "let go PARAKEET", "delete FUTO_74"), phone.downloads.calls)
        phone.downloads.arrives(EngineOption.PARAKEET)
        advanceUntilIdle()
        assertEquals(android, phone.stored.value.activeEngineId)
    }

    @Test
    fun `the small model chosen by the user stays and is never deleted`() = watched(viaSmall) { phone ->
        phone.downloads.arrives(EngineOption.FUTO_74)
        advanceUntilIdle()
        phone.stored.update { FirstRun.chosen(it, whisper) }
        advanceUntilIdle()
        phone.downloads.arrives(EngineOption.PARAKEET)
        advanceUntilIdle()
        assertEquals(whisper, phone.stored.value.activeEngineId)
        assertEquals(listOf("fetch FUTO_74 on any network", "fetch PARAKEET", "let go PARAKEET"), phone.downloads.calls)
        assertTrue(phone.stored.value.engines.any { it.id == whisper })
    }

    @Test
    fun `parakeet chosen by the user keeps its download, and the small one goes once it is there`() = watched(viaSmall) { phone ->
        phone.downloads.arrives(EngineOption.FUTO_74)
        advanceUntilIdle()
        phone.stored.update { FirstRun.use(it, EngineOption.PARAKEET, TEST_PHONE) }
        advanceUntilIdle()
        assertEquals(listOf("fetch FUTO_74 on any network", "fetch PARAKEET"), phone.downloads.calls)
        phone.downloads.arrives(EngineOption.PARAKEET)
        advanceUntilIdle()
        assertEquals(parakeet, phone.stored.value.activeEngineId)
        assertEquals(listOf("fetch FUTO_74 on any network", "fetch PARAKEET", "delete FUTO_74"), phone.downloads.calls)
    }

    @Test
    fun `a replay starts nothing`() = watched(viaSmall.copy(firstRun = fresh.firstRun)) { phone ->
        assertEquals(emptyList(), phone.downloads.calls)
    }

    @Test
    fun `a phone too small for parakeet fetches the small model and deletes nothing`() =
        watched(FirstRun.quick(fresh, QuickPlan(EngineOption.FUTO_74, null, nowMissing = true), emptySet(), TEST_PHONE, finish = true)) { phone ->
            assertEquals(listOf("fetch FUTO_74"), phone.downloads.calls)
            phone.downloads.arrives(EngineOption.FUTO_74)
            advanceUntilIdle()
            assertNull(FirstRun.wanted(phone.stored.value))
            assertEquals(whisper, phone.stored.value.activeEngineId)
            assertEquals(listOf("fetch FUTO_74"), phone.downloads.calls)
        }

    @Test
    fun `settings from 0_33 - what waits behind android's is fetched and takes over`() = watched(waiting) { phone ->
        assertEquals(listOf("fetch PARAKEET"), phone.downloads.calls)
        assertEquals(android, phone.stored.value.activeEngineId)

        phone.downloads.arrives(EngineOption.PARAKEET)
        advanceUntilIdle()
        assertEquals(parakeet, phone.stored.value.activeEngineId)
        assertNull(phone.stored.value.firstRun.pendingEngine)
        assertEquals(listOf("fetch PARAKEET"), phone.downloads.calls)
    }

    @Test
    fun `a model that is there already takes over at once`() = watched(waiting, onPhone = setOf(EngineOption.PARAKEET)) { phone ->
        assertEquals(parakeet, phone.stored.value.activeEngineId)
    }

    @Test
    fun `a recognition chosen meanwhile stays and the held download is let go`() = watched(waiting) { phone ->
        phone.stored.update { it.withEngine(EngineConfig.fromPreset(EnginePresets.GROQ)).copy(activeEngineId = EnginePresets.GROQ.id) }
        advanceUntilIdle()
        assertEquals(EnginePresets.GROQ.id, phone.stored.value.activeEngineId)
        assertNull(phone.stored.value.firstRun.pendingEngine)
        assertEquals(listOf("fetch PARAKEET", "let go PARAKEET"), phone.downloads.calls)

        // Its model arriving later changes nothing.
        phone.downloads.arrives(EngineOption.PARAKEET)
        advanceUntilIdle()
        assertEquals(EnginePresets.GROQ.id, phone.stored.value.activeEngineId)
    }

    @Test
    fun `the download goes on for someone who made the waiting recognition active themselves`() = watched(waiting) { phone ->
        phone.stored.update { FirstRun.use(it, EngineOption.PARAKEET, TEST_PHONE) }
        advanceUntilIdle()
        assertNull(phone.stored.value.firstRun.pendingEngine)
        assertEquals(EngineOption.PARAKEET, FirstRun.wanted(phone.stored.value))
        assertEquals(listOf("fetch PARAKEET"), phone.downloads.calls)

        phone.downloads.arrives(EngineOption.PARAKEET)
        advanceUntilIdle()
        assertNull(FirstRun.wanted(phone.stored.value))
        assertEquals(parakeet, phone.stored.value.activeEngineId)
        assertEquals(listOf("fetch PARAKEET"), phone.downloads.calls)
    }

    /**
     * A phone without room or a CPU for the small model: quick setup or Skip make Parakeet
     * active before its model is there. The process dies (after Skip no service holds it), and
     * the next one has to take the download up again - nothing else would.
     */
    @Test
    fun `a model wanted for the active recognition is fetched again after the process died`() = watched(activeAtOnce) { phone ->
        assertEquals(listOf("fetch PARAKEET"), phone.downloads.calls)
        assertEquals(parakeet, phone.stored.value.activeEngineId)
        assertEquals(EngineOption.PARAKEET, FirstRun.wanted(phone.stored.value))

        phone.downloads.arrives(EngineOption.PARAKEET)
        advanceUntilIdle()
        assertEquals(parakeet, phone.stored.value.activeEngineId)
        assertNull(FirstRun.wanted(phone.stored.value))
        assertEquals(listOf("fetch PARAKEET"), phone.downloads.calls)
    }

    @Test
    fun `quick setup during the process starts the downloads`() = watched(fresh) { phone ->
        phone.stored.value = viaSmall
        advanceUntilIdle()
        assertEquals(listOf("fetch FUTO_74 on any network"), phone.downloads.calls)
    }

    @Test
    fun `quick setup without a stand-in starts the download in the same process`() = watched(fresh) { phone ->
        phone.stored.value = activeAtOnce
        advanceUntilIdle()
        assertEquals(listOf("fetch PARAKEET"), phone.downloads.calls)
    }

    @Test
    fun `a wanted model is let go when another recognition becomes the active one`() = watched(activeAtOnce) { phone ->
        phone.stored.update { it.withEngine(EngineConfig.fromPreset(EnginePresets.GROQ)).copy(activeEngineId = EnginePresets.GROQ.id) }
        advanceUntilIdle()
        assertNull(FirstRun.wanted(phone.stored.value))
        assertEquals(EnginePresets.GROQ.id, phone.stored.value.activeEngineId)
        assertEquals(listOf("fetch PARAKEET", "let go PARAKEET"), phone.downloads.calls)
    }

    @Test
    fun `another model wanted in its place gets a round of its own`() = watched(activeAtOnce) { phone ->
        val small = FirstRun.quick(fresh, QuickPlan(EngineOption.FUTO_244, null, nowMissing = true), emptySet(), TEST_PHONE, finish = true)
        phone.stored.value = small
        advanceUntilIdle()
        assertEquals(listOf("fetch PARAKEET", "let go PARAKEET", "fetch FUTO_244"), phone.downloads.calls)
        assertEquals(EngineOption.FUTO_244, FirstRun.wanted(phone.stored.value))

        // Parakeet arriving after all does not settle the Whisper model's wait.
        phone.downloads.arrives(EngineOption.PARAKEET)
        advanceUntilIdle()
        assertEquals(EngineOption.FUTO_244, FirstRun.wanted(phone.stored.value))
        phone.downloads.arrives(EngineOption.FUTO_244)
        advanceUntilIdle()
        assertNull(FirstRun.wanted(phone.stored.value))
        assertEquals(small.activeEngine, phone.stored.value.activeEngine)
    }

    @Test
    fun `android chosen by the user stays, and the download for the dropped model is let go`() = watched(waiting) { phone ->
        phone.stored.update { FirstRun.chosen(it, android) }
        advanceUntilIdle()
        assertEquals(listOf("fetch PARAKEET", "let go PARAKEET"), phone.downloads.calls)

        phone.downloads.arrives(EngineOption.PARAKEET)
        advanceUntilIdle()
        assertEquals(android, phone.stored.value.activeEngineId)
        assertNull(FirstRun.wanted(phone.stored.value))
    }

    @Test
    fun `another model chosen in the introduction lets the first one go`() = watched(fresh) { phone ->
        phone.stored.update { FirstRun.use(it, EngineOption.PARAKEET, TEST_PHONE) }
        advanceUntilIdle()
        phone.stored.update { FirstRun.use(it, EngineOption.FUTO_244, TEST_PHONE) }
        advanceUntilIdle()
        assertEquals(listOf("fetch PARAKEET", "let go PARAKEET", "fetch FUTO_244"), phone.downloads.calls)
        assertEquals(EngineOption.FUTO_244, FirstRun.wanted(phone.stored.value))
    }

    @Test
    fun `a waiting name this build does not know is dropped without a download`() =
        watched(fresh.copy(firstRun = fresh.firstRun.copy(pendingEngine = "GONE"))) { phone ->
            assertNull(phone.stored.value.firstRun.pendingEngine)
            assertEquals(emptyList(), phone.downloads.calls)
        }

    @Test
    fun `a wanted name this build does not know is dropped without a download`() =
        watched(fresh.copy(firstRun = fresh.firstRun.copy(wantedModel = "GONE"))) { phone ->
            assertNull(phone.stored.value.firstRun.wantedModel)
            assertEquals(emptyList(), phone.downloads.calls)
        }

    /** The process died between the switch and the deletion: the next one deletes it. */
    @Test
    fun `a small model left over is deleted at the next start`() {
        val switched = FirstRun.settle(FirstRun.settle(viaSmall, true, TEST_PHONE), true, TEST_PHONE)
        watched(switched, onPhone = setOf(EngineOption.FUTO_74, EngineOption.PARAKEET)) { phone ->
            assertEquals(listOf("delete FUTO_74"), phone.downloads.calls)
            assertNull(phone.stored.value.firstRun.standIn)
        }
    }
}
