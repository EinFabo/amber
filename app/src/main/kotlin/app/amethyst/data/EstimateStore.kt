package app.amethyst.data

import android.content.Context
import app.amethyst.core.engine.RecognitionEstimate

/**
 * Persists what [RecognitionEstimate] learned - how long recognition, upload and cleanup take
 * per engine and model on this phone - so the progress ring is right from the first dictation
 * after a restart. Rates only: engine and model ids, never text or audio.
 */
class EstimateStore(context: Context) {

    private val prefs = context.getSharedPreferences("amethyst_estimates", Context.MODE_PRIVATE)

    fun load(): Map<String, Double> =
        prefs.all.mapNotNull { (key, raw) -> (raw as? String)?.toDoubleOrNull()?.let { key to it } }.toMap()

    fun save(rates: Map<String, Double>) {
        prefs.edit().clear().apply {
            rates.forEach { (key, rate) -> putString(key, rate.toString()) }
        }.apply()
    }
}
