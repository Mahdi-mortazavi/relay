package io.relay.app.net

import io.relay.app.core.ErrorCode
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Every network link the phone has, and what Relay made of each one.
 *
 * Written after reading a real user's diagnostic log. It said, seven times:
 *
 * ```
 * No usable Wi-Fi or hotspot interface found
 * ```
 *
 * which is true, and tells whoever reads it nothing. Was the cable not plugged
 * in? Plugged in with tethering off? Tethering on but the interface still
 * negotiating? Was there a Wi-Fi link that got rejected for looking like a VPN?
 * Four completely different faults with four different fixes, and the log could
 * not distinguish them. It took a round trip to the person, in another country,
 * on Telegram, to learn that they had turned tethering on and pressed the button
 * immediately — which was the answer, and which one line here would have given.
 *
 * So this enumerates everything, including the links [LocalAddress] deliberately
 * refuses, and records the refusal. A line like
 *
 * ```
 * rndis0(no-ipv4) wlan0(192.168.1.5 wifi score-3) tun0(10.8.0.2 vpn)
 * ```
 *
 * answers all four at a glance.
 *
 * The formatting is pure and unit-tested; only [take] touches the platform.
 */
object LinkSnapshot {

    /**
     * One interface as Relay saw it.
     *
     * @param ipv4 its site-local IPv4, or null when it has none yet — which is
     *   exactly what USB tethering looks like for the first few seconds.
     * @param verdict why it was or was not advertisable. See [verdictFor].
     * @param score [LocalAddress.score], for the links that had one. Two usable
     *   links and a surprising choice is a fault that has happened twice.
     */
    data class Link(
        val name: String,
        val ipv4: String?,
        val verdict: String,
        val score: Int? = null,
    )

    /** What the phone's links look like right now. Never throws. */
    fun take(): List<Link> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { !it.isLoopback }.getOrDefault(false) }
            .map { nic ->
                val name = nic.name.lowercase()
                val up = runCatching { nic.isUp }.getOrDefault(false)
                val ipv4 = runCatching {
                    nic.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull { it.isSiteLocalAddress }
                        ?.hostAddress
                }.getOrNull()
                val verdict = verdictFor(name, ipv4, up)
                Link(
                    name = name,
                    ipv4 = ipv4,
                    verdict = verdict,
                    score = if (verdict == USABLE && ipv4 != null) {
                        LocalAddress.score(name, ipv4)
                    } else {
                        null
                    },
                )
            }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * What Relay decided about one link, in the order the decisions are made.
     *
     * Order matters and is the point: a `tun0` that is down is reported as down,
     * not as a VPN, because "the VPN is not running" and "the VPN is running and
     * we ignored it" send a reader to different places.
     */
    internal fun verdictFor(name: String, ipv4: String?, up: Boolean): String = when {
        !up -> "down"
        ipv4 == null -> NO_IPV4
        else -> LocalAddress.rejection(name) ?: LocalAddress.linkKind(name) ?: USABLE
    }

    /**
     * One line naming every link, its address and its verdict.
     *
     * One line on purpose. The log is a ring buffer a person scrolls on a phone
     * and then pastes into a chat; a block of six lines per failure pushes the
     * thing that failed off the top of what anybody reads.
     */
    fun summarise(links: List<Link>): String =
        if (links.isEmpty()) "no interfaces at all"
        else links.joinToString(" ") { link ->
            val detail = listOfNotNull(
                link.ipv4,
                link.verdict.takeIf { it != USABLE },
                link.score?.let { "score-$it" },
            ).joinToString(" ")
            if (detail.isEmpty()) link.name else "${link.name}($detail)"
        }

    /**
     * Which failure this is, once [LocalAddress.findAdvertisableIpv4] has come
     * back with nothing.
     *
     * There is only one way to fail and four reasons for it, and they need four
     * different sentences. "Connect the phone to Wi-Fi, turn on its hotspot, or
     * plug in a cable" is the honest thing to say when there is genuinely
     * nothing there. Said to somebody whose cable *is* plugged in and whose
     * tethering *is* on, it is the app telling them to do the thing they have
     * just done — which is how a user concludes the app is broken, and they are
     * not being unreasonable.
     *
     * Ordered by how specific the answer is. A phone can easily have mobile data
     * and a VPN and a cable coming up all at once, and the cable is the one
     * worth talking about.
     */
    fun diagnose(links: List<Link>): ErrorCode = when {
        // Up, no address yet: it is already working, it just is not finished.
        // Worth its own message because the action is "wait", and every other
        // message here asks the person to go and change something.
        links.any { it.verdict == NO_IPV4 } -> ErrorCode.LINK_NEGOTIATING

        // Something has an address and we refused all of them. Which refusal it
        // was decides what to say, so ask about the more actionable one first:
        // turning Wi-Fi on fixes mobile-data-only, and there is nothing to
        // "turn on" about a VPN.
        links.any { it.verdict == CELLULAR } -> ErrorCode.ONLY_MOBILE_DATA
        links.any { it.verdict == VPN } -> ErrorCode.ONLY_VPN

        // Nothing up, nothing addressed, nothing refused.
        else -> ErrorCode.HOTSPOT_OFF
    }

    /** Advertisable, and of a kind [LocalAddress.linkKind] has no name for. */
    private const val USABLE = "usable"

    /** [LocalAddress.rejection]'s own answers, not copies of them. */
    private const val CELLULAR = LocalAddress.REJECTED_CELLULAR
    private const val VPN = LocalAddress.REJECTED_VPN

    /**
     * Up, and no site-local IPv4 yet. The one that mattered: a cable whose
     * tethering was switched on a second ago looks exactly like this.
     */
    private const val NO_IPV4 = "no-ipv4"
}
