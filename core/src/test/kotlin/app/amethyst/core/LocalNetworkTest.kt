package app.amethyst.core

import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.net.CleartextGuard
import app.amethyst.core.net.LocalNetwork
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which addresses plain http may go to - for the typed address and the connected one alike. */
class LocalNetworkTest {

    private fun local(literal: String): Boolean =
        LocalNetwork.isLocal(assertNotNull(LocalNetwork.parseLiteral(literal), literal))

    /** The JVM's own parser, for literals only - it never looks these up. */
    private fun inet(literal: String): InetAddress = InetAddress.getByName(literal)

    private val localV4 = listOf(
        "127.0.0.1", "127.255.255.254",
        "10.0.0.0", "10.255.255.255",
        "172.16.0.0", "172.31.255.255",
        "192.168.0.0", "192.168.255.255",
        "169.254.0.1", "169.254.255.254",
        "100.64.0.0", "100.101.1.2", "100.127.255.255",
    )

    private val publicV4 = listOf(
        "8.8.8.8", "1.1.1.1", "0.0.0.0", "255.255.255.255",
        "9.255.255.255", "11.0.0.0", "126.255.255.255", "128.0.0.0",
        "172.15.255.255", "172.32.0.0",
        "192.167.255.255", "192.169.0.0",
        "169.253.255.255", "169.255.0.0",
        "100.63.255.255", "100.128.0.0",
    )

    private val localV6 = listOf(
        "::1", "0:0:0:0:0:0:0:1",
        "fc00::", "fd12:3456::1", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "fe80::", "fe80::1", "febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "::ffff:192.168.1.10", "::ffff:10.0.0.1", "::ffff:127.0.0.1", "::ffff:100.64.0.1", "::ffff:c0a8:010a",
    )

    private val publicV6 = listOf(
        "::", "::2", "2001:db8::1", "2606:4700:4700::1111",
        "fbff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "fe00::1", "fec0::1", "ff02::1",
        // fd::1 is 00fd::1 and fe8::1 is 0fe8::1.
        "fd::1", "fe8::1",
        "::ffff:8.8.8.8", "::ffff:100.128.0.0", "::ffff:172.32.0.1",
        // IPv4-compatible, not mapped: no longer an IPv4 address.
        "::192.168.1.10", "64:ff9b::c0a8:10a",
    )

    @Test
    fun `each local ipv4 range, edges included, is local`() {
        for (address in localV4) assertTrue(local(address), address)
    }

    @Test
    fun `addresses just outside the ranges are public`() {
        for (address in publicV4) assertFalse(local(address), address)
    }

    @Test
    fun `ipv6 loopback, unique local, link local and mapped local ipv4 are local`() {
        for (address in localV6) assertTrue(local(address), address)
    }

    @Test
    fun `other ipv6 addresses are public`() {
        for (address in publicV6) assertFalse(local(address), address)
    }

    @Test
    fun `connected addresses are judged the same as typed ones`() {
        for (address in localV4 + localV6) assertTrue(LocalNetwork.isLocal(inet(address)), address)
        for (address in publicV4 + publicV6) assertFalse(LocalNetwork.isLocal(inet(address)), address)
        // The JVM hands a mapped address over as the IPv4 address it carries.
        assertTrue(LocalNetwork.isLocal(inet("::ffff:192.168.1.10")))
    }

    @Test
    fun `literals parse to the same bytes as the jvm parses them`() {
        for (address in localV4 + publicV4 + localV6 + publicV6 - "::ffff:192.168.1.10" - "::ffff:10.0.0.1" -
            "::ffff:127.0.0.1" - "::ffff:100.64.0.1" - "::ffff:c0a8:010a" - "::ffff:8.8.8.8" -
            "::ffff:100.128.0.0" - "::ffff:172.32.0.1"
        ) {
            assertContentEquals(inet(address).address, LocalNetwork.parseLiteral(address), address)
        }
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, -64, -88, 1, 10), LocalNetwork.parseLiteral("::ffff:192.168.1.10"))
        assertContentEquals(LocalNetwork.parseLiteral("::ffff:192.168.1.10"), LocalNetwork.parseLiteral("::ffff:c0a8:10a"))
    }

    @Test
    fun `brackets and zones are accepted, host names and broken literals are not literals`() {
        assertTrue(local("[fd00::5]"))
        assertTrue(local("FE80::1%wlan0"))
        for (text in listOf(
            "nas", "example.com", "fdn-transcribe.example.com", "1.2.3", "1.2.3.4.5", "256.1.1.1",
            "010.0.0.1", "1.2.3.-4", "+1.2.3.4", "", "[]", "1:2:3:4:5:6:7:8:9", "fd00:::1", "fd00::g", "1::2::3",
            "12345::1", "::ffff:1.2.3", "1:2:3:4:5:6:7:1.2.3.4",
        )) {
            assertNull(LocalNetwork.parseLiteral(text), text)
        }
    }

    @Test
    fun `the typed address check uses the same ranges`() {
        for (address in localV4 + localV6) assertTrue(ServerProviders.isPrivateHost(address), address)
        for (address in publicV4 + publicV6) assertFalse(ServerProviders.isPrivateHost(address), address)
        // A bare number is an IPv4 address to some resolvers (2130706433 = 127.0.0.1,
        // 134744072 = 8.8.8.8), not a name in the home network.
        assertFalse(ServerProviders.isPrivateHost("134744072"))
        assertFalse(ServerProviders.isPrivateHost("010.0.0.1"))
    }

    @Test
    fun `plain http only to a local connected address`() {
        val lan = inet("192.168.1.20")
        val internet = inet("8.8.8.8")
        assertTrue(CleartextGuard.allowed(https = true, host = "example.com", connected = internet, proxied = false))
        assertTrue(CleartextGuard.allowed(https = false, host = "nas", connected = lan, proxied = false))
        // The name does not matter, only where it led.
        assertTrue(CleartextGuard.allowed(https = false, host = "server.example.com", connected = lan, proxied = false))
        assertFalse(CleartextGuard.allowed(https = false, host = "nas", connected = internet, proxied = false))
        assertFalse(CleartextGuard.allowed(https = false, host = "192.168.1.20", connected = null, proxied = false))
        assertTrue(CleartextGuard.allowed(https = false, host = "localhost", connected = inet("::1"), proxied = false))
        assertTrue(CleartextGuard.allowed(https = false, host = "box", connected = inet("100.100.1.1"), proxied = false))
    }

    @Test
    fun `through a proxy both the proxy and the name must be local`() {
        val proxy = inet("192.168.1.1")
        assertTrue(CleartextGuard.allowed(https = false, host = "192.168.1.20", connected = proxy, proxied = true))
        assertTrue(CleartextGuard.allowed(https = false, host = "nas.local", connected = proxy, proxied = true))
        assertFalse(CleartextGuard.allowed(https = false, host = "example.com", connected = proxy, proxied = true))
        assertFalse(CleartextGuard.allowed(https = false, host = "192.168.1.20", connected = inet("8.8.8.8"), proxied = true))
    }
}
