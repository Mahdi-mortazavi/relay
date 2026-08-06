package io.relay.app

import io.relay.app.net.LocalAddress
import io.relay.app.net.LocalAddress.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAddressTest {

    @Test
    fun `prefers the hotspot AP over station wifi`() {
        val chosen = LocalAddress.choose(
            listOf(
                Candidate("wlan0", "192.168.1.20"),
                Candidate("ap0", "192.168.43.1"),
            )
        )
        assertEquals("192.168.43.1", chosen)
    }

    @Test
    fun `falls back to station wifi on a shared LAN when no hotspot`() {
        val chosen = LocalAddress.choose(
            listOf(
                Candidate("wlan0", "192.168.1.20"),
                Candidate("dummy0", "10.0.2.3"),
            )
        )
        assertEquals("192.168.1.20", chosen)
    }

    @Test
    fun `uses a wired address when it's the only site-local one`() {
        assertEquals("10.0.0.5", LocalAddress.choose(listOf(Candidate("eth0", "10.0.0.5"))))
    }

    @Test
    fun `returns null when there are no candidates`() {
        assertNull(LocalAddress.choose(emptyList()))
    }

    @Test
    fun `rejects cellular and tunnel interfaces the client cannot route to`() {
        // issue #18: rmnet_data0's carrier address (10.174.226.46) was advertised
        // with hotspot and Wi-Fi off, producing a QR no PC could ever connect to.
        for (name in listOf(
            "rmnet_data0", "v4-rmnet_data0", "ccmni0", "pdp_ip0", "ppp0", "tun0", "dummy0",
        )) {
            assertFalse("expected $name to be rejected", LocalAddress.isReachableFromClient(name))
        }
    }

    @Test
    fun `keeps hotspot wifi and wired interfaces`() {
        // "softap0" contains "tap" — the blocklist must not match it.
        for (name in listOf("ap0", "swlan0", "softap0", "wlan0", "wlan1", "eth0", "rndis0")) {
            assertTrue("expected $name to be kept", LocalAddress.isReachableFromClient(name))
        }
    }

    @Test
    fun `score ranks hotspot above wifi above wired above other`() {
        val ap = LocalAddress.score("ap0", "192.168.43.1")
        val wifi = LocalAddress.score("wlan0", "192.168.1.5")
        val wired = LocalAddress.score("eth0", "10.0.0.5")
        val other = LocalAddress.score("rmnet0", "10.1.2.3")
        assert(ap > wifi) { "ap=$ap wifi=$wifi" }
        assert(wifi > wired) { "wifi=$wifi wired=$wired" }
        assert(wired > other) { "wired=$wired other=$other" }
    }
}
