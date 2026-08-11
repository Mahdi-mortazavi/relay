package io.relay.app.core

/**
 * Pure assembly of WireGuard configuration from a generated key set. Kept free
 * of any Android/crypto dependency so it is unit-tested on the JVM; key
 * generation itself lives in [io.relay.app.net.wg.WgKeys].
 *
 * Addressing is fixed and private to the tunnel: the phone (server) is
 * 10.13.37.1, the single client (laptop) is 10.13.37.2. Those two addresses are
 * written down in three places — here, `tunnelAddress` in /wg/relaywg.go, and
 * `WgClientConfig` on Windows — and all three must agree. They did not: this
 * file used to say 10.7.0.x, which routes nothing, because the endpoint drops
 * any packet whose source is outside the peer's allowed IPs.
 */
object WgConfig {
    const val SERVER_TUNNEL_IP = "10.13.37.1"
    const val CLIENT_TUNNEL_IP = "10.13.37.2"
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
     * The phone-side (server) configuration, in the form wireguard-go's IPC
     * actually consumes (ADR-0008).
     *
     * Not the `wg-quick` INI a person reads. `relaywg.Start` hands this straight
     * to `Device.IpcSet`, which takes flat `key=value` lines with **hex** keys
     * and lowercase names, and rejects an INI file outright. This function used
     * to emit the INI, so Full Mode could not start at all: every attempt came
     * back as WG_START_FAILED with "rejected configuration", which reads like a
     * broken tunnel rather than the wrong dialect.
     *
     * Order matters to the parser: interface settings first, then `public_key`
     * opens the peer section and everything after it belongs to that peer.
     *
     * @throws IllegalArgumentException if a key is not a 32-byte WireGuard key.
     */
    fun serverConfig(keys: KeySet): String = buildString {
        append("private_key=").append(toHex(keys.serverPrivateKey)).append('\n')
        append("listen_port=").append(keys.endpointPort).append('\n')
        // Peer section: the one laptop this pairing was minted for.
        append("public_key=").append(toHex(keys.clientPublicKey)).append('\n')
        // Cryptokey routing. Only packets whose source is the client's tunnel
        // address are accepted from this peer, and only packets addressed to it
        // are sent back — which is why this must match the address the client
        // gives itself, and why the two disagreeing produced a tunnel that
        // handshakes and then carries nothing.
        append("allowed_ip=").append(CLIENT_TUNNEL_IP).append("/32").append('\n')
    }

    /**
     * Converts a base64 WireGuard key to the hex the IPC form wants. Mirrors
     * `WgClientConfig.ToHex` on Windows deliberately: the two sides encode the
     * same keys for the same library, and a difference between them is a
     * tunnel that comes up and talks to nobody.
     *
     * @throws IllegalArgumentException if [base64Key] is not 32 bytes.
     */
    fun toHex(base64Key: String): String {
        val raw = try {
            java.util.Base64.getDecoder().decode(base64Key)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("not a base64 WireGuard key", e)
        }
        require(raw.size == KEY_BYTES) { "not a 32-byte WireGuard key (${raw.size} bytes)" }
        return buildString(KEY_BYTES * 2) {
            for (byte in raw) append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
        }
    }

    private const val KEY_BYTES = 32
    private const val HEX = "0123456789abcdef"
}
