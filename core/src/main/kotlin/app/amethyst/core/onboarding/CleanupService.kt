package app.amethyst.core.onboarding

import app.amethyst.core.engine.AmberAddress
import app.amethyst.core.engine.AmberCatalog
import app.amethyst.core.engine.AmberCatalogs
import app.amethyst.core.engine.ConfigIssue
import app.amethyst.core.engine.EngineConfigValidator
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.KeyBinding
import app.amethyst.core.engine.ModelList
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.engine.Servers
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.EngineConfig

/** Which service a typed API key is, by its shape. */
sealed interface ServiceKey {
    data object Blank : ServiceKey

    /** An Anthropic key ("sk-ant-"): Claude, for the cleanup only - its address and models are known. */
    data object Claude : ServiceKey

    /** Groq or OpenAI, told by the key's prefix: address and models are known. */
    data class Known(val quick: Servers.Quick) : ServiceKey

    /** Neither - an own server, an Amber PC, another cloud service: its address says where it goes. */
    data object Unknown : ServiceKey
}

/**
 * The two fields of the sheet that adds an own server: its address and its API key.
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

/** What an own server's answer to "which models?" comes to. */
sealed interface ServiceAnswer {
    /** An Amber PC or Amber Server: its own speech and cleanup models, the recommended ones in the plan. */
    data class Ready(val plan: ServicePlan) : ServiceAnswer

    /** An Amber that offers no chat model to clean up with. */
    data object NoCleanupModel : ServiceAnswer

    /** Another server listed its models: one is picked. */
    data class Pick(val baseUrl: String, val models: List<String>) : ServiceAnswer

    /** No list to pick from - the model's name is typed. [answered]: the server answered, with an empty list. */
    data class Type(val baseUrl: String, val answered: Boolean) : ServiceAnswer
}

/**
 * The server engine a cleanup runs on, and its chat model: what Use on a service's sheet comes
 * to ([CleanupServices]). [isNew]: the server is not in the settings yet. [keyGiven]: a key was
 * given just now for this address, so it is bound to it - else the server keeps the binding it
 * has.
 */
data class ServiceSetup(
    val engine: EngineConfig,
    val cleanupModel: String,
    val catalog: AmberCatalog?,
    val isNew: Boolean,
    val keyGiven: Boolean,
)

/**
 * A cleanup's server from a key or an address: the same server the settings would set up under
 * Speech recognition, made right where the cleanup is chosen. A known service is told by its
 * key or its address; an own server is asked which models it has. The list of services and
 * what Use writes are [CleanupServices]'.
 */
object CleanupService {

    fun read(key: String): ServiceKey {
        val k = key.trim()
        if (k.isEmpty()) return ServiceKey.Blank
        if (Servers.isClaudeKey(k)) return ServiceKey.Claude
        return Servers.quickForKey(k)?.let(ServiceKey::Known) ?: ServiceKey.Unknown
    }

    /** A known service: its address, its first speech model, its recommended chat model. */
    fun known(quick: Servers.Quick): ServicePlan = ServicePlan(
        baseUrl = quick.baseUrl,
        speechModel = quick.modelId,
        cleanupModel = quick.provider.cleanupModels.first { it.recommended }.id,
    )

    /** Claude: Anthropic's address, no speech model, the recommended chat model. */
    fun claude(): ServicePlan = ServicePlan(
        baseUrl = EnginePresets.ANTHROPIC_BASE_URL,
        speechModel = null,
        cleanupModel = ServerProviders.ANTHROPIC.cleanupModels.first { it.recommended }.id,
    )

    /** What is wrong with a typed own server's address; empty when its model list may be asked for. */
    fun addressProblems(address: String): List<ConfigIssue> =
        EngineConfigValidator.addressProblems(probe(address))

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
    const val MODELS_SHOWN = 8

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
     * Without a key given just now ([keyGiven] false) a server already there keeps its binding:
     * a key stored for another host is never taken along to this one ([KeyBinding]).
     */
    fun setup(
        settings: AmethystSettings,
        plan: ServicePlan,
        phone: List<String>,
        keyGiven: Boolean,
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
        val bound = if (keyGiven || existing == null) KeyBinding.bind(engine) else engine
        return ServiceSetup(bound, plan.cleanupModel, plan.catalog, isNew = existing == null, keyGiven = keyGiven)
    }

    private fun probe(address: String): EngineConfig =
        Servers.newServer("probe", null).copy(baseUrl = address.trim().takeIf { it.isNotEmpty() })
}
