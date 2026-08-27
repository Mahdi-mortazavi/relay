package io.relay.app.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * Decides whether a computer may use this phone's proxy, by asking the person
 * holding the phone.
 *
 * This is what makes a two-digit pairing code defensible. The code picks a
 * phone out of the ones announcing themselves; it cannot also be the thing that
 * keeps strangers out, because ninety values is not a secret. The gate is: the
 * first connection from an address nobody has approved stops and waits for a
 * human. Contract: /shared/pairing-beacon.md, "Approval".
 *
 * Decisions last for the sharing session and are never persisted. A decision
 * remembered across sessions is a decision made about a network you may have
 * since left.
 */
class ClientGate(
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    enum class Decision { ALLOWED, DENIED }

    /** A client waiting on a person. Null when nothing is pending. */
    data class Pending(val address: String)

    private val decisions = ConcurrentHashMap<String, Decision>()

    /**
     * One promise per waiting client.
     *
     * Deliberately a CompletableDeferred rather than a raw continuation. A
     * continuation resumed by hand runs the rest of the coroutine *inline, on
     * whatever thread called resume* -- which here is the UI thread, the moment
     * someone taps Allow. The coroutine that continues is the one that goes on
     * to relay the connection, so tapping Allow would have run socket I/O on
     * the main thread: a frozen app, and on Android a
     * NetworkOnMainThreadException. Completing a deferred instead wakes the
     * waiter on its own dispatcher, where it belongs.
     */
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Decision>>()
    private val queue = Mutex()

    private val _pending = MutableStateFlow<Pending?>(null)

    /** The address currently waiting for an answer, for the UI to prompt about. */
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    /**
     * Suspends until the person allows or denies [address], returning at once
     * if they have already decided about it this session.
     *
     * Silence is a denial. A phone in a pocket must fail closed, or "I did not
     * notice the prompt" becomes "anyone on this network may use my data".
     */
    suspend fun authorize(address: String): Boolean {
        decisions[address]?.let { return it == Decision.ALLOWED }

        // One prompt at a time: two computers connecting together must not race
        // to replace each other's dialog, leaving one waiting on an answer the
        // person was never shown.
        return queue.withLock {
            decisions[address]?.let { return@withLock it == Decision.ALLOWED }
            val answer = CompletableDeferred<Decision>()
            waiters[address] = answer
            _pending.value = Pending(address)
            var answered = true
            val decision = try {
                withTimeout(timeoutMs) { answer.await() }
            } catch (_: TimeoutCancellationException) {
                answered = false
                Decision.DENIED
            } finally {
                waiters.remove(address)
                _pending.value = null
            }
            // Only a person's answer is remembered.
            //
            // Refusing on silence is right and stays: a phone in a pocket must
            // fail closed. Recording that refusal was not. Nobody decided
            // anything, and yet every later attempt from that computer was
            // turned away in milliseconds without anyone being asked -- so the
            // one prompt that was missed was the only prompt there would ever
            // be, and the person was left pressing Connect against a phone that
            // had quietly stopped listening to them.
            //
            // Reproduced on hardware: one missed prompt, and the next request
            // came back ERR_PAIRING_DENIED in 0.03s with no dialog. The only
            // way out was restarting sharing, which is what toggling a VPN
            // happens to do -- which is why this was reported as a VPN bug.
            if (answered) decisions[address] = decision
            decision == Decision.ALLOWED
        }
    }

    /**
     * Called by the UI when the person answers. Returns immediately: completing
     * the promise hands the waiting coroutine back to its own dispatcher rather
     * than running it here.
     */
    fun resolve(address: String, allowed: Boolean) {
        val decision = if (allowed) Decision.ALLOWED else Decision.DENIED
        decisions[address] = decision
        waiters.remove(address)?.complete(decision)
        if (_pending.value?.address == address) _pending.value = null
    }

    /** True when [address] has already been decided, either way. */
    fun isKnown(address: String): Boolean = decisions.containsKey(address)

    /** Forgets every decision. Called when sharing stops. */
    fun reset() {
        decisions.clear()
        waiters.keys.toList().forEach { waiters.remove(it)?.complete(Decision.DENIED) }
        _pending.value = null
    }

    companion object {
        const val TIMEOUT_MS = 60_000L
    }
}
