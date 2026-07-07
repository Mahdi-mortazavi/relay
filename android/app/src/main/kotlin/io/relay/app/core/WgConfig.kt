package io.relay.app.core

/**
 * Pure assembly of WireGuard configuration from a generated key set. Kept free
 * of any Android/crypto dependency so it is unit-tested on the JVM; key
 * generation itself lives in [io.relay.app.net.wg.WgKeys].
 *
 * Addressing is fixed and private to the tunnel: the phone (server) is
 * 10.7.0.1, the single client (laptop) is 10.7.0.2.
 */
object WgConfig {
    const val SERVER_TUNNEL_IP = "10.7.0.1"
    const val CLIENT_TUNNEL_IP = "10.7.0.2"
    const val CLIENT_ALLOWED_IPS = "0.0.0.0/0"
    const val DEFAULT_DNS = "1.1.1.1"
    const val DEFAULT_ENDPOINT_PORT = 51820

    /** Keys and parameters for one pairing. Private keys never leave the intended device. */
    data class KeySet(
        val serverPrivateKey: String,
        val serverPublicKey: String,
        val clientPrivateKey: String,
        val clientPublicKey: String,
        val endpointPort: Int = DEFAULT_ENDPOINT_PORT,
        val dns: String = DEFAULT_DNS,
    )

    /** What goes into the QR `wg` block: the client needs the server's *public* key and its own *private* key. */
    fun toWgParams(keys: KeySet): WgParams = WgParams(
        serverPublicKey = keys.serverPublicKey,
        clientPrivateKey = keys.clientPrivateKey,
        allowedIps = CLIENT_ALLOWED_IPS,
        endpointPort = keys.endpointPort,
        dns = keys.dns,
    )

    /**
     * The phone-side (server) config the userspace forwarder consumes
     * (ADR-0008). It listens on [KeySet.endpointPort] and accepts the single
     * client peer routed at 10.7.0.2/32; the netstack forwards its traffic out
     * over the phone's default network.
     */
    fun serverConfig(keys: KeySet): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${keys.serverPrivateKey}")
        appendLine("Address = $SERVER_TUNNEL_IP/24")
        appendLine("ListenPort = ${keys.endpointPort}")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${keys.clientPublicKey}")
        appendLine("AllowedIPs = $CLIENT_TUNNEL_IP/32")
    }
}
