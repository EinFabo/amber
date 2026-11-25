package app.amethyst.core

import app.amethyst.core.engine.ModelSweep
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelSweepTest {

    private val current = setOf("base-q5", "small-q5", "eigene-datei", "parakeet-tdt-0.6b-v3-int8")

    @Test
    fun `current slots, a picked model and the current Parakeet folder stay`() {
        val files = listOf("base-q5.bin", "small-q5.bin", "eigene-datei.bin", "parakeet-tdt-0.6b-v3-int8/")
        assertEquals(emptyList(), ModelSweep.stale(files, current))
    }

    @Test
    fun `an unfinished part of a current model stays so the download resumes`() {
        val files = listOf("small-q5.bin.part", "small-q5.bin.part.from", "eigene-datei.bin.part")
        assertEquals(emptyList(), ModelSweep.stale(files, current))
    }

    @Test
    fun `a model that left the catalog, with its part, and an old Parakeet folder go`() {
        val files = listOf(
            "tiny-q5.bin", "tiny-q5.bin.part", "tiny-q5.bin.part.from",
            "parakeet-tdt-0.6b-v2-int8/", "base-q5.bin",
        )
        assertEquals(
            listOf("tiny-q5.bin", "tiny-q5.bin.part", "tiny-q5.bin.part.from", "parakeet-tdt-0.6b-v2-int8/"),
            ModelSweep.stale(files, current),
        )
    }

    @Test
    fun `anything not recognized stays`() {
        val files = listOf("notes.txt", "whisper.bin.bak", "other-folder/", "model-guard/", ".nomedia", "x.deleting")
        assertEquals(emptyList(), ModelSweep.stale(files, current))
    }

    @Test
    fun `an id kept for any reason, such as one an engine still names, stays`() {
        assertEquals(emptyList(), ModelSweep.stale(listOf("tiny-q5.bin"), current + "tiny-q5"))
    }

    @Test
    fun `nothing is swept when no current model is known`() {
        assertEquals(emptyList(), ModelSweep.stale(listOf("base-q5.bin", "parakeet-old/"), emptySet()))
    }
}
