package io.relay.app

import io.relay.app.core.RouteChoice
import io.relay.app.core.RouteChoice.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The automatic route (ADR-0011). Each case is an arrangement a user on
 * #124–#126 actually had, and what the PC should end up with.
 */
class RouteChoiceTest {

    private val proxy = "127.0.0.1:1819"

    @Test
    fun `inside the VPN the phone's route already is the VPN`() {
        // The group-1 phone (FSecure, LAN replies escape). It works today, and a
        // found proxy is no reason to change a path that already carries the VPN.
        val route = RouteChoice.choose("", automatic = true, relayInsideVpn = true, foundProxy = proxy)
        assertEquals(RouteChoice.Route("", Why.RELAY_IN_VPN), route)
    }

    @Test
    fun `excluded from the VPN, the VPN's proxy is the way back in`() {
        // The arrangement that works on a capturing VPN: Relay excluded so its
        // replies reach the PC, and the PC still gets the VPN — no longer by
        // finding the port and typing it in.
        val route = RouteChoice.choose("", automatic = true, relayInsideVpn = false, foundProxy = proxy)
        assertEquals(RouteChoice.Route(proxy, Why.VPN_PROXY), route)
    }

    @Test
    fun `a VPN app in proxy-only mode is shared too`() {
        // No system VPN at all, so nothing captures Relay — and the proxy is the
        // only place that VPN exists. Same inputs as the case above, by design.
        val route = RouteChoice.choose("", automatic = true, relayInsideVpn = false, foundProxy = proxy)
        assertEquals(proxy, route.proxy)
    }

    @Test
    fun `no VPN and no proxy is the phone's own connection`() {
        val route = RouteChoice.choose("", automatic = true, relayInsideVpn = false, foundProxy = null)
        assertEquals(RouteChoice.Route("", Why.NO_PROXY), route)
    }

    @Test
    fun `a typed proxy always wins`() {
        // Even over Relay being inside the VPN: the person said where it goes.
        val route = RouteChoice.choose("10.0.0.5:1080", automatic = true, relayInsideVpn = true, foundProxy = proxy)
        assertEquals(RouteChoice.Route("10.0.0.5:1080", Why.MANUAL), route)
    }

    @Test
    fun `automatic off means the phone's route whatever is found`() {
        val route = RouteChoice.choose("", automatic = false, relayInsideVpn = false, foundProxy = proxy)
        assertEquals(RouteChoice.Route("", Why.AUTOMATIC_OFF), route)
    }

    @Test
    fun `the first look always answers`() {
        val switcher = RouteChoice.Switcher()
        assertEquals(
            RouteChoice.Route("", Why.NO_PROXY),
            switcher.next("", automatic = true, relayInsideVpn = false, foundProxy = null),
        )
    }

    @Test
    fun `a proxy appearing mid-session is taken at once`() {
        // The VPN app was opened after sharing started.
        val switcher = RouteChoice.Switcher()
        switcher.next("", true, false, null)
        assertEquals(proxy, switcher.next("", true, false, proxy)?.proxy)
    }

    @Test
    fun `one missed look does not drop a working proxy`() {
        // Dropping it sends the PC around the VPN. A single slow answer from a
        // busy VPN client is not worth that.
        val switcher = RouteChoice.Switcher()
        switcher.next("", true, false, proxy)
        assertNull(switcher.next("", true, false, null))
        assertEquals(proxy, switcher.current?.proxy)

        // A hit in between resets the count.
        assertNull(switcher.next("", true, false, proxy))
        assertNull(switcher.next("", true, false, null))
        assertEquals(proxy, switcher.current?.proxy)
    }

    @Test
    fun `a proxy that stays gone is given up`() {
        // The VPN app was closed. Relay keeps the PC online over the phone's
        // own connection rather than forwarding into a port nobody holds.
        val switcher = RouteChoice.Switcher()
        switcher.next("", true, false, proxy)
        repeat(RouteChoice.MISSES_BEFORE_DROPPING - 1) {
            assertNull(switcher.next("", true, false, null))
        }
        assertEquals(
            RouteChoice.Route("", Why.NO_PROXY),
            switcher.next("", true, false, null),
        )
    }

    @Test
    fun `a proxy moving to another port follows it at once`() {
        val switcher = RouteChoice.Switcher()
        switcher.next("", true, false, proxy)
        assertEquals("127.0.0.1:10808", switcher.next("", true, false, "127.0.0.1:10808")?.proxy)
    }

    @Test
    fun `a new reason on the same path switches nothing but is remembered`() {
        val switcher = RouteChoice.Switcher()
        switcher.next("", true, true, null)
        assertNull(switcher.next("", false, true, null))
        assertEquals(Why.AUTOMATIC_OFF, switcher.current?.why)
    }

    @Test
    fun `typing a proxy switches immediately and is never dropped`() {
        val switcher = RouteChoice.Switcher()
        switcher.next("", true, true, null)
        assertEquals("127.0.0.1:7890", switcher.next("127.0.0.1:7890", true, true, null)?.proxy)
        repeat(5) { assertNull(switcher.next("127.0.0.1:7890", true, false, null)) }
        assertEquals(Why.MANUAL, switcher.current?.why)
    }
}
