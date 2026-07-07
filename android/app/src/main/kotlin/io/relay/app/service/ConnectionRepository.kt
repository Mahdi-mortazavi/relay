package io.relay.app.service

import io.relay.app.core.ConnectionRules
import io.relay.app.core.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single observable source of the connection state, written by
 * [SharingService], read by the UI. Every write goes through the shared
 * transition table — an illegal event is dropped, never applied.
 */
object ConnectionRepository {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

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
