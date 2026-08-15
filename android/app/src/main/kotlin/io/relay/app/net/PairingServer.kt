package io.relay.app.net

import io.relay.app.core.WgParams
import io.relay.app.service.LocalLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Hands a laptop the tunnel configuration, once the person holding the phone
 * has allowed it.
 *
 * This is what makes a two-digit code work for Full Mode. The keys cannot ride
 * the beacon — a beacon is broadcast, unauthenticated, and readable by anything
 * on the network — so the beacon carries only this port and the configuration
 * comes over a connection a human has to approve.
 * Contract: /shared/pairing-beacon.md → "The pairing exchange"; ADR-0009.
 *
 * What this is worth is exactly what Fast Mode's SOCKS port was worth: anything
 * on the network can open it, and the person holding the phone is the gate. The
 * code selects; the human consents.
 */
class PairingServer(
    private val preferredPort: Int,
    private val gate: ClientGate,
    /**
     * The configuration to hand out, read at approval time rather than captured
     * at construction: sharing can rebind onto a new address mid-session, and a
     * captured host would send the laptop to where the phone used to be.
     */
    private val configuration: () -> Configuration?,
    /** Overridable so a test can prove the idle close without waiting it out. */
    private val idleTimeoutMs: Int = IDLE_TIMEOUT_MS,
) {
    /** Everything a client needs, matching the `wg` block in qr-payload.schema.json. */
    data class Configuration(val host: String, val port: Int, val wg: WgParams)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: ServerSocket? = null
    private var job: Job? = null

    /** The port actually bound, or -1 before [start] succeeds. */
    var boundPort: Int = -1
        private set

    /**
     * Binds and begins serving.
     *
     * @throws IOException if the port cannot be bound. The caller turns that
     * into a phone that is QR-only rather than a phone that cannot share: the
     * beacon then omits `pairingPort`, and the PC says to scan instead of
     * reporting a correct code as invalid.
     */
    @Throws(IOException::class)
    fun start() {
        if (socket != null) return
        val server = ServerSocket(preferredPort)
        socket = server
        boundPort = server.localPort
        job = scope.launch {
            while (isActive) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    return@launch // closed, or the interface went away
                }
                // One connection per coroutine: approval blocks for up to a
                // minute, and a second laptop has to be able to queue behind it
                // rather than find a port that never answers.
                launch { serve(client) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
        boundPort = -1
        scope.cancel()
    }

    private suspend fun serve(client: Socket) {
        val address = client.inetAddress?.hostAddress ?: return
        try {
            client.soTimeout = idleTimeoutMs
            val reader = client.getInputStream().bufferedReader()
            val writer = client.getOutputStream().bufferedWriter()

            val line = try {
                reader.readLine()
            } catch (_: SocketTimeoutException) {
                // This port is reachable by anything on the network, so a socket
                // that opens and then says nothing is not held open for it.
                return
            } ?: return

            when (classify(line)) {
                Request.IGNORE -> return // not ours; say nothing at all

                // Answered rather than dropped: a newer PC has to learn it is
                // talking to an older phone, instead of waiting out a timeout and
                // reporting the phone as unreachable.
                Request.WRONG_VERSION -> {
                    writer.appendLine(error(ERR_VERSION)); writer.flush()
                    LocalLog.add("A PC at $address asked in a newer pairing version")
                }

                Request.PAIR -> {
                    LocalLog.add("A PC at $address asked to pair")
                    val allowed = gate.authorize(address)
                    val config = configuration()
                    if (!allowed || config == null) {
                        writer.appendLine(error(ERR_DENIED)); writer.flush()
                        LocalLog.add("Refused $address")
                        return
                    }
                    writer.appendLine(accepted(config)); writer.flush()
                    LocalLog.add("Sent the configuration to $address")
                }
            }
        } catch (_: IOException) {
            // A client that hangs up mid-exchange is ordinary, not an error
            // worth putting in front of someone.
        } finally {
            runCatching { client.close() }
        }
    }

    internal enum class Request { PAIR, WRONG_VERSION, IGNORE }

    companion object {
        /** Where the phone offers configurations. Announced in the beacon. */
        const val DEFAULT_PORT = 47655

        /** How long a connection may stay silent before it is closed. */
        const val IDLE_TIMEOUT_MS = 10_000

        const val VERSION = 1
        const val ERR_DENIED = "ERR_PAIRING_DENIED"
        const val ERR_VERSION = "ERR_PAIRING_VERSION"

        /**
         * What a line on this port means.
         *
         * Deliberately narrow in both directions, exactly as [Beacon.isProbe]
         * is. Serving anything that arrives hands key material to whatever
         * opened the socket; refusing too much leaves a laptop with no way in
         * while the phone plainly shows a code. The exact set either way is in
         * /shared/test-vectors.json → pairingExchange.
         *
         * Parsed with kotlinx.serialization rather than org.json so this is
         * reachable from a plain JVM test — org.json is an android.jar stub off
         * the device, and a rule this consequential being untestable until it
         * reaches hardware is how it goes wrong quietly.
         */
        internal fun classify(text: String): Request = try {
            val obj = Json.parseToJsonElement(text).jsonObject
            val version = obj["v"]?.jsonPrimitive?.intOrNull
            val pair = obj["pair"]?.jsonPrimitive
            val asks = pair != null &&
                (pair.booleanOrNull == true || (pair.intOrNull ?: 0) != 0)
            when {
                !asks || version == null -> Request.IGNORE
                version != VERSION -> Request.WRONG_VERSION
                else -> Request.PAIR
            }
        } catch (_: Exception) {
            Request.IGNORE // not ours; something else found this port
        }

        /**
         * The refusal, and the only thing a denied client ever learns.
         *
         * Built with kotlinx.serialization rather than org.json for the same
         * reason [classify] parses with it: org.json is an android.jar stub off
         * the device, and these two functions are exactly what
         * /shared/test-vectors.json pins. A response format that cannot be
         * asserted until it reaches hardware is one that drifts.
         */
        internal fun error(code: String): String = buildJsonObject {
            put("v", VERSION)
            put("error", code)
        }.toString()

        /**
         * The configuration, in the shape /shared/test-vectors.json pins. The
         * `wg` object is the one from qr-payload.schema.json, so both pairing
         * paths hand the client the same structure.
         */
        internal fun accepted(config: Configuration): String = buildJsonObject {
            put("v", VERSION)
            put("ok", 1)
            put("host", config.host)
            put("port", config.port)
            putJsonObject("wg") {
                put("serverPublicKey", config.wg.serverPublicKey)
                put("clientPrivateKey", config.wg.clientPrivateKey)
                put("allowedIps", config.wg.allowedIps)
                put("endpointPort", config.wg.endpointPort)
                put("dns", config.wg.dns)
            }
        }.toString()
    }
}
