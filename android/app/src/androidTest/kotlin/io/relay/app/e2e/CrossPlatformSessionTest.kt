package io.relay.app.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relay.app.MainActivity
import io.relay.app.R
import io.relay.app.core.ConnectionState
import io.relay.app.core.QrPayload
import io.relay.app.core.QrPayloadCodec
import io.relay.app.net.PairingServer
import io.relay.app.service.ConnectionRepository
import io.relay.app.service.SharingService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Holds a real sharing session open while the **host** runs the Windows-side
 * client against it (`windows/Relay.E2E.Tests`, driven by
 * `.github/workflows/e2e.yml`). This is the only test in the suite where the
 * two platforms' real code meets: the phone advertises, the host decodes that
 * advertisement with the shipping Windows decoder and pushes real traffic
 * through the phone over an `adb forward` tunnel.
 *
 * The rendezvous is two marker files in the app's external files directory:
 * this test writes `ready` when the session is up, then blocks until the
 * workflow pushes `host-done`. Nothing about the app is modified for testing —
 * no debug-only entry points, no injected fakes.
 */
@RunWith(AndroidJUnit4::class)
class CrossPlatformSessionTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        runCatching { SharingService.stop(compose.activity) }
        runCatching { awaitState<ConnectionState.Idle>(15_000) }
    }

    @HostHarness
    @Test
    fun holdsASessionWhileTheHostClientConnects() {
        val evidence = DeviceEvidence.directory
        File(evidence, "ready").delete()
        File(evidence, "host-done").delete()

        compose.onNodeWithText(compose.activity.getString(R.string.action_start)).performClick()
        val advertising = awaitState<ConnectionState.Advertising>()
        val payload = advertising.payload

        DeviceEvidence.recordPairing(
            QrPayloadCodec.encodeForQr(payload), payload.host, payload.port, advertising.typedCode,
        )
        // Let the crossfade finish before capturing, or the evidence shows the
        // outgoing idle panel while the state machine is already advertising.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithContentDescription(
                compose.activity.getString(R.string.qr_content_description)
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        DeviceEvidence.note("Host harness: advertising ${payload.host}:${payload.port}")
        DeviceEvidence.screenshot("host-harness-advertising")

        // Tell the workflow the session is up and where to reach it.
        // The pairing port, not the endpoint: the endpoint is UDP and the host
        // reaches this phone through `adb forward`, which is TCP only. This is
        // also the port the interesting cross-platform assertion uses -- the two
        // implementations completing the pairing exchange against each other.
        File(evidence, "ready").writeText("${payload.host}:${PairingServer.DEFAULT_PORT}")

        // Stand in for the person holding the phone.
        //
        // The exchange is gated on a human tapping Allow, and there is no human
        // on a runner. Approving from here keeps the gate in the path -- the
        // request still blocks until something answers, and an unapproved client
        // still gets nothing -- while letting the host half run unattended.
        // GoldenJourneyTest is where the prompt itself is asserted to reach the
        // screen; this one is about the two platforms agreeing on the wire.
        kotlin.concurrent.thread(isDaemon = true, name = "relay-e2e-approver") {
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                ConnectionRepository.clientGate.pending.value?.let {
                    DeviceEvidence.note("Host asked to pair from ${it.address}; allowing")
                    ConnectionRepository.clientGate.resolve(it.address, allowed = true)
                }
                Thread.sleep(50)
            }
        }

        // Stand in for the person holding the phone, for as long as this test
        // needs one. The gate holds every unapproved address until someone
        // answers (/shared/pairing-beacon.md), and this harness has no UI to tap
        // -- it drives the service directly. Answering here is the same act the
        // golden-journey test performs by tapping Allow, not a way around the
        // gate: an unapproved client still never gets through.
        //
        // It runs for the whole test rather than only while waiting for the
        // host, because the last thing this test does is open its *own*
        // connection, from the phone's address rather than the host's tunnel.
        // That is a second address, so it gets a second prompt. Answering only
        // until `host-done` appeared left that one unanswered: the gate did
        // exactly what it should -- waited a minute and refused -- and the
        // re-probe died on "Connection reset" while reading a greeting that
        // was never going to come.
        val answering = AtomicBoolean(true)
        val standIn = thread(isDaemon = true, name = "relay-e2e-approver") {
            while (answering.get()) {
                ConnectionRepository.clientGate.pending.value?.let { waiting ->
                    DeviceEvidence.note("Allowing ${waiting.address} on the person's behalf")
                    ConnectionRepository.clientGate.resolve(waiting.address, allowed = true)
                }
                Thread.sleep(100)
            }
        }

        try {
            val done = File(evidence, "host-done")
            val deadline = System.currentTimeMillis() + HOST_TIMEOUT_MS
            while (!done.exists() && System.currentTimeMillis() < deadline) {
                Thread.sleep(500)
            }
            assertTrue(
                "the host harness never finished within ${HOST_TIMEOUT_MS / 1000}s — " +
                    "see the host job's log for what it was doing",
                done.exists(),
            )
            checkTheSessionSurvivedTheHost(payload)
        } finally {
            answering.set(false)
            standIn.join(2_000)
        }
    }

    /** The post-conditions, once the host has had its turn. */
    private fun checkTheSessionSurvivedTheHost(payload: QrPayload) {
        // The host's traffic must have registered as a connected device, and the
        // session must still be healthy after it.
        val state = ConnectionRepository.state.value
        assertTrue(
            "expected to still be sharing after the host leg, was ${state.stateName}",
            state is ConnectionState.Advertising || state is ConnectionState.Connected,
        )
        DeviceEvidence.note("Host harness finished; phone state is ${state.stateName}")
        DeviceEvidence.screenshot("host-harness-after")

        // And the port is still serving — a session that survives one client is
        // the difference between a demo and a product.
        Socket().use { probe ->
            probe.connect(InetSocketAddress(payload.host, payload.port), 5_000)
            // Without a read timeout this blocks on the approval gate for its
            // full minute and then fails as a bare "Connection reset", which
            // says nothing about why. Ten seconds is far longer than a greeting
            // needs and far shorter than the gate's patience, so a stall here
            // reports itself as a stall.
            probe.soTimeout = 10_000
            probe.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
            probe.getOutputStream().flush()
            val reply = ByteArray(2)
            var offset = 0
            while (offset < 2) {
                val read = probe.getInputStream().read(reply, offset, 2 - offset)
                if (read < 0) throw IOException("proxy closed during re-probe")
                offset += read
            }
            assertEquals("proxy must still greet after the host leg", 5, reply[0].toInt())
            assertEquals(0, reply[1].toInt())
        }
    }

    private inline fun <reified T : ConnectionState> awaitState(timeoutMs: Long = 30_000): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = ConnectionRepository.state.value
            if (state is T) return state
            if (state is ConnectionState.Error) {
                throw AssertionError("sharing failed with ${state.code}")
            }
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting for ${T::class.simpleName}")
    }

    private companion object {
        const val HOST_TIMEOUT_MS = 240_000L
    }
}
