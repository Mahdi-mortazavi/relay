package io.relay.app

import io.relay.app.net.VpnCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Choosing an address to ask the routing question about.
 *
 * The reply-path probe needs somewhere to aim before any PC has connected. Any
 * address on the same /24 gives the same answer, because the kernel decides by
 * prefix — but two specific choices would give the *wrong* answer, and both are
 * easy to write by accident.
 */
class VpnCapturePeerTest {

    @Test
    fun `picks an address on the same network`() {
        // Same prefix or the probe measures a different route entirely.
        assertEquals("192.168.1.1", VpnCapture.aPeerOn("192.168.1.14"))
        assertEquals("10.0.5.1", VpnCapture.aPeerOn("10.0.5.77"))
        assertEquals("192.168.43.1", VpnCapture.aPeerOn("192.168.43.128"))
    }

    @Test
    fun `never returns the phone's own address`() {
        // The trap. A phone acting as its own hotspot *is* 192.168.43.1, and a
        // route lookup to yourself resolves to loopback — which would answer
        // "not swallowed" on every hotspot, in every case, forever. A check
        // that cannot fail is worse than no check.
        val ownHotspot = "192.168.43.1"
        assertNotEquals(ownHotspot, VpnCapture.aPeerOn(ownHotspot))
        assertEquals("192.168.43.2", VpnCapture.aPeerOn(ownHotspot))

        // Same for any other network whose gateway slot we occupy.
        assertEquals("10.0.0.2", VpnCapture.aPeerOn("10.0.0.1"))
    }

    @Test
    fun `refuses to guess about anything that is not a dotted quad`() {
        // An address it cannot parse is one it must not invent a peer for:
        // probing a made-up address would produce a confident line in the
        // diagnostic log about a route nobody asked about.
        assertNull(VpnCapture.aPeerOn(""))
        assertNull(VpnCapture.aPeerOn("192.168.1"))
        assertNull(VpnCapture.aPeerOn("192.168.1.14.9"))
        assertNull(VpnCapture.aPeerOn("fe80::1"))
        assertNull(VpnCapture.aPeerOn("192.168.1.abc"))
        assertNull(VpnCapture.aPeerOn("192.168.1.999"))
        assertNull(VpnCapture.aPeerOn("not an address at all"))
    }
}
