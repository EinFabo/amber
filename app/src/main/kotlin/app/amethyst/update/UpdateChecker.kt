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

    /** Which build this is, from the key it is signed with; read once. */
    val channel: ReleaseChannel by lazy { readChannel() }

    /** This build's version name, "0.23.0" (a debug build adds "-debug"). */
    val installedVersion: String by lazy {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    private val running = MutableStateFlow(false)

    /** A check is under way, for the Updates page's "Check now". */
    val checking: StateFlow<Boolean> = running.asStateFlow()

    /** Amber's screen opened or the service connected: a check if one is due. */
    fun checkIfDue() = launch(force = false)

    /** "Check now" on the Updates page: a check whatever the time, if checks are on. */
    fun checkNow() = launch(force = true)

    private fun launch(force: Boolean) {
        scope.launch(Dispatchers.IO) {
            // The signature is read here, on the IO pool, the first time.
            if (!channel.checksForUpdates) return@launch
            val settings = loaded.filterNotNull().first()
            if (!settings.updates.enabled) return@launch
            if (!force && !UpdatePolicy.isDue(channel, settings.updates, System.currentTimeMillis())) return@launch
            // One at a time: the screen and the service may both ask at the same moment.
            if (!running.compareAndSet(expect = false, update = true)) return@launch
            try {
                check()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update check not stored", e)
            } finally {
                running.value = false
            }
        }
    }

    private suspend fun check() {
        val outcome = GithubReleases.fetch(transport(), installedVersion)
        when (outcome) {
            is UpdateFetch.Found -> Log.i(TAG, "update check: latest release ${outcome.release.version}")
            is UpdateFetch.Failed -> Log.i(TAG, "update check failed: ${outcome.detail}")
        }
        val canNotify = notifier.canNotify()
        var notify: UpdateNotice? = null
        update { current ->
            var updates = UpdatePolicy.afterCheck(current.updates, outcome, System.currentTimeMillis())
            // Marked as notified only when the notification can be posted: without the
            // permission, a later check - once it is granted - still tells.
            notify = UpdatePolicy.toNotify(channel, updates, installedVersion)?.takeIf { canNotify }
            notify?.let { updates = updates.copy(notifiedVersion = it.version) }
            current.copy(updates = updates)
        }
        notify?.let(notifier::post)
    }

    /**
     * The SHA-256 of the certificate this APK is signed with now (minSdk 28, so the signing info
     * is always there). Anything unreadable is "another build": no checks.
     */
    private fun readChannel(): ReleaseChannel = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        ReleaseChannel.ofCertificates(signers.map { it.toByteArray() })
    }.getOrElse {
        Log.w(TAG, "signature not readable: ${it.javaClass.simpleName}")
        ReleaseChannel.OTHER
    }

    private companion object {
        const val TAG = "Amber"
    }
}
