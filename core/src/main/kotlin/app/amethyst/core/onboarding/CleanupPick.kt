package app.amethyst.core.onboarding

import app.amethyst.core.engine.AmberCatalogs
import app.amethyst.core.engine.CatalogFetch
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.engine.Servers
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.EngineConfig

/**
 * The introduction's cleanup choice: off, Tinfoil - the one server that fits a first run, one
 * key away - another service from its key ([CleanupService]), or, on a replay, a cleanup set up
 * elsewhere kept as it is. Continue on an unchanged choice must never rewrite the setting.
 */
enum class CleanupPick {
    OFF, TINFOIL, SERVICE, KEEP;

    /**
     * The settings once the step's Continue is pressed; [keyReady] says a Tinfoil key exists,
     * [service] is the server another service's key and address came to. A Tinfoil engine
     * added here starts in the [phone]'s first language, like any new engine.
     */
    fun applyTo(
        settings: AmethystSettings,
        keyReady: Boolean,
        phone: List<String>,
        service: ServiceSetup? = null,
    ): AmethystSettings = when {
        this == OFF -> settings.copy(cleanup = null)
        this == TINFOIL && keyReady -> {
            val tinfoil = EnginePresets.TINFOIL
            val engine = settings.engines.firstOrNull { it.id == tinfoil.id }
                ?: EngineConfig.fromPreset(tinfoil, languageTag = EngineConfig.defaultLanguageTag(tinfoil.kind, phone))
            // A Tinfoil model chosen earlier stays; only a new Tinfoil cleanup gets the recommended one.
            val model = settings.cleanup?.takeIf { it.engineId == tinfoil.id }?.model
                ?: ServerProviders.TINFOIL.cleanupModels.first { it.recommended }.id
            settings.withEngine(engine).copy(cleanup = CleanupChoice(tinfoil.id, model))
        }
        this == SERVICE && service != null -> {
            val engine = service.engine
            val added = settings.withEngine(engine)
            val listed = service.catalog?.let { AmberCatalogs.apply(added, engine.id, engine.baseUrl.orEmpty(), CatalogFetch.Found(it)) } ?: added
            // Named and bound as the settings store keeps it.
            Servers.normalized(listed.copy(cleanup = CleanupChoice(engine.id, service.cleanupModel)))
        }
        // Tinfoil or another service without a key, or a cleanup kept as it is: nothing changes.
        else -> settings
    }

    companion object {
        fun initial(settings: AmethystSettings): CleanupPick {
            val current = settings.cleanup ?: return OFF
            return if (current.engineId == EnginePresets.TINFOIL.id) TINFOIL else KEEP
        }
    }
}
