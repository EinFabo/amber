package app.amethyst.core

import app.amethyst.core.engine.ModelRelease
import app.amethyst.core.engine.ModelRelease.Memory
import app.amethyst.core.engine.ModelRelease.Trim
import app.amethyst.core.engine.ModelRelease.Wait
import kotlin.test.Test
import kotlin.test.assertEquals

/** When the speech model on the phone is given back: as before with room to spare, sooner when memory runs short. */
class ModelReleaseTest {

    private val mb = 1_000_000L
    private val whisper = 574 * mb

    /** A Pixel 9a's memory on a quiet day: 8 GB, gigabytes free. */
    private fun roomy(sdk: Int = 35) = Memory(sdk = sdk, lowRamDevice = false, lowMemory = false, availBytes = 3_500 * mb, thresholdBytes = 600 * mb)

    @Test
    fun `with plenty of memory the model stays its three idle minutes`() {
        assertEquals(Wait.IDLE, ModelRelease.afterDictation(roomy(), whisper))
        assertEquals(Wait.IDLE, ModelRelease.afterDictation(roomy(), 874 * mb), "the largest Whisper model too")
        assertEquals(3 * 60 * 1000L, Wait.IDLE.ms)
    }

    @Test
    fun `Android 13 and lower keep the idle release - the trim callback still warns there`() {
        val tight = Memory(sdk = 33, lowRamDevice = true, lowMemory = true, availBytes = 100 * mb, thresholdBytes = 200 * mb)
        assertEquals(Wait.IDLE, ModelRelease.afterDictation(tight, whisper))
    }

    @Test
    fun `Android saying memory is low frees it at once`() {
        assertEquals(Wait.NOW, ModelRelease.afterDictation(roomy().copy(lowMemory = true), whisper))
        assertEquals(Wait.NOW, ModelRelease.afterDictation(roomy().copy(availBytes = 600 * mb), whisper), "at the threshold")
        assertEquals(0L, Wait.NOW.ms)
    }

    @Test
    fun `less room above the threshold than the model and a margin frees it soon`() {
        val memory = roomy().copy(availBytes = 600 * mb + whisper + 100 * mb)
        assertEquals(Wait.SOON, ModelRelease.afterDictation(memory, whisper))
        assertEquals(30_000L, Wait.SOON.ms)
        // The same room is plenty for the small model.
        assertEquals(Wait.IDLE, ModelRelease.afterDictation(memory, 81 * mb))
        // Just enough room: no change.
        assertEquals(Wait.IDLE, ModelRelease.afterDictation(roomy().copy(availBytes = 600 * mb + whisper + ModelRelease.MARGIN_BYTES), whisper))
    }

    @Test
    fun `a low-RAM phone frees it soon whatever is free right now`() {
        assertEquals(Wait.SOON, ModelRelease.afterDictation(roomy().copy(lowRamDevice = true), whisper))
    }

    @Test
    fun `nothing loaded or nothing known about memory changes nothing`() {
        assertEquals(Wait.IDLE, ModelRelease.afterDictation(roomy().copy(lowMemory = true), 0))
        assertEquals(Wait.IDLE, ModelRelease.afterDictation(null, whisper))
    }

    @Test
    fun `a model loading for a dictation about to start is never freed at once`() {
        assertEquals(Wait.SOON, ModelRelease.beforeDictation(roomy().copy(lowMemory = true), whisper))
        assertEquals(Wait.SOON, ModelRelease.beforeDictation(roomy().copy(lowRamDevice = true), whisper))
        assertEquals(Wait.IDLE, ModelRelease.beforeDictation(roomy(), whisper))
    }

    @Test
    fun `real memory pressure frees it on every Android`() {
        for (sdk in listOf(28, 33, 34, 36)) {
            assertEquals(Trim.RELEASE, ModelRelease.onTrim(ModelRelease.TRIM_RUNNING_CRITICAL, sdk))
            assertEquals(Trim.RELEASE, ModelRelease.onTrim(ModelRelease.TRIM_COMPLETE, sdk))
        }
    }

    @Test
    fun `screens going away look at memory again from Android 14 on, and change nothing before`() {
        assertEquals(Trim.REVIEW, ModelRelease.onTrim(ModelRelease.TRIM_UI_HIDDEN, 34))
        assertEquals(Trim.REVIEW, ModelRelease.onTrim(ModelRelease.TRIM_BACKGROUND, 35))
        assertEquals(Trim.NONE, ModelRelease.onTrim(ModelRelease.TRIM_UI_HIDDEN, 33))
        assertEquals(Trim.NONE, ModelRelease.onTrim(ModelRelease.TRIM_BACKGROUND, 33))
        // RUNNING_MODERATE and RUNNING_LOW: only the bubble's own idle release, as before.
        assertEquals(Trim.NONE, ModelRelease.onTrim(5, 33))
        assertEquals(Trim.NONE, ModelRelease.onTrim(10, 33))
    }
}
