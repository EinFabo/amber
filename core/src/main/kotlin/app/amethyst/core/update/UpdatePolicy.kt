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
    fun summary(channel: ReleaseChannel, updates: UpdateSettings, installed: String): UpdateSummary = when {
        !channel.checksForUpdates -> UpdateSummary.FromInstaller
        !updates.enabled -> UpdateSummary.Off
        else -> available(channel, updates, installed)?.let { UpdateSummary.Available(it.version) } ?: UpdateSummary.Daily
    }

    /**
     * The Updates page's line about the last check: when, and what it found. Switched off, it
     * only says when - nothing is retried then, and no release shows as available.
     */
    fun lastCheck(updates: UpdateSettings, installed: String, now: Long): LastCheck {
        if (updates.lastCheckedAt <= 0L) return LastCheck.NotYet
        val ago = ago(updates.lastCheckedAt, now)
        if (updates.lastCheckFailed) {
            return LastCheck.Failed(ago, offline = updates.lastCheckOffline, retried = updates.enabled)
        }
        if (!updates.enabled) return LastCheck.Done(ago, found = null)
        val newer = available(ReleaseChannel.GITHUB, updates, installed)
        return LastCheck.Done(ago, found = newer?.version ?: "")
    }

    /** "just now", "5 minutes ago", "3 hours ago", "2 days ago". A time ahead of [now] is just now. */
    fun ago(at: Long, now: Long): Ago {
        val elapsed = now - at
        return when {
            elapsed < MINUTE_MS -> Ago(0, Ago.Scale.JUST_NOW)
            elapsed < HOUR_MS -> Ago(elapsed / MINUTE_MS, Ago.Scale.MINUTES)
            elapsed < INTERVAL_MS -> Ago(elapsed / HOUR_MS, Ago.Scale.HOURS)
            else -> Ago(elapsed / INTERVAL_MS, Ago.Scale.DAYS)
        }
    }
}

/** The Updates row's state, worded by the app. */
sealed interface UpdateSummary {
    /** A store or an adb install: that is where updates come from. */
    data object FromInstaller : UpdateSummary
    data object Off : UpdateSummary
    data class Available(val version: String) : UpdateSummary

    /** Checks GitHub once a day. */
    data object Daily : UpdateSummary
}

/** When the last update check ran and what it found, worded by the app. */
sealed interface LastCheck {
    data object NotYet : LastCheck

    /** [retried]: within an hour when [offline], else within a day; not at all while switched off. */
    data class Failed(val ago: Ago, val offline: Boolean, val retried: Boolean) : LastCheck

    /** [found]: the newer version, "" for up to date, null while switched off (only when). */
    data class Done(val ago: Ago, val found: String?) : LastCheck
}

/** How long ago, in whole [scale]s. */
data class Ago(val count: Long, val scale: Scale) {
    enum class Scale { JUST_NOW, MINUTES, HOURS, DAYS }
}
