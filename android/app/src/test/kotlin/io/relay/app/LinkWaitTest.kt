package io.relay.app

import io.relay.app.core.LinkWait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long the phone is willing to wait for a link to appear.
 *
 * The numbers themselves are a judgement call. What is not a judgement call is
 * the shape around them, and each of these asserts a way the shape could be
 * broken without anyone noticing until a real phone was in someone's hand.
 */
class LinkWaitTest {

    @Test
    fun `the first look costs nothing`() {
        // The whole common case: a phone already on Wi-Fi. If the first delay is
        // ever nudged above zero, every single start gets slower to fix a case
        // that most people never hit — and nothing else here would catch it.
        assertEquals(0L, LinkWait.lookDelaysMs.first())
    }

    @Test
    fun `waits long enough for USB tethering to come up`() {
        // rndis0 takes a few seconds to get an address after the toggle. A
        // window under about five seconds would still fail the report this was
        // written for, which is the only reason any of this exists.
        assertTrue(
            "window is ${LinkWait.totalBoundMs} ms, too short for a link that is still negotiating",
            LinkWait.totalBoundMs >= 5_000,
        )
    }

    @Test
    fun `does not hold the screen long enough to read as a hang`() {
        // The other half of the trade. "Preparing…" for fifteen seconds is not
        // patience, it is a frozen app — and someone will force-quit before it
        // ever gets to answer.
        assertTrue(
            "window is ${LinkWait.totalBoundMs} ms, long enough to look broken",
            LinkWait.totalBoundMs <= 10_000,
        )
    }

    @Test
    fun `looks more than once`() {
        // A single look is the old behaviour wearing a new name: it would pass
        // every other assertion here while changing nothing.
        assertTrue("only ${LinkWait.looks} look(s)", LinkWait.looks >= 4)
    }

    @Test
    fun `gaps never shrink`() {
        // Early looks are cheap and catch a link that is nearly up; later ones
        // are for a link that is still negotiating. A schedule that tightened
        // toward the end would spend its attempts in the wrong place.
        val gaps = LinkWait.lookDelaysMs.toList()
        assertEquals(
            "delays should be non-decreasing, got $gaps",
            gaps.sorted(),
            gaps,
        )
    }
}
