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

    /**
     * The gomobile AAR itself. Availability is asked about *this*, not about
     * [IMPLEMENTATION]: the Kotlin bridge class ships in every build, so
     * checking for it would answer "yes" on a build with no Go library in it
     * and offer a mode that could only ever fail.
     */
    private const val NATIVE_LIBRARY = "relaywg.Relaywg"

    fun create(): WgForwarder? = try {
        Class.forName(IMPLEMENTATION)
            .getDeclaredConstructor()
            .newInstance() as WgForwarder
    } catch (_: Throwable) {
        null
    }

    /**
     * True when this build actually ships the Go library. The UI must ask this
     * before offering Full Mode: a selectable option that always ends in
     * WG_START_FAILED is worse than one honestly labelled as unavailable.
     *
     * Deliberately does not instantiate — availability is asked on every render
     * of the idle screen, and the answer cannot change within a process.
     */
    val isAvailable: Boolean by lazy {
        try {
            Class.forName(NATIVE_LIBRARY)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
