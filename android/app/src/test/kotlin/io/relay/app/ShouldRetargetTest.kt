package io.relay.app

import io.relay.app.core.ShouldRetarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a better link appears half way through a session.
 *
 * Found on hardware: with sharing already running over Wi-Fi, turning on USB
 * tethering — which the offer card asks people to do — left the QR and the
 * address in Advanced still naming 192.168.1.14, because the watcher only ever
 * acted when the address was *lost*. The beacon had already moved on; the QR
 * had not, so a laptop scanning it over the new cable was handed an address it
 * could not route to.
 */
class ShouldRetargetTest {

    @Test
    fun `a better address while nobody is connected is taken`() {
        assertTrue(
            ShouldRetarget.now(advertised = "192.168.1.14", best = "192.168.99.48", connected = false),
        )
    }

    @Test
    fun `a live session is never moved`() {
        // The restraint is the point. Re-issuing the payload drops back to
        // Advertising and rotates what the PC was given, so chasing a nominally
        // better link would end a session that is working for one that has never
        // carried a packet. Plugging in to charge must not disconnect anybody.
        assertFalse(
            ShouldRetarget.now(advertised = "192.168.1.14", best = "192.168.99.48", connected = true),
        )
    }

    @Test
    fun `the same address is not a change`() {
        // This runs every two seconds for the life of a session. Treating "still
        // the best" as a reason to act would re-issue the payload — and redraw
        // the QR — twice a second, forever.
        assertFalse(
            ShouldRetarget.now(advertised = "192.168.1.14", best = "192.168.1.14", connected = false),
        )
    }

    @Test
    fun `a session that has not advertised yet is not retargeted`() {
        // Null is a session starting, not an address changing: there is no
        // payload for this to replace, and the caller is about to issue one.
        assertFalse(ShouldRetarget.now(advertised = null, best = "192.168.99.48", connected = false))
        assertFalse(ShouldRetarget.now(advertised = "192.168.1.14", best = null, connected = false))
        assertFalse(ShouldRetarget.now(advertised = null, best = null, connected = false))
    }
}
