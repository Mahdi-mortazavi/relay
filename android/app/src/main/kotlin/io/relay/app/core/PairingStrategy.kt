package io.relay.app.core

/**
 * Pluggable pairing scheme (see docs/security.md). v1 is [DirectPairingStrategy]:
 * the QR carries connection parameters directly. Stronger strategies (expiring
 * QR, one-time token, mutual confirmation) implement this same interface and
 * bump the payload version — transport code never changes.
 */
interface PairingStrategy {
    /** Builds the payload advertised to clients for the current session. */
    fun issuePayload(mode: String, host: String, port: Int, deviceName: String?): QrPayload

    /** The 8-char typed-code fallback, or null when unavailable (see /shared/typed-code.md). */
    fun issueTypedCode(payload: QrPayload): String?
}

class DirectPairingStrategy(
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : PairingStrategy {

    override fun issuePayload(mode: String, host: String, port: Int, deviceName: String?): QrPayload =
        QrPayload(
            v = QrPayloadCodec.SUPPORTED_VERSION,
            mode = mode,
            host = host,
            port = port,
            name = deviceName,
            issuedAt = clock(),
        )

    override fun issueTypedCode(payload: QrPayload): String? =
        TypedCode.encode(payload.host, payload.port)
}
