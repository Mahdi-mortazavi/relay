package io.relay.app

import io.relay.app.net.Beacon
import io.relay.app.net.LocalAddress
import io.relay.app.net.LocalAddress.Candidate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Which link the phone announces, and what it calls it.
 *
 * The Windows side asserts the *listener's* half of this against the same
 * section of /shared/test-vectors.json. This is the announcer's half: a phone
 * on a cable and Wi-Fi at once has to say which is which, or the laptop cannot
 * choose between them.
 */
class BeaconPathTest {

    @Test
    fun `a cable outranks every radio`() {
        // This is the bug that made USB tethering useless. USB interfaces were
        // not named anywhere in the scoring, so they took the baseline 1 and
        // lost to wlan0's 3 — and a laptop connected by cable was handed the
        // phone's Wi-Fi address, which it had no route to. The phone appeared
        // in the list and nothing could connect to it.
        val usb = LocalAddress.score("rndis0", "192.168.42.129")
        val wifi = LocalAddress.score("wlan0", "192.168.1.14")
        val hotspot = LocalAddress.score("ap0", "192.168.43.1")

        assertTrue("a cable must beat station Wi-Fi", usb > wifi)
        assertTrue("a cable must beat the phone's own hotspot", usb > hotspot)

        // Below the cable these two are ranked the opposite way here and on the
        // listener, and deliberately. This score picks the single address to
        // print in a QR, where the person holding the phone is almost certainly
        // on its hotspot. PathRank picks between beacons a PC actually heard,
        // and hearing the station Wi-Fi one means it is on that LAN. Do not
        // "fix" them into agreement; they answer different questions.
        assertTrue("the QR still prefers the hotspot to station Wi-Fi", hotspot > wifi)
    }

    @Test
    fun `with a cable in and the hotspot up, the QR carries the cable address`() {
        // The scores, not the ordering of the list: `choose` breaks a tie by
        // whichever candidate the interface enumeration happened to yield
        // first, so a tie here is a coin toss dressed up as a decision. USB and
        // a gateway-looking hotspot address were both worth 5.
        val chosen = LocalAddress.choose(
            listOf(
                Candidate("ap0", "192.168.43.1"),
                Candidate("wlan0", "192.168.1.14"),
                Candidate("rndis0", "192.168.42.129"),
            )
        )
        assertEquals("192.168.42.129", chosen)

        // And with the cable listed first, in case the order was doing the work.
        assertEquals(
            "192.168.42.129",
            LocalAddress.choose(
                listOf(
                    Candidate("rndis0", "192.168.42.129"),
                    Candidate("ap0", "192.168.43.1"),
                )
            ),
        )
    }

    @Test
    fun `every name Android has used for USB tethering is recognised`() {
        // rndis0 is the long-standing one; Android 11 and later prefer NCM and
        // name it ncm0; some OEMs simply call it usb0. Missing one of these
        // means the cable silently scores as "unknown link" on those phones.
        for (name in listOf("rndis0", "ncm0", "usb0")) {
            assertEquals("$name should be a USB link", "usb", LocalAddress.linkKind(name))
            assertTrue(
                "$name should outrank Wi-Fi",
                LocalAddress.score(name, "192.168.42.129") > LocalAddress.score("wlan0", "192.168.1.14"),
            )
        }
    }

    @Test
    fun `each kind of link is named the way the contract names it`() {
        assertEquals("wifi", LocalAddress.linkKind("wlan0"))
        assertEquals("hotspot", LocalAddress.linkKind("ap0"))
        assertEquals("hotspot", LocalAddress.linkKind("softap0"))
        assertEquals("usb", LocalAddress.linkKind("rndis0"))
    }

    @Test
    fun `an unrecognised link is left unnamed rather than guessed`() {
        // The contract makes the field optional and sorts an absent one last,
        // which is exactly where a link nobody here has a name for belongs.
        // Guessing "wifi" would send a laptop to a better-ranked path than the
        // one it should prefer.
        assertNull(LocalAddress.linkKind("bnep0"))
        assertNull(LocalAddress.linkKind("something-new0"))
    }

    @Test
    fun `links a laptop could never route to are never announced`() {
        // A VPN tun is always present on this phone — sharing one is the app's
        // whole purpose — and the carrier's link hands out site-local IPv4 that
        // looks perfectly advertisable and reaches nobody.
        assertTrue(LocalAddress.isReachable("rndis0", "192.168.42.129"))
        assertTrue(LocalAddress.isReachable("wlan0", "192.168.1.14"))
        assertFalse("a tun is a VPN, not a way in", LocalAddress.isReachable("tun0", "26.26.26.1"))
        assertFalse("rmnet is the carrier", LocalAddress.isReachable("rmnet1", "10.4.102.34"))
        assertFalse("an address-less link is not a way in", LocalAddress.isReachable("rndis0", ""))
    }

    @Test
    fun `every link name the shared vectors use is one this phone can produce`() {
        // The two sides meet here. Windows ranks the names in
        // /shared/test-vectors.json; this phone is the only thing that ever
        // sends them. A name added to the contract that linkKind cannot emit is
        // a rank nothing will ever occupy — a feature that is only ever tested
        // against a beacon no phone sends.
        val namesInVectors = SharedContracts.json("test-vectors.json")
            .jsonObject.getValue("beaconPaths")
            .jsonObject.getValue("cases").jsonArray
            .flatMap { it.jsonObject.getValue("beacons").jsonArray }
            .mapNotNull { it.jsonObject["link"]?.jsonPrimitive?.content }
            .toSet()

        val namesThisPhoneSends = listOf("rndis0", "ncm0", "usb0", "wlan0", "ap0", "softap0")
            .mapNotNull { LocalAddress.linkKind(it) }
            .toSet()

        assertEquals(
            "the contract names a link this phone has no interface name for",
            emptySet<String>(),
            namesInVectors - namesThisPhoneSends,
        )
    }

