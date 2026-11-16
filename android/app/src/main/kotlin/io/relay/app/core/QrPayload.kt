package io.relay.app.core

import kotlinx.serialization.Serializable

/**
 * Versioned pairing payload. Wire contract: /shared/qr-payload.schema.json.
 * Any change here must change the schema first and bump [QrPayloadCodec.SUPPORTED_VERSION].
 */
@Serializable
data class QrPayload(
    val v: Int,
    val mode: String,
    val host: String,
    val port: Int,
    val name: String? = null,
    val issuedAt: Long? = null,
    val transport: String? = null,
    val wg: WgParams? = null,
) {
    companion object {
        const val MODE_SOCKS5 = "socks5"
        const val MODE_WIREGUARD = "wireguard"
        const val TRANSPORT_LAN = "lan"
        const val TRANSPORT_WIFI_DIRECT = "wifi_direct"
        const val TRANSPORT_ADB = "adb"
    }
}

@Serializable
data class WgParams(
    val serverPublicKey: String,
    val clientPrivateKey: String,
    val allowedIps: String,
    val endpointPort: Int,
    val dns: String,
    val clientAddress: String? = null,
    val clientIpv6: String? = null,
    val dnsIpv6: String? = null,
    val dohUrl: String? = null,
)
