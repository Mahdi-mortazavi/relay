package io.relay.app

import io.relay.app.net.VpnLockdown
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When this phone admits, on its broadcast, that it cannot answer.
 *
 * A phone whose own VPN captures Relay can still be *heard* — a link-scoped
 * broadcast bypasses the tunnel while a unicast answer is routed into it — and
 * `blocked` is what rides that one surviving channel. It is the only way to
 * reach the case where the TCP handshake never completes, so the phone never
 * learns a PC tried and shows nothing at all.
 *
 * Contract and vectors: /shared/pairing-beacon.md and beaconBlocked.
 */
class BeaconBlockedTest {

    @Test
    fun `lockdown alone is enough, because of what lockdown does`() {
        // Not an inference. "Block connections without VPN" drops every
        // non-tunnel packet by definition, and a LAN reply is a non-tunnel
        // packet. Waiting for a PC to fail first would mean staying silent
        // through the one case that cannot report itself.
        assertEquals(
            VpnLockdown.BLOCKED_REPLIES,
            VpnLockdown.blockedValue(VpnLockdown.State.ON, pcWentUnanswered = false),
        )
    }

    @Test
    fun `a PC that went unanswered is enough on its own`() {
        // Direct evidence rather than a forecast: it asked, and nothing came
        // back within the client's own timeout. True even where the setting
        // reads off, which is the plain full-tunnel VPN case.
        assertEquals(
            VpnLockdown.BLOCKED_REPLIES,
            VpnLockdown.blockedValue(VpnLockdown.State.OFF, pcWentUnanswered = true),
        )
    }

    @Test
    fun `a phone with nothing wrong says nothing`() {
        // The overwhelmingly common beacon. A field that were always present
        // would put a warning beside every phone in every PC's list.
        assertNull(VpnLockdown.blockedValue(VpnLockdown.State.OFF, pcWentUnanswered = false))
    }

    @Test
    fun `an unreadable setting is not a reason to warn a whole network`() {
        // UNKNOWN means the platform would not answer -- the key is @hide and
        // some builds refuse it. Broadcasting a warning on that guess would tell
        // every device on the link something this phone does not actually know,
        // and would put "can't answer" beside a phone that answers fine.
        assertNull(VpnLockdown.blockedValue(VpnLockdown.State.UNKNOWN, pcWentUnanswered = false))
    }

    @Test
    fun `evidence still wins when the setting cannot be read`() {
        // The two grounds are independent. A phone that cannot read the setting
        // but has watched a PC go unanswered knows perfectly well.
        assertEquals(
            VpnLockdown.BLOCKED_REPLIES,
            VpnLockdown.blockedValue(VpnLockdown.State.UNKNOWN, pcWentUnanswered = true),
        )
    }

    @Test
    fun `this phone only ever sends the value the contract accepts`() {
        // The two sides meet here. The Windows client accepts exactly one value
        // and ignores every other; if this phone could emit something else, the
        // field would be silently dropped at the far end and the feature would
        // do nothing while both suites stayed green.
        val accepted = SharedContracts.json("test-vectors.json")
            .jsonObject.getValue("beaconBlocked")
            .jsonObject.getValue("cases").jsonArray
            .filter { it.jsonObject.getValue("accept").jsonPrimitive.booleanOrNull == true }
            .mapNotNull { it.jsonObject["blocked"]?.jsonPrimitive?.contentOrNull }
            .toSet()

        val everythingThisPhoneCanSend = buildSet {
            for (state in VpnLockdown.State.entries) {
                for (unanswered in listOf(true, false)) {
                    VpnLockdown.blockedValue(state, unanswered)?.let(::add)
                }
            }
        }

        assertEquals(accepted, everythingThisPhoneCanSend)
    }

    @Test
    fun `the value does not name the cause`() {
        // The beacon is unauthenticated and broadcast to the whole link. Naming
        // the cause would tell a cafe that this phone's owner runs a VPN --
        // which, for the people this app is built for, is not a neutral fact
        // about a network setting. The PC explains it from its own local
        // strings, to one person.
        val value = VpnLockdown.BLOCKED_REPLIES
        assertTrue(
            "the wire value names the cause: $value",
            listOf("vpn", "lockdown", "block").none { value.contains(it, ignoreCase = true) },
        )
    }
}
