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
    private fun roomy(sdk: Int = 35) = Memory(sdk = sdk, lowRamDevice = false, lowMemory = false, availBytes = 3_500 * mb, thresholdBytes = 600 * mb, totalBytes = 7_400 * mb)

    /** A phone with 6 GB: Android reports a little less, the kernel keeps its share. */
    private fun sixGb() = roomy().copy(totalBytes = 5_700 * mb)

    @Test
    fun `with plenty of memory the model stays its three idle minutes`() {
        assertEquals(Wait.IDLE, ModelRelease.afterDictation(roomy(), whisper))
        assertEquals(Wait.IDLE, ModelRelease.afterDictation(roomy(), 874 * mb), "the largest Whisper model too")
        assertEquals(3 * 60 * 1000L, Wait.IDLE.ms)
    }

    @Test
    fun `Android 13 and lower keep the idle release - the trim callback still warns there`() {
        val tight = Memory(sdk = 33, lowRamDevice = true, lowMemory = true, availBytes = 100 * mb, thresholdBytes = 200 * mb, totalBytes = 2_000 * mb)
        assertEquals(Wait.IDLE, ModelRelease.afterDictation(tight, whisper))
    }

    @Test
    fun `Android saying memory is low frees it at once`() {
        assertEquals(Wait.NOW, ModelRelease.afterDictation(roomy().copy(lowMemory = true), whisper))
        assertEquals(Wait.NOW, ModelRelease.afterDictation(roomy().copy(availBytes = 600 * mb), whisper), "at the threshold")
        assertEquals(0L, Wait.NOW.ms)
    }

    @Test
    fun `on a phone of 6 GB or less, less room above the threshold than the model and a margin frees it soon`() {
        val memory = sixGb().copy(availBytes = 600 * mb + whisper + 100 * mb)
        assertEquals(Wait.SOON, ModelRelease.afterDictation(memory, whisper))
        assertEquals(30_000L, Wait.SOON.ms)
        // The same room is plenty for the small model.
        assertEquals(Wait.IDLE, ModelRelease.afterDictation(memory, 81 * mb))
        // Just enough room: no change.
        assertEquals(Wait.IDLE, ModelRelease.afterDictation(sixGb().copy(availBytes = 600 * mb + whisper + ModelRelease.MARGIN_BYTES), whisper))
    }

    @Test
    fun `an 8 GB phone keeps the model its idle minutes although little reads as available`() {
        // Pixel 9a, API 37, Parakeet (2026-11-23): availMem counts cached processes as
        // used, so it read 531-731 MB after every dictation - and the model went after 30 s,
        // each next dictation reading it again for 3.5-3.9 s.
        val parakeet = 670 * mb
        for (available in listOf(531L, 605L, 731L)) {
            val pixel = Memory(sdk = 37, lowRamDevice = false, lowMemory = false, availBytes = available * mb, thresholdBytes = 226 * mb, totalBytes = 7_400 * mb)
            assertEquals(Wait.IDLE, ModelRelease.afterDictation(pixel, parakeet), "available $available MB")
            assertEquals(Wait.IDLE, ModelRelease.beforeDictation(pixel, parakeet), "available $available MB")
        }
        // Android saying it is low on memory still frees it there.
        val low = Memory(sdk = 37, lowRamDevice = false, lowMemory = true, availBytes = 300 * mb, thresholdBytes = 226 * mb, totalBytes = 7_400 * mb)
        assertEquals(Wait.NOW, ModelRelease.afterDictation(low, parakeet))
    }

    @Test
    fun `a 3 GB phone gives it back soon when room is tight, and up to Android 13 waits for the trim call`() {
        val parakeet = 670 * mb
        // Samsung A20e: 3 GB, Android 11 - RUNNING_CRITICAL still comes there.
        val a20e = Memory(sdk = 30, lowRamDevice = false, lowMemory = false, availBytes = 700 * mb, thresholdBytes = 200 * mb, totalBytes = 2_800 * mb)
        assertEquals(Wait.IDLE, ModelRelease.afterDictation(a20e, parakeet))
        // The same phone on Android 14: room 500 MB, less than the model and the margin.
        assertEquals(Wait.SOON, ModelRelease.afterDictation(a20e.copy(sdk = 34), parakeet))
        // A phone that does not say how much it has is taken for a small one.
        assertEquals(Wait.SOON, ModelRelease.afterDictation(roomy().copy(availBytes = 900 * mb, totalBytes = 0), whisper))
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
