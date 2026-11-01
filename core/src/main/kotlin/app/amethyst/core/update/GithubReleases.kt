package app.amethyst.core.update

import app.amethyst.core.net.HttpRequest
import app.amethyst.core.net.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The newest published release on GitHub: its version, its page, and its title if it has one. */
data class LatestRelease(val version: AppVersion, val url: String, val name: String?)

/** What asking GitHub came to. */
sealed interface UpdateFetch {
    data class Found(val release: LatestRelease) : UpdateFetch

    /** Offline, rate limited, a server error or an answer without a usable version. */
    data class Failed(val detail: String) : UpdateFetch
}

/**
 * Amber's releases on GitHub. The check is one anonymous request for the latest release - no
 * account, no token, nothing about the user beyond what any request shows: GitHub sees the IP
 * address, that Amber asks, and the version in the User-Agent.
 */
object GithubReleases {
    const val LATEST_URL = "https://api.github.com/repos/EinFabo/amber/releases/latest"

    /** The list of all releases; also where a release whose own page looks wrong leads. */
    const val RELEASES_URL = "https://github.com/EinFabo/amber/releases"

    /** A small answer; a slow one is tried again tomorrow rather than waited for. */
    const val TIMEOUT_MS = 10_000L

    private val json = Json { ignoreUnknownKeys = true }

    fun request(installedVersion: String): HttpRequest = HttpRequest(
        method = "GET",
        url = LATEST_URL,
        headers = mapOf(
            "Accept" to "application/vnd.github+json",
            "User-Agent" to if (installedVersion.isBlank()) "Amber" else "Amber/$installedVersion",
        ),
        timeoutMs = TIMEOUT_MS,
    )

    /**
     * The release in GitHub's answer, or null without a valid version in `tag_name`. The
     * `latest` endpoint already leaves out drafts and pre-releases; one marked so anyway is
     * ignored too. A page that is not one of this repository's releases - the URL is opened in
     * the browser - becomes the list of releases.
     */
    fun parse(body: String): LatestRelease? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if (root.flag("draft") || root.flag("prerelease")) return null
        val version = AppVersion.parse(root.string("tag_name")) ?: return null
        val page = root.string("html_url")?.takeIf { it.startsWith("$RELEASES_URL/") } ?: RELEASES_URL
        return LatestRelease(version, page, root.string("name")?.takeIf { it.isNotBlank() })
    }

    /** Asks GitHub for the latest release. Never throws, except to cancel. */
    suspend fun fetch(transport: HttpTransport, installedVersion: String): UpdateFetch {
        val response = try {
            transport.execute(request(installedVersion))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return UpdateFetch.Failed(e.javaClass.simpleName)
        }
        if (!response.isSuccessful) return UpdateFetch.Failed("HTTP ${response.code}")
        val release = parse(response.bodyAsText()) ?: return UpdateFetch.Failed("no release with a version")
        return UpdateFetch.Found(release)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
}
