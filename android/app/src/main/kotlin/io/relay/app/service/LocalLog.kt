package io.relay.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * In-memory, local-only diagnostic log (zero-telemetry: never persisted, never
 * leaves the device). A bounded ring buffer surfaced in Advanced settings so a
 * user can see what happened without any data going anywhere.
 */
object LocalLog {
    private const val CAPACITY = 200

    data class Entry(val elapsedMs: Long, val message: String)

    private val start = System.currentTimeMillis()
    private val buffer = ArrayDeque<Entry>(CAPACITY)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    @Synchronized
    fun add(message: String) {
        if (buffer.size >= CAPACITY) buffer.removeFirst()
        buffer.addLast(Entry(System.currentTimeMillis() - start, message))
        _entries.value = buffer.toList()
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
    }
}
