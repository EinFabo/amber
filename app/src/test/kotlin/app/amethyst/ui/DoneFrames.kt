package app.amethyst.ui

import app.amethyst.core.engine.EngineOption
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.ModelDownload
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.ui.onboarding.DoneStep
import org.junit.Test

/** The last step: the bubble finishing over the title, the summary's rows coming in. */
class DoneFrames : TimelineFrames() {

    private val settings = AmethystSettings.defaults(listOf("en-US"))
        .withEngine(EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET))
        .copy(activeEngineId = EnginePresets.LOCAL_PARAKEET.id)

    /** The rows on their way in and the fuse running; the fuse further round; the burst; at rest. */
    @Test
    fun finished() = frames("6-done", 0.25f, 0.5f, 0.7f, 1f) { DoneStep(settings, null, null) {} }

    @Test
    fun finishedLight() = frames("6-done", 0.5f, 0.7f, dark = false, languages = listOf("de")) { DoneStep(settings, null, null) {} }

    /** A model still downloading: the rim shows how far it is. */
    @Test
    fun downloading() = frames("6-done-downloading", 1f) {
        DoneStep(settings, ModelDownload.Running(520_000_000, 670_000_000), null) {}
    }

    /** The same on the quick way, where Android's recognition stands in meanwhile. */
    @Test
    fun pending() = frames("6-done-pending", 1f, dark = false) {
        DoneStep(settings, null, PendingModel(EngineOption.PARAKEET, ModelDownload.Running(300_000_000, 670_000_000), failed = false) {}) {}
    }
}
