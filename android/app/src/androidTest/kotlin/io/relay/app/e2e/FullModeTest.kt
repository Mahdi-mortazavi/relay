package io.relay.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.relay.app.core.ConnectionState
import io.relay.app.core.QrPayload
import io.relay.app.core.TransportMode
import io.relay.app.core.WgConfig
import io.relay.app.net.wg.WgForwarderProvider
import io.relay.app.service.ConnectionRepository
import io.relay.app.service.Settings
import io.relay.app.service.SharingService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Full Mode on a real Android image (ADR-0008).
 *
 * The Go suite proves a WireGuard tunnel terminates and its traffic reaches the
 * internet, but it proves that in a process on the runner's own architecture.
 * What it cannot prove is the part that had actually been broken for the whole
 * life of this feature: that the *app* can bring that endpoint up on a phone.
 * Three separate things stood between the two, and none of them had a test —
 * the library was not in the APK at all, the configuration the app assembles
 * was in a dialect wireguard-go does not read, and the peer address it routed
 * did not match the one the client uses.
 *
 * So this test starts Full Mode the way a person does — the real setting, the
 * real service — and then checks the endpoint from outside the app's own
 * opinion of itself: the UDP port is really held, and it is really released.
 */
@RunWith(AndroidJUnit4::class)
class FullModeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings by lazy { Settings(context) }
    private var previousMode: String = TransportMode.FAST.name

    @Before
    fun selectFullMode() {
        previousMode = settings.transportMode
        settings.transportMode = TransportMode.FULL.name
    }

    @After
    fun tearDown() {
        runCatching { SharingService.stop(context) }
        runCatching { awaitState<ConnectionState.Idle>(15_000) }
        settings.transportMode = previousMode
    }

    @Test
    fun theBuildActuallyContainsTheForwarder() {
        // The UI offers Full Mode only when this is true, so when it is false
        // the mode silently disappears from a shipped app and every test above
        // still passes. -PrelayRequireWg makes the build fail instead; this
        // makes the *device* say so.
        assertTrue(
            "This APK has no Full Mode library. It was built without " +
                "android/app/libs/relaywg.aar — see scripts/build-wg-aar.sh.",
            WgForwarderProvider.isAvailable,
        )
    }

    @Test
    fun startsARealEndpointAndAdvertisesIt() {
        SharingService.start(context)
        val advertising = awaitState<ConnectionState.Advertising>()
        val payload = advertising.payload

        assertEquals("mode", QrPayload.MODE_WIREGUARD, payload.mode)
        val wg = payload.wg
        assertNotNull("the QR carries no wg block, so no client could connect", wg)
        requireNotNull(wg)

        // The keys are what the laptop authenticates with; Full Mode has no
        // approval prompt precisely because these are a real secret, so an
        // empty or malformed one is a security hole, not a cosmetic defect.
        assertEquals(64, WgConfig.toHex(wg.serverPublicKey).length)
        assertEquals(64, WgConfig.toHex(wg.clientPrivateKey).length)
        assertEquals(WgConfig.CLIENT_ALLOWED_IPS, wg.allowedIps)
        assertEquals(payload.port, wg.endpointPort)

        DeviceEvidence.note("Full Mode advertising ${payload.host}:${payload.port}")
        DeviceEvidence.screenshot("full-mode-advertising")

        // The claim under test, checked from outside: something is holding that
        // UDP port. Every earlier version of this code reported a healthy
        // session while wireguard-go had rejected the configuration outright.
        assertTrue(
            "nothing is listening on UDP ${payload.port} — the endpoint did not really start",
            portIsHeld(payload.port),
        )

        SharingService.stop(context)
        awaitState<ConnectionState.Idle>()

        // And released again. A port still held after Stop means the next
        // session cannot bind it, which is how "Full Mode stopped working after
        // I turned it off and on" happens.
        assertTrue(
            "UDP ${payload.port} is still held after stopping",
            waitUntil(10_000) { !portIsHeld(payload.port) },
        )
    }

    /**
     * True when the port cannot be bound, i.e. something else has it.
     *
     * Deliberately exclusive: SO_REUSEADDR on a UDP socket would let this bind
     * alongside the endpoint and report "free" while the endpoint was running,
     * which is the answer that would make this test pass no matter what.
     */
    private fun portIsHeld(port: Int): Boolean = try {
        DatagramSocket(null).use { probe ->
            probe.reuseAddress = false
            probe.bind(InetSocketAddress(port))
            false
        }
    } catch (_: Exception) {
        true
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private inline fun <reified T : ConnectionState> awaitState(timeoutMs: Long = 30_000): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = ConnectionRepository.state.value
            if (state is T) return state
            if (state is ConnectionState.Error) {
                // WG_START_FAILED here is the whole point of the test: it is
                // what the app reported for every one of the three defects
                // above, with no way to tell them apart from the outside.
                throw AssertionError("Full Mode failed to start: ${state.code}")
            }
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting for ${T::class.simpleName}")
    }
}
