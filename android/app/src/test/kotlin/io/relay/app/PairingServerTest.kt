package io.relay.app

import io.relay.app.core.WgParams
import io.relay.app.net.ClientGate
import io.relay.app.net.PairingServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket

/**
 * The pairing exchange from /shared/pairing-beacon.md, against the shared
 * vectors, over real loopback sockets — the same discipline as
 * `Socks5ServerTest`, and for the same reason: what matters here is what goes
 * on the wire, not what a mock agreed to.
 *
 * Both halves matter and they pull in opposite directions. This port hands out
 * key material, so a phone that serves anything arriving on it gives the tunnel
 * to whatever opened the socket. But a phone that refuses too much leaves a
 * laptop with no way in while the phone plainly shows a code — which is the
 * failure the two-digit code exists to prevent. Being strict is not the safe
 * direction by default.
 */
class PairingServerTest {

    private val vectors =
        SharedContracts.json("test-vectors.json").jsonObject.getValue("pairingExchange").jsonObject

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

    /** Starts a server on an ephemeral port with a gate that answers [allow]. */
    private fun serving(
        allow: Boolean,
        idleTimeoutMs: Int = 10_000,
        body: (port: Int, gate: ClientGate) -> Unit,
    ) {
        val gate = ClientGate(timeoutMs = 2_000)
        val server = PairingServer(
            preferredPort = 0,
            gate = gate,
            configuration = { _ -> config },
            idleTimeoutMs = idleTimeoutMs,
        )
        server.start()
        try {
            // The gate asks a human; here the human is a thread that answers as
            // soon as a prompt appears.
            val answerer = Thread {
                val deadline = System.currentTimeMillis() + 3_000
                while (System.currentTimeMillis() < deadline) {
                    val waiting = gate.pending.value
                    if (waiting != null) {
                        gate.resolve(waiting.address, allow)
                        return@Thread
                    }
                    Thread.sleep(10)
                }
            }
            answerer.isDaemon = true
            answerer.start()
            body(server.boundPort, gate)
        } finally {
            server.stop()
        }
    }

    private fun exchange(port: Int, request: String): String? =
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write((request + "\n").toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().bufferedReader().readLine()
        }

    @Test
    fun `serves every shape the contract calls a pairing request`() {
        val shapes = vectors.getValue("served").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("the contract lists no request shapes", shapes.isNotEmpty())
        for (shape in shapes) {
            assertEquals(
                "should have served: $shape",
                PairingServer.Request.PAIR,
                PairingServer.classify(shape),
            )
        }
    }

    @Test
    fun `stays silent for everything else the contract lists`() {
        val shapes = vectors.getValue("refused").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("the contract lists nothing to refuse", shapes.isNotEmpty())
        for (shape in shapes) {
            assertEquals(
                "should have ignored: $shape",
                PairingServer.Request.IGNORE,
                PairingServer.classify(shape),
            )
        }
    }

    @Test
    fun `tells a newer PC it is newer instead of leaving it to time out`() {
        val mismatch = vectors.getValue("versionMismatch").jsonObject
        val request = mismatch.getValue("request").jsonPrimitive.content
        assertEquals(PairingServer.Request.WRONG_VERSION, PairingServer.classify(request))

        serving(allow = true) { port, _ ->
            val response = exchange(port, request)
            assertEquals(mismatch.getValue("response").jsonPrimitive.content, response)
        }
    }

    @Test
    fun `hands over the configuration only after the person allows it`() {
        val request = vectors.getValue("request").jsonPrimitive.content
        serving(allow = true) { port, _ ->
            val raw = exchange(port, request)
            assertNotNull("no response", raw)
            val response = Json.parseToJsonElement(raw!!).jsonObject
            assertEquals(1, response.getValue("ok").jsonPrimitive.content.toInt())
            assertEquals("192.168.1.14", response.getValue("host").jsonPrimitive.content)

            // The wg block must be the one from qr-payload.schema.json, field for
            // field: the QR path and this path hand the client the same
            // structure, or the two ways in stop being the same product.
            val expected = vectors.getValue("allowed").jsonObject.getValue("wg").jsonObject
            val actual = response.getValue("wg").jsonObject
            assertEquals(
                "the wg block must match the shared vector's fields",
                expected.keys.sorted(),
                actual.keys.sorted(),
            )
        }
    }

    @Test
    fun `a refusal says so and carries no key material`() {
        val request = vectors.getValue("request").jsonPrimitive.content
        serving(allow = false) { port, _ ->
            val raw = exchange(port, request)
            assertEquals(vectors.getValue("denied").jsonPrimitive.content, raw)

            // The point of the refusal is what is *not* in it.
            assertTrue(
                "a denied client must not receive a key",
                raw != null && !raw.contains("clientPrivateKey") && !raw.contains("serverPublicKey"),
            )
        }
    }

    @Test
    fun `a socket that says nothing is closed rather than held open`() {
        // This port is reachable by anything on the network. An opener that
        // never speaks must not be able to occupy it. Short idle timeout so the
        // close is proved rather than waited out.
        serving(allow = true, idleTimeoutMs = 300) { port, _ ->
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.soTimeout = 5_000
                // Say nothing at all, then read: the server closes, so this is
                // end-of-stream rather than a hang.
                assertNull(
                    "the server should have closed a silent connection",
                    socket.getInputStream().bufferedReader().readLine(),
                )
            }
        }
    }

    @Test
    fun `nothing is asked of the person twice in one session`() = runBlocking {
        val gate = ClientGate(timeoutMs = 500)
        gate.resolve("10.0.0.7", allowed = true)
        // Already decided, so this must not raise a second prompt.
        assertTrue(gate.authorize("10.0.0.7"))
        assertNull("no prompt should be pending", gate.pending.value)
    }
}
