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
 * shows the lists opens - at most once a minute per engine - and notes in a list when its
 * server refused a compressed upload. What decides and what an answer does lives in
 * [AmberCatalogs]; this only schedules it, off the main thread.
 */
class AmberCatalogRefresher(
    private val scope: CoroutineScope,
    private val transport: HttpTransport,
    /** Decrypts the engine's key, for its bound host only; called on a background thread. */
    private val keyFor: (EngineConfig) -> String?,
    private val settings: () -> AmethystSettings,
    private val update: suspend ((AmethystSettings) -> AmethystSettings) -> Unit,
    private val throttle: CatalogThrottle = CatalogThrottle(clock = SystemClock::elapsedRealtime),
) {

    /** Every own server's list, as the Recognition or Cleanup page opens. */
    fun refreshAll() {
        settings().engines.forEach { refresh(it) }
    }

    /**
     * [engine]'s list, if it is an own server; [force] skips the once-a-minute limit, but not
     * a fetch of the same address that is still running.
     */
    fun refresh(engine: EngineConfig, force: Boolean = false) {
        if (!AmberCatalogs.isCandidate(engine)) return
        val baseUrl = engine.baseUrl?.trim() ?: return
        if (!throttle.claim(engine.id, baseUrl, force)) return
        scope.launch(Dispatchers.IO) {
            try {
                val outcome = AmberCatalog.fetch(transport, baseUrl, keyFor(engine))
                // Never the address: it carries the server's key.
                if (outcome is CatalogFetch.Failed) Log.i("Amber", "model list of ${engine.id}: ${outcome.detail}")
                update { AmberCatalogs.apply(it, engine.id, baseUrl, outcome) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Amber", "model list of ${engine.id} not stored", e)
            } finally {
                throttle.finished(engine.id, baseUrl)
            }
        }
    }

    /**
     * [engineId]'s server at [baseUrl] answered a compressed upload with 400 although its list
     * offered it: WAV for that engine until its list is fetched again.
     */
    fun compressionRefused(engineId: String, baseUrl: String) {
        // Never the address: it carries the server's key.
        Log.i("Amber", "$engineId refused a compressed upload - WAV until its model list is fetched again")
        scope.launch(Dispatchers.IO) {
            try {
                update { AmberCatalogs.compressionRefused(it, engineId, baseUrl) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Amber", "refused compression of $engineId not stored", e)
            }
        }
    }
}
