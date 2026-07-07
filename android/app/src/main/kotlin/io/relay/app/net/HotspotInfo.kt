package io.relay.app.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Finds the phone's Wi-Fi hotspot IPv4 address — the address clients on the
 * hotspot network reach us at. There is no public API for this, so we score
 * candidate interfaces: hotspot interfaces are up, non-loopback, carry a
 * site-local IPv4, are conventionally named ap0/swlan0/softap0/wlan1, and
 * conventionally sit at x.x.x.1 as the network's gateway.
 */
object HotspotInfo {

    fun findHotspotIpv4(): String? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
        } catch (_: Exception) {
            return null
        }

        return interfaces
            .asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nic ->
                nic.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .map { nic.name.lowercase() to it.hostAddress!! }
            }
            .maxByOrNull { (name, address) -> score(name, address) }
            ?.takeIf { (name, address) -> score(name, address) > 0 }
            ?.second
    }

    private fun score(name: String, address: String): Int {
        var score = 0
        if (HOTSPOT_NAME_HINTS.any { name.startsWith(it) }) score += 2
        if (address.endsWith(".1")) score += 1
        if (name.startsWith("wlan0")) score -= 1 // usually the client Wi-Fi, not the AP
        return score
    }

    private val HOTSPOT_NAME_HINTS = listOf("ap", "swlan", "softap", "wlan1", "wigig")
}
