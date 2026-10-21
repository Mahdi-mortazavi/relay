package io.relay.app.net.wg

/**
 * The phone-side userspace WireGuard endpoint + netstack forwarder (ADR-0008).
 * The concrete implementation is backed by the gomobile-built wireguard-go AAR
 * ([GoWgForwarder]); this interface keeps the service decoupled from it and
 * lets the rest of the app compile and be tested without the native library.
 */
interface WgForwarder {
    /**
     * Brings up the endpoint from a server [config] (see [io.relay.app.core.WgConfig]).
     *
     * @param upstreamProxy a SOCKS5 `host:port` to forward the PC's traffic
     *   through — in practice a VPN client's own local port — or empty for the
     *   phone's default route, which is what every release before 2.8.6 did.
     *
     *   It exists because of a trade measured on hardware: an Android VPN
     *   captures by UID, so with Relay inside the tunnel the phone cannot
     *   answer the PC at all, and with Relay excluded the PC gets the phone's
     *   connection instead of the phone's VPN. Sending through the VPN's own
     *   proxy is the only arrangement that gives both.
     *
     * @throws WgForwarderException if the endpoint could not start.
     */
    fun start(config: String, upstreamProxy: String = "")

    fun stop()

    /**
     * Re-points a running endpoint at [upstreamProxy] (empty for the phone's
     * own route) without restarting it: same keys, same peer, no handshake
     * lost. New flows take the new path; open ones finish on the old.
     *
     * @return false when this build's library cannot switch mid-session or the
     *   switch was refused. The caller then keeps the route it had.
     */
    fun setUpstream(upstreamProxy: String): Boolean = false

    /**
     * When the peer last completed a handshake, in seconds since the epoch, or
     * 0 if it never has.
     *
     * The only honest "is a PC there?" signal Full Mode has. Fast Mode counts
     * accepted sockets; a UDP endpoint has none to count and answers the same
     * whether or not anyone is on the far side.
     */
    fun lastHandshakeUnix(): Long

    /** Tunnel byte counters, from the phone's point of view. */
    fun bytesReceived(): Long

    fun bytesSent(): Long
}

class WgForwarderException(message: String, cause: Throwable? = null) : Exception(message, cause)
