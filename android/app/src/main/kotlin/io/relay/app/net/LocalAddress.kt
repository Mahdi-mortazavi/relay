package io.relay.app.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Picks the phone's local IPv4 address to advertise to the client. Works for
 * BOTH the phone's own hotspot AND a shared Wi-Fi/LAN the laptop is also on —
 * the SOCKS server already listens on all interfaces, so the only requirement
 * is advertising an address the client can route to.
 *
 * There is no public API for "which interface will the client reach me on", so
 * we score candidates: hotspot AP interfaces are preferred (the client is
 * almost certainly on them), then station Wi-Fi, then wired; a gateway-looking
 * `.1` host nudges AP interfaces higher. Cellular and VPN links are excluded
 * outright (see [isReachableFromClient]); every remaining site-local IPv4 is
 * advertisable, and finding none is a real "no Wi-Fi or hotspot" error.
 */
object LocalAddress {

    /** One candidate address; split out so [choose] is pure and unit-tested. */
    data class Candidate(val interfaceName: String, val ip: String)

    fun findAdvertisableIpv4(): String? = choose(enumerate())

    private fun enumerate(): List<Candidate> {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList() ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        return interfaces
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nic ->
                nic.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .map { Candidate(nic.name.lowercase(), it.hostAddress ?: "") }
            }
            .filter { it.ip.isNotEmpty() && isReachableFromClient(it.interfaceName) }
    }

    /**
     * Rejects links the client can never route to. Both the carrier's mobile-data
     * link and a VPN tunnel hand out site-local IPv4 (a carrier commonly uses
     * 10.0.0.0/8, a tun sits on 10.x or 172.16.x), so [Inet4Address.isSiteLocalAddress]
     * alone will happily advertise an address no PC can reach — and since sharing a
     * phone VPN is this app's whole point, a tun interface is always present.
     *
     * Blocklist, not allowlist: an OEM hotspot under an unexpected name keeps
     * working on the baseline score instead of regressing to "no Wi-Fi or hotspot".
     */
    internal fun isReachableFromClient(interfaceName: String): Boolean =
        UNREACHABLE_HINTS.none { interfaceName.contains(it) }

    /** Pure selection over already-enumerated candidates. Highest score wins; null if none. */
    internal fun choose(candidates: List<Candidate>): String? =
        candidates.maxByOrNull { score(it.interfaceName, it.ip) }?.ip

    /** Every site-local candidate scores >= 1, so we always advertise *something* usable. */
    internal fun score(interfaceName: String, ip: String): Int {
        var score = 1 // baseline: any site-local address is advertisable
        when {
            // A cable is preferred over every radio: nothing shares the medium
            // with it, it works with no Wi-Fi in range, and it costs no battery
            // holding an access point up. (Not "faster" — that is unmeasured.)
            // It was worth
            // nothing at all before this — USB interfaces were not named here,
            // so they took the baseline 1 and lost to wlan0's 3, and a laptop
            // on the cable was advertised the phone's Wi-Fi address.
            // Five, not four, so the gateway nudge below cannot pull a hotspot
            // level with it: `ap0` on 192.168.43.1 scores 3 + 1, and a tie is
            // decided by interface enumeration order, which is to say by
            // nothing. A phone with the hotspot up and a cable in would put its
            // hotspot address in the QR about half the time.
            USB_HINTS.any { interfaceName.startsWith(it) } -> score += 5
            AP_HINTS.any { interfaceName.startsWith(it) } -> score += 3 // phone hotspot
            interfaceName.startsWith("wlan") -> score += 2              // station Wi-Fi (shared LAN)
            interfaceName.startsWith("eth") || interfaceName.startsWith("en") -> score += 1
        }
        // A gateway-looking address is typical of a phone's own AP.
        if (ip.endsWith(".1") && AP_HINTS.any { interfaceName.startsWith(it) }) score += 1
        return score
    }

    /**
     * What kind of link this is, for the beacon's `link` field.
     *
     * Null for anything unrecognised rather than a guess: the contract makes the
     * field optional and sorts an absent one last, which is the right place for
     * a link nobody here has a name for.
     */
    internal fun linkKind(interfaceName: String): String? = when {
        USB_HINTS.any { interfaceName.startsWith(it) } -> "usb"
        AP_HINTS.any { interfaceName.startsWith(it) } -> "hotspot"
        interfaceName.startsWith("wlan") -> "wifi"
        else -> null
    }

    /**
     * The kinds of link this phone is currently reachable on.
     *
     * Same enumeration the beacon announces from, so the UI cannot claim a
     * cable the beacon is not using, or stay quiet about one it is. Anything
     * [linkKind] has no name for is left out rather than counted as unknown:
     * the callers ask about a specific kind.
     */
    fun activeLinkKinds(): Set<String> =
        enumerate().mapNotNull { linkKind(it.interfaceName) }.toSet()

    /** Whether this link is worth announcing on at all. */
    internal fun isReachable(interfaceName: String, ip: String): Boolean =
        ip.isNotEmpty() && isReachableFromClient(interfaceName)

    /**
     * USB tethering, under the names Android has used for it. `rndis0` is the
     * long-standing one; Android 11 and later prefer NCM and name it `ncm0`.
     * Some OEMs simply call it `usb0`.
     */
    private val USB_HINTS = listOf("rndis", "ncm", "usb")

    private val AP_HINTS = listOf("ap", "swlan", "softap", "wlan1", "wigig")

    /**
     * Why a link was rejected, or null when it was not.
     *
     * Exists so a diagnostic log can say *which* kind of unreachable a link was.
     * "No usable Wi-Fi, hotspot or USB link" is true and unhelpful on a phone
     * that had four interfaces up; "the cable was there and had no address yet"
     * is the same fact in a form somebody can act on. See [LinkSnapshot].
     */
    internal fun rejection(interfaceName: String): String? = when {
        CELLULAR_HINTS.any { interfaceName.contains(it) } -> "cellular"
        VPN_HINTS.any { interfaceName.contains(it) } -> "vpn"
        else -> null
    }

    /** Substrings of the carrier's own links. 464XLAT names one `v4-rmnet_data0`. */
    private val CELLULAR_HINTS = listOf("rmnet", "ccmni", "pdp", "seth", "wwan", "qmi", "ppp")

    /**
     * VPN tunnels and placeholders.
     *
     * No "tap" here: it is a substring of the legitimate `softap0` hotspot, and
     * Android VPNs land on tun anyway.
     */
    private val VPN_HINTS = listOf("tun", "ipsec", "dummy")

    /**
     * Substrings (not prefixes) of interfaces whose addresses are unreachable
     * from the client. Both halves hand out site-local IPv4 — a carrier commonly
     * uses 10.0.0.0/8, a tun sits on 10.x or 172.16.x — so `isSiteLocalAddress`
     * alone will happily advertise an address no PC can reach. See issue #18:
     * with hotspot and Wi-Fi both off, `rmnet_data0`'s carrier address was the
     * only candidate left and got advertised, producing a QR that could never
     * connect.
     *
     * Derived from the two named lists rather than written out again, so the
     * reason a link is rejected and the fact that it is rejected cannot drift
     * apart.
     */
    private val UNREACHABLE_HINTS = CELLULAR_HINTS + VPN_HINTS
}
