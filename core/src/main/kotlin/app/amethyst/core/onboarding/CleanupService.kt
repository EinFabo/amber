package app.amethyst.core.onboarding

import app.amethyst.core.engine.AmberAddress
import app.amethyst.core.engine.AmberCatalog
import app.amethyst.core.engine.AmberCatalogs
import app.amethyst.core.engine.ConfigIssue
import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.KeyBinding
import app.amethyst.core.engine.ModelList
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

    /** Neither - an own server, an Amber PC, another cloud service: its address is asked for. */
    data object Unknown : ServiceKey
}

/**
 * The step's two fields: the API key, and the address asked for when the key names no service.
 * An Amber PC's pairing link or its address with the key in its path, typed into either, goes
 * apart as on a server's page: the address without the key, the key into its own field - so a
 * key never ends up in the stored address. An address pasted into the key field moves over.
 */
data class ServiceFields(val key: String = "", val address: String = "") {
    fun withKey(typed: String): ServiceFields {
        AmberAddress.split(typed)?.let { return ServiceFields(it.key, it.baseUrl) }
        val t = typed.trim()
        if (t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)) return ServiceFields("", t)
        return copy(key = typed)
    }

    fun withAddress(typed: String): ServiceFields =
        AmberAddress.split(typed)?.let { ServiceFields(it.key, it.baseUrl) } ?: copy(address = typed)
}

/**
 * Where a cleanup server is and its models: the chat model for the cleanup, and a speech model
 * when the server is known to have one - an own server used for the cleanup only has none.
 */
data class ServicePlan(
    val baseUrl: String,
    val speechModel: String?,
    val cleanupModel: String,
    /** The model list an Amber PC or Amber Server sent, kept as the settings keep it. */
    val catalog: AmberCatalog? = null,
)

/** What pressing Continue on "Another service" does with the two fields. */
sealed interface ServiceRoute {
    /** A known service, by its key or its address: written at once. */
    data class Write(val plan: ServicePlan) : ServiceRoute

    /** An own server: asked for its models first. */
    data class Check(val address: String, val key: String) : ServiceRoute

    /** The address cannot be asked yet. */
    data class Fix(val problems: List<ConfigIssue>) : ServiceRoute

    /** No key and no address, or a Claude key alone: on without a cleanup. */
    data object Skip : ServiceRoute
}

/** What an own server's answer to "which models?" comes to. */
sealed interface ServiceAnswer {
    /** An Amber PC or Amber Server: its own speech and cleanup models, written at once. */
    data class Ready(val plan: ServicePlan) : ServiceAnswer

    /** An Amber that offers no chat model to clean up with. */
    data object NoCleanupModel : ServiceAnswer

    /** Another server listed its models: one is picked. */
    data class Pick(val baseUrl: String, val models: List<String>) : ServiceAnswer

    /** No list to pick from - the model's name is typed. [answered]: the server answered, with an empty list. */
    data class Type(val baseUrl: String, val answered: Boolean) : ServiceAnswer
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

    /**
     * Whether the address field shows: for a key that names no service, when the user asked
     * for another address ([otherAddress]), or when one is in it already.
     */
    fun asksAddress(fields: ServiceFields, otherAddress: Boolean): Boolean =
        otherAddress || fields.address.isNotBlank() || read(fields.key) == ServiceKey.Unknown

    /** Where Continue goes from [fields]. A key is optional at an own server ([Servers.keyRequired]). */
    fun route(fields: ServiceFields, otherAddress: Boolean): ServiceRoute {
        val key = read(fields.key)
        if (!asksAddress(fields, otherAddress)) {
            return if (key is ServiceKey.Known) ServiceRoute.Write(known(key.quick)) else ServiceRoute.Skip
        }
        knownAt(fields.address)?.let { return ServiceRoute.Write(it) }
        val problems = addressProblems(fields.address)
        if (problems.isNotEmpty()) return ServiceRoute.Fix(problems)
        return ServiceRoute.Check(fields.address.trim(), fields.key.trim())
    }

    /** What is wrong with a typed own server's address; empty when its model list may be asked for. */
    fun addressProblems(address: String): List<ConfigIssue> =
        EngineConfigValidator.addressProblems(probe(address))

    /** A known service's address typed by hand: set up as its key would have it, no list asked for. */
    fun knownAt(address: String): ServicePlan? {
        val provider = ServerProviders.forUrl(address.trim())
        return Servers.QUICK.firstOrNull { it.provider === provider }?.let(::known)
    }

    /** [list], the own server at [baseUrl]'s answer to `GET /models`. */
    fun answer(baseUrl: String, list: ModelList): ServiceAnswer {
        val address = baseUrl.trim()
        return when (list) {
            is ModelList.Amber -> {
                val catalog = list.catalog
                val speech = AmberCatalogs.speechModelToPreselect(null, catalog)
                val cleanup = (catalog.cleanup.firstOrNull { it.recommended } ?: catalog.cleanup.firstOrNull())?.id
                if (cleanup == null) ServiceAnswer.NoCleanupModel
                else ServiceAnswer.Ready(ServicePlan(address, speech, cleanup, catalog))
            }
            is ModelList.Plain -> if (list.ids.isEmpty()) ServiceAnswer.Type(address, answered = true) else ServiceAnswer.Pick(address, list.ids)
            is ModelList.Failed -> ServiceAnswer.Type(address, answered = false)
        }
    }

    /** Rows a server's list shows at most; a longer one is searched first. */
    const val MODELS_SHOWN = 12

    /** Whether [models] is too long to show whole: a search field comes over it. */
    fun searchable(models: List<String>): Boolean = models.size > MODELS_SHOWN

    /** The rows shown for [query]: a short list whole, a long one's matches, at most [MODELS_SHOWN]. */
    fun shownModels(models: List<String>, query: String): List<String> {
        if (!searchable(models)) return models
        val q = query.trim()
        return models.filter { q.isEmpty() || it.contains(q, ignoreCase = true) }.take(MODELS_SHOWN)
    }

    /** A model picked from a server's list - only one on it. */
    fun picked(answer: ServiceAnswer.Pick, model: String): ServicePlan? =
        model.trim().takeIf { it in answer.models }?.let { ServicePlan(answer.baseUrl.trim(), null, it) }

    /** An own server's chat model, picked from its list or typed; null while none is given. */
    fun chosen(baseUrl: String, model: String): ServicePlan? =
        model.trim().takeIf { it.isNotEmpty() }?.let { ServicePlan(baseUrl.trim(), null, it) }

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
