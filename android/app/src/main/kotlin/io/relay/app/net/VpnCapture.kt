package io.relay.app.net

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Whether this phone's own VPN would swallow Relay's replies to the PC.
 *
 * Android routes by UID. A full-tunnel VPN claims every UID but its own, so
 * Relay's traffic *to the laptop* is routed into the VPN's tun rather than out
 * over Wi-Fi -- measured directly on hardware:
 *
 *     ip route get 192.168.1.13 uid 10677  -> dev tun0  src 26.26.26.1   (Relay)
 *     ip route get 192.168.1.13 uid 10601  -> dev wlan0 src 192.168.1.14 (the VPN app)
 *
 * Inbound still works, so the pairing exchange completes and the WireGuard
 * endpoint receives the laptop's handshake. What never arrives is the *reply*,
 * and the laptop reports a tunnel that came up and was never answered -- a
 * message that sends someone looking at their QR code for a fault that is on
 * the other device entirely.
 *
 * There is no way out from inside the app, and all three obvious ones were
 * tried on hardware: `Network.bindSocket` fails EPERM for an app inside a VPN,
 * binding the source address leaves the UID rule deciding the interface, and
 * `SO_BINDTODEVICE` needs CAP_NET_RAW. wireguard-go's sticky sockets, which
 * would reply out of the arrival interface, are disabled on Android upstream.
 *
 * So this does not fix it. It detects it, so the apps can say what is actually
 * wrong instead of blaming the pairing.
 */
object VpnCapture {

    /**
     * True when a datagram aimed at [client] would leave by something other
     * than the interface that owns [advertisedHost].
     *
     * Asks the kernel rather than parsing routes: connecting a UDP socket
     * performs the route lookup and binds the source address the reply would
     * carry, without sending a packet. If that address is not the one being
     * advertised, the reply is going somewhere the laptop cannot see.
     */
    fun wouldSwallow(client: String, advertisedHost: String): Boolean = try {
        DatagramSocket().use { probe ->
            probe.connect(InetSocketAddress(InetAddress.getByName(client), DISCARD_PORT))
            val chosen = probe.localAddress?.hostAddress
            chosen != null && chosen != advertisedHost && !chosen.startsWith("0.")
        }
    } catch (_: Exception) {
        // Undecidable is not the same as broken: never claim a fault on the
        // strength of a probe that failed for its own reasons.
        false
    }

    /**
     * A stand-in for "some laptop on the same network as [advertisedHost]", so
     * the reply path can be asked about *before* any PC has connected.
     *
     * Why this is needed at all: [wouldSwallow] is called from the pairing
     * server's configuration step, which only runs once a PC has completed a
     * TCP connection. When the capture is severe enough to stop that handshake
     * completing, `accept()` never returns — so the phone shows no prompt *and*
     * no warning. The one case that most needs the message is the case that
     * cannot produce it. Reported as #126, third category.
     *
     * The routing decision is per-destination-prefix, not per-host, so any
     * address on the same /24 answers the same question. `.1` is the usual
     * gateway; when this phone *is* `.1` (its own hotspot) `.2` is used instead,
     * because a route lookup to our own address answers a different question.
     *
     * Returns null for anything that is not a dotted IPv4 quad — an address
     * this cannot parse is one it should not guess about.
     */
    fun aPeerOn(advertisedHost: String): String? {
        val parts = advertisedHost.split(".")
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        if (octets.any { it !in 0..255 }) return null
        val last = if (octets[3] == 1) 2 else 1
        return "${octets[0]}.${octets[1]}.${octets[2]}.$last"
    }

    /** Any port will do; connect() only performs the lookup, nothing is sent. */
    private const val DISCARD_PORT = 9
}
