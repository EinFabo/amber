package app.amethyst.data

import android.content.Context
import app.amethyst.core.insert.InsertionStrategy
import app.amethyst.core.insert.StrategyMemory

/** Persists [StrategyMemory] - which insertion method worked in which app. */
class StrategyStore(context: Context) {

    private val prefs = context.getSharedPreferences("amethyst_insertion", Context.MODE_PRIVATE)

    fun load(): StrategyMemory {
        val restored = prefs.all.mapNotNull { (pkg, raw) ->
            val strategy = (raw as? String)?.let { name ->
                InsertionStrategy.entries.firstOrNull { it.name == name }
            }
            strategy?.let { pkg to it }
        }.toMap()
        return StrategyMemory(restored)
    }

    fun save(memory: StrategyMemory) {
        prefs.edit().clear().apply {
            memory.snapshot().forEach { (pkg, strategy) -> putString(pkg, strategy.name) }
        }.apply()
    }
}
