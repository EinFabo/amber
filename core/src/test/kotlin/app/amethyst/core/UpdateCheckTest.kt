package app.amethyst.core

import app.amethyst.core.net.HttpResponse
import app.amethyst.core.settings.UpdateSettings
import app.amethyst.core.update.AppVersion
import app.amethyst.core.update.GithubReleases
import app.amethyst.core.update.LatestRelease
import app.amethyst.core.update.ReleaseChannel
import app.amethyst.core.update.UpdateFetch
import app.amethyst.core.update.UpdateNotice
import app.amethyst.core.update.Ago
import app.amethyst.core.update.LastCheck
import app.amethyst.core.update.UpdatePolicy
import app.amethyst.core.update.UpdateSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppVersionTest {

    private fun v(text: String) = assertNotNull(AppVersion.parse(text), text)

    @Test
    fun `a version reads with or without the v of a tag`() {
        assertEquals(AppVersion(0, 24, 0), v("v0.24.0"))
        assertEquals(AppVersion(0, 24, 0), v("0.24.0"))
        assertEquals(AppVersion(1, 2, 3), v("V1.2.3"))
        assertEquals(AppVersion(0, 24, 0), v(" v0.24.0 "))
        assertEquals("0.24.0", v("v0.24.0").toString())
    }

    @Test
    fun `a suffix such as -debug is ignored`() {
        assertEquals(AppVersion(0, 23, 0), v("0.23.0-debug"))
        assertEquals(AppVersion(0, 23, 0), v("0.23.0+build.7"))
    }

    @Test
    fun `equal, older and newer compare by number`() {
        assertEquals(0, v("0.23.0").compareTo(v("v0.23.0")))
        assertTrue(v("0.22.9") < v("0.23.0"))
        assertTrue(v("0.24.0") > v("0.23.0"))
        assertTrue(v("1.0.0") > v("0.99.99"))
        assertTrue(v("0.23.1") > v("0.23.0"))
    }

    @Test
    fun `two-digit parts are numbers, not text`() {
        assertTrue(v("0.10.0") > v("0.9.9"))
        assertTrue(v("0.9.10") > v("0.9.9"))
        assertTrue(v("10.0.0") > v("9.0.0"))
    }

    @Test
    fun `garbage is no version`() {
        for (text in listOf("", "v", "latest", "0.24", "0.24.0.1", "1..2", "a.b.c", "0.24.x", "-0.24.0", "0.-1.0",
            "v 0.24.0", "0.24.0 beta", "99999999999.0.0", "١.٢.٣", "0x1.0.0")) {
            assertNull(AppVersion.parse(text), "\"$text\"")
        }
        assertNull(AppVersion.parse(null))
    }
}

class ReleaseChannelTest {

    @Test
    fun `the github release key is the github channel, however the fingerprint is spelled`() {
        val key = ReleaseChannel.GITHUB_SIGNER_SHA256
        assertEquals("c3f69e720b6f195fc0b45dd538a0844e02c196e73b2909fc88d959be96d68e11", key)
        assertEquals(ReleaseChannel.GITHUB, ReleaseChannel.of(listOf(key)))
        assertEquals(ReleaseChannel.GITHUB, ReleaseChannel.of(listOf(key.uppercase())))
        assertEquals(ReleaseChannel.GITHUB, ReleaseChannel.of(listOf(key.chunked(2).joinToString(":"))))
        assertTrue(ReleaseChannel.GITHUB.checksForUpdates)
    }

    @Test
    fun `any other signature never checks`() {
        assertEquals(ReleaseChannel.OTHER, ReleaseChannel.of(listOf("0".repeat(64))))
        assertEquals(ReleaseChannel.OTHER, ReleaseChannel.of(emptyList()))
        assertEquals(ReleaseChannel.OTHER, ReleaseChannel.of(listOf(ReleaseChannel.GITHUB_SIGNER_SHA256.dropLast(1))))
        // Signed by the key and another: not a build this project publishes.
        assertEquals(ReleaseChannel.OTHER, ReleaseChannel.of(listOf(ReleaseChannel.GITHUB_SIGNER_SHA256, "0".repeat(64))))
        assertFalse(ReleaseChannel.OTHER.checksForUpdates)
    }

