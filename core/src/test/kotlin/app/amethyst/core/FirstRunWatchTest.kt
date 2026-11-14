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
import kotlin.test.assertNull

/** The watch over the recognition that waits for its model, with the downloads faked. */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstRunWatchTest {

    private val fresh = AmethystSettings.defaults(TEST_PHONE)
    private val android = EnginePresets.ANDROID_ON_DEVICE.id
    private val parakeet = EnginePresets.LOCAL_PARAKEET.id
    private val waiting = FirstRun.quick(fresh, QuickPlan(EngineOption.ANDROID, EngineOption.PARAKEET), emptySet(), TEST_PHONE, finish = true)

    private val activeAtOnce = FirstRun.quick(fresh, QuickPlan(EngineOption.PARAKEET, null, nowMissing = true), emptySet(), TEST_PHONE, finish = true)

    private class Downloads : ModelFetch {
        val onPhone = MutableStateFlow(emptySet<EngineOption>())
        val calls = mutableListOf<String>()

        override fun ready(option: EngineOption): Flow<Boolean> = onPhone.map { option in it }

        override suspend fun fetch(option: EngineOption) {
            calls += "fetch ${option.name}"
        }

        override suspend fun letGo(option: EngineOption) {
            calls += "let go ${option.name}"
        }

        fun arrives(option: EngineOption) = onPhone.update { it + option }
    }

    private class Phone(scope: TestScope, start: AmethystSettings) {
        val stored = MutableStateFlow(start)
        val downloads = Downloads()
        private val watch = FirstRunWatch(stored, { transform -> stored.update(transform) }, downloads, { TEST_PHONE })
        val job: Job = scope.launch { watch.run() }
    }

    private fun watched(start: AmethystSettings, body: suspend TestScope.(Phone) -> Unit) = runTest {
        val phone = Phone(this, start)
        advanceUntilIdle()
        body(phone)
        phone.job.cancel()
    }

    @Test
    fun `nothing waiting asks nothing of the downloads`() = watched(fresh) { phone ->
        assertEquals(emptyList(), phone.downloads.calls)
        assertEquals(fresh, phone.stored.value)
    }

    @Test
    fun `what waits is fetched at the start of the process and takes over when it is there`() = watched(waiting) { phone ->
        assertEquals(listOf("fetch PARAKEET"), phone.downloads.calls)
        assertEquals(android, phone.stored.value.activeEngineId)

        phone.downloads.arrives(EngineOption.PARAKEET)
        advanceUntilIdle()
        assertEquals(parakeet, phone.stored.value.activeEngineId)
        assertNull(phone.stored.value.firstRun.pendingEngine)
        assertEquals(listOf("fetch PARAKEET"), phone.downloads.calls)
    }

    @Test
    fun `a model that is there already takes over at once`() = runTest {
        val phone = Phone(this, waiting).also { it.downloads.arrives(EngineOption.PARAKEET) }
        advanceUntilIdle()
        assertEquals(parakeet, phone.stored.value.activeEngineId)
        phone.job.cancel()
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
     * A phone without Android's on-device recognition: quick setup or Skip make Parakeet active
     * before its model is there. The process dies (after Skip no service holds it), and the
     * next one has to take the download up again - nothing else would.
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

    @Test
    fun `quick setup during the process starts the wait`() = watched(fresh) { phone ->
        phone.stored.value = waiting
        advanceUntilIdle()
        assertEquals(listOf("fetch PARAKEET"), phone.downloads.calls)
    }
}
