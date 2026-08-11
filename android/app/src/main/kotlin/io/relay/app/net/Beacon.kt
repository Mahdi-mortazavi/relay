package io.relay.app.net

import android.util.Log
import io.relay.app.core.PairingCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    fun start() {
        if (job != null) return
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
        private const val NAME_MAX = 32
        private const val TAG = "RelayBeacon"

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
