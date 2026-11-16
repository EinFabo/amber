package app.amethyst.data

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import app.amethyst.core.history.AesGcmLock
import app.amethyst.core.history.DictationHistory
import app.amethyst.core.history.HistoryEntry
import app.amethyst.core.history.HistoryKeep
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The history of recent dictations on this phone ([DictationHistory]): sealed with AES-GCM keys
 * in Android's keystore, one per day, that work only while the phone is unlocked
 * (setUnlockedDeviceRequired), so a locked phone can neither read it nor write to it. A day's key
 * is deleted once the day is past keeping, so what is left of its words on the flash cannot be
 * read. In noBackupFilesDir: never in a cloud backup or a transfer to a new phone, and the
 * keystore keys could not go along anyway.
 *
 * Every call touches the file or the keystore: not on the main thread.
 */
class HistoryStore(private val context: Context) {

    private val history = DictationHistory(
        File(context.noBackupFilesDir, "history/dictations"),
        AesGcmLock(::key, ::days, ::deleteKey),
    )

    /**
     * Keeps [text], dictated into [app], for [keep] - only while the phone is unlocked: locked,
     * nothing is written and nobody is told.
     */
    fun add(text: String, app: String?, keep: HistoryKeep) {
        if (keep == HistoryKeep.OFF || locked()) return
        if (!guarded(false) { history.add(text, app, keep) }) Log.i(TAG, "a dictation was not kept in the history")
    }

    /** The dictations kept for [keep], newest first; what is past its time goes first. */
    fun entries(keep: HistoryKeep): List<HistoryEntry> = guarded(emptyList()) { history.entries(keep) }

    /** How many dictations are kept for [keep], without opening any; what is past its time goes first. */
    fun count(keep: HistoryKeep): Int = guarded(0) { history.count(keep) }

    /** Deletes what is older than [keep] allows, and the keys of the days past it; off, everything. Works while locked. */
    fun prune(keep: HistoryKeep) = guarded(Unit) { history.prune(keep) }

    /** Deletes every dictation, and every key. */
    fun deleteAll() = history.deleteAll()

    private fun locked(): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false

    private fun <T> guarded(otherwise: T, action: () -> T): T = try {
        action()
    } catch (e: Exception) {
        Log.w(TAG, "history not read: ${e.javaClass.simpleName}")
        otherwise
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /** [day]'s key, made when [create] says so and there is none. Null when the keystore refuses. */
    private fun key(day: Long, create: Boolean): SecretKey? = try {
        val alias = PREFIX + day
        (keyStore().getKey(alias, null) as? SecretKey) ?: if (!create) null else KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // A locked phone cannot use it: the history cannot be read while it is locked.
                    .setUnlockedDeviceRequired(true)
                    .build(),
            )
            generateKey()
        }
    } catch (e: Exception) {
        Log.w(TAG, "history key not available: ${e.javaClass.simpleName}")
        null
    }

    /**
     * The days a key exists for. The one key of the first builds, for every day at once, goes
     * here: what it sealed can no longer be read, and goes when it is past its time.
     */
    private fun days(): Set<Long> = try {
        val store = keyStore()
        val aliases = store.aliases().toList()
        if (LEGACY_ALIAS in aliases) store.deleteEntry(LEGACY_ALIAS)
        aliases.mapNotNull { it.removePrefix(PREFIX).takeIf { _ -> it.startsWith(PREFIX) }?.toLongOrNull() }.toSet()
    } catch (e: Exception) {
        Log.w(TAG, "history keys not listed: ${e.javaClass.simpleName}")
        emptySet()
    }

    private fun deleteKey(day: Long) {
        try {
            keyStore().deleteEntry(PREFIX + day)
        } catch (e: Exception) {
            Log.w(TAG, "history key not deleted: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "Amber"
        const val KEYSTORE = "AndroidKeyStore"
        const val PREFIX = "amber.history.day."
        const val LEGACY_ALIAS = "amber.history"
    }
}
