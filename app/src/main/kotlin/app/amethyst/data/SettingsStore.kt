package app.amethyst.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.SettingsCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.amethystDataStore: DataStore<Preferences> by preferencesDataStore(name = "amethyst")

/**
 * Settings live as one JSON blob rather than as individual preference keys, so the schema
 * is owned by :core (where it is unit-tested) instead of being spread across the app.
 */
class SettingsStore(private val context: Context) {

    val settings: Flow<AmethystSettings> =
        context.amethystDataStore.data.map { prefs -> SettingsCodec.decode(prefs[KEY].orEmpty()) }

    suspend fun update(transform: (AmethystSettings) -> AmethystSettings) {
        context.amethystDataStore.edit { prefs ->
            val current = SettingsCodec.decode(prefs[KEY].orEmpty())
            prefs[KEY] = SettingsCodec.encode(transform(current))
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("settings_json")
    }
}
