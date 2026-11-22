package app.amethyst.core.update

import app.amethyst.core.net.HttpRequest
import app.amethyst.core.net.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.IOException
import java.net.URI

/** The newest published release on GitHub: its version, its page, and its title if it has one. */
data class LatestRelease(val version: AppVersion, val url: String, val name: String?)

/** What asking GitHub came to. */
sealed interface UpdateFetch {
    data class Found(val release: LatestRelease) : UpdateFetch

    /** No answer at all: offline, no name lookup, no connection, a timeout. Worth another try soon. */
    data class NoAnswer(val detail: String) : UpdateFetch

    /** GitHub answered, but not with a release: rate limited, none yet, a server error, garbage. */
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
            "User-Agent" to if (installedVersion.isBlank()) "Embersay" else "Embersay/$installedVersion",
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

    /**
     * Asks GitHub for the latest release. Never throws, except to cancel. A network failure
     * (any IOException - a timeout and a refused TLS handshake are ones too) is [UpdateFetch.NoAnswer].
     *
     * The transport follows no redirect (a recording must never be replayed elsewhere), but a
     * renamed or moved repository answers here with one: GitHub's API points to the same
     * release under api.github.com. That one is followed, up to [MAX_REDIRECTS] times - only
     * over https and only to api.github.com ([redirectTarget]); any other is a failed check
     * that says where it pointed.
     */
    suspend fun fetch(transport: HttpTransport, installedVersion: String): UpdateFetch {
        var request = request(installedVersion)
        var redirects = 0
        while (true) {
            val response = try {
                transport.execute(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return UpdateFetch.NoAnswer(e.javaClass.simpleName)
            } catch (e: Exception) {
                return UpdateFetch.Failed(e.javaClass.simpleName)
            }
            if (response.code in REDIRECT_CODES) {
                val location = response.header("Location")
                val next = redirectTarget(request.url, location)
                    ?: return UpdateFetch.Failed("HTTP ${response.code} to ${where(request.url, location)}, not followed")
                if (++redirects > MAX_REDIRECTS) return UpdateFetch.Failed("more than $MAX_REDIRECTS redirects")
                request = request.copy(url = next)
                continue
            }
            if (!response.isSuccessful) return UpdateFetch.Failed("HTTP ${response.code}")
            val release = parse(response.bodyAsText()) ?: return UpdateFetch.Failed("no release with a version")
            return UpdateFetch.Found(release)
        }
    }

    /**
     * Where a redirect from [from] to [location] leads, when the check may follow it: https,
     * api.github.com on its own port, nobody's user info. Null for anything else, a missing or
     * unreadable [location] included.
     */
    fun redirectTarget(from: String, location: String?): String? {
        val target = location?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { URI(from).resolve(it) }.getOrNull() } ?: return null
        val sameHost = target.scheme.equals("https", ignoreCase = true) &&
            target.host.equals(API_HOST, ignoreCase = true) &&
            (target.port == -1 || target.port == 443) &&
            target.rawUserInfo == null
        return if (sameHost) target.toString() else null
    }

    /** A redirect's target for the log: its host, or that it named none. */
    private fun where(from: String, location: String?): String =
        location?.let { runCatching { URI(from).resolve(it.trim()) }.getOrNull() }
            ?.let { "${it.scheme}://${it.host}" } ?: "no address"

    private const val API_HOST = "api.github.com"

    /** Moved for good or for now; a repository rename answers 301. */
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    /** A rename, then perhaps a transfer: more is a loop. */
    private const val MAX_REDIRECTS = 3

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
}
