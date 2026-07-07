package io.relay.app.net.wg

import com.wireguard.crypto.KeyPair
import io.relay.app.core.WgConfig

/**
 * Per-pairing WireGuard key generation using the official WireGuard crypto
 * (`com.wireguard.crypto`, no hand-rolled crypto — ADR-0008). The phone mints
 * both key pairs — its own (server) and the client's — so the QR can carry the
 * server public key and the client private key. All of it is discarded on
 * disconnect (§4.2).
 */
object WgKeys {
    fun generate(
        endpointPort: Int = WgConfig.DEFAULT_ENDPOINT_PORT,
        dns: String = WgConfig.DEFAULT_DNS,
    ): WgConfig.KeySet {
        val server = KeyPair()
        val client = KeyPair()
        return WgConfig.KeySet(
            serverPrivateKey = server.privateKey.toBase64(),
            serverPublicKey = server.publicKey.toBase64(),
            clientPrivateKey = client.privateKey.toBase64(),
            clientPublicKey = client.publicKey.toBase64(),
            endpointPort = endpointPort,
            dns = dns,
        )
    }
}
