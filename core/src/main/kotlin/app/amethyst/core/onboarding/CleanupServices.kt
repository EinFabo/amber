package app.amethyst.core.onboarding

import app.amethyst.core.engine.AmberCatalog
import app.amethyst.core.engine.AmberCatalogs
import app.amethyst.core.engine.CatalogFetch
import app.amethyst.core.engine.ConfigIssue
import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.EnginePresets.ModelChoice
import app.amethyst.core.engine.KeyBinding
import app.amethyst.core.engine.ServerProvider
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.engine.Servers
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppCleanup
import app.amethyst.core.settings.AppCleanupMode
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.CleanupChoice
import app.amethyst.core.settings.EngineConfig

/** Which service a row of the cleanup's list stands for; [SERVER] is any own server. */
enum class ServiceSlot { TINFOIL, CLAUDE, GROQ, OPENAI, SERVER }

/** How far a row is from cleaning up. */
enum class ServiceState {
    READY,

    /** Only its API key is missing, or was given for another address. */
    NEEDS_KEY,

    /** Something else stands in the way - an address that cannot be asked, Tinfoil off its enclave ([ServiceRow.issues]). */
    NEEDS_SETUP,
}

/**
 * One row of the cleanup's service list: Tinfoil, Claude, Groq and OpenAI always, set up or
 * not, and every own server that is. [engine] is the server it runs on, null while a known
 * service has none yet; [provider] names it and lists its models.
 */
data class ServiceRow(
    val slot: ServiceSlot,
    val provider: ServerProvider,
    val engine: EngineConfig?,
    val state: ServiceState,
    /** A key is stored that may go to this row's address. */
    val keyStored: Boolean,
    /** What stands in the way besides the key. */
    val issues: List<ConfigIssue> = emptyList(),
    /**
     * A stored key that was given for another address ([ConfigIssue.KeyForOtherAddress]): said
     * on the sheet, since "needs a key" alone would not explain why the one there does not count.
     */
    val keyElsewhere: ConfigIssue.KeyForOtherAddress? = null,
) {
    /** Told apart in a list: the server's id, or the service's name while it has none. */
    val id: String get() = engine?.id ?: slot.name

    /**
     * A known service asks for its key; an own server may need none - unless the key it has
     * was given for another address, which has to be given again for this one.
     */
    val keyRequired: Boolean get() = slot != ServiceSlot.SERVER || keyElsewhere != null
}

/** What a key typed into a service's sheet looks like. */
sealed interface KeyVerdict {
    data object Fine : KeyVerdict

    /** Claude's sheet, and the key is not Anthropic's ("sk-ant-"). */
    data object NotClaude : KeyVerdict

    /** By its shape the key is [slot]'s, not the sheet's. */
    data class Other(val slot: ServiceSlot) : KeyVerdict
}

/**
 * The cleanup's services as one list, and what choosing one writes - the same in Settings →
 * Cleanup, the introduction's cleanup step and one app's own model. A service needs no detour
 * through the speech recognition's page: Groq, OpenAI and Claude are set up from their key
 * right here, as the server the settings would add for them.
 */
object CleanupServices {

    private val KNOWN = listOf(
        ServiceSlot.TINFOIL to ServerProviders.TINFOIL,
        ServiceSlot.CLAUDE to ServerProviders.ANTHROPIC,
        ServiceSlot.GROQ to ServerProviders.GROQ,
        ServiceSlot.OPENAI to ServerProviders.OPENAI,
    )

    /** The known service at [address], null for an own server's. */
    fun slotAt(address: String?): ServiceSlot? {
        val provider = ServerProviders.forUrl(address?.trim())
        return KNOWN.firstOrNull { it.second === provider }?.first
    }

    fun providerOf(slot: ServiceSlot): ServerProvider =
        KNOWN.firstOrNull { it.first == slot }?.second ?: ServerProviders.CUSTOM

    /**
     * The list, top to bottom: Tinfoil, Claude, Groq, OpenAI - one row each, or one for every
     * server pointed at it - then the own servers in the order they were added.
     *
     * Tinfoil's row is its engine's whatever its address: an engine that requires its enclave
     * (or has Tinfoil's id) pointed elsewhere, from settings of long ago, is still Tinfoil and
     * says what is wrong with it - never an own server, never another service's row.
     */
    fun rows(settings: AmethystSettings, hasKey: (String?) -> Boolean): List<ServiceRow> {
        val servers = settings.engines.filter { it.kind == EngineKind.OPENAI_COMPATIBLE }
        fun slotOf(engine: EngineConfig): ServiceSlot = when {
            engine.requireEnclave || engine.id == EnginePresets.TINFOIL.id -> ServiceSlot.TINFOIL
            else -> slotAt(engine.baseUrl) ?: ServiceSlot.SERVER
        }
        val bySlot = servers.groupBy(::slotOf)
        val known = KNOWN.flatMap { (slot, provider) ->
            bySlot[slot]?.map { row(slot, provider, it, hasKey) } ?: listOf(notSetUp(slot, provider, hasKey))
        }
        val own = bySlot[ServiceSlot.SERVER].orEmpty().map { row(ServiceSlot.SERVER, ServerProviders.forEngine(it, settings), it, hasKey) }
        return known + own
    }

