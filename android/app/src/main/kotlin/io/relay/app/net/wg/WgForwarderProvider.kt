package io.relay.app.net.wg

/**
 * Supplies the concrete [WgForwarder]. The real implementation ([GoWgForwarder])
 * is backed by the gomobile-built wireguard-go AAR; it is constructed
 * reflectively so the rest of the app compiles and runs even when the native
 * library is absent (in which case Full Mode reports it can't start rather than
 * failing the build).
 */
object WgForwarderProvider {
    fun create(): WgForwarder? = try {
        Class.forName("io.relay.app.net.wg.GoWgForwarder")
            .getDeclaredConstructor()
            .newInstance() as WgForwarder
    } catch (_: Throwable) {
        null
    }
}
