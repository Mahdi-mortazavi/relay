package io.relay.app.net.wg

/**
 * Supplies the concrete [WgForwarder]. The real implementation ([GoWgForwarder])
 * is backed by the gomobile-built wireguard-go AAR; it is constructed
 * reflectively so the rest of the app compiles and runs even when the native
 * library is absent (in which case Full Mode reports it can't start rather than
 * failing the build).
 */
object WgForwarderProvider {
    private const val IMPLEMENTATION = "io.relay.app.net.wg.GoWgForwarder"

    fun create(): WgForwarder? = try {
        Class.forName(IMPLEMENTATION)
            .getDeclaredConstructor()
            .newInstance() as WgForwarder
    } catch (_: Throwable) {
        null
    }

    /**
     * True when this build actually ships the forwarder. Full Mode is a Phase 3
     * deliverable (docs/roadmap.md) and the AAR is not in the build yet, so the
     * UI must ask this before offering the mode: a selectable option that always
     * ends in WG_START_FAILED is worse than an option that is honestly labelled
     * as not available yet.
     *
     * Deliberately does not instantiate — availability is asked on every render
     * of the idle screen, and the answer cannot change within a process.
     */
    val isAvailable: Boolean by lazy {
        try {
            Class.forName(IMPLEMENTATION)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
