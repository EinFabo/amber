package app.amethyst.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.amethyst.core.engine.Servers
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.SettingsCodec
import app.amethyst.core.settings.SettingsRescue
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
 *
 * A blob this build cannot read is never written over: [SettingsRescue] copies it aside first
 * and brings it back once a build reads it again. [noted] gets a line for Diagnostics when that
 * happens.
 */
class SettingsStore(private val context: Context, private val noted: (String) -> Unit = {}) {

    val settings: Flow<AmethystSettings> =
        context.amethystDataStore.data
            // The disk failing under the file (full, pulled, a read error) is no reason to die:
            // the defaults stand in until it reads again.
            .catch { failure ->
                if (failure !is IOException) throw failure
                Log.w(TAG, "settings not readable: ${failure.javaClass.simpleName}")
                emit(emptyPreferences())
            }
            // The same answer the next write starts from: a copy that reads again shows at once.
            .map { prefs -> SettingsRescue.of(stored(prefs), PhoneLocales.tags()).settings }

    /**
     * A write that fails - a full disk - is dropped and logged, not thrown: these writes come
     * from everywhere, a drag of the bubble included, on a scope nobody watches.
     */
    suspend fun update(transform: (AmethystSettings) -> AmethystSettings) {
        try {
            context.amethystDataStore.edit { prefs ->
                val current = rescue(prefs)
                prefs[KEY] = SettingsCodec.encode(Servers.normalized(transform(current)))
            }
        } catch (failure: IOException) {
            Log.w(TAG, "settings not written: ${failure.javaClass.simpleName}")
        }
    }

    /**
     * Once at start: unreadable settings go aside before anything writes, a copy that reads
     * again comes back - and Diagnostics hears of it even when nothing changes a setting.
     */
    suspend fun rescueAtStart() {
        try {
            context.amethystDataStore.edit { prefs -> rescue(prefs) }
        } catch (failure: IOException) {
            Log.w(TAG, "settings not checked: ${failure.javaClass.simpleName}")
        }
    }

    /** Puts [SettingsRescue]'s answer in place within the edit; the settings a change starts from. */
    private fun rescue(prefs: MutablePreferences): AmethystSettings {
        val outcome = SettingsRescue.of(stored(prefs), PhoneLocales.tags())
        outcome.store?.let { store ->
            store.main.let { if (it == null) prefs.remove(KEY) else prefs[KEY] = it }
            store.backup.let { if (it == null) prefs.remove(BACKUP_KEY) else prefs[BACKUP_KEY] = it }
        }
        outcome.event?.let { event ->
            Log.w(TAG, event.line)
            noted(event.line)
        }
        return outcome.settings
    }

    private fun stored(prefs: Preferences) = SettingsRescue.Stored(prefs[KEY], prefs[BACKUP_KEY])

    private companion object {
        val KEY = stringPreferencesKey("settings_json")

        /** The settings as they were when this build could not read them ([SettingsRescue]). */
        val BACKUP_KEY = stringPreferencesKey("settings_json_unreadable")
        const val TAG = "Embersay"
    }
}
