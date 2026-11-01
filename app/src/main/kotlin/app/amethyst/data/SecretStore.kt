package app.amethyst.data

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
 *
 * Never in plain text. When the encrypted store cannot be opened - a keystore that lost its
 * key, or files carried over to a new phone without it - it is recreated empty (the keys are
 * entered again); if even that fails, keys live in memory until the app restarts. Up to 0.16
 * such a failure silently switched to a plain file; one left over is moved in and deleted.
 */
class SecretStore(context: Context) {

    private val encrypted: SharedPreferences? = openEncrypted(context)
        ?: run {
            Log.e(TAG, "encrypted secret store unreadable - recreating it; keys must be entered again")
            context.deleteSharedPreferences(FILE)
            openEncrypted(context)
        }

    /** Only when the keystore is unusable altogether: keys until the next restart, nowhere on disk. */
    private val memory = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The aliases a key is stored under, read once when the store opens - which the
     * accessibility service does on the IO pool - and kept in step with [put] and [clear]. So
     * [has], asked while the settings screens compose and on the dictation path, never
     * decrypts just to learn whether there is a key. Null when the stored entries could not be
     * listed: [has] then asks the store each time, as it always did.
     */
    private val stored: MutableSet<String>? = runCatching { encrypted?.all.orEmpty() }
        .onFailure { Log.w(TAG, "stored key aliases unreadable: ${it.javaClass.simpleName}") }
        .getOrNull()
        ?.let { entries ->
            java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
                entries.forEach { (alias, value) -> if ((value as? String)?.isNotBlank() == true) add(alias) }
            }
        }

    init {
        if (encrypted == null) Log.e(TAG, "no keystore - API keys are kept in memory only")
        migratePlainFile(context)
    }

    fun get(alias: String?): String? =
        alias?.let { (encrypted?.getString(it, null) ?: memory[it])?.takeIf(String::isNotBlank) }

    fun put(alias: String, value: String) {
        val key = value.trim()
        if (encrypted != null) encrypted.edit().putString(alias, key).apply() else memory[alias] = key
        if (key.isNotEmpty()) stored?.add(alias) else stored?.remove(alias)
    }

    fun clear(alias: String) {
        encrypted?.edit()?.remove(alias)?.apply()
        memory.remove(alias)
        stored?.remove(alias)
    }

    private fun openEncrypted(context: Context): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.w(TAG, "encrypted secret store unavailable", e)
        null
    }

    /** The plain file an older version fell back to: its keys go into the encrypted store, the file goes. */
    private fun migratePlainFile(context: Context) {
        val plain = context.getSharedPreferences(LEGACY_PLAIN_FILE, Context.MODE_PRIVATE)
        val entries = plain.all
        if (entries.isEmpty()) {
            context.deleteSharedPreferences(LEGACY_PLAIN_FILE)
            return
        }
        entries.forEach { (alias, value) -> (value as? String)?.let { put(alias, it) } }
        plain.edit().clear().commit()
        context.deleteSharedPreferences(LEGACY_PLAIN_FILE)
        Log.i(TAG, "moved ${entries.size} key(s) out of the old plain-text store")
    }

    /** Whether a key is stored under [alias] - from memory, without decrypting it. */
    fun has(alias: String?): Boolean {
        if (alias == null) return false
        return stored?.contains(alias) ?: !get(alias).isNullOrBlank()
    }

    private companion object {
        const val TAG = "Amber"
        const val FILE = "amethyst_secrets"
        const val LEGACY_PLAIN_FILE = "amethyst_secrets_plain"
    }
}
