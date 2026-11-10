package io.relay.app

import io.relay.app.core.TrustProof
import io.relay.app.core.WgParams
import io.relay.app.net.ClientGate
import io.relay.app.net.PairingServer
import io.relay.app.service.TrustedDevices
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.InetAddress
import java.net.Socket

/**
 * Trusted computers over the wire (/shared/pairing-beacon.md → "Trusted
 * computers"; ADR-0012), on real loopback sockets like [PairingServerTest]. The
 * test plays the Windows PC: it reads the phone's challenge, signs what it was
 * actually sent, and reads the answer.
 *
 * What matters here is the negative space. Skipping the prompt is the one thing
 * this feature does, so every way of *not* earning that gets a test.
 */
class PairingServerTrustTest {

    private val config = PairingServer.Configuration(
        host = "192.168.1.14",
        port = 51820,
        wg = WgParams(
            serverPublicKey = "j8f7dEXAMPLEserverPublicKeyBase64Padding0123=",
            clientPrivateKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
            allowedIps = "0.0.0.0/0",
            endpointPort = 51820,
            dns = "1.1.1.1",
        ),
    )

    private class Rig(
        val gate: ClientGate,
        val trust: TrustedDevices,
        val server: PairingServer,
    )

    /**
     * A running server. [person] plays the human holding the phone: it is handed
     * the pending prompt, and answers it (or does not answer at all, if null).
     */
    private fun rig(
        trusted: List<TestComputer> = emptyList(),
        idleTimeoutMs: Int = 10_000,
        person: ((ClientGate, ClientGate.Pending) -> Unit)? = null,
        body: (port: Int, rig: Rig) -> Unit,
    ) {
        lateinit var trustRef: TrustedDevices
        val gate = ClientGate(timeoutMs = 600, onRemember = { key, name -> trustRef.add(key, name) })
        val trust = TrustedDevices().also {
            it.attach(load = { null }, save = {})
            trustRef = it
        }
        trusted.forEach { trust.add(it.key, "already trusted") }
        val server = PairingServer(
            preferredPort = 0,
            gate = gate,
            configuration = { _ -> config },
            idleTimeoutMs = idleTimeoutMs,
            trust = trust,
        )
        server.start()
        val answerer = Thread {
            val deadline = System.currentTimeMillis() + 4_000
            while (System.currentTimeMillis() < deadline) {
                val waiting = gate.pending.value
                if (waiting != null) {
                    person?.invoke(gate, waiting)
                    return@Thread
                }
                Thread.sleep(5)
            }
        }
        answerer.isDaemon = true
        try {
            if (person != null) answerer.start()
            body(server.boundPort, Rig(gate, trust, server))
        } finally {
            server.stop()
        }
    }

