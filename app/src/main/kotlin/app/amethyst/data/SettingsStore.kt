package app.amethyst.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.amethyst.core.engine.Servers
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.SettingsCodec
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * A settings file that cannot be read starts over empty rather than crashing Amber at every
 * start: the defaults are a working app, and the keys live in their own store.
 */
private val Context.amethystDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "amethyst",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Settings live as one JSON blob rather than as individual preference keys, so the schema
 * is owned by :core (where it is unit-tested) instead of being spread across the app.
 */
class SettingsStore(private val context: Context) {

    val settings: Flow<AmethystSettings> =
        context.amethystDataStore.data
            // The disk failing under the file (full, pulled, a read error) is no reason to die:
            // the defaults stand in until it reads again.
            .catch { failure ->
                if (failure !is IOException) throw failure
                Log.w(TAG, "settings not readable: ${failure.javaClass.simpleName}")
                emit(emptyPreferences())
            }
            .map { prefs -> SettingsCodec.decode(prefs[KEY].orEmpty(), PhoneLocales.tags()) }

    /**
     * A write that fails - a full disk - is dropped and logged, not thrown: these writes come
     * from everywhere, a drag of the bubble included, on a scope nobody watches.
     */
    suspend fun update(transform: (AmethystSettings) -> AmethystSettings) {
        try {
            context.amethystDataStore.edit { prefs ->
                val current = SettingsCodec.decode(prefs[KEY].orEmpty(), PhoneLocales.tags())
                prefs[KEY] = SettingsCodec.encode(Servers.normalized(transform(current)))
            }
        } catch (failure: IOException) {
            Log.w(TAG, "settings not written: ${failure.javaClass.simpleName}")
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("settings_json")
        const val TAG = "Amber"
    }
}
