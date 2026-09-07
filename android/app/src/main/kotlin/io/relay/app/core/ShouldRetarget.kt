package io.relay.app.core

/**
 * Whether to move the advertised address onto a better link that has appeared
 * mid-session.
 *
 * The case this exists for is the cable. The offer card invites someone to turn
 * on USB tethering *while already sharing*, and when they do, the phone gains an
 * address that outranks the one in the QR. The beacon copes on its own — it
 * rebuilds its links every tick and announces each one — but the QR and the
 * address shown in Advanced are fixed at the moment the payload was issued, so
 * a laptop that scans the QR over the new cable is handed the Wi-Fi address and
 * has no route to it.
 *
 * The rule is one line and the restraint in it is the whole point, so it is
 * separated from the service and asserted rather than read.
 */
object ShouldRetarget {

    /**
     * @param advertised the address currently in the QR, or null before one exists
     * @param best the highest-ranked address the phone has right now
     * @param connected whether a PC is using the tunnel
     */
    fun now(advertised: String?, best: String?, connected: Boolean): Boolean {
        // Never under a live session. Re-issuing the payload drops back to
        // Advertising and rotates what the PC was given, so taking a nominally
        // better link would end a working session to chase one that has never
        // carried a packet. /shared/pairing-beacon.md says the same thing to the
        // client: stay on a path until it stops working.
        if (connected) return false

        // Nothing advertised yet is not a retarget; it is a session starting,
        // and the caller has not issued a payload for this to replace.
        if (advertised == null || best == null) return false

        return best != advertised
    }
}
