package app.nocturne.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.nocturne.core.settings.NocturneSettings
import app.nocturne.core.settings.SettingsCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.nocturneDataStore: DataStore<Preferences> by preferencesDataStore(name = "nocturne")

/**
 * Settings live as one JSON blob rather than as individual preference keys, so the schema
 * is owned by :core (where it is unit-tested) instead of being spread across the app.
 */
class SettingsStore(private val context: Context) {

    val settings: Flow<NocturneSettings> =
        context.nocturneDataStore.data.map { prefs -> SettingsCodec.decode(prefs[KEY].orEmpty()) }

    suspend fun update(transform: (NocturneSettings) -> NocturneSettings) {
        context.nocturneDataStore.edit { prefs ->
            val current = SettingsCodec.decode(prefs[KEY].orEmpty())
            prefs[KEY] = SettingsCodec.encode(transform(current))
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("settings_json")
    }
}
