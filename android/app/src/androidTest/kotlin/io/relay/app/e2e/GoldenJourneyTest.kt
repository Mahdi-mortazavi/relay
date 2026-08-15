package io.relay.app.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
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
import io.relay.app.net.PairingServer
import io.relay.app.service.ConnectionRepository
import io.relay.app.service.SharingService
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The golden user journey, on a real Android image, driven through the real UI:
 *
 *     open the app → Start Sharing → QR appears → a laptop pairs by code →
 *     the person allows it → Stop → the phone stops offering keys
 *
 * Nothing here is mocked: the real [SharingService] runs as a real foreground
 * service, the real endpoint binds the address the app actually chose to
 * advertise, and the pairing exchange happens over a real socket against the
 * real approval gate. The pairing payload the app issues is written out for the
 * cross-platform leg, where the Windows-side decoder consumes exactly this
 * string.
 *
 * What this deliberately does not do is carry traffic. Since ADR-0009 the only
 * transport is WireGuard, and standing up a peer that completes a handshake
 * belongs to [FullModeTest] and to the Go suite, which already do it. Faking it
 * here would produce a test that passes for the wrong reason.
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
        DeviceEvidence.note("=== Golden journey: open → start → pair by code → stop ===")

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
        assertEquals("there is one transport now (ADR-0009)", "wireguard", payload.mode)
        assertTrue("advertised port must be real", payload.port in 1..65535)
        assertTrue(
            "must advertise a routable IPv4, not loopback (got ${payload.host})",
            payload.host != "127.0.0.1" && payload.host.count { it == '.' } == 3,
        )
        assertTrue("a wireguard payload must carry its keys", payload.wg != null)

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

        // 4. The other way in: two digits and no camera. A laptop asks on the
        //    pairing port and the person holding the phone allows it — which is
        //    the only thing standing between a stranger and these keys.
        val configuration = pairWithApproval(payload.host)
        DeviceEvidence.screenshot("approved")

        // 5. What came back has to be the same tunnel the QR describes, or the
        //    two ways in are two different products.
        val offered = JSONObject(configuration)
        assertEquals(1, offered.getInt("ok"))
        assertEquals(payload.host, offered.getString("host"))
        assertEquals(payload.port, offered.getInt("port"))
        val wg = offered.getJSONObject("wg")
        assertEquals(payload.wg!!.serverPublicKey, wg.getString("serverPublicKey"))
        assertEquals(payload.wg!!.clientPrivateKey, wg.getString("clientPrivateKey"))
        assertEquals(payload.wg!!.endpointPort, wg.getInt("endpointPort"))
        DeviceEvidence.note("Pairing by code returned the same tunnel the QR describes")

        // 6. Stop means stop: the state returns to Idle and the phone stops
        //    offering configurations. (A pairing port that outlived Stop would
        //    hand out keys for a tunnel that no longer exists.)
        compose.onNodeWithText(compose.activity.getString(R.string.action_stop))
            .performScrollTo()
            .performClick()
        awaitState<ConnectionState.Idle>()
        DeviceEvidence.screenshot("stopped")

        var refused = false
        try {
            Socket().use {
                it.connect(InetSocketAddress(payload.host, PairingServer.DEFAULT_PORT), 3_000)
            }
        } catch (_: IOException) {
            refused = true
        }
        assertTrue("the pairing port must stop accepting after Stop", refused)
        DeviceEvidence.note("Stop closed the pairing port")
    }

    /**
     * Failure injection: something else already holds the pairing port.
     *
     * This must degrade, not fail. Offering a configuration by code is a
     * convenience; the QR carries everything by itself, so a phone that cannot
     * bind that port is still a phone you can pair with. Refusing to share at
     * all here would turn a missing convenience into a broken product.
     */
    @Test
    fun aPhoneThatCannotOfferPairingStillShares() {
        val squatter = ServerSocket()
        squatter.reuseAddress = true
        squatter.bind(InetSocketAddress(PairingServer.DEFAULT_PORT), 4)
        destinations += squatter

        compose.onNodeWithText(compose.activity.getString(R.string.action_start)).performClick()
        val advertising = awaitState<ConnectionState.Advertising>()

        DeviceEvidence.note(
            "Pairing port ${squatter.localPort} occupied → still advertising " +
                "${advertising.payload.mode} on ${advertising.payload.host}:${advertising.payload.port}"
        )
        assertEquals("wireguard", advertising.payload.mode)
        assertTrue("the QR must still carry a usable tunnel", advertising.payload.wg != null)

        // And the QR is still on screen, which is the way in that remains.
        val qrDescription = compose.activity.getString(R.string.qr_content_description)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithContentDescription(qrDescription)
                .fetchSemanticsNodes().isNotEmpty()
        }
        DeviceEvidence.screenshot("pairing-port-taken")
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

    /**
     * Asks the phone for a configuration and answers the prompt while it waits.
     *
     * The prompt is not incidental to the journey -- it is the only thing
     * standing between a stranger on the network and these keys
     * (/shared/pairing-beacon.md), so a test that routed around it would be
     * testing a product nobody ships. It has to happen on another thread
     * because the request blocks until someone taps Allow, and that someone is
     * this test.
     */
    private fun pairWithApproval(host: String): String {
        val pending = java.util.concurrent.CompletableFuture.supplyAsync {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, PairingServer.DEFAULT_PORT), 5_000)
                socket.soTimeout = 30_000
                socket.getOutputStream().write(
                    ("""{"v":1,"pair":1,"name":"Relay E2E"}""" + "
").toByteArray()
                )
                socket.getOutputStream().flush()
                socket.getInputStream().bufferedReader().readLine()
                    ?: throw AssertionError("the phone closed without answering")
            }
        }

        // Assert the person is actually asked -- that is the part worth proving,
        // and the part a regression would remove.
        // Matched on the dialog's title, not its button: the battery banner also
        // has a button reading "Allow", so the button text finds two nodes and
        // the assertion fails on the ambiguity rather than on anything real.
        val prompt = compose.activity.getString(R.string.approve_title)
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText(prompt).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(prompt).assertIsDisplayed()
        DeviceEvidence.screenshot("approval-prompt")

        // Answer through the gate rather than by tapping. A Compose dialog
        // renders in its own window, and injecting touch into that window fails
        // on these images with "Failed to inject touch input" -- an emulator
        // limitation, not a product one. The assertion above already proves the
        // prompt reached the screen; this only supplies the answer, and an
        // unapproved client still never gets a key.
        val waiting = ConnectionRepository.clientGate.pending.value
        assertTrue("the gate should have a client waiting", waiting != null)
        DeviceEvidence.note("Approval prompt shown for ${waiting!!.address}; allowing")
        ConnectionRepository.clientGate.resolve(waiting.address, allowed = true)
        return pending.get(30, java.util.concurrent.TimeUnit.SECONDS)
    }

}
