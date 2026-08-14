package io.relay.app.net

import android.util.Log
import io.relay.app.core.PairingCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Announces this phone on the local network so a PC can find it from a
 * two-digit code instead of an eight-character one.
 *
 * Contract: /shared/pairing-beacon.md.
 *
 * Sends to the broadcast address of every up, non-loopback interface rather
 * than to 255.255.255.255: Android drops the latter on some hotspot
 * configurations, and a phone acting as a hotspot has the interface that
 * matters sitting behind a route the global address does not reach.
 */
class Beacon(
    private val code: String,
    private val mode: String,
    private val host: String,
    private val port: Int,
    private val deviceName: String?,
    private val intervalMs: Long = INTERVAL_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var answerJob: Job? = null

    /**
     * Whether this phone can be found on the network at all — i.e. whether the
     * two-digit code means anything.
     *
     * The two-digit code is a *selector*: it carries no address, so it is only
     * useful if a PC can hear this phone. On a network that supports neither
     * path — no interface with a broadcast address, and 47654 already taken so
     * probes go unanswered — the phone would still show two digits, the PC
     * would search for them forever, and the "my phone shows a longer code"
     * escape hatch on the PC led to a code the phone had decided not to print.
     * Asking this lets the UI fall back to the self-describing eight-character
     * code, which needs no discovery at all.
     */
    @Volatile
    var canAnnounce: Boolean = false
        private set

    fun start() {
        if (job != null) return

        // Bind the probe socket here rather than inside the coroutine so that
        // `canAnnounce` has an answer by the time start() returns — the caller
        // decides what to put on screen from it, and cannot wait for a
        // coroutine to be scheduled first. Both operations are local socket and
        // interface lookups; neither talks to the network.
        val probeSocket = openProbeSocket()
        canAnnounce = probeSocket != null || broadcastAddresses().isNotEmpty()
        if (!canAnnounce) {
            Log.d(TAG, "no way to announce on this network; the short code would be a dead end")
        }

        job = scope.launch {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val sharing = payload(STATE_SHARING)
                while (isActive) {
                    send(socket, sharing)
                    delay(intervalMs)
                }
            }
        }
        answerJob = probeSocket?.let { socket -> scope.launch { answerProbes(socket) } }
    }

    /**
     * Replies to probes (/shared/pairing-beacon.md) with one unicast beacon.
     *
     * Broadcasting alone is not enough to be found. Windows Firewall drops
     * unsolicited inbound UDP to an unelevated app, and Relay's Windows
     * installer is per-user so it cannot add a rule — the PC never hears a word
     * of this, and the user is left holding a phone showing a code the PC
     * insists nobody is using. When the PC speaks first, its own firewall lets
     * the answer back through, so this is the path that works on a machine
     * nobody configured.
     *
     * Best effort by design: if 47654 is already taken, broadcasting still
     * happens on its own socket and the passive path still works. Failing to
     * bind must not stop the phone from sharing.
     */
    // Note: on a phone whose own VPN captures this app, an answer sent from this
    // socket is routed into the tunnel and never reaches the PC — send() still
    // succeeds. It cannot be fixed here; Network.bindSocket fails EPERM for an
    // app inside a VPN. See /shared/pairing-beacon.md → "The answer cannot
    // always be sent".
    private fun openProbeSocket(): DatagramSocket? = try {
        DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = PROBE_POLL_MS
            bind(java.net.InetSocketAddress(PORT))
        }
    } catch (e: IOException) {
        Log.d(TAG, "not answering probes: ${e.message}")
        null
    }

    private suspend fun answerProbes(socket: DatagramSocket) {
        socket.use {
            val answer = payload(STATE_SHARING)
            val buffer = ByteArray(512)
            while (currentCoroutineContext().isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: IOException) {
                    continue // soTimeout, so cancellation is noticed promptly
                }
                // Everything else on this port is ignored, including this
                // phone's own broadcasts, which the host loops straight back.
                if (!isProbe(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))) continue
                try {
                    socket.send(DatagramPacket(answer, answer.size, packet.address, packet.port))
                } catch (e: IOException) {
                    Log.d(TAG, "probe answer to ${packet.address} failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Stops announcing and sends one last datagram saying so, so a listener
     * removes this phone at once instead of waiting out its timeout.
     *
     * Not suspending, and it owns its own teardown. The first version asked the
     * *service's* scope to run the goodbye, which meant that if the service was
     * already tearing down -- the usual case -- the launch never ran, this
     * scope was never cancelled, and the loop went on broadcasting once a
     * second for the life of the process. A phone that had been told to stop
     * sharing would keep advertising a proxy that no longer existed.
     *
     * The goodbye rides a plain daemon thread for the same reason: by the time
     * anyone calls this, no coroutine scope in the app is guaranteed to still
     * be alive to carry it. Best effort -- if the network is already gone, the
     * listener falls back to its staleness timeout, which is what that timeout
     * is for.
     */
    fun stop() {
        job?.cancel()
        job = null
        answerJob?.cancel()
        answerJob = null
        val goodbye = payload(STATE_STOPPED)
        kotlin.concurrent.thread(isDaemon = true, name = "relay-beacon-goodbye") {
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    send(socket, goodbye)
                }
            } catch (e: IOException) {
                Log.d(TAG, "final beacon not sent: ${e.message}")
            }
        }
        scope.cancel()
    }

    private fun payload(state: String): ByteArray {
        val json = JSONObject()
            .put("v", VERSION)
            .put("code", code)
            .put("mode", mode)
            .put("host", host)
            .put("port", port)
            .put("state", state)
        deviceName?.take(NAME_MAX)?.let { json.put("name", it) }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun send(socket: DatagramSocket, bytes: ByteArray) {
        for (address in broadcastAddresses()) {
            try {
                socket.send(DatagramPacket(bytes, bytes.size, address, PORT))
            } catch (e: IOException) {
                // One interface refusing is normal — a VPN's tun device has no
                // broadcast peer. Only every interface failing is interesting,
                // and that shows up as the PC never finding the phone.
                Log.d(TAG, "beacon to $address failed: ${e.message}")
            }
        }
    }

    private fun broadcastAddresses(): List<InetAddress> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast }
    } catch (e: Exception) {
        Log.d(TAG, "no interfaces to announce on: ${e.message}")
        emptyList()
    }

    companion object {
        const val PORT = 47654
        const val VERSION = 1
        const val INTERVAL_MS = 1000L
        const val STATE_SHARING = "sharing"
        const val STATE_STOPPED = "stopped"

        /**
         * How long a blocked receive waits before looking at cancellation.
         * A probe answered a fifth of a second late is invisible to a person;
         * a coroutine that cannot be cancelled for a whole second outlives the
         * service that owns it.
         */
        const val PROBE_POLL_MS = 200

        private const val NAME_MAX = 32
        private const val TAG = "RelayBeacon"

        /**
         * The probe a listener sends (/shared/pairing-beacon.md → The probe),
         * asserted byte-for-byte against `pairingProbe.datagram` in
         * /shared/test-vectors.json by the Windows suite.
         */
        const val PROBE_JSON = """{"v":1,"probe":1}"""

        fun probeDatagram(): ByteArray = PROBE_JSON.toByteArray(Charsets.UTF_8)

        /**
         * True when [text] is a probe this phone should answer.
         *
         * Deliberately narrow, in both directions. Answering reveals that this
         * phone is sharing and at what address, so a datagram has to say the
         * version it speaks and actually ask the question before it gets a
         * reply — but a phone that answers nothing is invisible behind a
         * Windows firewall, so refusing too much is not the safe default it
         * looks like. The exact set either way is in /shared/test-vectors.json.
         *
         * Parsed with kotlinx.serialization rather than org.json so this is
         * reachable from a plain JVM test: org.json is an android.jar stub off
         * the device, and a rule this consequential being untestable until it
         * reaches hardware is how it goes wrong quietly.
         */
        fun isProbe(text: String): Boolean = try {
            val obj = Json.parseToJsonElement(text).jsonObject
            val version = obj["v"]?.jsonPrimitive?.intOrNull
            val probe = obj["probe"]?.jsonPrimitive
            version == VERSION && probe != null &&
                (probe.booleanOrNull == true || (probe.intOrNull ?: 0) != 0)
        } catch (_: Exception) {
            false // not ours; something else uses this port
        }

        /** Codes currently claimed by other phones, for [PairingCode.draw]. */
        fun observedCodes(listenMs: Long = 1200): Set<String> = try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.broadcast = true
                socket.soTimeout = 250
                socket.bind(java.net.InetSocketAddress(PORT))
                val seen = mutableSetOf<String>()
                val deadline = System.currentTimeMillis() + listenMs
                val buffer = ByteArray(1024)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: IOException) {
                        continue // soTimeout: just keep waiting out the window
                    }
                    parseCode(String(packet.data, 0, packet.length, Charsets.UTF_8))?.let(seen::add)
                }
                seen
            }
        } catch (e: IOException) {
            // Port busy, or no permission. Drawing without knowing what is taken
            // risks a collision, which the PC handles by asking which device --
            // worse than unique, far better than refusing to share.
            Log.d(TAG, "could not survey codes in use: ${e.message}")
            emptySet()
        }

        private fun parseCode(text: String): String? = try {
            val json = JSONObject(text)
            if (json.optInt("v") != VERSION) null
            else if (json.optString("state") != STATE_SHARING) null
            else PairingCode.normalize(json.optString("code"))
        } catch (_: Exception) {
            null // not our datagram; something else uses this port
        }
    }
}
