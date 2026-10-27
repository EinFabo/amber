package androidx.datastore.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlin.properties.ReadOnlyProperty

fun preferencesDataStore(
    name: String,
): ReadOnlyProperty<Context, DataStore<Preferences>> = throw UnsupportedOperationException()
