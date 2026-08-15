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

    /**
     * The eight-character code, which carries an address and nothing else.
     *
     * It used to be Fast Mode only, because Full Mode's key material does not
     * fit in a human code and an address alone could not build a tunnel. Since
     * ADR-0009 an address alone is enough: the PC dials the pairing port there
     * and the keys come over that exchange, gated by the person holding the
     * phone.
     *
     * It has to keep working, and not only as a nicety. The two-digit code is
     * withheld when the phone cannot announce itself, so if this returned null
     * as well, a phone on a network with no broadcast would display no code at
     * all -- shareable only by QR, with nothing on screen saying why.
     */
    override fun issueTypedCode(payload: QrPayload): String? =
        TypedCode.encode(payload.host, payload.port)
}
