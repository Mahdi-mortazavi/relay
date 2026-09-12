package io.relay.app

import io.relay.app.core.WarningCode
import io.relay.app.net.VpnLockdown
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What Relay is allowed to say once a PC has gone unanswered.
 *
 * Reading the setting needs a `ContentResolver`, and there is no Robolectric
 * here, so the platform read is deliberately a thin wrapper around one public
 * call and the decision it feeds is what gets asserted. That decision is also
 * the part that can be wrong in a way a user notices.
 */
class VpnLockdownTest {

    @Test
    fun `a confirmed lockdown gets the message that names it`() {
        // The only case where Relay may be definite, offer the screen, and tell
        // someone to turn a specific switch off.
        assertEquals(
            WarningCode.VPN_LOCKDOWN_ON,
            VpnLockdown.warningFor(VpnLockdown.State.ON),
        )
    }

    @Test
    fun `lockdown off still warns, because a plain full-tunnel VPN does this too`() {
        // The fault is already established by the time this is asked -- a PC
        // took the settings and no handshake came back. A VPN with no always-on
        // configured produces exactly that while this setting reads off, so
        // "lockdown is off" is not "nothing is wrong".
        assertEquals(
            WarningCode.PC_GOT_NO_REPLY,
            VpnLockdown.warningFor(VpnLockdown.State.OFF),
        )
    }

    @Test
    fun `unknown is never treated as off`() {
        // The key is @hide. Google closed both of its neighbours for a modern
        // targetSdk and could close this one; when that happens the read throws
        // and the answer is UNKNOWN. Folding that into "off" would turn a
        // platform change into Relay quietly telling people their settings are
        // fine -- and it would still show the general message, so this asserts
        // the reason rather than only the outcome.
        assertEquals(3, VpnLockdown.State.entries.size)
        assertEquals(
            WarningCode.PC_GOT_NO_REPLY,
            VpnLockdown.warningFor(VpnLockdown.State.UNKNOWN),
        )
    }

    @Test
    fun `unknown does not tell anyone to turn off a switch that may not be on`() {
        // The specific message sends someone to a settings page to switch
        // something off. Shown on a guess, they go looking for something that is
        // not there and conclude the advice -- and the app -- is unreliable.
        val guesses = listOf(VpnLockdown.State.OFF, VpnLockdown.State.UNKNOWN)

        for (state in guesses) {
            assertEquals(
                "state $state must not claim the setting is on",
                WarningCode.PC_GOT_NO_REPLY,
                VpnLockdown.warningFor(state),
            )
        }
    }

    @Test
    fun `every state has a word for a log line`() {
        // These go into a diagnostic report's header, where the whole point is
        // that a stranger can read it. An enum name leaking through, or a state
        // with no case, would put "null" in front of whoever is helping.
        assertEquals("on", VpnLockdown.describe(VpnLockdown.State.ON))
        assertEquals("off", VpnLockdown.describe(VpnLockdown.State.OFF))
        assertEquals("unknown", VpnLockdown.describe(VpnLockdown.State.UNKNOWN))
    }
}
