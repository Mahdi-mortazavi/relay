package io.relay.app

import io.relay.app.service.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one setting in Relay that changes where packets go.
 *
 * It exists for a trade nothing else escapes: an Android VPN captures by UID,
 * so with Relay inside the tunnel the phone cannot answer the PC at all, and
 * with Relay excluded the PC gets the phone's connection rather than the
 * phone's VPN. Pointing this at the VPN client's own local port is the only
 * arrangement that gives both.
 *
 * A typo here does not fail loudly. The tunnel comes up, the PC connects, and
 * nothing moves — so it is checked on the screen where it was typed.
 */
class UpstreamProxyTest {

    @Test
    fun `empty means the phone's own route, and is not an error`() {
        // The default, and what every release before this one did. Treating it
        // as invalid would make the field impossible to clear.
        assertTrue(Settings.isUsableProxy(""))
        assertTrue(Settings.isUsableProxy("   "))
    }

    @Test
    fun `a bare port means this phone`() {
        // ":10808" is what a person types, because every proxy this targets is
        // a VPN client listening on loopback on the same device.
        assertTrue(Settings.isUsableProxy(":10808"))
        assertTrue(Settings.isUsableProxy(":1080"))
    }

    @Test
    fun `an ordinary host and port is accepted`() {
        assertTrue(Settings.isUsableProxy("127.0.0.1:10808"))
        assertTrue(Settings.isUsableProxy("localhost:1080"))
    }

    @Test
    fun `a missing port is refused`() {
        // The commonest typo, and the one that produces a tunnel which comes up
        // and forwards nothing.
        assertFalse(Settings.isUsableProxy("127.0.0.1"))
        assertFalse(Settings.isUsableProxy("localhost"))
    }

    @Test
    fun `a port outside the range is refused`() {
        assertFalse(Settings.isUsableProxy("127.0.0.1:0"))
        assertFalse(Settings.isUsableProxy("127.0.0.1:65536"))
        assertFalse(Settings.isUsableProxy("127.0.0.1:-1"))
    }

    @Test
    fun `something that is not a port at all is refused`() {
        assertFalse(Settings.isUsableProxy("127.0.0.1:socks"))
        assertFalse(Settings.isUsableProxy("nonsense"))
    }

    @Test
    fun `an IPv6 literal keeps its last colon as the separator`() {
        // Splitting on the *first* colon would read "::1:1080" as host ":" and
        // refuse a perfectly good loopback address.
        assertTrue(Settings.isUsableProxy("[::1]:1080"))
        assertTrue(Settings.isUsableProxy("::1:1080"))
    }
}
