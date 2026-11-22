package app.amethyst.core.settings

/** What reading the stored settings came to ([SettingsCodec.read]). */
sealed interface SettingsRead {
    /** What the app runs on. */
    val settings: AmethystSettings

    /** Nothing saved yet: a first launch. */
    data class Fresh(override val settings: AmethystSettings) : SettingsRead

    data class Saved(override val settings: AmethystSettings) : SettingsRead

    /**
     * Saved, and read with the parts this build cannot read left out - an engine of a kind a
     * newer build added. The app runs on the rest; [reason] as for [Unreadable].
     */
    data class Partial(override val settings: AmethystSettings, val reason: String) : SettingsRead

    /**
     * Saved, but this build cannot read it - written by a newer build after a downgrade, or
     * damaged. The app runs on the defaults; [reason] is for Diagnostics: the failure's kind
     * and where in the settings, never a value (the settings hold the user's own words).
     */
    data class Unreadable(override val settings: AmethystSettings, val reason: String) : SettingsRead

    companion object {
        private val PATH = Regex("""at path:? (\$[^\s,]*)""")

        internal fun reason(failure: Throwable): String {
            val path = failure.message?.let { PATH.find(it)?.groupValues?.get(1) }
            return listOfNotNull(failure.javaClass.simpleName, path?.let { "at $it" }).joinToString(" ")
        }
    }
}

/**
 * Settings this build cannot read are never written over. Before, the defaults stood in and
 * the next change - a drag of the bubble - saved them over everything the user had set.
 *
 * Now the unreadable settings are copied aside first ([Stored.backup]), so writes may go on
 * (the app keeps working on the defaults, the introduction can finish). As soon as a build
 * reads the copy again - the user went back to the newer version - it comes back in place of
 * what was saved meanwhile: those were changes made to the defaults, the copy is what the user
 * had built up. A copy no build can read (damaged) just stays.
 */
object SettingsRescue {

    /** The settings blob and the copy kept aside; null for nothing stored. */
    data class Stored(val main: String?, val backup: String?)

    data class Outcome(
        /** What the app runs on and the next change starts from. */
        val settings: AmethystSettings,
        /** What to store before anything else, or null when nothing changes. */
        val store: Stored?,
        /** A line for Diagnostics when something was copied aside or brought back. */
        val event: Event?,
    )

    sealed interface Event {
        /** Diagnostics only: English, never shown as a message. */
        val line: String

        data class KeptAside(val reason: String, val inPart: Boolean = false) : Event {
            override val line: String
                get() = "Stored settings could not be read${if (inPart) " in full" else ""} ($reason): a copy is kept " +
                    "and comes back with an app version that reads it; " +
                    (if (inPart) "the parts read stand in until then" else "the defaults stand in until then")
        }

        data object Restored : Event {
            override val line: String
                get() = "Settings restored from the copy kept when they could not be read"
        }
    }

    fun of(stored: Stored, phone: List<String>): Outcome {
        val main = SettingsCodec.read(stored.main.orEmpty(), phone)
        // The settings in place are always the newest: only another build can have written
        // something this one cannot read, and it did so after this one last saved. They go
        // aside, over an older copy. Read in part, the same: what was left out must not be lost
        // to the next save.
        val aside = when (main) {
            is SettingsRead.Unreadable -> Event.KeptAside(main.reason)
            is SettingsRead.Partial -> Event.KeptAside(main.reason, inPart = true)
            else -> null
        }
        if (aside != null) {
            val keep = if (stored.backup == stored.main) null else Stored(stored.main, stored.main)
            return Outcome(main.settings, keep, keep?.let { aside })
        }
        // Only a copy read in full comes back: one this build reads in part is the one it
        // kept aside itself, and bringing it back would undo every change made since.
        val backup = stored.backup?.let { SettingsCodec.read(it, phone) }
        if (backup is SettingsRead.Saved) {
            return Outcome(backup.settings, Stored(stored.backup, null), Event.Restored)
        }
        return Outcome(main.settings, null, null)
    }
}
