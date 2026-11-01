package app.amethyst.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import app.amethyst.core.net.HttpTransport
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.update.GithubReleases
import app.amethyst.core.update.ReleaseChannel
import app.amethyst.core.update.UpdateFetch
import app.amethyst.core.update.UpdateNotice
import app.amethyst.core.update.UpdatePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Which build this is - from the key it is signed with - and its version name ("0.23.0", "0.23.0-debug"). */
data class BuildInfo(val channel: ReleaseChannel, val version: String)

/**
 * Asks GitHub for Amber's latest release - on the GitHub build only, while switched on, at most
 * once a day - when Amber's screen opens or the accessibility service connects, and posts one
 * notification per new version. When a check is due and what it shows is decided in
 * [UpdatePolicy]; this only runs it, off the main thread.
 */
class UpdateChecker(
    private val context: Context,
    private val scope: CoroutineScope,
    /** The shared HTTP client, built only when a check really runs. */
    private val transport: () -> HttpTransport,
    /** The stored settings, null until read: a decision on the defaults would be wrong. */
    private val loaded: StateFlow<AmethystSettings?>,
    private val update: suspend ((AmethystSettings) -> AmethystSettings) -> Unit,
    private val notifier: UpdateNotifier = UpdateNotifier(context),
) {

    private val identified = MutableStateFlow<BuildInfo?>(null)

    /**
     * Which build this is and its version, read once from the package manager - on the IO pool,
     * as soon as this is built, so no screen waits on that binder call. Null until then.
     */
    val build: StateFlow<BuildInfo?> = identified.asStateFlow()

    private val running = MutableStateFlow(false)

    /** A check is under way, for the Updates page's "Check now". */
    val checking: StateFlow<Boolean> = running.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { identified.value = identify() }
    }

    /** Amber's screen opened or the service connected: a check if one is due. */
    fun checkIfDue() = launch(force = false)

    /** "Check now" on the Updates page: a check whatever the time, if checks are on. */
    fun checkNow() = launch(force = true)

    private fun launch(force: Boolean) {
        scope.launch(Dispatchers.IO) {
            val build = identified.filterNotNull().first()
            if (!build.channel.checksForUpdates) return@launch
            val settings = loaded.filterNotNull().first()
            if (!settings.updates.enabled) return@launch
            if (!force && !UpdatePolicy.isDue(build.channel, settings.updates, System.currentTimeMillis())) return@launch
            // One at a time: the screen and the service may both ask at the same moment.
            if (!running.compareAndSet(expect = false, update = true)) return@launch
            try {
                check(build)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update check not stored", e)
            } finally {
                running.value = false
            }
        }
    }

    /** Runs only one at a time (see [launch]), so the two writes below cannot interleave with another check. */
    private suspend fun check(build: BuildInfo) {
        val outcome = GithubReleases.fetch(transport(), build.version)
        when (outcome) {
            is UpdateFetch.Found -> Log.i(TAG, "update check: latest release ${outcome.release.version}")
            is UpdateFetch.NoAnswer -> Log.i(TAG, "update check got no answer: ${outcome.detail}")
            is UpdateFetch.Failed -> Log.i(TAG, "update check failed: ${outcome.detail}")
        }
        var notify: UpdateNotice? = null
        var stale = false
        update { current ->
            val updates = UpdatePolicy.afterCheck(current.updates, outcome, System.currentTimeMillis())
            notify = UpdatePolicy.toNotify(build.channel, updates, build.version)
            stale = UpdatePolicy.clearsNotification(outcome, updates, build.version)
            current.copy(updates = updates)
        }
        // Installed since, or withdrawn: the old notice goes.
        if (stale) notifier.cancel()
        val notice = notify ?: return
        // Marked as notified only once it is posted: without the permission, or if posting
        // fails, a later check still tells.
        if (notifier.post(notice)) {
            update { it.copy(updates = it.updates.copy(notifiedVersion = notice.version)) }
        }
    }

    /**
     * The SHA-256 of the certificate this APK is signed with now (minSdk 28, so the signing info
     * is always there), and the version name. A signature that cannot be read is "another
     * build": no checks.
     */
    private fun identify(): BuildInfo {
        val pm = context.packageManager
        val version = runCatching { pm.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
        val channel = runCatching {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            ReleaseChannel.ofCertificates(info.signingInfo?.apkContentsSigners.orEmpty().map { it.toByteArray() })
        }.getOrElse {
            Log.w(TAG, "signature not readable: ${it.javaClass.simpleName}")
            ReleaseChannel.OTHER
        }
        return BuildInfo(channel, version)
    }

    private companion object {
        const val TAG = "Amber"
    }
}