    private fun connect(port: Int, body: (BufferedReader, BufferedWriter) -> Unit) {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 6_000
            body(
                socket.getInputStream().bufferedReader(),
                socket.getOutputStream().bufferedWriter(),
            )
        }
    }

    private fun BufferedWriter.line(text: String) { write(text + "\n"); flush() }

    private fun request(pc: TestComputer?, name: String = "MAHDI-LAPTOP") =
        if (pc == null) """{"v":1,"pair":1,"name":"$name"}"""
        else """{"v":1,"pair":1,"name":"$name","key":"${TrustProof.encode(pc.key)}"}"""

    private fun String.field(name: String) =
        Json.parseToJsonElement(this).jsonObject[name]?.jsonPrimitive?.content

    /** Reads the challenge and answers it with [pc]'s signature, as the Windows client does. */
    private fun answerChallenge(reader: BufferedReader, writer: BufferedWriter, pc: TestComputer) {
        val challengeLine = reader.readLine()
        assertNotNull("the phone must challenge a key", challengeLine)
        val challenge = TrustProof.decode(challengeLine.field("challenge"))!!
        assertEquals(TrustProof.CHALLENGE_BYTES, challenge.size)
        writer.line("""{"v":1,"proof":"${TrustProof.encode(pc.sign(challenge))}"}""")
    }

    @Test
    fun `a trusted computer that proves its key is served with no prompt`() {
        val pc = TestComputer()
        rig(trusted = listOf(pc)) { port, rig ->
            connect(port) { r, w ->
                w.line(request(pc))
                answerChallenge(r, w, pc)
                val answer = r.readLine()
                assertEquals("1", answer.field("ok"))
            }
            assertTrue("the address is allowed for this session", rig.gate.isKnown("127.0.0.1"))
            assertNull("nobody was asked", rig.gate.pending.value)
            assertNotNull("the use is recorded", rig.trust.devices.value.single().lastUsedAtMs)
        }
    }

    @Test
    fun `an unknown computer that proves its key gets the prompt, with Always allow offered`() {
        val pc = TestComputer()
        var offered: String? = null
        rig(person = { gate, waiting ->
            offered = waiting.provenKey
            gate.resolve(waiting.address, allowed = true, remember = true)
        }) { port, rig ->
            connect(port) { r, w ->
                w.line(request(pc))
                answerChallenge(r, w, pc)
                assertEquals("1", r.readLine().field("ok"))
            }
            assertEquals(TrustProof.encode(pc.key), offered)
            assertTrue("Always allow put it on the list", rig.trust.isTrusted(pc.key))

            // The next sharing session: the address decision is gone, the key is not.
            rig.gate.reset()
            connect(port) { r, w ->
                w.line(request(pc))
                answerChallenge(r, w, pc)
                assertEquals("1", r.readLine().field("ok"))
            }
            assertNull("and this time nobody was asked", rig.gate.pending.value)
        }
    }

    @Test
    fun `the challenge does not depend on whether the key is on the list`() {
        val known = TestComputer()
        val stranger = TestComputer()
        rig(trusted = listOf(known), person = { gate, p -> gate.resolve(p.address, false) }) { port, _ ->
            for (pc in listOf(known, stranger)) {
                connect(port) { r, w ->
                    w.line(request(pc))
                    val first = r.readLine()
                    assertNotNull(first.field("challenge"))
                    // Say nothing more; the point was only that it was asked.
                }
            }
        }
    }

    @Test
    fun `a key that is never proven is not offered Always allow and is not remembered`() {
        val pc = TestComputer()
        var offered: String? = "unset"
        rig(
            idleTimeoutMs = 300,
            person = { gate, waiting ->
                offered = waiting.provenKey
                gate.resolve(waiting.address, allowed = true, remember = true)
            },
        ) { port, rig ->
            connect(port) { r, w ->
                w.line(request(pc))
                assertNotNull(r.readLine().field("challenge"))
                // ...and never answer it. The phone gives up waiting and asks the person.
                assertEquals("1", r.readLine().field("ok"))
            }
            assertNull("an unproven key must not reach the prompt as a key", offered)
            assertFalse(rig.trust.isTrusted(pc.key))
        }
    }

    @Test
    fun `a wrong proof does not skip the prompt, even for a computer on the list`() {
        val pc = TestComputer()
        rig(trusted = listOf(pc), person = { gate, p -> gate.resolve(p.address, allowed = false) }) { port, rig ->
            connect(port) { r, w ->
                w.line(request(pc))
                val challenge = TrustProof.decode(r.readLine().field("challenge"))!!
                // Someone who copied the public key and cannot sign: sign the wrong thing.
                val forged = TestComputer().sign(challenge)
                w.line("""{"v":1,"proof":"${TrustProof.encode(forged)}"}""")
                assertEquals("ERR_PAIRING_DENIED", r.readLine().field("error"))
            }
            assertNull("a refused request is not 'used'", rig.trust.devices.value.single().lastUsedAtMs)
        }
    }

    @Test
    fun `a replayed proof does not work, because the challenge is new every time`() {
        val pc = TestComputer()
        var recorded: String? = null
        rig(trusted = listOf(pc), person = { gate, p -> gate.resolve(p.address, allowed = false) }) { port, rig ->
            connect(port) { r, w ->
                w.line(request(pc))
                val challenge = TrustProof.decode(r.readLine().field("challenge"))!!
                recorded = """{"v":1,"proof":"${TrustProof.encode(pc.sign(challenge))}"}"""
                w.line(recorded.orEmpty())
                assertEquals("1", r.readLine().field("ok"))
            }
            // A new sharing session (the address decision does not carry over), and
            // an eavesdropper replays the proof it saw.
            rig.gate.reset()
            connect(port) { r, w ->
                w.line(request(pc))
                assertNotNull(r.readLine().field("challenge"))
                w.line(recorded!!)
                assertEquals("ERR_PAIRING_DENIED", r.readLine().field("error"))
            }
        }
    }

    @Test
    fun `a request with no key is never challenged`() {
        rig(person = { gate, p -> gate.resolve(p.address, allowed = true) }) { port, _ ->
            connect(port) { r, w ->
                w.line(request(null))
                val first = r.readLine()
                assertNull("the first thing back is the answer, not a challenge", first.field("challenge"))
                assertEquals("1", first.field("ok"))
            }
        }
    }

    @Test
    fun `a malformed key is treated as no key`() {
        rig(person = { gate, p -> gate.resolve(p.address, allowed = true) }) { port, _ ->
            for (junk in listOf("AAAA", "not base64!!", "")) {
                connect(port) { r, w ->
                    w.line("""{"v":1,"pair":1,"name":"X","key":"$junk"}""")
                    assertNull(r.readLine().field("challenge"))
                }
            }
        }
    }

    @Test
    fun `copying a trusted computer's name earns nothing`() {
        val pc = TestComputer()
        rig(trusted = listOf(pc), person = { gate, p -> gate.resolve(p.address, allowed = false) }) { port, _ ->
            // Another machine calling itself the same thing, with no key: asked
            // like anyone else, and here refused.
            connect(port) { r, w ->
                w.line(request(null, name = "already trusted"))
                assertEquals("ERR_PAIRING_DENIED", r.readLine().field("error"))
            }
        }
    }
}
