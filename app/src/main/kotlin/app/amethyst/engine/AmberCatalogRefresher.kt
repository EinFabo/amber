package app.amethyst.engine

import android.os.SystemClock
import android.util.Log
import app.amethyst.core.engine.AmberCatalog
import app.amethyst.core.engine.AmberCatalogs
import app.amethyst.core.engine.CatalogFetch
import app.amethyst.core.engine.CatalogThrottle
import app.amethyst.core.net.HttpTransport
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.EngineConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Keeps the model lists of Amber PCs and Amber Servers fresh: asks an own server's
 * `GET /models` after its engine is saved or passes a connection test, and when a page that
 * shows the lists opens - at most once a minute per engine. What decides and what an answer
 * does lives in [AmberCatalogs]; this only schedules it, off the main thread.
 */
class AmberCatalogRefresher(
    private val scope: CoroutineScope,
    private val transport: HttpTransport,
    /** Decrypts the engine's key; called on a background thread. */
    private val keyFor: (String?) -> String?,
    private val settings: () -> AmethystSettings,
    private val update: suspend ((AmethystSettings) -> AmethystSettings) -> Unit,
    private val throttle: CatalogThrottle = CatalogThrottle(clock = SystemClock::elapsedRealtime),
) {

    /** Every own server's list, as the Recognition or Cleanup page opens. */
    fun refreshAll() {
        settings().engines.forEach { refresh(it) }
    }

    /** [engine]'s list, if it is an own server; [force] skips the once-a-minute limit. */
    fun refresh(engine: EngineConfig, force: Boolean = false) {
        if (!AmberCatalogs.isCandidate(engine)) return
        val baseUrl = engine.baseUrl?.trim() ?: return
        if (!throttle.claim(engine.id, baseUrl, force)) return
        scope.launch(Dispatchers.IO) {
            val outcome = AmberCatalog.fetch(transport, baseUrl, keyFor(engine.apiKeyAlias))
            // Never the address: it carries the server's key.
            if (outcome is CatalogFetch.Failed) Log.i("Amber", "model list of ${engine.id}: ${outcome.detail}")
            try {
                update { AmberCatalogs.apply(it, engine.id, baseUrl, outcome) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Amber", "model list of ${engine.id} not stored", e)
            }
        }
    }
}
