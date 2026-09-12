package io.relay.app

import io.relay.app.core.ErrorCode
import io.relay.app.net.LinkSnapshot
import io.relay.app.net.LinkSnapshot.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line that answers "so what *was* plugged in?".
 *
 * Written from a real report in which the phone said, seven times, that there
 * was no usable link — and it took a message to the user, in another country,
 * to find out that a cable was plugged in with tethering just switched on and
 * still negotiating its address. Every assertion here is one of the four
 * different faults that line used to flatten into the same sentence.
 */
class LinkSnapshotTest {

    @Test
    fun `a cable still getting its address is not the same as no cable`() {
        // The exact case that cost the round trip. rndis0 is up and has no
        // address yet; if this collapsed to "nothing usable" the log would be
        // back where it started.
        assertEquals("no-ipv4", LinkSnapshot.verdictFor("rndis0", ipv4 = null, up = true))
    }

    @Test
    fun `a cable that is not plugged in says so differently`() {
        assertEquals("down", LinkSnapshot.verdictFor("rndis0", ipv4 = null, up = false))
    }

    @Test
    fun `a VPN that is down reads as down, not as a VPN`() {
        // "The VPN is not running" and "the VPN is running and we ignored it"
        // send whoever reads this to opposite ends of the problem.
        assertEquals("down", LinkSnapshot.verdictFor("tun0", "10.8.0.2", up = false))
        assertEquals("vpn", LinkSnapshot.verdictFor("tun0", "10.8.0.2", up = true))
    }

    @Test
    fun `the carrier's own link is named as cellular, not lumped in with the VPN`() {
        // Both are refused, for the same reason and by the same list, but they
        // mean completely different things to someone reading a report: one is
        // "turn on Wi-Fi", the other is "your VPN is in the way".
        assertEquals("cellular", LinkSnapshot.verdictFor("rmnet_data0", "10.66.4.1", up = true))
    }

    @Test
    fun `464XLAT's prefixed name is still recognised as the carrier`() {
        // Named v4-rmnet_data0, which is why the hints are substrings rather
        // than prefixes. A prefix match would advertise a carrier address.
        assertEquals("cellular", LinkSnapshot.verdictFor("v4-rmnet_data0", "10.66.4.1", up = true))
    }

    @Test
    fun `a usable link is named by its kind`() {
        assertEquals("wifi", LinkSnapshot.verdictFor("wlan0", "192.168.1.5", up = true))
        assertEquals("hotspot", LinkSnapshot.verdictFor("ap0", "192.168.43.1", up = true))
        assertEquals("usb", LinkSnapshot.verdictFor("rndis0", "192.168.42.129", up = true))
    }

    @Test
    fun `the summary is one line and names every link`() {
        val line = LinkSnapshot.summarise(
            listOf(
                Link("rndis0", ipv4 = null, verdict = "no-ipv4"),
                Link("wlan0", "192.168.1.5", verdict = "wifi", score = 3),
                Link("tun0", "10.8.0.2", verdict = "vpn"),
            )
        )

        // One line, because this goes into a ring buffer somebody scrolls on a
        // phone and then pastes into a chat. Six lines per failure pushes the
        // failure itself off the top of what anyone reads.
        assertTrue("summary spans lines: $line", !line.contains("\n"))

        // And it has to actually carry the three facts, or none of the above
        // reaches a report.
        assertTrue(line, line.contains("rndis0(no-ipv4)"))
        assertTrue(line, line.contains("wlan0(192.168.1.5 wifi score-3)"))
        assertTrue(line, line.contains("tun0(10.8.0.2 vpn)"))
    }

    @Test
    fun `a cable that is still coming up is not "there is no network"`() {
        // The whole reason for splitting the code. Telling someone whose cable
        // IS plugged in and whose tethering IS on to plug in a cable and turn on
        // tethering is the app telling them to do what they have just done, and
        // concluding the app is broken is the reasonable response.
        assertEquals(
            ErrorCode.LINK_NEGOTIATING,
            LinkSnapshot.diagnose(
                listOf(
                    Link("rndis0", ipv4 = null, verdict = "no-ipv4"),
                    Link("rmnet_data0", "10.66.4.1", verdict = "cellular"),
                )
            ),
        )
    }

    @Test
    fun `mobile data only asks for Wi-Fi, not for a cable that is already there`() {
        assertEquals(
            ErrorCode.ONLY_MOBILE_DATA,
            LinkSnapshot.diagnose(listOf(Link("rmnet_data0", "10.66.4.1", verdict = "cellular"))),
        )
    }

    @Test
    fun `a VPN alone gets its own message`() {
        // "Turn on Wi-Fi" is right; "turn on your VPN" would be absurd, and the
        // mobile-data message would be wrong in a way the user can see.
        assertEquals(
            ErrorCode.ONLY_VPN,
            LinkSnapshot.diagnose(listOf(Link("tun0", "10.8.0.2", verdict = "vpn"))),
        )
    }

    @Test
    fun `mobile data wins over a VPN, because one of them can be acted on`() {
        // A phone on mobile data with a VPN up has both. Turning Wi-Fi on fixes
        // it; there is nothing to "turn on" about the VPN, and saying so sends
        // the person to switch off the thing they are trying to share.
        assertEquals(
            ErrorCode.ONLY_MOBILE_DATA,
            LinkSnapshot.diagnose(
                listOf(
                    Link("tun0", "10.8.0.2", verdict = "vpn"),
                    Link("rmnet_data0", "10.66.4.1", verdict = "cellular"),
                )
            ),
        )
    }

    @Test
    fun `nothing at all is still the message it has always been`() {
        // HOTSPOT_OFF keeps its name and its meaning; it is now the last answer
        // rather than the only one. Codes are never renamed (docs/errors.md).
        assertEquals(ErrorCode.HOTSPOT_OFF, LinkSnapshot.diagnose(emptyList()))
        assertEquals(
            ErrorCode.HOTSPOT_OFF,
            LinkSnapshot.diagnose(listOf(Link("wlan0", ipv4 = null, verdict = "down"))),
        )
    }

    @Test
    fun `the diagnosis reads the same verdicts the summary prints`() {
        // diagnose() matches on verdict strings that verdictFor() produces. They
        // are in two places and nothing but this connects them: reword one and
        // every phone would report HOTSPOT_OFF again, silently, while the log
        // line underneath it said exactly what was wrong.
        val cellular = LinkSnapshot.verdictFor("rmnet_data0", "10.66.4.1", up = true)
        val vpn = LinkSnapshot.verdictFor("tun0", "10.8.0.2", up = true)
        val comingUp = LinkSnapshot.verdictFor("rndis0", ipv4 = null, up = true)

        assertEquals(
            ErrorCode.ONLY_MOBILE_DATA,
            LinkSnapshot.diagnose(listOf(Link("rmnet_data0", "10.66.4.1", cellular))),
        )
        assertEquals(
            ErrorCode.ONLY_VPN,
            LinkSnapshot.diagnose(listOf(Link("tun0", "10.8.0.2", vpn))),
        )
        assertEquals(
            ErrorCode.LINK_NEGOTIATING,
            LinkSnapshot.diagnose(listOf(Link("rndis0", null, comingUp))),
        )
    }

    @Test
    fun `no interfaces at all is said out loud`() {
        // An empty string in a report reads as a bug in the reporting, and
        // sends whoever is reading it looking in the wrong place.
        assertEquals("no interfaces at all", LinkSnapshot.summarise(emptyList()))
    }
}
