package io.relay.app.core

/**
 * Where the PC's traffic leaves this phone, decided again and again while
 * sharing rather than once at start (ADR-0011).
 *
 * There are two ways out, and which is right depends on how the VPN on this
 * phone treats Relay — something the person did in another app, can change at
 * any moment, and was asked to report to us by hand until now:
 *
 * - **The phone's own route.** An ordinary socket. If Relay is inside the VPN,
 *   that socket goes through the VPN and the PC gets it. This is what every
 *   release did, and it stays the answer whenever it already works.
 * - **The VPN app's local proxy.** If Relay is *outside* the VPN — excluded in
 *   its per-app list, which is what makes the connection to the PC possible on
 *   a capturing VPN — then the phone's own route skips the VPN and the PC gets
 *   the phone's bare connection. The VPN client's SOCKS5 port is the one door
 *   back in. The same is true of a VPN app running in proxy-only mode, where
 *   there is no system VPN at all and nothing to capture Relay's replies: then
 *   the proxy is the *only* way the PC can get that VPN.
 *
 * So the rule is: inside the VPN, use the route; outside it, use the VPN's
 * proxy if one answers; otherwise the route. A proxy someone typed by hand
 * always wins and never falls back — that is a person stating where traffic
 * must go, and quietly sending it somewhere else would be a leak.
 *
 * Pure and free of Android types, like [HandshakeWatch], so it is decided on
 * the JVM where the tests run.
 */
object RouteChoice {

    /** How often the service looks again while sharing. Loopback probes; cheap. */
    const val POLL_MS = 10_000L

    /**
     * How many looks in a row must miss before a proxy that was working is
     * given up.
     *
     * Dropping it sends the PC out by the phone's own route, which — when Relay
     * is outside the VPN — is the phone's bare connection. One probe that lost
     * a race with a busy VPN client is not a reason to do that; two in a row,
     * twenty seconds apart, is a VPN app that has closed its port.
     */
    const val MISSES_BEFORE_DROPPING = 2

    /** Why a route was chosen. Written to the log, so a report says it outright. */
    enum class Why {
        /** A proxy was typed in Advanced. Never second-guessed. */
        MANUAL,

        /** Automatic is off and nothing was typed: the phone's own route, always. */
        AUTOMATIC_OFF,

        /** Relay itself is inside the VPN, so the phone's route already is the VPN. */
        RELAY_IN_VPN,

        /** Relay is outside any VPN and a VPN app's proxy answered. */
        VPN_PROXY,

        /** Relay is outside any VPN and no proxy answered. */
        NO_PROXY,
    }

    /** [proxy] is a SOCKS5 `host:port`, or empty for the phone's own route. */
    data class Route(val proxy: String, val why: Why) {
        val viaProxy: Boolean get() = proxy.isNotEmpty()
    }

    /**
     * One decision from one look.
     *
     * @param manualProxy what is typed in Advanced, already validated; empty
     *   when nothing usable is.
     * @param automatic whether Relay may choose for itself.
     * @param relayInsideVpn whether Relay's own default network is a VPN.
     * @param foundProxy a loopback SOCKS5 port that granted UDP ASSOCIATE, or
     *   null. Only consulted when it could change the answer.
     */
    fun choose(
        manualProxy: String,
        automatic: Boolean,
        relayInsideVpn: Boolean,
        foundProxy: String?,
    ): Route = when {
        manualProxy.isNotEmpty() -> Route(manualProxy, Why.MANUAL)
        !automatic -> Route("", Why.AUTOMATIC_OFF)
        relayInsideVpn -> Route("", Why.RELAY_IN_VPN)
        foundProxy != null -> Route(foundProxy, Why.VPN_PROXY)
        else -> Route("", Why.NO_PROXY)
    }

    /**
     * [choose], with memory: the same inputs over time, turned into the
     * changes worth making.
     *
     * Not thread-safe; the service drives it from one coroutine.
     */
    class Switcher {
        /** The route in force, or null before the first look. */
        var current: Route? = null
            private set

        private var misses = 0

        /**
         * Puts back [route] after a switch [next] proposed could not be made,
         * so the next look compares against what is really in force.
         */
        fun restore(route: Route) {
            current = route
            misses = 0
        }

        /**
         * Returns the route to switch to, or null to keep the one in force.
         *
         * The first look always answers. After that, the only change that is
         * held back is a working VPN proxy disappearing — see
         * [MISSES_BEFORE_DROPPING]. Every other change is taken at once: a proxy
         * appearing moves the PC onto the VPN, a typed proxy is the person's
         * word, and Relay landing inside the VPN means the route is the VPN.
         */
        fun next(
            manualProxy: String,
            automatic: Boolean,
            relayInsideVpn: Boolean,
            foundProxy: String?,
        ): Route? {
            val wanted = choose(manualProxy, automatic, relayInsideVpn, foundProxy)
            val now = current
            if (now == null) {
                current = wanted
                misses = 0
                return wanted
            }
            if (wanted == now) {
                misses = 0
                return null
            }
            val proxyVanished = now.why == Why.VPN_PROXY && wanted.why == Why.NO_PROXY
            if (proxyVanished && ++misses < MISSES_BEFORE_DROPPING) return null
            misses = 0
            // Same path, different reason (say, auto turned off while already
            // on the phone's route): nothing to switch, but remember why.
            current = wanted
            return wanted.takeIf { it.proxy != now.proxy }
        }
    }
}
