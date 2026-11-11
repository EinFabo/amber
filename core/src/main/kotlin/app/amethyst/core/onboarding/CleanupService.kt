package app.amethyst.core.onboarding

import app.amethyst.core.engine.AmberAddress
import app.amethyst.core.engine.AmberCatalog
import app.amethyst.core.engine.AmberCatalogs
import app.amethyst.core.engine.CatalogFetch
import app.amethyst.core.engine.ConfigIssue
import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.KeyBinding
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.engine.Servers
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.EngineConfig

/** What the introduction's "Another service" makes of a pasted API key. */
sealed interface ServiceKey {
    data object Blank : ServiceKey

    /** An Anthropic key ("sk-ant-"): Amber has no way to talk to Claude yet. */
    data object Claude : ServiceKey

    /** Groq or OpenAI, told by the key's prefix: address and models are known. */
    data class Known(val quick: Servers.Quick) : ServiceKey

    /** Neither - an own server, an Amber PC: its address is asked for. */
    data object Unknown : ServiceKey
}

/**
 * The step's two fields: the API key, and the address asked for when the key names no service.
 * An Amber PC's pairing link or its address with the key in its path, typed into either, goes
 * apart as on a server's page: the address without the key, the key into its own field - so a
 * key never ends up in the stored address.
 */
data class ServiceFields(val key: String = "", val address: String = "") {
    fun withKey(typed: String): ServiceFields =
        AmberAddress.split(typed)?.let { ServiceFields(it.key, it.baseUrl) } ?: copy(key = typed)

    fun withAddress(typed: String): ServiceFields =
        AmberAddress.split(typed)?.let { ServiceFields(it.key, it.baseUrl) } ?: copy(address = typed)
}

/** Where a cleanup server is and which two models it gets: speech (every server has one) and chat. */
data class ServicePlan(
    val baseUrl: String,
    val speechModel: String,
    val cleanupModel: String,
    /** The model list an Amber PC or Amber Server sent, kept as the settings keep it. */
    val catalog: AmberCatalog? = null,
)

/** What an own server's answer to "which models?" comes to. */
sealed interface ServiceAnswer {
    data class Ready(val plan: ServicePlan) : ServiceAnswer

    /** A server, but not an Amber one: its models have to be typed, in the settings. */
    data object NotAmber : ServiceAnswer

    /** An Amber that offers no chat model to clean up with. */
    data object NoCleanupModel : ServiceAnswer

    /** No answer, a refusal, or nothing readable. */
    data object NoAnswer : ServiceAnswer
}

/** The server engine a cleanup runs on, and its chat model: what [CleanupPick.SERVICE] writes. */
data class ServiceSetup(val engine: EngineConfig, val cleanupModel: String, val catalog: AmberCatalog?)

/**
 * The introduction's cleanup on another service: the same server the settings would set up
 * (Speech recognition → add a server, then its chat model on the Cleanup page), from one key -
 * and an address when the key does not say where it goes.
 */
object CleanupService {

    fun read(key: String): ServiceKey {
        val k = key.trim()
        if (k.isEmpty()) return ServiceKey.Blank
        if (k.startsWith("sk-ant-")) return ServiceKey.Claude
        return Servers.quickForKey(k)?.let(ServiceKey::Known) ?: ServiceKey.Unknown
    }

    /** A known service: its address, its first speech model, its recommended chat model. */
    fun known(quick: Servers.Quick): ServicePlan = ServicePlan(
        baseUrl = quick.baseUrl,
        speechModel = quick.modelId,
        cleanupModel = quick.provider.cleanupModels.first { it.recommended }.id,
    )

    /** What is wrong with a typed own server's address; empty when its model list may be asked for. */
    fun addressProblems(address: String): List<ConfigIssue> =
        EngineConfigValidator.addressProblems(probe(address))

    /** A known service's address typed by hand: set up as its key would have it, no list asked for. */
    fun knownAt(address: String): ServicePlan? {
        val provider = ServerProviders.forUrl(address.trim())
        return Servers.QUICK.firstOrNull { it.provider === provider }?.let(::known)
    }

    /** [outcome] of asking the own server at [baseUrl] for its models. */
    fun answer(baseUrl: String, outcome: CatalogFetch): ServiceAnswer = when (outcome) {
        is CatalogFetch.Found -> {
            val catalog = outcome.catalog
            val speech = AmberCatalogs.speechModelToPreselect(null, catalog)
            val cleanup = (catalog.cleanup.firstOrNull { it.recommended } ?: catalog.cleanup.firstOrNull())?.id
            if (speech == null || cleanup == null) ServiceAnswer.NoCleanupModel
            else ServiceAnswer.Ready(ServicePlan(baseUrl.trim(), speech, cleanup, catalog))
        }
        CatalogFetch.NotAmber -> ServiceAnswer.NotAmber
        is CatalogFetch.Failed -> ServiceAnswer.NoAnswer
    }

    /**
     * The server for [plan]: one already at that address (its speech model kept), else a new
     * one in the [phone]'s first language - with the key about to be stored bound to its host.
     */
    fun setup(
        settings: AmethystSettings,
        plan: ServicePlan,
        phone: List<String>,
        random: () -> Int = { (0..0xffffff).random() },
    ): ServiceSetup {
        val address = AmberCatalog.fingerprint(plan.baseUrl)
        val existing = settings.engines.firstOrNull { Servers.isServer(it) && !it.baseUrl.isNullOrBlank() && AmberCatalog.fingerprint(it.baseUrl) == address }
        val base = existing?.let { it.copy(apiKeyAlias = it.apiKeyAlias ?: "key.${it.id}") }
            ?: Servers.newServer(
                Servers.newId(random, taken = settings.engines.map { it.id }.toSet()),
                EngineConfig.defaultLanguageTag(EngineKind.OPENAI_COMPATIBLE, phone),
            )
        val engine = base.copy(baseUrl = plan.baseUrl, modelId = base.modelId?.takeIf { it.isNotBlank() } ?: plan.speechModel)
        return ServiceSetup(KeyBinding.bind(engine), plan.cleanupModel, plan.catalog)
    }

    private fun probe(address: String): EngineConfig =
        Servers.newServer("probe", null).copy(baseUrl = address.trim().takeIf { it.isNotEmpty() })
}
