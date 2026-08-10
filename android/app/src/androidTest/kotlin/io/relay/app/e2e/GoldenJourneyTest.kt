package io.relay.app.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relay.app.MainActivity
import io.relay.app.R
import io.relay.app.core.ConnectionState
import io.relay.app.core.QrPayloadCodec
import io.relay.app.core.TypedCode
import io.relay.app.service.ConnectionRepository
import io.relay.app.service.SharingService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * The golden user journey, on a real Android image, driven through the real UI:
 *
 *     open the app → Start Sharing → QR appears → a device connects →
 *     real bytes flow → Stop → everything is torn down
 *
 * Nothing here is mocked: the real [SharingService] runs as a real foreground
 * service, the real SOCKS5 server binds the address the app actually chose to
 * advertise, and the traffic is a real HTTP exchange over real sockets. The
 * pairing payload the app issues is written out for the cross-platform leg,
 * where the Windows-side decoder consumes exactly this string.
 */
@RunWith(AndroidJUnit4::class)
class GoldenJourneyTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val destinations = mutableListOf<ServerSocket>()
    private val sockets = mutableListOf<Socket>()

    @After
    fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
        destinations.forEach { runCatching { it.close() } }
        // Teardown must never be the thing that fails a run: startService throws
        // if the activity has already been stopped by the time we get here.
        runCatching { SharingService.stop(compose.activity) }
        runCatching { awaitState<ConnectionState.Idle>(timeoutMs = 10_000) }
    }

    @Test
    fun openScanConnected() {
        DeviceEvidence.note("=== Golden journey: open → start → pair → transfer → stop ===")

        // 1. The app opens on the idle screen with a single obvious action.
        val startLabel = compose.activity.getString(R.string.action_start)
        compose.onNodeWithText(startLabel).assertIsDisplayed()
        DeviceEvidence.screenshot("idle")

        // 2. Start sharing — the same tap a user makes.
        compose.onNodeWithText(startLabel).performClick()

        // 3. The phone reaches Advertising and shows a scannable QR.
        val advertising = awaitState<ConnectionState.Advertising>()
        // The QR is encoded off the main thread, so the node appears a frame or
        // two after the state does. Wait for it rather than racing it.
        val qrDescription = compose.activity.getString(R.string.qr_content_description)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithContentDescription(qrDescription)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(qrDescription)
            .performScrollTo()
            .assertIsDisplayed()
        DeviceEvidence.screenshot("advertising-qr")

        val payload = advertising.payload
        DeviceEvidence.note("Advertising ${payload.mode} on ${payload.host}:${payload.port}")
        assertEquals("Fast Mode must issue a socks5 payload", "socks5", payload.mode)
        assertTrue("advertised port must be real", payload.port in 1..65535)
        assertTrue(
            "must advertise a routable IPv4, not loopback (got ${payload.host})",
            payload.host != "127.0.0.1" && payload.host.count { it == '.' } == 3,
        )

        // The QR string is the wire contract; the Windows side decodes this exact
        // text in the cross-platform leg of this workflow.
        val qrText = QrPayloadCodec.encodeForQr(payload)
        DeviceEvidence.recordPairing(qrText, payload.host, payload.port, advertising.typedCode)

        // The typed code is only defined for 192.168.0.0/16 (shared/typed-code.md),
        // and both platforms must agree about when it is unavailable.
        assertEquals(
            "typed code must match the shared contract for this host",
            TypedCode.encode(payload.host, payload.port),
            advertising.typedCode,
        )

        // 4. A client connects to the advertised address — not to loopback, so
        //    this also proves LocalAddress picked an address that is actually
        //    bound and reachable on this device.
        val destination = startHttpDestination()
        val client = socksConnect(payload.host, payload.port, "127.0.0.1", destination.localPort)

        // 5. Real bytes, both directions.
        client.getOutputStream().write("GET /probe HTTP/1.1\r\nHost: relay-e2e\r\n\r\n".toByteArray())
        client.getOutputStream().flush()
        val response = readAvailable(client)
        assertTrue("expected a real HTTP response, got: $response", response.startsWith("HTTP/1.1 200"))
        assertTrue("expected the body to come back", response.contains(BODY))
        DeviceEvidence.note("Relayed a real HTTP exchange through the phone's SOCKS5 server")

        // 6. The phone reports the connected device.
        val connected = awaitState<ConnectionState.Connected>()
        assertEquals(1, connected.clientCount)
        compose.waitForIdle()
        DeviceEvidence.screenshot("connected")

        // 7. Stop means stop: the state returns to Idle, the tunnel is torn down,
        //    and the port stops accepting. (Regression: stop() used to leave
        //    established tunnels relaying.)
        compose.onNodeWithText(compose.activity.getString(R.string.action_stop))
            .performScrollTo()
            .performClick()
        awaitState<ConnectionState.Idle>()
        DeviceEvidence.screenshot("stopped")

        client.soTimeout = 5_000
        val eof = try {
            client.getInputStream().read()
        } catch (_: SocketTimeoutException) {
            throw AssertionError("the tunnel outlived Stop: the client's read never ended")
        } catch (_: IOException) {
            -1
        }
        assertEquals("the tunnel must be closed by Stop", -1, eof)

        var refused = false
        try {
            Socket().use { it.connect(InetSocketAddress(payload.host, payload.port), 3_000) }
        } catch (_: IOException) {
            refused = true
        }
        assertTrue("the SOCKS port must stop accepting after Stop", refused)
        DeviceEvidence.note("Stop tore down the listener and the live tunnel")
    }

    /**
     * Failure injection: the preferred SOCKS port is already taken. The user
     * should never see this — the phone picks another port and puts it in the QR.
     */
    @Test
    fun fallsBackToAnotherPortWhenTheFirstIsTaken() {
        val squatter = ServerSocket()
        squatter.reuseAddress = true
        squatter.bind(InetSocketAddress(SharingService.CANDIDATE_PORTS.first()), 4)
        destinations += squatter

        compose.onNodeWithText(compose.activity.getString(R.string.action_start)).performClick()
        val advertising = awaitState<ConnectionState.Advertising>()

        DeviceEvidence.note("Port ${squatter.localPort} occupied → advertised ${advertising.payload.port}")
        assertTrue(
            "must not advertise the occupied port",
            advertising.payload.port != squatter.localPort,
        )
        assertTrue(
            "must fall back to a known candidate port",
            advertising.payload.port in SharingService.CANDIDATE_PORTS,
        )

        // And the fallback port must actually work.
        val destination = startHttpDestination()
        val client = socksConnect(
            advertising.payload.host, advertising.payload.port, "127.0.0.1", destination.localPort,
        )
        client.getOutputStream().write("GET / HTTP/1.1\r\nHost: relay-e2e\r\n\r\n".toByteArray())
        client.getOutputStream().flush()
        assertTrue(readAvailable(client).contains(BODY))
        DeviceEvidence.screenshot("port-fallback")
    }

    /** Every surfaced state must be a state the UI can actually render. */
    @Test
    fun theUiFollowsTheServiceThroughEveryState() {
        compose.onNodeWithText(compose.activity.getString(R.string.action_start)).performClick()
        awaitState<ConnectionState.Advertising>()
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.action_stop)).assertIsDisplayed()

        SharingService.stop(compose.activity)
        awaitState<ConnectionState.Idle>()
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.action_start)).assertIsDisplayed()
        DeviceEvidence.note("UI returned to the idle screen after a stop")
    }

    // --- helpers --------------------------------------------------------------

    private inline fun <reified T : ConnectionState> awaitState(timeoutMs: Long = 30_000): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = ConnectionRepository.state.value
            if (state is T) return state
            if (state is ConnectionState.Error) {
                throw AssertionError("sharing failed with ${state.code} while waiting for ${T::class.simpleName}")
            }
            Thread.sleep(100)
        }
        throw AssertionError(
            "timed out waiting for ${T::class.simpleName}; " +
                "state is ${ConnectionRepository.state.value.stateName}"
        )
    }

    /** A destination "on the internet", running on the device behind the proxy. */
    private fun startHttpDestination(): ServerSocket {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        destinations += server
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    return@thread
                }
                thread(isDaemon = true) {
                    socket.use {
                        runCatching {
                            it.getInputStream().read(ByteArray(4096))
                            it.getOutputStream().write(
                                ("HTTP/1.1 200 OK\r\nContent-Length: ${BODY.length}\r\n" +
                                    "Connection: close\r\n\r\n$BODY").toByteArray()
                            )
                            it.getOutputStream().flush()
                        }
                    }
                }
            }
        }
        return server
    }

    /** Full SOCKS5 greeting + CONNECT, exactly as a client would speak it. */
    private fun socksConnect(
        proxyHost: String,
        proxyPort: Int,
        destinationHost: String,
        destinationPort: Int,
    ): Socket {
        val socket = Socket()
        sockets += socket
        socket.connect(InetSocketAddress(proxyHost, proxyPort), 10_000)
        socket.soTimeout = 15_000
        socket.tcpNoDelay = true

        val output = socket.getOutputStream()
        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        val greeting = ByteArray(2)
        readFully(socket, greeting)
        assertEquals("SOCKS version in greeting reply", 5, greeting[0].toInt())
        assertEquals("server must accept no-auth", 0, greeting[1].toInt())

        val host = destinationHost.split(".").map { it.toInt().toByte() }.toByteArray()
        output.write(
            byteArrayOf(0x05, 0x01, 0x00, 0x01) + host +
                byteArrayOf((destinationPort shr 8).toByte(), (destinationPort and 0xFF).toByte())
        )
        output.flush()
        val reply = ByteArray(10)
        readFully(socket, reply)
        assertEquals("SOCKS version in CONNECT reply", 5, reply[0].toInt())
        assertEquals("CONNECT must succeed", 0, reply[1].toInt())
        assertNotNull(socket)
        return socket
    }

    private fun readFully(socket: Socket, into: ByteArray) {
        var offset = 0
        while (offset < into.size) {
            val read = socket.getInputStream().read(into, offset, into.size - offset)
            if (read < 0) throw AssertionError("peer closed after $offset of ${into.size} bytes")
            offset += read
        }
    }

    /** Reads until the peer closes or the response is clearly complete. */
    private fun readAvailable(socket: Socket): String {
        socket.soTimeout = 15_000
        val buffer = ByteArray(8192)
        val text = StringBuilder()
        while (true) {
            val read = try {
                socket.getInputStream().read(buffer)
            } catch (_: SocketTimeoutException) {
                break
            }
            if (read < 0) break
            text.append(String(buffer, 0, read, Charsets.UTF_8))
            if (text.contains(BODY)) break
        }
        return text.toString()
    }

    private companion object {
        const val BODY = "relay-e2e-ok"
    }
}
