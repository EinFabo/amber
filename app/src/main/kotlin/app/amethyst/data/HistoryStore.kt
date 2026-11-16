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
 * The history of recent dictations on this phone ([DictationHistory]): sealed with an AES-GCM key
 * in Android's keystore that works only while the phone is unlocked (setUnlockedDeviceRequired),
 * so a locked phone can neither read it nor write to it. In noBackupFilesDir: never in a cloud
 * backup or a transfer to a new phone, and the keystore key could not go along anyway.
 *
 * Every call touches the file or the keystore: not on the main thread.
 */
class HistoryStore(private val context: Context) {

    private val history = DictationHistory(
        File(context.noBackupFilesDir, "history/dictations"),
        AesGcmLock(::key, ::deleteKey),
    )

    /**
     * Keeps [text], dictated into [app], for [keep] - only while the phone is unlocked: locked,
     * nothing is written and nobody is told.
     */
    fun add(text: String, app: String?, keep: HistoryKeep) {
        if (keep == HistoryKeep.OFF || locked()) return
        if (!history.add(text, app, keep)) Log.i(TAG, "a dictation was not kept in the history: the key cannot be used now")
    }

    /** The dictations kept for [keep], newest first; what is past its time goes first. */
    fun entries(keep: HistoryKeep): List<HistoryEntry> = guarded(emptyList()) { history.entries(keep) }

    /** How many dictations are kept for [keep], without opening any. */
    fun count(keep: HistoryKeep): Int = guarded(0) { history.count(keep) }

    /** Deletes what is older than [keep] allows; off, everything and its key. Works while locked. */
    fun prune(keep: HistoryKeep) = guarded(Unit) { history.prune(keep) }

    /** Deletes every dictation, and the key. */
    fun deleteAll() = history.deleteAll()

    private fun locked(): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false

    private fun <T> guarded(otherwise: T, action: () -> T): T = try {
        action()
    } catch (e: Exception) {
        Log.w(TAG, "history not read: ${e.javaClass.simpleName}")
        otherwise
    }

    /** The history's key, made the first time it is needed. Null when the keystore refuses. */
    private fun key(): SecretKey? = try {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
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

    private fun deleteKey() {
        try {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "history key not deleted: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "Amber"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "amber.history"
    }
}
