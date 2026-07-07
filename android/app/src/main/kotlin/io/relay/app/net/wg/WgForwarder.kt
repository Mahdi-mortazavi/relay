package io.relay.app.net.wg

/**
 * The phone-side userspace WireGuard endpoint + netstack forwarder (ADR-0008).
 * The concrete implementation is backed by the gomobile-built wireguard-go AAR
 * ([GoWgForwarder]); this interface keeps the service decoupled from it and
 * lets the rest of the app compile and be tested without the native library.
 */
interface WgForwarder {
    /**
     * Brings up the endpoint from a server [config] (see [io.relay.app.core.WgConfig]).
     * @throws WgForwarderException if the endpoint could not start.
     */
    fun start(config: String)

    fun stop()
}

class WgForwarderException(message: String, cause: Throwable? = null) : Exception(message, cause)
