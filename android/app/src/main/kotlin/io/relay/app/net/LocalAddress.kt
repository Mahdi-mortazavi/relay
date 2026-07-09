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
 * `.1` host nudges AP interfaces higher. Any site-local IPv4 is advertisable.
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
            .filter { it.ip.isNotEmpty() }
    }

    /** Pure selection over already-enumerated candidates. Highest score wins; null if none. */
    internal fun choose(candidates: List<Candidate>): String? =
        candidates.maxByOrNull { score(it.interfaceName, it.ip) }?.ip

    /** Every site-local candidate scores >= 1, so we always advertise *something* usable. */
    internal fun score(interfaceName: String, ip: String): Int {
        var score = 1 // baseline: any site-local address is advertisable
        when {
            AP_HINTS.any { interfaceName.startsWith(it) } -> score += 3 // phone hotspot
            interfaceName.startsWith("wlan") -> score += 2              // station Wi-Fi (shared LAN)
            interfaceName.startsWith("eth") || interfaceName.startsWith("en") -> score += 1
        }
        // A gateway-looking address is typical of a phone's own AP.
        if (ip.endsWith(".1") && AP_HINTS.any { interfaceName.startsWith(it) }) score += 1
        return score
    }

    private val AP_HINTS = listOf("ap", "swlan", "softap", "wlan1", "wigig")
}