    /** The row [choice] runs on, if its server is still there. */
    fun rowOf(rows: List<ServiceRow>, choice: CleanupChoice?): ServiceRow? =
        choice?.let { c -> rows.firstOrNull { it.engine?.id == c.engineId } }

    private fun row(slot: ServiceSlot, provider: ServerProvider, engine: EngineConfig, hasKey: (String?) -> Boolean): ServiceRow {
        val has = hasKey(engine.apiKeyAlias)
        val issues = EngineConfigValidator.validate(engine, has).filterNot(EngineConfigValidator::onlyAboutSpeech)
        val beyondKey = issues.filterNot { it == ConfigIssue.ApiKeyMissing || it is ConfigIssue.KeyForOtherAddress }
        return ServiceRow(
            slot = slot,
            provider = provider,
            engine = engine,
            state = when {
                issues.isEmpty() -> ServiceState.READY
                beyondKey.isEmpty() -> ServiceState.NEEDS_KEY
                else -> ServiceState.NEEDS_SETUP
            },
            keyStored = engine.apiKeyAlias != null && has && KeyBinding.allows(engine),
            issues = beyondKey,
            keyElsewhere = issues.firstNotNullOfOrNull { it as? ConfigIssue.KeyForOtherAddress },
        )
    }

    /**
     * A known service without a server yet. Only Tinfoil can have a key all the same: its
     * engine was removed and the key, stored under the preset's name, stayed.
     */
    private fun notSetUp(slot: ServiceSlot, provider: ServerProvider, hasKey: (String?) -> Boolean): ServiceRow {
        val stored = slot == ServiceSlot.TINFOIL && hasKey(EngineConfig.fromPreset(EnginePresets.TINFOIL).apiKeyAlias)
        return ServiceRow(slot, provider, engine = null, state = if (stored) ServiceState.READY else ServiceState.NEEDS_KEY, keyStored = stored)
    }

    /** A service's cleanup models as its sheet lists them: the recommended one first. */
    fun listed(provider: ServerProvider): List<ModelChoice> = provider.cleanupModels.sortedByDescending { it.recommended }

    /**
     * The model [row]'s sheet starts with: the one [inUse] on this very server, else the
     * service's recommended one, else its first. Null when the service lists none - an own
     * server is asked, or the name is typed.
     */
    fun preselected(row: ServiceRow, inUse: CleanupChoice?): String? {
        val engine = row.engine
        if (engine != null && inUse?.engineId == engine.id && inUse.model.isNotBlank()) return inUse.model
        return listed(row.provider).firstOrNull()?.id
    }

    /**
     * A key typed into [slot]'s sheet. One that by its shape belongs to another known service
     * must not be sent to this one; an own server ([ServiceSlot.SERVER]) may take any - there
     * [KeyVerdict.Other] is a hint, since many services copy OpenAI's key shape.
     */
    fun keyVerdict(slot: ServiceSlot, typed: String): KeyVerdict {
        val named = when (val read = CleanupService.read(typed)) {
            ServiceKey.Blank -> return KeyVerdict.Fine
            ServiceKey.Claude -> ServiceSlot.CLAUDE
            is ServiceKey.Known -> KNOWN.firstOrNull { it.second === read.quick.provider }?.first
            ServiceKey.Unknown -> null
        }
        return when {
            named != null && named != slot -> KeyVerdict.Other(named)
            slot == ServiceSlot.CLAUDE && named == null -> KeyVerdict.NotClaude
            else -> KeyVerdict.Fine
        }
    }

    /** Whether [verdict] keeps a key from being used in [slot]'s sheet. */
    fun blocks(slot: ServiceSlot, verdict: KeyVerdict): Boolean = when (verdict) {
        KeyVerdict.Fine -> false
        KeyVerdict.NotClaude -> true
        is KeyVerdict.Other -> slot != ServiceSlot.SERVER
    }

