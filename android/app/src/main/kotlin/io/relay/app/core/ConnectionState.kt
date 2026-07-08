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
        /** True while the bounded reconnect policy is re-binding the hotspot (ADR-0007). */
        val reconnecting: Boolean = false,
    ) : ConnectionState {
        override val stateName = "Advertising"
    }

    data class Connected(
        val payload: QrPayload,
        val typedCode: String?,
        val clientCount: Int,
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
    HOTSPOT_OFF,
    HOTSPOT_LOST,
    PORT_IN_USE,
    SERVICE_FAILED,
    WG_START_FAILED,
}

/** Non-blocking, dismissible advisories shown as a banner (docs/errors.md → Warning). */
enum class WarningCode {
    NO_VPN_ACTIVE,
    BATTERY_UNRESTRICTED_DENIED,
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