    @Test
    fun `a probe is answered with the address on the link it arrived by`() {
        // The match is by prefix, and the prefix is the one the interface
        // actually carries. USB tethering hands out a /24 out of 192.168.42.0,
        // which is close enough to a home LAN's 192.168.1.0/24 that a sloppier
        // comparison — first two octets, say — would answer a laptop on the
        // cable with the Wi-Fi address and leave it unable to connect.
        val overCable = InetAddress.getByName("192.168.42.129")
        val laptopOnCable = InetAddress.getByName("192.168.42.63")
        val overWifi = InetAddress.getByName("192.168.1.14")

        assertTrue(Beacon.inSameNetwork(overCable, laptopOnCable, 24))
        assertFalse(Beacon.inSameNetwork(overWifi, laptopOnCable, 24))
    }

    @Test
    fun `a prefix that does not land on a byte boundary is still respected`() {
        // A /22 covers 192.168.4.0 through 192.168.7.255. Masking whole bytes
        // only would put .8.x inside it, and the phone would answer a laptop it
        // shares no link with.
        val phone = InetAddress.getByName("192.168.4.10")
        assertTrue(Beacon.inSameNetwork(phone, InetAddress.getByName("192.168.7.99"), 22))
        assertFalse(Beacon.inSameNetwork(phone, InetAddress.getByName("192.168.8.1"), 22))
    }

    @Test
    fun `a nonsense prefix matches nothing rather than everything`() {
        // networkPrefixLength is -1 on interfaces that do not report one, and a
        // shift by a negative count is not a compile error. Matching everything
        // would answer every probe with the first address to hand.
        val phone = InetAddress.getByName("192.168.42.129")
        assertFalse(Beacon.inSameNetwork(phone, InetAddress.getByName("10.0.0.1"), -1))
        assertFalse(Beacon.inSameNetwork(phone, InetAddress.getByName("10.0.0.1"), 33))

        // Mixing families cannot match either; an IPv6 probe on a v4 link is
        // not a laptop on that link.
        assertFalse(Beacon.inSameNetwork(phone, InetAddress.getByName("::1"), 24))
    }

    @Test
    fun `the best path keeps the version every client has always spoken`() {
        // The compatibility rule, and the reason it exists. Relay 2.7.1 keys a
        // phone by its address and requires v==1, so a phone announcing two
        // addresses was listed twice by the released client, which then refused
        // to connect: "Two phones are showing that code (SM-A307FN, SM-A307FN)".
        // Keeping v1 on the best path leaves that client with exactly one
        // address — and the best one, which it can route to.
        assertEquals(Beacon.VERSION, Beacon.versionFor(isBestPath = true))
        assertEquals(Beacon.VERSION_EXTRA_PATH, Beacon.versionFor(isBestPath = false))
        assertEquals(1, Beacon.VERSION)
    }

    @Test
    fun `the versions the contract says we send are the ones we send`() {
        // /shared/test-vectors.json -> beaconVersions.sends lists the version
        // per link kind, ordered best first. The Windows suite asserts it can
        // read every one of them; this asserts the phone emits them.
        val sends = SharedContracts.json("test-vectors.json")
            .jsonObject.getValue("beaconVersions")
            .jsonObject.getValue("sends")
            .jsonObject.getValue("links").jsonArray

        sends.forEachIndexed { index, entry ->
            val link = entry.jsonObject.getValue("link").jsonPrimitive.content
            val version = entry.jsonObject.getValue("version").jsonPrimitive.content.toInt()
            assertEquals(
                "the contract says $link carries v$version",
                version,
                Beacon.versionFor(isBestPath = index == 0),
            )
        }

        // And the order the contract lists them in is the order *this phone*
        // ranks them — LocalAddress.score, which puts the phone's own hotspot
        // above station Wi-Fi. The listener ranks those two the other way round
        // and beaconPaths covers that; writing the listener's order here made
        // this test fail, which is the asymmetry earning its keep.
        val ranked = sends
            .map { it.jsonObject.getValue("link").jsonPrimitive.content }
            .map { link ->
                when (link) {
                    "usb" -> LocalAddress.score("rndis0", "192.168.42.129")
                    "wifi" -> LocalAddress.score("wlan0", "192.168.1.14")
                    "hotspot" -> LocalAddress.score("ap0", "192.168.43.10")
                    else -> throw AssertionError("the contract names a link this phone cannot score: $link")
                }
            }
        assertEquals("the contract lists the links in a different order than this phone ranks them",
            ranked.sortedDescending(), ranked)
    }

    @Test
    fun `every version the contract lists as accepted is one this phone can read`() {
        // The survey that avoids drawing a code already in use reads other
        // phones' beacons, and a phone on two links announces v2 on the second.
        // Ignoring those would let two phones land on the same two digits.
        val accepted = SharedContracts.json("test-vectors.json")
            .jsonObject.getValue("beaconVersions")
            .jsonObject.getValue("accepted").jsonArray
            .map { it.jsonPrimitive.content.toInt() }
            .toSet()

        assertEquals(accepted, Beacon.KNOWN_VERSIONS)
    }
}
