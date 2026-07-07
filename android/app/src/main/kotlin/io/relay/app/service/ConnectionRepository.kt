package io.relay.app.service

import io.relay.app.core.ConnectionRules
import io.relay.app.core.ConnectionState
import io.relay.app.core.WarningCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single observable source of the connection state, written by
 * [SharingService], read by the UI. Every write goes through the shared
 * transition table — an illegal event is dropped, never applied.
 */
object ConnectionRepository {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Non-blocking advisories shown as dismissible banners (docs/errors.md → Warning). */
    private val _warnings = MutableStateFlow<Set<WarningCode>>(emptySet())
    val warnings: StateFlow<Set<WarningCode>> = _warnings.asStateFlow()

    fun setWarning(code: WarningCode, active: Boolean) {
        _warnings.update { if (active) it + code else it - code }
    }

    fun clearWarnings() {
        _warnings.value = emptySet()
    }

    /**
     * Sets the "Reconnecting…" annotation in place. This is a presentation flag,
     * not a state transition (ADR-0007): the state name is unchanged, so it does
     * not go through the transition table.
     */
    @Synchronized
    fun annotateReconnecting(active: Boolean) {
        _state.update { current ->
            when (current) {
                is ConnectionState.Connected -> current.copy(reconnecting = active)
                is ConnectionState.Advertising -> current.copy(reconnecting = active)
                else -> current
            }
        }
    }

    /**
     * After a reconnect that rebinds on a new hotspot IP, the old QR/code and any
     * client count are stale: return to Advertising with the fresh payload.
     * Only valid while sharing (Advertising/Connected); ignored otherwise.
     */
    @Synchronized
    fun reissue(payload: io.relay.app.core.QrPayload, typedCode: String?) {
        _state.update { current ->
            if (current is ConnectionState.Advertising || current is ConnectionState.Connected) {
                ConnectionState.Advertising(payload, typedCode)
            } else {
                current
            }
        }
    }

    /**
     * Applies [event]; [build] produces the new state, whose name must equal
     * the table's target. Returns false when the event is illegal here.
     */
    @Synchronized
    fun dispatch(event: String, build: (ConnectionState) -> ConnectionState): Boolean {
        val current = _state.value
        val target = ConnectionRules.target(current.stateName, event) ?: return false
        val next = build(current)
        check(next.stateName == target) {
            "Event '$event' from '${current.stateName}' must produce '$target', got '${next.stateName}'"
        }
        _state.value = next
        return true
    }
}
