package app.nocturne.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API keys, kept out of the settings JSON.
 *
 * The settings blob only ever holds an alias; the key itself lives here, encrypted with a
 * keystore-backed master key. That way exporting or logging settings can never leak a key.
 */
class SecretStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "nocturne_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // A broken keystore must not make the app unusable: fall back to plain prefs and
        // say so in the log, rather than crashing on launch.
        Log.e(TAG, "EncryptedSharedPreferences unavailable, falling back to plain storage", e)
        context.getSharedPreferences("nocturne_secrets_plain", Context.MODE_PRIVATE)
    }

    fun get(alias: String?): String? =
        alias?.let { prefs.getString(it, null)?.takeIf(String::isNotBlank) }

    fun put(alias: String, value: String) {
        prefs.edit().putString(alias, value.trim()).apply()
    }

    fun clear(alias: String) {
        prefs.edit().remove(alias).apply()
    }

    fun has(alias: String?): Boolean = !get(alias).isNullOrBlank()

    private companion object {
        const val TAG = "Nocturne"
    }
}
