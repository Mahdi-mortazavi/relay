package io.relay.app

import io.relay.app.net.Beacon
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe rules from /shared/pairing-beacon.md, against the shared vectors.
 *
 * Both halves of this matter and they pull in opposite directions. Answering a
 * datagram tells whoever sent it that this phone is sharing and at what
 * address, so the phone must not answer just anything that lands on the port.
 * But a phone that answers nothing is invisible to a Windows PC — the firewall
 * there drops unsolicited inbound UDP by default and Relay's installer is
 * per-user, so it cannot add a rule — and "no phone has that code" in front of
 * a phone plainly showing it is the failure this whole mechanism exists to
 * prevent. Being too strict is not the safe direction.
 */
class BeaconProbeTest {

    private val probe =
        SharedContracts.json("test-vectors.json").jsonObject.getValue("pairingProbe").jsonObject

    @Test
    fun `the probe this phone would send matches the shared contract`() {
        // The phone does not probe — but the constant is the one both sides
        // agree on, and a listener whose datagram differs by a byte is a
        // listener this phone silently never answers.
        assertEquals(
            probe.getValue("datagram").jsonPrimitive.content,
            Beacon.PROBE_JSON,
        )
        assertEquals(Beacon.PROBE_JSON, String(Beacon.probeDatagram(), Charsets.UTF_8))
    }

    @Test
    fun `answers every shape the contract says is a probe`() {
        val shapes = probe.getValue("answered").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("the contract lists no probe shapes", shapes.isNotEmpty())
        for (shape in shapes) {
            assertTrue("should have answered: $shape", Beacon.isProbe(shape))
        }
    }

    @Test
    fun `stays silent for everything else the contract lists`() {
        val shapes = probe.getValue("ignored").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("the contract lists nothing to ignore", shapes.isNotEmpty())
        for (shape in shapes) {
            assertFalse("should have stayed silent for: $shape", Beacon.isProbe(shape))
        }
    }

    @Test
    fun `does not answer its own broadcasts`() {
        // The host loops a broadcast straight back to the sender, so the very
        // first datagram this phone receives on that port is usually its own.
        // Answering it would put the phone in a conversation with itself once a
        // second for the life of the session.
        val ownBeacon =
            """{"v":1,"code":"42","mode":"socks5","host":"192.168.43.1","port":1080,"state":"sharing"}"""
        assertFalse(Beacon.isProbe(ownBeacon))
    }
}
