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
 * The keys the history is sealed with: one per day ([HistoryRetention.day]), so a day's words can
 * be made unreadable for good by destroying its key once the day is past keeping - also what is
 * left of them on the flash, where a deleted file is not overwritten at once. Sealing and opening
 * fail - null - when the key cannot be used now: in the app, while the phone is locked (a
 * keystore key that needs it unlocked), or when there is no key. Then nothing is written, and
 * nothing can be read.
 */
interface HistoryLock {
    /** [plain] sealed with [day]'s key, made the first time it is needed. */
    fun seal(day: Long, plain: ByteArray, associated: ByteArray): ByteArray?

    /** [sealed] opened with [day]'s key; null when there is none, or it cannot be used now. */
    fun open(day: Long, sealed: ByteArray, associated: ByteArray): ByteArray?

    /** The days a key exists for. */
    fun days(): Set<Long>

    /** Destroys [day]'s key: whatever was sealed with it can never be opened again. */
    fun destroy(day: Long)
}

/**
 * AES-GCM with a key per day, [key] - in the app keys in Android's keystore that only work while
 * the phone is unlocked; in the tests ones made on the spot. [key] makes the day's key when
 * [create] says so and there is none. The nonce is the cipher's own, random each time (a keystore
 * key insists on that), and goes before the sealed bytes. [days] lists the days a key exists for,
 * [destroyKey] deletes one.
 */
class AesGcmLock(
    private val key: (day: Long, create: Boolean) -> SecretKey?,
    private val days: () -> Set<Long>,
    private val destroyKey: (day: Long) -> Unit,
) : HistoryLock {

    override fun seal(day: Long, plain: ByteArray, associated: ByteArray): ByteArray? {
        val key = key(day, true) ?: return null
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

    override fun open(day: Long, sealed: ByteArray, associated: ByteArray): ByteArray? {
        if (sealed.size <= IV_BYTES) return null
        val key = key(day, false) ?: return null
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

    override fun days(): Set<Long> = days.invoke()

    override fun destroy(day: Long) = destroyKey(day)

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
 * key - also while the phone is locked - and then its words and app, sealed with the key of its
 * day, the time bound to them so a line cannot be given another one. New lines are added at the
 * end without opening the others.
 *
 * What is past its time goes whenever the history is touched - a dictation added, counted, read,
 * pruned - and with it the key of every day that is wholly past ([HistoryRetention.dayPast]).
 * "Now" for that is never earlier than the newest dictation kept or the newest day with a key
 * ([HistoryRetention.now]): a clock set back does not bring anything back.
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
     * off, nothing was said, the clock is set back past what is kept, or the key cannot be used
     * now (the phone is locked) - then nothing at all is written, and nobody is told.
     */
    @Synchronized
    fun add(text: String, app: String?, keep: HistoryKeep): Boolean {
        if (keep == HistoryKeep.OFF || text.isBlank()) return false
        prune(keep)
        val at = clock()
        if (!HistoryRetention.kept(at, HistoryRetention.keptFrom(now(), keep))) return false
        val plain = json.encodeToString(Sealed.serializer(), Sealed(text, app)).toByteArray()
        val sealed = lock.seal(HistoryRetention.day(at), plain, associated(at)) ?: return false
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
            val plain = lock.open(HistoryRetention.day(at), sealed, associated(at)) ?: return@mapNotNull null
            runCatching { json.decodeFromString(Sealed.serializer(), String(plain)) }.getOrNull()
                ?.let { HistoryEntry(at, it.text, it.app) }
        }.sortedByDescending { it.atMillis }
    }

    /** How many dictations are kept for [keep], without opening any - what is past its time goes first. */
    @Synchronized
    fun count(keep: HistoryKeep): Int {
        prune(keep)
        return lines().size
    }

    /**
     * Deletes what is older than [keep] allows, without the key, and destroys the key of every
     * day wholly past it; [HistoryKeep.OFF] deletes all ([deleteAll]). The file is written anew,
     * never edited in place.
     */
    @Synchronized
    fun prune(keep: HistoryKeep) {
        if (keep == HistoryKeep.OFF) {
            deleteAll()
            return
        }
        val from = HistoryRetention.keptFrom(now(), keep)
        if (file.exists()) {
            val all = file.readLines().filter { it.isNotBlank() }
            val kept = all.filter { line -> HistoryRetention.kept(line.substringBefore(SEPARATOR).toLongOrNull() ?: Long.MIN_VALUE, from) }
            if (kept.isEmpty()) {
                file.delete()
            } else if (kept.size != all.size) {
                val fresh = File(file.path + ".new")
                fresh.writeText(kept.joinToString("") { "$it\n" })
                if (!fresh.renameTo(file)) {
                    file.delete()
                    fresh.renameTo(file)
                }
            }
        }
        lock.days().filter { HistoryRetention.dayPast(it, from) }.forEach(lock::destroy)
    }

    /** Deletes every dictation, and every key they were sealed with: what may be left on the flash cannot be opened. */
    @Synchronized
    fun deleteAll() {
        file.delete()
        File(file.path + ".new").delete()
        lock.days().forEach(lock::destroy)
    }

    /** Now, for what is kept: the clock, but never earlier than what the history has seen ([HistoryRetention.now]). */
    private fun now(): Long = HistoryRetention.now(clock(), lines().maxOfOrNull { it.first }, lock.days().maxOrNull())

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

/** What the history keeps: the dictations after [keptFrom], and the keys of the days not [dayPast]. */
object HistoryRetention {

    const val DAY_MS = 86_400_000L

    /** The day a dictation made [at] is sealed under: whole days since 1970 in UTC, so a trip or a change of time zone moves nothing. */
    fun day(at: Long): Long = Math.floorDiv(at, DAY_MS)

    /**
     * Now, for deciding what is past its time: the [clock], but never earlier than the [newest]
     * dictation kept or the start of the newest day that has a key ([newestKeyDay]). A clock set
     * back - to read what should be gone - is taken for what it is: what was seen later stands.
     */
    fun now(clock: Long, newest: Long?, newestKeyDay: Long?): Long =
        maxOf(clock, newest ?: Long.MIN_VALUE, newestKeyDay?.let { it * DAY_MS } ?: Long.MIN_VALUE)

    /** The time after which a dictation is still kept at [now] for [keep]; for [HistoryKeep.OFF], none is. */
    fun keptFrom(now: Long, keep: HistoryKeep): Long =
        if (keep == HistoryKeep.OFF) Long.MAX_VALUE else now - keep.millis

    /** Whether a dictation made [at] is still kept: made after [from] ([keptFrom]) - one exactly 24 hours old is gone. */
    fun kept(at: Long, from: Long): Boolean = at > from

    /** Whether all of [day] is past keeping at [from] ([keptFrom]): its key can go, and with it whatever it sealed. */
    fun dayPast(day: Long, from: Long): Boolean = !kept((day + 1) * DAY_MS - 1, from)
}
