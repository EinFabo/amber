package androidx.datastore.preferences.core

import androidx.datastore.core.DataStore

abstract class Preferences internal constructor() {
    class Key<T> internal constructor(val name: String)

    abstract operator fun <T> get(key: Key<T>): T?
    abstract fun <T> contains(key: Key<T>): Boolean
    abstract fun asMap(): Map<Key<*>, Any>
}

class MutablePreferences internal constructor() : Preferences() {
    override fun <T> get(key: Key<T>): T? = null
    override fun <T> contains(key: Key<T>): Boolean = false
    override fun asMap(): Map<Key<*>, Any> = emptyMap()
    operator fun <T> set(key: Key<T>, value: T) = Unit
    fun <T> remove(key: Key<T>): T? = null
    fun clear() = Unit
}

fun stringPreferencesKey(name: String): Preferences.Key<String> = throw UnsupportedOperationException()

suspend fun DataStore<Preferences>.edit(
    transform: suspend (MutablePreferences) -> Unit,
): Preferences = throw UnsupportedOperationException()
