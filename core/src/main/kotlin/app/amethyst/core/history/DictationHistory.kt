package app.amethyst.core.history

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** How long the history keeps a dictation: the choice under Settings → History. */
enum class HistoryKeep(val hours: Int) {
    OFF(0),
    DAY(24),
    WEEK(24 * 7),
    ;

    val millis: Long get() = hours * 3_600_000L
}

/** One dictation in the history: its words, the app it went into (a package, null: none known) and when. */
data class HistoryEntry(val atMillis: Long, val text: String, val app: String?)

/**
 * The key the history is sealed with. Sealing and opening fail - null - when the key cannot be
 * used now: in the app, while the phone is locked (a keystore key that needs it unlocked), or
 * when there is no key. Then nothing is written, and nothing can be read.
 */
interface HistoryLock {
    fun seal(plain: ByteArray, associated: ByteArray): ByteArray?

    fun open(sealed: ByteArray, associated: ByteArray): ByteArray?

    /** Destroys the key: whatever was sealed with it can never be opened again. */
    fun destroy()
}

/**
 * AES-GCM with [key] - in the app a key in Android's keystore that only works while the phone
 * is unlocked; in the tests one made on the spot. The nonce is the cipher's own, random each
 * time (a keystore key insists on that), and goes before the sealed bytes. [destroyKey] deletes
 * the key for [destroy].
 */
class AesGcmLock(private val key: () -> SecretKey?, private val destroyKey: () -> Unit) : HistoryLock {

    override fun seal(plain: ByteArray, associated: ByteArray): ByteArray? {
        val key = key() ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(associated)
            val sealed = cipher.doFinal(plain)
            val iv = cipher.iv
            if (iv == null || iv.size != IV_BYTES) null else iv + sealed
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: RuntimeException) {
            // A keystore that refuses (the phone locked) may throw an unchecked exception of its own.
            null
        }
    }

    override fun open(sealed: ByteArray, associated: ByteArray): ByteArray? {
        if (sealed.size <= IV_BYTES) return null
        val key = key() ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            cipher.updateAAD(associated)
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    override fun destroy() = destroyKey()

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * The history of recent dictations - their words, app and time - kept only sealed ([HistoryLock])
 * in [file], and only for as long as [HistoryKeep] says.
 *
 * One line per dictation: its time in the clear, so what is past its time can go without the
 * key - also while the phone is locked - and then its words and app, sealed, the time bound to
 * them so a line cannot be given another one. New lines are added at the end without opening
 * the others.
 *
 * Every call holds the lock and touches the file: not on the main thread.
 */
class DictationHistory(
    private val file: File,
    private val lock: HistoryLock,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Serializable
    private class Sealed(val text: String, val app: String? = null)

    /**
     * Keeps [text], dictated into [app], for [keep]. False when it was not kept: the history is
     * off, nothing was said, or the key cannot be used now (the phone is locked) - then nothing
     * at all is written, and nobody is told.
     */
    @Synchronized
    fun add(text: String, app: String?, keep: HistoryKeep): Boolean {
        if (keep == HistoryKeep.OFF || text.isBlank()) return false
        val at = clock()
        val plain = json.encodeToString(Sealed.serializer(), Sealed(text, app)).toByteArray()
        val sealed = lock.seal(plain, associated(at)) ?: return false
        file.parentFile?.mkdirs()
        file.appendText("$at$SEPARATOR${Base64.getEncoder().encodeToString(sealed)}\n")
        return true
    }

    /**
     * The dictations kept for [keep], newest first - what is past its time is deleted first
     * ([prune]). A line that cannot be opened (the phone locked, a key gone) is left out.
     */
    @Synchronized
    fun entries(keep: HistoryKeep): List<HistoryEntry> {
        prune(keep)
        return lines().mapNotNull { (at, sealed) ->
            val plain = lock.open(sealed, associated(at)) ?: return@mapNotNull null
            runCatching { json.decodeFromString(Sealed.serializer(), String(plain)) }.getOrNull()
                ?.let { HistoryEntry(at, it.text, it.app) }
        }.sortedByDescending { it.atMillis }
    }

    /** How many dictations are kept for [keep], without opening any. */
    @Synchronized
    fun count(keep: HistoryKeep): Int {
        val from = HistoryRetention.keptFrom(clock(), keep)
        return lines().count { (at, _) -> HistoryRetention.kept(at, from) }
    }

    /**
     * Deletes what is older than [keep] allows, without the key; [HistoryKeep.OFF] deletes all
     * ([deleteAll]). The file is written anew, never edited in place.
     */
    @Synchronized
    fun prune(keep: HistoryKeep) {
        if (keep == HistoryKeep.OFF) {
            deleteAll()
            return
        }
        if (!file.exists()) return
        val from = HistoryRetention.keptFrom(clock(), keep)
        val all = file.readLines().filter { it.isNotBlank() }
        val kept = all.filter { line -> HistoryRetention.kept(line.substringBefore(SEPARATOR).toLongOrNull() ?: Long.MIN_VALUE, from) }
        if (kept.size == all.size) return
        if (kept.isEmpty()) {
            file.delete()
            return
        }
        val fresh = File(file.path + ".new")
        fresh.writeText(kept.joinToString("") { "$it\n" })
        if (!fresh.renameTo(file)) {
            file.delete()
            fresh.renameTo(file)
        }
    }

    /** Deletes every dictation, and the key they were sealed with: what may be left on the flash cannot be opened. */
    @Synchronized
    fun deleteAll() {
        file.delete()
        File(file.path + ".new").delete()
        lock.destroy()
    }

    private fun lines(): List<Pair<Long, ByteArray>> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            val at = line.substringBefore(SEPARATOR, "").toLongOrNull() ?: return@mapNotNull null
            val sealed = runCatching { Base64.getDecoder().decode(line.substringAfter(SEPARATOR)) }.getOrNull() ?: return@mapNotNull null
            at to sealed
        }
    }

    private fun associated(at: Long): ByteArray = "amber-history:$at".toByteArray()

    private companion object {
        const val SEPARATOR = ' '
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** What the history keeps: the dictations after [keptFrom]. */
object HistoryRetention {

    /** The time after which a dictation is still kept at [now] for [keep]; for [HistoryKeep.OFF], none is. */
    fun keptFrom(now: Long, keep: HistoryKeep): Long =
        if (keep == HistoryKeep.OFF) Long.MAX_VALUE else now - keep.millis

    /** Whether a dictation made [at] is still kept: made after [from] ([keptFrom]) - one exactly 24 hours old is gone. */
    fun kept(at: Long, from: Long): Boolean = at > from
}
