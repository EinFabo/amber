package app.amethyst.core.net

/**
 * Addresses as logs and diagnostics may show them. An Amber PC or Amber Server carries its key
 * in the address's path, and a query or user info may carry one too: only scheme, host and
 * port are kept - "https://192.168.1.20:8443/…".
 */
object Redact {

    private val URL = Regex("""\b([a-zA-Z][a-zA-Z0-9+.-]*://)(?:[^/\s@]*@)?([^/\s?#:]+)(:\d+)?([/?#][^\s"'<>]*)?""")

    /** [url] with everything after the host and port replaced by "…"; anything unreadable is hidden whole. */
    fun url(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val match = URL.matchEntire(url.trim()) ?: return "<address>"
        return redacted(match)
    }

    /** [text] - an exception's message, say - with every address in it redacted as [url] does. */
    fun text(text: String?): String = text.orEmpty().replace(URL) { redacted(it) }

    private fun redacted(match: MatchResult): String {
        val (scheme, host, port, rest) = match.destructured
        return scheme.lowercase() + host + port + if (rest.isNotEmpty()) "/…" else ""
    }
}
