package io.relay.app.core

/**
 * UI-facing connection state. Names and legal transitions mirror
 * /shared/connection-states.json (enforced by [ConnectionRules] and its test).
 */
sealed interface ConnectionState {
    val stateName: String

    data object Idle : ConnectionState {
        override val stateName = "Idle"
    }

    data object Preparing : ConnectionState {
        override val stateName = "Preparing"
    }

    data class Advertising(
        val payload: QrPayload,
        val typedCode: String?,
        /**
         * The two-digit code from /shared/pairing-beacon.md. Null when the
         * phone could not announce itself, in which case the QR and the long
         * typed code are the only ways in.
         */
        val shortCode: String? = null,
        /** True while the bounded reconnect policy is re-binding the hotspot (ADR-0007). */
        val reconnecting: Boolean = false,
    ) : ConnectionState {
        override val stateName = "Advertising"
    }

    data class Connected(
        val payload: QrPayload,
        val typedCode: String?,
        val shortCode: String? = null,
        val clientCount: Int = 0,
        val bytesUp: Long = 0,
        val bytesDown: Long = 0,
        /** True while the bounded reconnect policy is recovering a dropped hotspot (ADR-0007). */
        val reconnecting: Boolean = false,
    ) : ConnectionState {
        override val stateName = "Connected"
    }

    /** [code] is a stable error code from docs/errors.md. */
    data class Error(val code: ErrorCode) : ConnectionState {
        override val stateName = "Error"
    }
}

/** Android-side error codes; the full cross-platform taxonomy is docs/errors.md. */
enum class ErrorCode {
    /**
     * Nothing to share on at all: no Wi-Fi, no hotspot, no cable.
     *
     * Kept as the name it has always had — codes are never renamed — but it is
     * now the *last* answer rather than the only one. The three below are the
     * cases it used to swallow, each with a different next action.
     */
    HOTSPOT_OFF,

    /**
     * A link is up and has no address yet, even after [LinkWait]'s eight
     * seconds. Almost always USB tethering that is taking its time.
     */
    LINK_NEGOTIATING,

    /** The only links are the carrier's. A PC cannot route to mobile data. */
    ONLY_MOBILE_DATA,

    /** The only link with an address is the phone's own VPN tunnel. */
    ONLY_VPN,

    HOTSPOT_LOST,
    PORT_IN_USE,
    SERVICE_FAILED,
    WG_START_FAILED,
}

/** Non-blocking, dismissible advisories shown as a banner (docs/errors.md → Warning). */
enum class WarningCode {
    NO_VPN_ACTIVE,
    BATTERY_UNRESTRICTED_DENIED,

    /**
     * A PC took Relay's settings and the tunnel never handshaked, so replies
     * are not leaving this phone. See [io.relay.app.core.HandshakeWatch].
     */
    PC_GOT_NO_REPLY,
}

/**
 * The canonical transition table. The unit test asserts this is exactly the
 * contents of /shared/connection-states.json — edit that file first.
 */
object ConnectionRules {
    val states = setOf("Idle", "Preparing", "Advertising", "Connected", "Error")
    const val initial = "Idle"

    /** (fromState, event) -> toState */
    val transitions: Map<Pair<String, String>, String> = mapOf(
        ("Idle" to "start") to "Preparing",
        ("Preparing" to "ready") to "Advertising",
        ("Preparing" to "failure") to "Error",
        ("Preparing" to "stop") to "Idle",
        ("Advertising" to "clientConnected") to "Connected",
        ("Advertising" to "stop") to "Idle",
        ("Advertising" to "failure") to "Error",
        ("Connected" to "clientCountChanged") to "Connected",
        ("Connected" to "lastClientDisconnected") to "Advertising",
        ("Connected" to "stop") to "Idle",
        ("Connected" to "failure") to "Error",
        ("Error" to "dismiss") to "Idle",
        ("Error" to "retry") to "Preparing",
    )

    /** True when [event] is legal in [from]; illegal events must be ignored, never applied. */
    fun canTransition(from: String, event: String): Boolean =
        (from to event) in transitions

    fun target(from: String, event: String): String? = transitions[from to event]
}
