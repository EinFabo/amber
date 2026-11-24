package app.amethyst.engine

import android.content.Context
import android.os.storage.StorageManager
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Room for models on the volume that holds a folder. File.usableSpace leaves out the cache files
 * the system may clear, so on a nearly full phone a download was refused that fits once that
 * cache is gone; StorageManager counts it. Blocking calls - off the main thread.
 */
class ModelRoom(context: Context, private val dir: File) {

    // Every Android version this app runs on has the service; said once here, not at each use.
    private val storage: StorageManager = checkNotNull(context.getSystemService(StorageManager::class.java)) {
        "no StorageManager"
    }

    /** The bytes a download into [dir] can have, clearable cache included. */
    fun available(): Long = storage.getAllocatableBytes(volume())

    /**
     * Has the system clear cache until [bytes] are free, so a download that only fits with that
     * cache gone does not run out of room halfway. False when the room is not there.
     */
    fun claim(bytes: Long): Boolean = try {
        storage.allocateBytes(volume(), bytes)
        true
    } catch (e: IOException) {
        false
    }

    // The app's own storage sits on the default volume unless it was moved to adopted storage,
    // which getUuidForPath finds; it only fails for a path on no volume at all.
    private fun volume(): UUID {
        dir.mkdirs()
        return try {
            storage.getUuidForPath(dir)
        } catch (e: IOException) {
            StorageManager.UUID_DEFAULT
        }
    }
}
