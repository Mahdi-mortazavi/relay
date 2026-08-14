package io.relay.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relay.app.net.Beacon
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * The half of pairing that decides whether the PC can find this phone at all.
 *
 * The phone shouts its two-digit code onto the network once a second, and for a
 * long time that was the whole mechanism. It is not enough: Windows Firewall
 * drops unsolicited inbound UDP to an unelevated program by default, and
 * Relay's Windows installer is per-user so it cannot add a rule. On a PC nobody
 * configured, every one of those broadcasts is discarded before Relay sees it —
 * the phone displays a code and the PC insists no phone is showing it, which is
 * the single most demoralising way this product can fail. So the PC asks first,
 * and its own firewall lets the answer back through.
 *
 * This runs the real socket path in the real app process. A parse-level test of
 * the same rules lives in the JVM suite; neither is a substitute for the other,
 * because the thing that breaks here is binding and answering, not parsing.
 */
@RunWith(AndroidJUnit4::class)
class PairingDiscoveryTest {

    private var beacon: Beacon? = null

    @After
    fun tearDown() {
        beacon?.stop()
        beacon = null
    }

    private fun startBeacon(): Beacon = Beacon(
        code = CODE,
        mode = "socks5",
        host = HOST,
        port = PORT,
        deviceName = "Relay Test Phone",
    ).also {
        beacon = it
        it.start()
    }

    @Test
    fun answersAProbeWithEverythingThePcNeedsToConnect() {
        startBeacon()

        val reply = probeUntilAnswered() ?: throw AssertionError(
            "The phone never answered a probe. A PC behind the default Windows " +
                "firewall can only find it this way, so this is invisibility, not slowness."
        )

        val json = JSONObject(reply)
        assertEquals(Beacon.VERSION, json.getInt("v"))
        assertEquals(CODE, json.getString("code"))
        assertEquals("socks5", json.getString("mode"))
        // The address and port are the whole point of the answer: the code only
        // selects a phone, the beacon is what says where it is.
        assertEquals(HOST, json.getString("host"))
        assertEquals(PORT, json.getInt("port"))
        assertEquals(Beacon.STATE_SHARING, json.getString("state"))
        assertEquals("Relay Test Phone", json.getString("name"))
    }

    @Test
    fun answersTheProbeTheWindowsClientActuallySends() {
        // Byte-for-byte the datagram in /shared/test-vectors.json, which the
        // Windows suite asserts its own sender emits. A phone that only answers
        // its own idea of a probe answers nobody.
        startBeacon()
        assertNotNull(probeUntilAnswered(Beacon.PROBE_JSON.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun staysSilentForDatagramsThatAreNotProbes() {
        startBeacon()
        // Establish it is listening at all first, or "no answer" proves nothing.
        assertNotNull(probeUntilAnswered())

        // Its own broadcast, looped back by the host, is the datagram it will
        // receive most often. Answering it would put the phone in a
        // conversation with itself once a second for the life of the session.
        for (datagram in NOT_PROBES) {
            assertNull(
                "answered a datagram that is not a probe: $datagram",
                askOnce(datagram.toByteArray(Charsets.UTF_8), timeoutMs = 600),
            )
        }
    }

    /**
     * Sends the probe until an answer comes back or the budget runs out. The
     * responder binds its socket on a background coroutine, so the first probe
     * after start() can genuinely arrive before there is anything listening —
     * retrying is the honest way to distinguish "not yet" from "never".
     */
    private fun probeUntilAnswered(datagram: ByteArray = Beacon.probeDatagram()): String? {
        repeat(ATTEMPTS) {
            askOnce(datagram, timeoutMs = 400)?.let { return it }
        }
        return null
    }

    /** One probe from an ephemeral port, exactly as the PC sends it. */
    private fun askOnce(datagram: ByteArray, timeoutMs: Int): String? =
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val target = InetAddress.getByName("127.0.0.1")
            socket.send(DatagramPacket(datagram, datagram.size, target, Beacon.PORT))

            val buffer = ByteArray(1024)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
                String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            } catch (_: IOException) {
                null
            }
        }

    private companion object {
        const val CODE = "42"
        const val HOST = "192.168.43.1"
        const val PORT = 1080
        const val ATTEMPTS = 20

        val NOT_PROBES = listOf(
            """{"v":1,"code":"42","mode":"socks5","host":"192.168.43.1","port":1080,"state":"sharing"}""",
            """{"v":2,"probe":1}""",
            """{"v":1,"probe":0}""",
            """{"v":1}""",
            "not json at all",
        )
    }
}
