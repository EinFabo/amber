package app.amethyst.core.net

import java.net.InetAddress

/**
 * Which addresses belong to the user's own network: the phone itself, the home network, a
 * tailnet. Plain http may go there and nowhere else - a recording must not cross the
 * internet unencrypted.
 *
 * One rule for both checks: EngineConfigValidator judges the address the user typed,
 * [CleartextGuard] the address a request actually connected to.
 */
object LocalNetwork {

    /**
     * Loopback (127/8, ::1), private (10/8, 172.16/12, 192.168/16), link-local (169.254/16,
     * fe80::/10), carrier-grade NAT (100.64/10 - Tailscale) and unique local (fc00::/7).
     * An IPv4-mapped IPv6 address (::ffff:a.b.c.d) is judged as the IPv4 address it carries.
     * [address] is 4 or 16 bytes; anything else is not local.
     */
    fun isLocal(address: ByteArray): Boolean = when (address.size) {
        4 -> isLocalV4(address.u(0), address.u(1))
        16 -> isLocalV6(address)
        else -> false
    }

    fun isLocal(address: InetAddress): Boolean = isLocal(address.address)

    /**
     * The bytes of an IP address literal - "192.168.1.10", "fd00::5", "[::1]", "fe80::1%wlan0",
     * "::ffff:10.0.0.1" - or null when [text] is none, e.g. a host name. Parsed by hand:
     * InetAddress would look an invalid literal up in DNS on Android. Strict on purpose: no
     * leading zeros in IPv4 ("010.0.0.1" is octal to some resolvers), no shortened forms.
     */
    fun parseLiteral(text: String): ByteArray? {
        val h = text.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
        if (h.isEmpty()) return null
        return if (':' in h) parseV6(h) else parseV4(h)
    }

    private fun isLocalV4(a: Int, b: Int): Boolean =
        a == 10 || a == 127 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            (a == 100 && b in 64..127)

    private fun isLocalV6(bytes: ByteArray): Boolean {
        val zeroUpTo = { n: Int -> (0 until n).all { bytes[it].toInt() == 0 } }
        // ::ffff:a.b.c.d
        if (zeroUpTo(10) && bytes.u(10) == 0xff && bytes.u(11) == 0xff) return isLocalV4(bytes.u(12), bytes.u(13))
        val loopback = zeroUpTo(15) && bytes.u(15) == 1
        val uniqueLocal = bytes.u(0) and 0xfe == 0xfc
        val linkLocal = bytes.u(0) == 0xfe && bytes.u(1) and 0xc0 == 0x80
        return loopback || uniqueLocal || linkLocal
    }

    private fun parseV4(h: String): ByteArray? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.length !in 1..3 || !part.all { it in '0'..'9' }) return null
            if (part.length > 1 && part[0] == '0') return null
            val value = part.toInt()
            if (value > 255) return null
            bytes[i] = value.toByte()
        }
        return bytes
    }

    /** "::" filled in, an IPv4 tail ("::ffff:1.2.3.4") taken as the last two groups. */
    private fun parseV6(h: String): ByteArray? {
        var address = h
        var tail = ByteArray(0)
        if ('.' in address.substringAfterLast(':')) {
            tail = parseV4(address.substringAfterLast(':')) ?: return null
            // Keeps a trailing "::" intact ("::1.2.3.4" -> "::" + v4).
            address = address.substringBeforeLast(':') + ":"
            if (!address.endsWith("::")) address = address.removeSuffix(":")
        }
        val halves = address.split("::")
        if (halves.size > 2) return null
        fun groups(part: String): List<Int>? =
            if (part.isEmpty()) {
                emptyList()
            } else {
                part.split(':').map { g ->
                    if (g.length !in 1..4 || !g.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                    g.toInt(16)
                }
            }
        val head = groups(halves[0]) ?: return null
        val rest = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
        val wanted = 8 - tail.size / 2
        val missing = wanted - head.size - rest.size
        if (if (halves.size == 2) missing < 1 else missing != 0) return null
        val all = head + List(missing) { 0 } + rest
        return ByteArray(16).also { out ->
            all.forEachIndexed { i, g ->
                out[2 * i] = (g shr 8).toByte()
                out[2 * i + 1] = g.toByte()
            }
            tail.copyInto(out, 12)
        }
    }

    private fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xff
}
