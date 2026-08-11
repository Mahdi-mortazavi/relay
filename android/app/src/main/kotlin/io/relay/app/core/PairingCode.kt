package io.relay.app.core

import java.security.SecureRandom

/**
 * The two-digit code a person reads off the phone and types on their PC.
 * Contract: /shared/pairing-beacon.md — change that first.
 *
 * It is a selector, not a secret: ninety values would be indefensible as a
 * password, and the beacon that carries it is visible to everything on the
 * network anyway. What protects the connection is the approval prompt on the
 * phone, not the difficulty of guessing this. See [io.relay.app.net.ClientGate].
 */
object PairingCode {

    const val LENGTH = 2

    /** Lowest value. Starting at 10 means no code ever has a leading zero. */
    const val MIN = 10

    /** Highest value. */
    const val MAX = 99

    private val random = SecureRandom()

    /**
     * Draws a code, avoiding [taken] — the codes of phones already announcing
     * themselves on this network.
     *
     * Chooses uniformly from the codes that are still free rather than guessing
     * and retrying. Rejection sampling gets steadily worse exactly when it
     * matters most: with a crowded network it can run out of tries and hand
     * back a code it was told to avoid, and it can do that while free codes
     * remain. Enumerating ninety strings costs nothing and cannot fail that way.
     *
     * When every value is taken it returns one anyway. A collision produces a
     * "which of these two?" prompt on the PC — worse than a unique code, and
     * far better than refusing to share.
     */
    fun draw(taken: Set<String> = emptySet()): String {
        val free = (MIN..MAX).map { it.toString() }.filterNot { it in taken }
        if (free.isEmpty()) return next()
        return free[random.nextInt(free.size)]
    }

    private fun next(): String = (MIN + random.nextInt(MAX - MIN + 1)).toString()

    /**
     * True when [input] is a code this scheme could have issued. Whitespace is
     * ignored so "4 2" works, since a person reading digits aloud pauses.
     */
    fun isValid(input: String?): Boolean = normalize(input)?.let { it.length == LENGTH } ?: false

    /**
     * Strips whitespace and returns the digits, or null if anything else is
     * present. Both platforms must normalise identically or a code that looks
     * accepted in the box is rejected by the matcher — that exact mismatch has
     * already cost this project one bug.
     */
    fun normalize(input: String?): String? {
        val trimmed = (input ?: return null).filterNot { it.isWhitespace() }
        if (trimmed.length != LENGTH) return null
        if (!trimmed.all { it in '0'..'9' }) return null
        if (trimmed[0] == '0') return null
        return trimmed
    }
}
