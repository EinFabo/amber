package app.amethyst.core.engine

/**
 * Which entries of the models folder nobody uses any more: a model that left the catalog, its
 * unfinished part, an older Parakeet folder. A wrong delete costs a download of hundreds of MB,
 * so only names that are clearly a model's are ever returned; everything unclear stays.
 */
object ModelSweep {

    private val FILE_SUFFIXES = listOf(".bin.part.from", ".bin.part", ".bin")
    private const val PARAKEET_PREFIX = "parakeet"

    /**
     * The entries of [files] to delete. [files] are the names in the models folder, a folder's
     * name ending in "/". [current] holds every model id in use: all catalog slots, the slot of a
     * picked file, the current Parakeet folder, whatever an engine names, whatever is under
     * suspicion or downloading. An empty [current] means nothing is known: nothing is swept.
     */
    fun stale(files: List<String>, current: Set<String>): List<String> {
        if (current.isEmpty()) return emptyList()
        return files.filter { name ->
            if (name.endsWith("/")) {
                val folder = name.removeSuffix("/")
                folder.startsWith(PARAKEET_PREFIX) && folder !in current
            } else {
                val suffix = FILE_SUFFIXES.firstOrNull { name.endsWith(it) } ?: return@filter false
                val owner = name.removeSuffix(suffix)
                owner.isNotEmpty() && owner !in current
            }
        }
    }
}
