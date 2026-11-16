package io.relay.app.net.wg

/**
 * The phone-side userspace WireGuard endpoint + netstack forwarder (ADR-0008).
 * The concrete implementation is backed by the gomobile-built wireguard-go AAR
 * ([GoWgForwarder]); this interface keeps the service decoupled from it and
 * lets the rest of the app compile and be tested without the native library.
 */
interface WgForwarder {
    fun start(config: String, upstreamProxy: String = "")

    fun stop()

    fun setUpstream(upstreamProxy: String): Boolean = false

    fun lastHandshakeUnix(): Long

    fun bytesReceived(): Long

    fun bytesSent(): Long

    // --- Multi-peer support ---
    fun addPeer(publicKeyBase64OrHex: String, allowedIps: String): Boolean = false

    fun removePeer(publicKeyBase64OrHex: String): Boolean = false

    fun peerCount(): Int = 0

    fun peersStatsJson(): String = "[]"

    // --- Live flow monitor ---
    fun activeFlowsJson(): String = "[]"

    // --- Power-user DNS/DoH, quota/throttling, carrier TTL & ICMP ---
    fun setTunnelDns(dnsServer: String, dohUrl: String): Boolean = false

    fun setTrafficLimits(upBytesPerSec: Long, downBytesPerSec: Long, quotaBytes: Long): Boolean = false

    fun quotaUsedBytes(): Long = 0L

    fun quotaExceeded(): Boolean = false

    fun setTetherTtl(ttl: Int): Boolean = false

    fun enableIcmpRelay(enabled: Boolean): Boolean = false

    // --- Inbound LAN-to-PC port forwarding ---
    fun addInboundForward(proto: String, listenPort: Int, targetHost: String, targetPort: Int): Boolean = false

    fun removeInboundForward(proto: String, listenPort: Int): Boolean = false

    fun inboundForwardsJson(): String = "[]"

    // --- WireGuard-over-TCP bridge (for ADB USB forwarding) ---
    fun startWgTcpBridge(tcpPort: Int, wgUdpPort: Int): Int = -1

    fun stopWgTcpBridge() {}
}

class WgForwarderException(message: String, cause: Throwable? = null) : Exception(message, cause)
