package app.amethyst.data

import android.content.Context
import app.amethyst.core.insert.InsertionStrategy
import app.amethyst.core.insert.StrategyMemory

/**
 * Persists [StrategyMemory] - which insertion method worked in which app. While [writing] says
 * no (the security mode, [app.amethyst.core.privacy.SecurityMode.remembersApps]) the memory
 * lives in this process only and nothing is written; [forget] drops it everywhere.
 */
class StrategyStore(context: Context, private val writing: () -> Boolean = { true }) {

    private val prefs = context.getSharedPreferences("amethyst_insertion", Context.MODE_PRIVATE)

    /** The one memory every insertion uses, read from the file the first time it is needed. */
    val memory: StrategyMemory by lazy { load() }

    private fun load(): StrategyMemory {
        val restored = prefs.all.mapNotNull { (pkg, raw) ->
            val strategy = (raw as? String)?.let { name ->
                InsertionStrategy.entries.firstOrNull { it.name == name }
            }
            strategy?.let { pkg to it }
        }.toMap()
        return StrategyMemory(restored)
    }

    fun save(memory: StrategyMemory) {
        if (!writing()) return
        prefs.edit().clear().apply {
            memory.snapshot().forEach { (pkg, strategy) -> putString(pkg, strategy.name) }
        }.apply()
    }

    /** Forgets every app, in memory and in the file. Blocking: not on the main thread. */
    fun forget() {
        memory.clear()
        prefs.edit().clear().commit()
    }

    /**
     * [forget], only when the file still names an app - at a start with the security mode on,
     * for what an earlier switch left ([app.amethyst.core.privacy.SecurityMode.repairsAtStart]).
     * True when something was there. Blocking: not on the main thread.
     */
    fun forgetIfAny(): Boolean {
        if (prefs.all.isEmpty()) return false
        forget()
        return true
    }
}
