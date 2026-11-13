package app.amethyst.core.settings

import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.onboarding.EngineName
import app.amethyst.core.onboarding.EngineNames

/**
 * A speech recognition as a row of a list that says where it runs beside it: the Speech
 * recognition page (grouped by place), the fallback's choices and an app's own recognition
 * (place as the note). The name leaves the place out, and the note never shows a model's id -
 * that is on the recognition's own page.
 */
object RecognitionRows {

    /**
     * The row's name: the introduction's where it has one ("Parakeet TDT", "Whisper small",
     * Android's recognizer). A Whisper model the introduction does not offer is "Whisper";
     * any other goes by its own name, which the app shows without its bracketed note
     * ([EngineName.Own]: "Tinfoil", a server as its owner named it).
     */
    fun name(engine: EngineConfig): EngineName =
        EngineNames.optionOf(engine)?.let(EngineNames::of)
            ?: if (engine.id == EnginePresets.LOCAL_WHISPER.id) EngineName.Plain("Whisper") else EngineName.Own(engine)

    /**
     * What the row says under the name: a server's model by the name its service gives it
     * (none for a model the service does not list - an id says nothing at a glance), that
     * Android's recognizer ends at a pause, and the wait measured on this phone
     * ([medianWaitMs], from the statistics).
     */
    fun note(engine: EngineConfig, settings: AmethystSettings, medianWaitMs: Long?): RecognitionNote = RecognitionNote(
        model = engine.takeIf { it.kind == EngineKind.OPENAI_COMPATIBLE }
            ?.let { ServerProviders.forEngine(it, settings).speechModel(it.modelId)?.label },
        stopsAtPause = engine.kind == EngineKind.ANDROID_ON_DEVICE,
        typicalMs = medianWaitMs,
    )
}

/** A recognition row's note in parts; the app words and joins them. Nothing to say: [isEmpty]. */
data class RecognitionNote(val model: String?, val stopsAtPause: Boolean, val typicalMs: Long?) {
    val isEmpty: Boolean get() = model == null && !stopsAtPause && typicalMs == null
}
