package io.relay.app.service

import io.relay.app.core.TrustProof
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One computer the person chose "Always allow" for.
 *
 * Identified by [key] and nothing else. [name] is whatever the computer called
 * itself the last time it paired -- display data, never consulted when deciding
 * anything.
 */
@Serializable
data class TrustedDevice(
    /** The 65-byte P-256 public key, base64. */
    val key: String,
    val name: String? = null,
    val addedAtMs: Long,
    val lastUsedAtMs: Long? = null,
) {
    /** `E0EB-C2E6`, so two computers with the same name can be told apart. */
    val fingerprint: String
        get() = TrustProof.decode(key)?.let(TrustProof::fingerprint) ?: "????-????"

    /** When it last paired, or failing that when it was added. */
    val activeAtMs: Long get() = lastUsedAtMs ?: addedAtMs
}

/**
 * The computers the phone no longer asks about (/shared/pairing-beacon.md →
 * "Trusted computers"; ADR-0012).
 *
 * Persisted, which is the one thing about approval that is: an *address*
 * decision still lasts a single sharing session (see [io.relay.app.net.ClientGate]).
 * What is kept here is a key only its owner can use, put here by a person tapping
 * Always allow.
 *
 * Storage is injected so the rules are testable without a phone. A store that
 * cannot be read, or reads back as something other than this list, is an empty
 * list: failing to remember someone costs one prompt, and the opposite mistake
 * costs a stranger the phone's data.
 *
 * Bounded at [MAX], replacing the computer that paired longest ago. That costs
 * it one prompt and nothing else, which is the safe direction to fail in.
 */
class TrustedDevices(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var save: (String) -> Unit = {}
    private var attached = false

    private val _devices = MutableStateFlow<List<TrustedDevice>>(emptyList())

    /** Most recently active first, for the list a person manages. */
    val devices: StateFlow<List<TrustedDevice>> = _devices.asStateFlow()

    /**
     * Connects this to its storage and loads it. Idempotent: the first caller
     * wins, because the activity and the sharing service both start here and
     * neither knows which is first.
     */
    @Synchronized
    fun attach(load: () -> String?, save: (String) -> Unit) {
        if (attached) return
        attached = true
        this.save = save
        _devices.value = parse(runCatching(load).getOrNull())
    }

    @Synchronized
    fun isTrusted(key: ByteArray): Boolean = find(TrustProof.encode(key)) != null

    /**
     * Adds a computer, or refreshes the name of one already here. The caller has
     * already verified [key]'s proof; this does not and cannot.
     */
    @Synchronized
    fun add(key: ByteArray, name: String?) {
        val encoded = TrustProof.encode(key)
        val now = clock()
        val current = _devices.value
        val existing = current.firstOrNull { it.key == encoded }
        val next = if (existing != null) {
            current.map { if (it.key == encoded) it.copy(name = name ?: it.name, lastUsedAtMs = now) else it }
        } else {
            current + TrustedDevice(encoded, name, addedAtMs = now)
        }
        commit(next.sortedByDescending { it.activeAtMs }.take(MAX))
    }

    /** Records that a trusted computer just paired. */
    @Synchronized
    fun used(key: ByteArray, name: String?) {
        val encoded = TrustProof.encode(key)
        if (find(encoded) == null) return
        val now = clock()
        commit(
            _devices.value.map {
                if (it.key == encoded) it.copy(name = name ?: it.name, lastUsedAtMs = now) else it
            }.sortedByDescending { it.activeAtMs },
        )
    }

    /** Stops trusting one computer. It is asked about again from its next request. */
    @Synchronized
    fun remove(key: String) {
        commit(_devices.value.filterNot { it.key == key })
    }

    @Synchronized
    fun clear() = commit(emptyList())

    private fun find(encoded: String) = _devices.value.firstOrNull { it.key == encoded }

    private fun commit(list: List<TrustedDevice>) {
        _devices.value = list
        runCatching { save(json.encodeToString(list)) }
    }

    companion object {
        /** How many computers the phone will remember. */
        const val MAX = 16

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The list in [text], or empty if it is absent or not a list. Entries
         * whose key is not a valid P-256 point are dropped one by one, so a
         * single damaged entry cannot take the rest with it, and cannot ever
         * match a request.
         */
        internal fun parse(text: String?): List<TrustedDevice> {
            if (text.isNullOrBlank()) return emptyList()
            return try {
                json.decodeFromString<List<TrustedDevice>>(text)
                    .filter { TrustProof.decodeKey(it.key) != null }
                    .distinctBy { it.key }
                    .sortedByDescending { it.activeAtMs }
                    .take(MAX)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