    @Test
    fun `a certificate is told by the sha-256 of its bytes, as apksigner prints it`() {
        val der = "not really a certificate".encodeToByteArray()
        val hex = MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
        assertEquals(hex, ReleaseChannel.sha256Hex(der))
        assertEquals(ReleaseChannel.OTHER, ReleaseChannel.ofCertificates(listOf(der)))
    }
}

class GithubReleasesTest {

    private val release = """{"url":"https://api.github.com/repos/EinFabo/amber/releases/1","tag_name":"v0.24.0",
        "html_url":"https://github.com/EinFabo/amber/releases/tag/v0.24.0","name":"Amber 0.24.0","draft":false,
        "prerelease":false,"assets":[{"name":"amber-0.24.0.apk"}],"body":"What changed"}"""

    @Test
    fun `the latest release gives its version, page and name`() {
        val parsed = assertNotNull(GithubReleases.parse(release))
        assertEquals(LatestRelease(AppVersion(0, 24, 0), "https://github.com/EinFabo/amber/releases/tag/v0.24.0", "Amber 0.24.0"), parsed)
    }

    @Test
    fun `anything without a valid version is ignored`() {
        assertNull(GithubReleases.parse(release.replace("\"v0.24.0\",", "\"nightly\",")))
        assertNull(GithubReleases.parse("""{"html_url":"https://github.com/EinFabo/amber/releases/tag/v0.24.0"}"""))
        assertNull(GithubReleases.parse("""{"tag_name":24}"""))
        assertNull(GithubReleases.parse("""{"message":"API rate limit exceeded"}"""))
        assertNull(GithubReleases.parse("<html>captive portal</html>"))
        assertNull(GithubReleases.parse("[]"))
        assertNull(GithubReleases.parse(""))
    }

    @Test
    fun `drafts and pre-releases never count, should the endpoint ever send one`() {
        assertNull(GithubReleases.parse(release.replace("\"draft\":false", "\"draft\":true")))
        assertNull(GithubReleases.parse(release.replace("\"prerelease\":false", "\"prerelease\":true")))
    }

    @Test
    fun `a page outside this repository's releases is replaced by the releases page`() {
        for (url in listOf("https://evil.example/amber", "http://github.com/EinFabo/amber/releases/tag/v0.24.0",
            "https://github.com/someone/else/releases/tag/v0.24.0", "https://github.com/EinFabo/amber.evil/releases")) {
            val parsed = assertNotNull(GithubReleases.parse(release.replace("https://github.com/EinFabo/amber/releases/tag/v0.24.0", url)))
            assertEquals(GithubReleases.RELEASES_URL, parsed.url, url)
        }
        val missing = assertNotNull(GithubReleases.parse("""{"tag_name":"v0.24.0"}"""))
        assertEquals(GithubReleases.RELEASES_URL, missing.url)
        assertNull(missing.name)
    }

    @Test
    fun `the check asks github's latest release without any account, briefly`() = runTest {
        val transport = FakeHttpTransport.json(release)
        val outcome = GithubReleases.fetch(transport, "0.23.0")
        assertEquals(AppVersion(0, 24, 0), assertIs<UpdateFetch.Found>(outcome).release.version)
        val request = transport.requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://api.github.com/repos/EinFabo/amber/releases/latest", request.url)
        assertEquals(mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "Amber/0.23.0"), request.headers)
        assertEquals(10_000L, request.timeoutMs)
        assertNull(request.body)
    }

    @Test
    fun `no answer at all - offline, no name lookup, a timeout - is told apart`() = runTest {
        for (e in listOf(IOException("offline"), UnknownHostException("api.github.com"), SocketTimeoutException("timeout"))) {
            assertIs<UpdateFetch.NoAnswer>(GithubReleases.fetch(FakeHttpTransport.failing(e), "0.23.0"), e.javaClass.simpleName)
        }
        // Not a network failure: nothing an hour later would change.
        assertIs<UpdateFetch.Failed>(GithubReleases.fetch(FakeHttpTransport.failing(IllegalArgumentException("bad header")), "0.23.0"))
    }

    @Test
    fun `rate limited, no release yet, a server error or garbage is a failed check`() = runTest {
        assertIs<UpdateFetch.Failed>(GithubReleases.fetch(FakeHttpTransport.json("""{"message":"API rate limit exceeded"}""", 403), "0.23.0"))
        assertIs<UpdateFetch.Failed>(GithubReleases.fetch(FakeHttpTransport.json("""{"message":"Not Found"}""", 404), "0.23.0"))
        assertIs<UpdateFetch.Failed>(GithubReleases.fetch(FakeHttpTransport.json("", 502), "0.23.0"))
        assertIs<UpdateFetch.Failed>(GithubReleases.fetch(FakeHttpTransport.json("<html></html>"), "0.23.0"))
        assertIs<UpdateFetch.Failed>(GithubReleases.fetch(FakeHttpTransport.json("""{"tag_name":"soon"}"""), "0.23.0"))
        // A redirect is not followed (the transport has redirects off) and not a release.
        assertIs<UpdateFetch.Failed>(GithubReleases.fetch(FakeHttpTransport { HttpResponse(301, ByteArray(0)) }, "0.23.0"))
    }

    @Test
    fun `a cancelled check stays cancelled`() = runTest {
        assertFailsWith<CancellationException> {
            GithubReleases.fetch(FakeHttpTransport.failing(CancellationException("stopped")), "0.23.0")
        }
    }
}

class UpdatePolicyTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_800_000_000_000L
    private val github = ReleaseChannel.GITHUB
    private val found = UpdateFetch.Found(LatestRelease(AppVersion(0, 24, 0), "https://github.com/EinFabo/amber/releases/tag/v0.24.0", "Amber 0.24.0"))

    @Test
    fun `a check is due at most once a day, only on the github build and only while on`() {
        assertTrue(UpdatePolicy.isDue(github, UpdateSettings(), now), "never checked")
        assertFalse(UpdatePolicy.isDue(github, UpdateSettings(lastCheckedAt = now - 23 * hour), now))
        assertTrue(UpdatePolicy.isDue(github, UpdateSettings(lastCheckedAt = now - day), now))
        assertTrue(UpdatePolicy.isDue(github, UpdateSettings(lastCheckedAt = now - 3 * day), now))
        assertFalse(UpdatePolicy.isDue(github, UpdateSettings(enabled = false), now))
        assertFalse(UpdatePolicy.isDue(ReleaseChannel.OTHER, UpdateSettings(), now))
    }

    @Test
    fun `a clock set back does not stop the checks until it catches up`() {
        assertTrue(UpdatePolicy.isDue(github, UpdateSettings(lastCheckedAt = now + 30 * day), now))
    }

    @Test
    fun `a found release is stored with the time, a failure only marks the time`() {
        val checked = UpdatePolicy.afterCheck(UpdateSettings(lastCheckFailed = true), found, now)
        assertEquals(now, checked.lastCheckedAt)
        assertFalse(checked.lastCheckFailed)
        assertEquals("0.24.0", checked.latestVersion)
        assertEquals("https://github.com/EinFabo/amber/releases/tag/v0.24.0", checked.latestUrl)

        val failed = UpdatePolicy.afterCheck(checked, UpdateFetch.Failed("HTTP 403"), now + day)
        assertEquals(now + day, failed.lastCheckedAt)
        assertTrue(failed.lastCheckFailed)
        assertEquals("0.24.0", failed.latestVersion, "what was seen before stays")
        // ... and is retried at the next due time, not before.
        assertFalse(UpdatePolicy.isDue(github, failed, now + day + hour))
        assertTrue(UpdatePolicy.isDue(github, failed, now + 2 * day))
    }

    @Test
    fun `a check that got no answer at all is retried after an hour, an answer keeps the daily rhythm`() {
        val seen = UpdatePolicy.afterCheck(UpdateSettings(), found, now - 2 * day)
        val offline = UpdatePolicy.afterCheck(seen, UpdateFetch.NoAnswer("UnknownHostException"), now)
        assertTrue(offline.lastCheckFailed)
        assertTrue(offline.lastCheckOffline)
        assertEquals("0.24.0", offline.latestVersion, "what was seen before stays")
        assertFalse(UpdatePolicy.isDue(github, offline, now + 59 * 60_000))
        assertTrue(UpdatePolicy.isDue(github, offline, now + hour))

        val answered = UpdatePolicy.afterCheck(offline, UpdateFetch.Failed("HTTP 404"), now + hour)
        assertTrue(answered.lastCheckFailed)
        assertFalse(answered.lastCheckOffline)
        assertFalse(UpdatePolicy.isDue(github, answered, now + 3 * hour))
        assertTrue(UpdatePolicy.isDue(github, answered, now + hour + day))

        val back = UpdatePolicy.afterCheck(offline, found, now + hour)
        assertFalse(back.lastCheckFailed)
        assertFalse(back.lastCheckOffline)
        assertFalse(UpdatePolicy.isDue(github, back, now + 3 * hour))
    }

    @Test
    fun `only a strictly newer release is available`() {
        val seen = UpdatePolicy.afterCheck(UpdateSettings(), found, now)
        assertEquals(UpdateNotice("0.24.0", "https://github.com/EinFabo/amber/releases/tag/v0.24.0"), UpdatePolicy.available(github, seen, "0.23.0"))
        assertNull(UpdatePolicy.available(github, seen, "0.24.0"), "equal")
        assertNull(UpdatePolicy.available(github, seen, "0.24.0-debug"), "equal, with a suffix")
        assertNull(UpdatePolicy.available(github, seen, "0.25.0"), "older")
        assertNull(UpdatePolicy.available(github, seen, ""), "the installed version unknown")
        assertNull(UpdatePolicy.available(github, UpdateSettings(), "0.23.0"), "nothing seen yet")
        assertNull(UpdatePolicy.available(github, seen.copy(enabled = false), "0.23.0"), "switched off")
        assertNull(UpdatePolicy.available(ReleaseChannel.OTHER, seen, "0.23.0"), "another build")
        assertEquals(GithubReleases.RELEASES_URL, UpdatePolicy.available(github, seen.copy(latestUrl = null), "0.23.0")?.url)
    }

    @Test
    fun `home shows a version until it is dismissed, a newer one again`() {
        val seen = UpdatePolicy.afterCheck(UpdateSettings(), found, now)
        assertEquals("0.24.0", UpdatePolicy.forHome(github, seen, "0.23.0")?.version)
        val dismissed = seen.copy(dismissedVersion = "0.24.0")
        assertNull(UpdatePolicy.forHome(github, dismissed, "0.23.0"))
        val newer = UpdatePolicy.afterCheck(dismissed, UpdateFetch.Found(LatestRelease(AppVersion(0, 25, 0), GithubReleases.RELEASES_URL, null)), now + day)
        assertEquals("0.25.0", UpdatePolicy.forHome(github, newer, "0.23.0")?.version)
    }

    @Test
    fun `one notification per new version`() {
        val seen = UpdatePolicy.afterCheck(UpdateSettings(), found, now)
        assertEquals("0.24.0", UpdatePolicy.toNotify(github, seen, "0.23.0")?.version)
        val notified = seen.copy(notifiedVersion = "0.24.0")
        assertNull(UpdatePolicy.toNotify(github, notified, "0.23.0"))
        assertNull(UpdatePolicy.toNotify(github, UpdatePolicy.afterCheck(notified, found, now + day), "0.23.0"), "seen again")
        val newer = UpdatePolicy.afterCheck(notified, UpdateFetch.Found(LatestRelease(AppVersion(0, 25, 0), GithubReleases.RELEASES_URL, null)), now + day)
        assertEquals("0.25.0", UpdatePolicy.toNotify(github, newer, "0.23.0")?.version)
    }

    @Test
    fun `a version dismissed on home gets no notification`() {
        val seen = UpdatePolicy.afterCheck(UpdateSettings(), found, now)
        assertNull(UpdatePolicy.toNotify(github, seen.copy(dismissedVersion = "0.24.0"), "0.23.0"))
        assertEquals("0.24.0", UpdatePolicy.toNotify(github, seen.copy(dismissedVersion = "0.23.5"), "0.23.0")?.version)
    }

    @Test
    fun `a check that finds nothing newer clears a notification left from before`() {
        val installed = UpdatePolicy.afterCheck(UpdateSettings(notifiedVersion = "0.24.0"), found, now)
        assertTrue(UpdatePolicy.clearsNotification(found, installed, "0.24.0"), "the notified version is installed now")
        assertTrue(UpdatePolicy.clearsNotification(found, installed, "0.25.0"))
        assertFalse(UpdatePolicy.clearsNotification(found, installed, "0.23.0"), "still newer")
        // A failed check knows nothing new: the notification stays.
        assertFalse(UpdatePolicy.clearsNotification(UpdateFetch.Failed("HTTP 503"), installed, "0.24.0"))
        assertFalse(UpdatePolicy.clearsNotification(UpdateFetch.NoAnswer("timeout"), installed, "0.24.0"))
        // An installed version that cannot be read decides nothing.
        assertFalse(UpdatePolicy.clearsNotification(found, installed, ""))
    }

    @Test
    fun `the settings row says what the check does in a few words`() {
        val seen = UpdatePolicy.afterCheck(UpdateSettings(), found, now)
        assertEquals(UpdateSummary.Daily, UpdatePolicy.summary(github, UpdateSettings(), "0.23.0"))
        assertEquals(UpdateSummary.Daily, UpdatePolicy.summary(github, seen, "0.24.0"))
        assertEquals(UpdateSummary.Daily, UpdatePolicy.summary(github, UpdateSettings(lastCheckFailed = true, lastCheckedAt = now), "0.23.0"))
        assertEquals(UpdateSummary.Available("0.24.0"), UpdatePolicy.summary(github, seen, "0.23.0"))
        assertEquals(UpdateSummary.Off, UpdatePolicy.summary(github, seen.copy(enabled = false), "0.23.0"))
        assertEquals(UpdateSummary.FromInstaller, UpdatePolicy.summary(ReleaseChannel.OTHER, seen, "0.23.0"))
    }

    @Test
    fun `the updates page tells when the last check was and how it went`() {
        assertEquals(LastCheck.NotYet, UpdatePolicy.lastCheck(UpdateSettings(), "0.23.0", now))
        val seen = UpdatePolicy.afterCheck(UpdateSettings(), found, now - 3 * hour)
        assertEquals(LastCheck.Done(Ago(3, Ago.Scale.HOURS), "0.24.0"), UpdatePolicy.lastCheck(seen, "0.23.0", now))
        assertEquals(LastCheck.Done(Ago(3, Ago.Scale.HOURS), ""), UpdatePolicy.lastCheck(seen, "0.24.0", now))
        val failed = UpdatePolicy.afterCheck(seen, UpdateFetch.Failed("HTTP 503"), now - 2 * hour)
        assertEquals(LastCheck.Failed(Ago(2, Ago.Scale.HOURS), offline = false, retried = true), UpdatePolicy.lastCheck(failed, "0.23.0", now))
        val offline = UpdatePolicy.afterCheck(seen, UpdateFetch.NoAnswer("UnknownHostException"), now - 20 * 60_000)
        assertEquals(LastCheck.Failed(Ago(20, Ago.Scale.MINUTES), offline = true, retried = true), UpdatePolicy.lastCheck(offline, "0.23.0", now))
        // Switched off, nothing is retried and nothing is available.
        assertEquals(LastCheck.Failed(Ago(2, Ago.Scale.HOURS), offline = false, retried = false), UpdatePolicy.lastCheck(failed.copy(enabled = false), "0.23.0", now))
        assertEquals(LastCheck.Failed(Ago(20, Ago.Scale.MINUTES), offline = true, retried = false), UpdatePolicy.lastCheck(offline.copy(enabled = false), "0.23.0", now))
        assertEquals(LastCheck.Done(Ago(3, Ago.Scale.HOURS), null), UpdatePolicy.lastCheck(seen.copy(enabled = false), "0.23.0", now))
        assertEquals(LastCheck.Done(Ago(3, Ago.Scale.HOURS), null), UpdatePolicy.lastCheck(seen.copy(enabled = false), "0.24.0", now))
    }

    @Test
    fun `how long ago, in words`() {
        assertEquals(Ago(0, Ago.Scale.JUST_NOW), UpdatePolicy.ago(now - 20_000, now))
        assertEquals(Ago(0, Ago.Scale.JUST_NOW), UpdatePolicy.ago(now + 5 * hour, now), "a clock set back")
        assertEquals(Ago(1, Ago.Scale.MINUTES), UpdatePolicy.ago(now - 60_000, now))
        assertEquals(Ago(59, Ago.Scale.MINUTES), UpdatePolicy.ago(now - 59 * 60_000, now))
        assertEquals(Ago(1, Ago.Scale.HOURS), UpdatePolicy.ago(now - hour, now))
        assertEquals(Ago(23, Ago.Scale.HOURS), UpdatePolicy.ago(now - 23 * hour - 59 * 60_000, now))
        assertEquals(Ago(1, Ago.Scale.DAYS), UpdatePolicy.ago(now - day, now))
        assertEquals(Ago(12, Ago.Scale.DAYS), UpdatePolicy.ago(now - 12 * day, now))
    }
}
