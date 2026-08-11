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
    private val stopEndpoint: Method
    private val isRunning: Method

    init {
        val relaywg = try {
            Class.forName(RELAYWG_CLASS)
        } catch (e: Throwable) {
            throw WgForwarderException(
                "Full Mode is not included in this build (the relaywg library is missing)", e,
            )
        }
        startEndpoint = relaywg.getMethod("startEndpoint", String::class.java)
        stopEndpoint = relaywg.getMethod("stopEndpoint")
        isRunning = relaywg.getMethod("isRunning")
    }

    override fun start(config: String) {
        try {
            startEndpoint.invoke(null, config)
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

    private companion object {
        /**
         * gomobile lowercases the package name and exposes each exported Go
         * function as a static method on a class named after the package.
         */
        const val RELAYWG_CLASS = "relaywg.Relaywg"
    }
}
