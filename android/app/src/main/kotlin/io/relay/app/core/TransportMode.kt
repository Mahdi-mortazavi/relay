package io.relay.app.core

/**
 * The two transport modes (§4.1). The selected mode decides which server the
 * phone brings up and which `mode` the QR carries; it is not a state-machine
 * state, so switching needs no restart (ADR-0008, AC3.3).
 */
enum class TransportMode(val payloadMode: String) {
    FAST(QrPayload.MODE_SOCKS5),
    FULL(QrPayload.MODE_WIREGUARD);

    companion object {
        fun fromSetting(value: String?): TransportMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: FAST
    }
}
