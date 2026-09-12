package io.relay.app.core

/**
 * Whether a PC that took Relay's settings ever got an answer back.
 *
 * This replaces a check that *predicted* the fault instead of observing it. The
 * old one asked the kernel, at pairing time, which interface a reply **would**
 * leave by, and raised a banner whenever the answer was not the advertised
 * link. A diagnostic log from a phone running 2.8.1 settled that question: in
 * one session the probe flipped its verdict four times, said "a reply would
 * leave by the VPN", and sixty-four seconds later that same phone completed a
 * pairing and carried traffic. A warning that fires on a connection which is
 * working is worse than no warning at all, because it is the reason the next
 * real one is not believed.
 *
 * What is left is two things the phone genuinely knows. A PC asked it for a
 * configuration — which only happens when someone pressed Connect on that PC —
 * and the WireGuard endpoint has seen no completed handshake since. Inbound
 * packets still arrive when a VPN captures the phone; what the capture eats is
 * the *reply*. So "it asked, and nothing came back" is the exact shape of the
 * fault [#126](https://github.com/Mahdi-mortazavi/relay/issues/126) describes,
 * and it is not the shape of anything else.
 *
 * Pure and free of Android types so the decision is testable on the JVM, which
 * is the only place any of this gets exercised before a phone is holding it.
 */
object HandshakeWatch {

    /**
     * How long the PC is given before the phone says anything.
     *
     * Matched to `handshakeTimeout` in `wg/cmd/relaywg-client/main.go`, which is
     * when the laptop gives up and shows `ERR_WG_NO_HANDSHAKE` — whose text
     * promises the user that "the phone will say on its own screen". Both are
     * timing the same wait, started within a fraction of a second of each other,
     * so they have to be the same number or that promise is only sometimes kept.
     * Change one and change the other.
     */
    const val GRACE_MS = 20_000L

    /**
     * True once a PC has waited [GRACE_MS] for a handshake that never arrived.
     *
     * @param configuredAtMs when a PC last took a configuration, or null when
     *   none ever has. Null is the ordinary case — a phone that is advertising
     *   and has not been asked yet — and it must never warn.
     * @param lastHandshakeUnix the endpoint's last completed handshake, in
     *   seconds since the epoch, or 0 for never. Any non-zero value means a
     *   reply did reach a PC during this sharing session: the endpoint and its
     *   keys are minted per session, so there is no handshake here left over
     *   from an earlier one. A session that once worked therefore stays quiet
     *   afterwards, which is right — a laptop closing its lid is not a fault.
     */
    fun replyLost(configuredAtMs: Long?, lastHandshakeUnix: Long, nowMs: Long): Boolean {
        if (configuredAtMs == null) return false
        if (lastHandshakeUnix > 0L) return false
        return nowMs - configuredAtMs >= GRACE_MS
    }
}
