package io.relay.app.net.wg

import java.lang.reflect.Method

/**
 * Bridges [WgForwarder] to the gomobile-built `relaywg` AAR (`/wg`).
 *
 * Reached reflectively rather than by a compile-time reference, because the AAR
 * is produced by a Go toolchain that only runs in CI. A direct call would make
 * every developer without gomobile unable to compile the app at all, and would
 * make the Android build depend on a Go build for a mode most people never turn
 * on. [WgForwarderProvider] already looks this class up the same way, so the
 * cost is one layer, not two.
 *
 * When the AAR is absent every call throws [WgForwarderException] with a
 * message that says so, which the service turns into WG_START_FAILED — an
 * honest "this build cannot do Full Mode" rather than a silent no-op.
 */
class GoWgForwarder : WgForwarder {

    private val startEndpoint: Method
    private val startEndpointVia: Method?
    private val stopEndpoint: Method
    private val isRunning: Method
    private val lastHandshakeUnix: Method
    private val bytesReceived: Method
    private val bytesSent: Method

    init {
        val relaywg = try {
            Class.forName(RELAYWG_CLASS)
        } catch (e: Throwable) {
            throw WgForwarderException(
                "Full Mode is not included in this build (the relaywg library is missing)", e,
            )
        }
        startEndpoint = relaywg.getMethod("startEndpoint", String::class.java)
        // Optional on purpose. The AAR is built by CI from /wg, and an app
        // built against an older one must keep working rather than refuse to
        // start Full Mode at all — so a missing method means "this library
        // cannot proxy", not "this library is broken".
        startEndpointVia = runCatching {
            relaywg.getMethod("startEndpointVia", String::class.java, String::class.java)
        }.getOrNull()
        stopEndpoint = relaywg.getMethod("stopEndpoint")
        isRunning = relaywg.getMethod("isRunning")
        lastHandshakeUnix = relaywg.getMethod("lastHandshakeUnix")
        bytesReceived = relaywg.getMethod("bytesReceived")
        bytesSent = relaywg.getMethod("bytesSent")
    }

    override fun start(config: String, upstreamProxy: String) {
        try {
            if (upstreamProxy.isEmpty()) {
                startEndpoint.invoke(null, config)
            } else {
                val via = startEndpointVia ?: throw WgForwarderException(
                    "This build's tunnel library is too old to forward through a proxy",
                )
                via.invoke(null, config, upstreamProxy)
            }
        } catch (e: Throwable) {
            // Reflection wraps whatever Go returned; the cause is the message
            // worth showing, and the wrapper says nothing useful.
            throw WgForwarderException(
                "The WireGuard endpoint did not start: ${e.cause?.message ?: e.message}", e,
            )
        }
        if (!running()) {
            throw WgForwarderException("The WireGuard endpoint reported no error but is not running")
        }
    }

    override fun stop() {
        // Deliberately swallowing: stop runs while the service is already
        // tearing down, often from a failure, and throwing here would replace
        // the real error with this one.
        runCatching { stopEndpoint.invoke(null) }
    }

    /** True when the Go side has a live endpoint, asked rather than remembered. */
    fun running(): Boolean = runCatching { isRunning.invoke(null) as Boolean }.getOrDefault(false)

    // Polled once a second to drive the screen. Every one of these answers 0
    // rather than throwing: a session that has just been torn down is the
    // normal case for the tick that arrives right after Stop, and an exception
    // there would turn a stale label into a crash.
    override fun lastHandshakeUnix(): Long = number(lastHandshakeUnix)

    override fun bytesReceived(): Long = number(bytesReceived)

    override fun bytesSent(): Long = number(bytesSent)

    private fun number(method: Method): Long =
        runCatching { method.invoke(null) as Long }.getOrDefault(0L)

    private companion object {
        /**
         * gomobile lowercases the package name and exposes each exported Go
         * function as a static method on a class named after the package.
         */
        const val RELAYWG_CLASS = "relaywg.Relaywg"
    }
}
