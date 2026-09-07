package io.relay.app

import io.relay.app.net.UsbLink.Cable
import io.relay.app.net.UsbLink.Offer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the cable offer comes back after it has been waved away.
 *
 * The signal behind the offer cannot tell a computer from a wall charger — see
 * UsbLink.cableToComputer — so a dismissal has to be cheap to undo. Getting
 * this wrong is silent in both directions: too sticky and someone loses the
 * feature for the life of the process on the strength of one wrong guess; not
 * sticky enough and the card they just dismissed is back two seconds later.
 */
class UsbOfferTest {

    @Test
    fun `an offer stands until it is dismissed`() {
        val offer = Offer()
        assertEquals(Cable.Offered, offer.observe(Cable.Offered))
        assertEquals(Cable.Offered, offer.observe(Cable.Offered))
    }

    @Test
    fun `a dismissed offer does not come straight back`() {
        // The poll runs every two seconds. Without this the card would reappear
        // on the next tick, which is worse than never having offered.
        val offer = Offer()
        offer.observe(Cable.Offered)
        offer.dismiss()

        repeat(5) { assertEquals(Cable.Absent, offer.observe(Cable.Offered)) }
    }

    @Test
    fun `unplugging and plugging back in is someone asking again`() {
        val offer = Offer()
        offer.dismiss()
        assertEquals(Cable.Absent, offer.observe(Cable.Offered))

        assertEquals(Cable.Absent, offer.observe(Cable.Absent)) // cable pulled
        assertEquals(Cable.Offered, offer.observe(Cable.Offered)) // and back in
    }

    @Test
    fun `turning tethering on is never hidden by a dismissal`() {
        // Carrying is not an offer, it is the "Over USB" line — the confirmation
        // that the cable is doing something. Suppressing it because an *offer*
        // was dismissed would hide the payoff from the person who took the
        // offer up.
        val offer = Offer()
        offer.dismiss()
        assertEquals(Cable.Absent, offer.observe(Cable.Offered))
        assertEquals(Cable.Carrying, offer.observe(Cable.Carrying))
    }

    @Test
    fun `dismissing before any cable is seen does not swallow the first one`() {
        // dismiss() is reachable only from the card, so this should not happen —
        // but the rule is stated in terms of a remembered situation, and a
        // remembered situation that was never observed is exactly the kind of
        // state that outlives its reason.
        val offer = Offer()
        offer.dismiss()
        assertEquals(Cable.Absent, offer.observe(Cable.Absent))
        assertEquals(Cable.Offered, offer.observe(Cable.Offered))
    }
}