    /**
     * What Use on [row]'s sheet sets up, with [model]: the row's own server - read again from
     * [settings] by its id, since two may share an address and a row may be a moment old - or,
     * for a known service without one, the server the settings would add: Tinfoil from its
     * preset, Claude, Groq and OpenAI as [CleanupService.setup] makes them.
     *
     * Null when nothing usable would come of it: no model, a row that is not ready and gets no
     * key now, a server removed meanwhile, an own server that is not there yet (its address
     * comes first, see [CleanupService.setup]). So a setup is always one the cleanup can run on.
     *
     * [keyGiven]: a key was typed just now, for this address - only then is a server already
     * there bound to it; a new one is bound to where it is. A key stored for another host is never taken along ([KeyBinding]). [catalog] is the
     * list an Amber PC or Amber Server just sent, kept with the server.
     */
    fun setup(
        settings: AmethystSettings,
        row: ServiceRow,
        model: String,
        keyGiven: Boolean,
        phone: List<String>,
        catalog: AmberCatalog? = null,
        random: () -> Int = { (0..0xffffff).random() },
    ): ServiceSetup? {
        val chosen = model.trim().takeIf { it.isNotEmpty() } ?: return null
        val usable = row.state == ServiceState.READY || (row.state == ServiceState.NEEDS_KEY && keyGiven)
        if (!usable) return null
        if (row.engine != null) {
            val engine = settings.engines.firstOrNull { it.id == row.engine.id } ?: return null
            if (!sameAddress(engine, row.engine)) return null
            val aliased = if (engine.apiKeyAlias == null) engine.copy(apiKeyAlias = "key.${engine.id}") else engine
            return ServiceSetup(if (keyGiven) KeyBinding.bind(aliased) else aliased, chosen, catalog, isNew = false, keyGiven = keyGiven)
        }
        return when (row.slot) {
            ServiceSlot.TINFOIL -> {
                val tinfoil = EnginePresets.TINFOIL
                // Its row has no engine only while none has its id ([rows]).
                if (settings.engines.any { it.id == tinfoil.id }) return null
                val engine = EngineConfig.fromPreset(tinfoil, EngineConfig.defaultLanguageTag(tinfoil.kind, phone))
                ServiceSetup(engine, chosen, null, isNew = true, keyGiven = keyGiven)
            }
            ServiceSlot.CLAUDE -> CleanupService.setup(settings, CleanupService.claude().copy(cleanupModel = chosen), phone, keyGiven, random)
            ServiceSlot.GROQ, ServiceSlot.OPENAI -> {
                val quick = Servers.QUICK.firstOrNull { it.provider === providerOf(row.slot) } ?: return null
                CleanupService.setup(settings, CleanupService.known(quick).copy(cleanupModel = chosen), phone, keyGiven, random)
            }
            ServiceSlot.SERVER -> null
        }
    }

    /** The choice [setup] comes to. */
    fun choice(setup: ServiceSetup): CleanupChoice = CleanupChoice(setup.engine.id, setup.cleanupModel)

    /**
     * [settings] with [setup]'s server in them, and an Amber's list kept; named and bound as the
     * settings store keeps it. The cleanup itself is the caller's to set: [forEveryApp] or
     * [forApp].
     *
     * [settings] may be newer than the ones [setup] was made from - a key's test takes a
     * moment, an Amber's list arrives in between. So a server already there is not replaced by
     * the older copy: it keeps what it is and only gets what Use gives it - a place for its
     * key, the binding of a key given now, a speech model if it had none. Null when the setup
     * no longer fits: its server was removed, or points elsewhere now - a key must not follow
     * it there, and nothing is written.
     */
    fun added(settings: AmethystSettings, setup: ServiceSetup): AmethystSettings? {
        val live = settings.engines.firstOrNull { it.id == setup.engine.id }
        val engine = when {
            live == null -> if (setup.isNew) setup.engine else return null
            !sameAddress(live, setup.engine) -> return null
            else -> {
                val merged = live.copy(
                    apiKeyAlias = live.apiKeyAlias ?: setup.engine.apiKeyAlias,
                    modelId = live.modelId?.takeIf { it.isNotBlank() } ?: setup.engine.modelId,
                )
                if (setup.keyGiven) KeyBinding.bind(merged) else merged
            }
        }
        val with = settings.withEngine(engine)
        val listed = setup.catalog?.let { AmberCatalogs.apply(with, engine.id, engine.baseUrl.orEmpty(), CatalogFetch.Found(it)) } ?: with
        return Servers.normalized(listed)
    }

    /** [setup] as the cleanup for every app; [settings] as they are when it no longer fits ([added]). */
    fun forEveryApp(settings: AmethystSettings, setup: ServiceSetup): AmethystSettings =
        added(settings, setup)?.copy(cleanup = choice(setup)) ?: settings

    /**
     * [setup] as [packageName]'s own model. An app whose profile is gone meanwhile gets none
     * back, and no server is added for it.
     */
    fun forApp(settings: AmethystSettings, setup: ServiceSetup, packageName: String): AmethystSettings {
        if (settings.profiles.none { it.packageName == packageName }) return settings
        val with = added(settings, setup) ?: return settings
        val own = AppCleanup(AppCleanupMode.MODEL, choice(setup))
        return with.copy(profiles = AppProfiles.edit(with.profiles, packageName) { it.copy(appCleanup = own) })
    }

    /** Both at one address - the same but for the case of scheme and host; none is no address. */
    private fun sameAddress(a: EngineConfig, b: EngineConfig): Boolean {
        val first = a.baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val second = b.baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return AmberCatalog.fingerprint(first) == AmberCatalog.fingerprint(second)
    }
}
