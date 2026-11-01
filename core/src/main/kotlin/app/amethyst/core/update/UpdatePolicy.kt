package app.amethyst.core.update

import app.amethyst.core.settings.UpdateSettings

/** A release newer than the installed Amber: "0.24.0" and the page to get it from. */
data class UpdateNotice(val version: String, val url: String)

/**
 * When the update check runs and what its result shows. It runs at most once a day, when
 * Amber's screen opens or the accessibility service connects - no scheduled jobs, alarms or
 * wake locks - and only on the GitHub build while switched on. A failed check is silent: it
 * waits for the next due time - an hour after no answer at all, a day after an answer - and
 * shows only as "failed" on the Updates page.
 */
object UpdatePolicy {
    const val INTERVAL_MS = 24 * 60 * 60 * 1000L

    /**
     * After a check that got no answer at all - offline at boot, no name lookup, a timeout. An
     * answer, even an error, keeps the daily rhythm: asking a rate-limited GitHub sooner helps
     * nobody.
     */
    const val OFFLINE_RETRY_MS = 60 * 60 * 1000L

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS

    /**
     * Whether to ask GitHub now. A last check in the future means the clock was set back:
     * waiting for it to catch up could stop the checks for months, so that counts as due.
     */
    fun isDue(channel: ReleaseChannel, updates: UpdateSettings, now: Long): Boolean {
        if (!channel.checksForUpdates || !updates.enabled) return false
        val last = updates.lastCheckedAt
        val interval = if (updates.lastCheckFailed && updates.lastCheckOffline) OFFLINE_RETRY_MS else INTERVAL_MS
        return last <= 0L || now < last || now - last >= interval
    }

    /** [updates] after a check that ended at [now]: a found release replaces the stored one, a failure keeps it. */
    fun afterCheck(updates: UpdateSettings, outcome: UpdateFetch, now: Long): UpdateSettings = when (outcome) {
        is UpdateFetch.Found -> updates.copy(
            lastCheckedAt = now,
            lastCheckFailed = false,
            lastCheckOffline = false,
            latestVersion = outcome.release.version.toString(),
            latestUrl = outcome.release.url,
        )
        is UpdateFetch.NoAnswer -> updates.copy(lastCheckedAt = now, lastCheckFailed = true, lastCheckOffline = true)
        is UpdateFetch.Failed -> updates.copy(lastCheckedAt = now, lastCheckFailed = true, lastCheckOffline = false)
    }

    /**
     * The release the check found, if it is strictly newer than [installed] ("0.23.0", a
     * suffix like "-debug" ignored). Nothing on another build, while switched off, or when
     * either version cannot be read.
     */
    fun available(channel: ReleaseChannel, updates: UpdateSettings, installed: String): UpdateNotice? {
        if (!channel.checksForUpdates || !updates.enabled) return null
        val latest = AppVersion.parse(updates.latestVersion) ?: return null
        val current = AppVersion.parse(installed) ?: return null
        if (latest <= current) return null
        return UpdateNotice(latest.toString(), updates.latestUrl?.takeIf { it.isNotBlank() } ?: GithubReleases.RELEASES_URL)
    }

    /** Home's card: an available release, until the user dismisses that version. */
    fun forHome(channel: ReleaseChannel, updates: UpdateSettings, installed: String): UpdateNotice? =
        available(channel, updates, installed)?.takeUnless { it.version == updates.dismissedVersion }

    /** A release to post a notification for: each version once, and none the user dismissed on Home. */
    fun toNotify(channel: ReleaseChannel, updates: UpdateSettings, installed: String): UpdateNotice? =
        available(channel, updates, installed)
            ?.takeUnless { it.version == updates.notifiedVersion || it.version == updates.dismissedVersion }

    /**
     * Whether a notification posted before is out of date: [outcome] found a release, and
     * nothing in [updates] after it is newer than [installed] - the user installed it, or the
     * release was withdrawn. A failed check knows nothing new and clears nothing.
     */
    fun clearsNotification(outcome: UpdateFetch, updates: UpdateSettings, installed: String): Boolean {
        if (outcome !is UpdateFetch.Found || AppVersion.parse(installed) == null) return false
        return available(ReleaseChannel.GITHUB, updates.copy(enabled = true), installed) == null
    }

    /** The Updates row in the settings list. A failed check is not news there. */
    fun summary(channel: ReleaseChannel, updates: UpdateSettings, installed: String): String = when {
        !channel.checksForUpdates -> "From where you installed Amber"
        !updates.enabled -> "Off"
        else -> available(channel, updates, installed)?.let { "${it.version} available" } ?: "Checks GitHub once a day"
    }

    /**
     * The Updates page's line about the last check: when, and what it found. Switched off, it
     * only says when - nothing is retried then, and no release shows as available.
     */
    fun lastCheck(updates: UpdateSettings, installed: String, now: Long): String {
        if (updates.lastCheckedAt <= 0L) return "Not yet"
        val ago = ago(updates.lastCheckedAt, now)
        if (updates.lastCheckFailed) {
            val (what, retry) = if (updates.lastCheckOffline) "No connection" to "within an hour" else "Failed" to "within a day"
            return if (updates.enabled) "$what $ago · retried $retry" else "$what $ago"
        }
        if (!updates.enabled) return ago
        val newer = available(ReleaseChannel.GITHUB, updates, installed)
        return "$ago · ${newer?.let { "${it.version} available" } ?: "up to date"}"
    }

    /** "just now", "5 minutes ago", "3 hours ago", "2 days ago". A time ahead of [now] is just now. */
    fun ago(at: Long, now: Long): String {
        val elapsed = now - at
        return when {
            elapsed < MINUTE_MS -> "just now"
            elapsed < HOUR_MS -> count(elapsed / MINUTE_MS, "minute")
            elapsed < INTERVAL_MS -> count(elapsed / HOUR_MS, "hour")
            else -> count(elapsed / INTERVAL_MS, "day")
        }
    }

    private fun count(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"} ago"
}
