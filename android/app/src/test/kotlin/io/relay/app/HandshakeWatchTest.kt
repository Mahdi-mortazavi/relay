package io.relay.app

import io.relay.app.core.HandshakeWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The banner this drives replaced one that fired on working connections.
 *
 * Every assertion here is a way of getting that wrong again. The two that
 * matter most are the quiet ones: a warning that appears when nothing is
 * broken is the reason nobody reads the next one.
 */
class HandshakeWatchTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `stays quiet until a PC has actually asked`() {
        // The ordinary case — a phone advertising, nobody connected yet. An
        // implementation that warns on "no handshake" alone would light the
        // banner on every phone that has been sharing for half a minute.
        assertFalse(
            HandshakeWatch.replyLost(
                configuredAtMs = null,
                lastHandshakeUnix = 0L,
                nowMs = now + 10 * HandshakeWatch.GRACE_MS,
            )
        )
    }

    @Test
    fun `a session that handshaked never warns, however long ago`() {
        // This is the regression that produced the old false positive. On the
        // 2.8.1 log this was written for, the phone had paired and carried
        // traffic while the banner said its VPN was blocking Relay. A handshake
        // is proof a reply got out; nothing after it can un-prove that.
        assertFalse(
            "a phone that answered a PC must never be told its replies are lost",
            HandshakeWatch.replyLost(
                configuredAtMs = now,
                lastHandshakeUnix = 1_699_999_000L,
                nowMs = now + 10 * HandshakeWatch.GRACE_MS,
            )
        )
    }

    @Test
    fun `does not warn while the PC is still within its own deadline`() {
        // The laptop is still trying. Warning here would put the banner up
        // during a handshake that is about to succeed on a slow link.
        assertFalse(
            HandshakeWatch.replyLost(
                configuredAtMs = now,
                lastHandshakeUnix = 0L,
                nowMs = now + HandshakeWatch.GRACE_MS - 1,
            )
        )
    }

    @Test
    fun `warns once the PC has waited out the whole grace with nothing back`() {
        // And it has to actually fire: ERR_WG_NO_HANDSHAKE on the laptop tells
        // the user in so many words that the phone is saying this on its screen.
        assertTrue(
            HandshakeWatch.replyLost(
                configuredAtMs = now,
                lastHandshakeUnix = 0L,
                nowMs = now + HandshakeWatch.GRACE_MS,
            )
        )
    }

    @Test
    fun `waits exactly as long as the client does`() {
        // wg/cmd/relaywg-client/main.go: handshakeTimeout = 20 * time.Second.
        // Nothing mechanical couples the two -- one is Go, one is Kotlin -- so
        // this assertion is the coupling. If the client's deadline is ever
        // changed, this fails and says where the other half lives.
        assertEquals(
            "must match handshakeTimeout in wg/cmd/relaywg-client/main.go",
            20_000L,
            HandshakeWatch.GRACE_MS,
        )
    }
}
