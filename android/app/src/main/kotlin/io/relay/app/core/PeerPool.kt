package io.relay.app.core

import java.util.concurrent.ConcurrentHashMap

/**
 * Allocates distinct WireGuard tunnel addresses (`10.13.37.k` and `fd13:37::k`)
 * and per-client key pairs so multiple PCs can share one session simultaneously
 * without colliding on cryptokey routing.
 */
class PeerPool(
    private val serverPublicKey: String,
    private val endpointPort: Int,
    initialKeys: WgConfig.KeySet,
    private val generateClientKeyPair: () -> Pair<String, String>,
) {
    data class PeerAllocation(
        val clientId: String,
        val slot: Int,
        val clientPrivateKey: String,
        val clientPublicKey: String,
        val ipv4: String,
        val ipv6: String,
    ) {
        val allowedIpsForServer: String get() = "$ipv4/32,$ipv6/128"
    }

    private val peers = ConcurrentHashMap<String, PeerAllocation>()
    private var nextSlot = 3 // slot 2 is reserved for the initial QR/initial KeySet peer

    init {
        peers[INITIAL_PEER_ID] = PeerAllocation(
            clientId = INITIAL_PEER_ID,
            slot = 2,
            clientPrivateKey = initialKeys.clientPrivateKey,
            clientPublicKey = initialKeys.clientPublicKey,
            ipv4 = WgConfig.CLIENT_TUNNEL_IP,
            ipv6 = WgConfig.CLIENT_TUNNEL_IPV6,
        )
    }

    val initialAllocation: PeerAllocation get() = peers.getValue(INITIAL_PEER_ID)

    /**
     * Returns an existing allocation for [clientId] or allocates a fresh slot
     * (3..253) with its own Curve25519 key pair.
     */
    @Synchronized
    fun allocateFor(clientId: String): Pair<PeerAllocation, Boolean> {
        peers[clientId]?.let { return it to false }
        if (nextSlot > MAX_SLOT) {
            // Reuse slot 2 if pool is full so pairing never deadlocks cleanly.
            return initialAllocation to false
        }
        val slot = nextSlot++
        val (priv, pub) = generateClientKeyPair()
        val allocation = PeerAllocation(
            clientId = clientId,
            slot = slot,
            clientPrivateKey = priv,
            clientPublicKey = pub,
            ipv4 = "10.13.37.$slot",
            ipv6 = "fd13:37::${slot.toString(16)}",
        )
        peers[clientId] = allocation
        return allocation to true
    }

    fun toWgParams(
        allocation: PeerAllocation,
        dns: String = WgConfig.DEFAULT_DNS,
        dohUrl: String? = null,
        dualStackIpv6: Boolean = true,
    ): WgParams = WgParams(
        serverPublicKey = serverPublicKey,
        clientPrivateKey = allocation.clientPrivateKey,
        allowedIps = if (dualStackIpv6) WgConfig.CLIENT_ALLOWED_IPS_DUAL else WgConfig.CLIENT_ALLOWED_IPS,
        endpointPort = endpointPort,
        dns = dns.ifBlank { WgConfig.DEFAULT_DNS },
        clientAddress = "${allocation.ipv4}/32",
        clientIpv6 = if (dualStackIpv6) "${allocation.ipv6}/128" else null,
        dnsIpv6 = if (dualStackIpv6) WgConfig.SERVER_TUNNEL_IPV6 else null,
        dohUrl = dohUrl?.takeIf { it.isNotBlank() },
    )

    fun snapshot(): List<PeerAllocation> = peers.values.sortedBy { it.slot }

    companion object {
        const val INITIAL_PEER_ID = "primary"
        private const val MAX_SLOT = 253
    }
}
