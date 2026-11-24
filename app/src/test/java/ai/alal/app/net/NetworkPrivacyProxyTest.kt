package ai.alal.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

class NetworkPrivacyProxyTest {

    @Test
    fun offIsDirectConnection() {
        val proxy = NetworkPrivacy.proxyFor(NetworkPrivacy.Route(mode = NetworkPrivacy.OFF))
        assertEquals(Proxy.Type.DIRECT, proxy.type())
    }

    @Test
    fun torIsUnresolvedSocksOnLoopback9050() {
        val proxy = NetworkPrivacy.proxyFor(NetworkPrivacy.Route(mode = NetworkPrivacy.TOR))
        assertEquals(Proxy.Type.SOCKS, proxy.type())
        val address = proxy.address() as InetSocketAddress
        assertEquals("127.0.0.1", address.hostString)
        assertEquals(9050, address.port)
        assertTrue("TOR must let the proxy resolve DNS", address.isUnresolved)
    }

    @Test
    fun socksUsesCustomHostAndPortUnresolved() {
        val proxy = NetworkPrivacy.proxyFor(
            NetworkPrivacy.Route(mode = NetworkPrivacy.SOCKS, host = "proxy.example.test", port = 1080)
        )
        assertEquals(Proxy.Type.SOCKS, proxy.type())
        val address = proxy.address() as InetSocketAddress
        assertEquals("proxy.example.test", address.hostString)
        assertEquals(1080, address.port)
        assertTrue("SOCKS must let the proxy resolve DNS", address.isUnresolved)
    }

    @Test
    fun unknownModeFailsClosedNeverDirect() {
        // No proxy is returned at all, so nothing can fall through to a direct connection.
        for (mode in listOf("", "off", "DIRECT", "HTTP")) {
            assertThrows(IllegalStateException::class.java) {
                NetworkPrivacy.proxyFor(NetworkPrivacy.Route(mode = mode))
            }
        }
    }
}
