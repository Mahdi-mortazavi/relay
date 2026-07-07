package io.relay.app.core

/**
 * Bounded auto-reconnect schedule. Contract: /shared/reconnect.md — any change
 * there first. A brief drop is absorbed silently; only exhausting the budget
 * surfaces an error (ADR-0007).
 */
object ReconnectPolicy {
    /** Delay before each retry attempt, in order. */
    val attemptDelaysMs = longArrayOf(1000, 2000, 2000, 3000, 3000)

    val attempts: Int get() = attemptDelaysMs.size

    /** The whole retry window; recovery is expected within this bound. */
    val totalBoundMs: Long get() = attemptDelaysMs.sum()
}
