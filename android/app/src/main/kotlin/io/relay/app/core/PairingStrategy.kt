package io.relay.app.core

/**
 * Pluggable pairing scheme (see docs/security.md). v1 is [DirectPairingStrategy]:
 * the QR carries connection parameters directly. Stronger strategies (expiring
 * QR, one-time token, mutual confirmation) implement this same interface and
 * bump the payload version — transport code never changes.
 */
interface PairingStrategy {
    /**
     * Builds the payload advertised to clients. [wg] is present only for Full
     * Mode (mode == wireguard) and carries the per-pairing key material.
     */
    fun issuePayload(
        mode: String,
        host: String,
        port: Int,
        deviceName: String?,
        wg: WgParams? = null,
    ): QrPayload

    /** The 8-char typed-code fallback, or null when unavailable (see /shared/typed-code.md). */
    fun issueTypedCode(payload: QrPayload): String?
}

class DirectPairingStrategy(
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : PairingStrategy {

    override fun issuePayload(
        mode: String,
        host: String,
        port: Int,
        deviceName: String?,
        wg: WgParams?,
    ): QrPayload =
        QrPayload(
            v = QrPayloadCodec.SUPPORTED_VERSION,
            mode = mode,
            host = host,
            port = port,
            name = deviceName,
            issuedAt = clock(),
            wg = wg,
        )

    /** Full Mode key material doesn't fit a human code — typed codes are Fast Mode only. */
    override fun issueTypedCode(payload: QrPayload): String? =
        if (payload.mode == QrPayload.MODE_SOCKS5) TypedCode.encode(payload.host, payload.port) else null
}
