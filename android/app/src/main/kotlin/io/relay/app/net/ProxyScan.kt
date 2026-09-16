package io.relay.app.net

import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Finds the local SOCKS5 port a VPN app on this phone is listening on.
 *
 * This exists because the manual step it replaces was unreasonable. The setting
 * that lets the PC use the phone's VPN needs that port, and nothing tells the
 * person what it is: the placeholder suggests v2ray's `10808`, the phone
 * measured during this feature's hardware session was on `1819`, and the same
 * app also listened on `1820`, which accepts TCP and is not a SOCKS5 port at
 * all. Finding it took dumping `/proc/net/tcp` over adb — which an app cannot
 * do, because Android 10 stopped showing an app any socket but its own.
 *
 * What an app *can* do is knock. A local proxy answers a five-byte greeting in
 * under a millisecond over loopback, and a port that is not one either refuses
 * the connection instantly or says something else.
 *
 * **Nothing leaves the phone.** Every connection here is to `127.0.0.1`, to a
 * process on this same device, and the probe sends the two requests RFC 1928
 * defines and then hangs up.
 *
 * This does not automate excluding Relay from the VPN's per-app list, because
 * that cannot be automated: the list belongs to the VPN app, which passes it to
 * `VpnService.Builder.addDisallowedApplication` when it builds its tunnel.
 * There is no public API for another app to change it and no settings key that
 * can be written — see `docs/vpn-compat.md`.
 */
object ProxyScan {

    /** The loopback host every proxy this targets listens on. */
    const val HOST = "127.0.0.1"

    /**
     * How long one port gets to prove it is a SOCKS5 proxy.
     *
     * Generous for loopback, where an answer is immediate, and the reason it
     * has to exist at all: a port can accept the connection and then never
     * reply, which is exactly what `1820` did on the phone this was measured
     * on. Without a read timeout that one port hangs the whole scan.
     */
    const val TIMEOUT_MS = 500

    /**
     * The ports to knock on, in the order the answer is preferred.
     *
     * Two of these are grounded rather than remembered: **1819** is what
     * Oblivion was measured listening on, and **10808** is what this app's own
     * placeholder has suggested since the setting shipped. The rest are the
     * neighbours those two live among — the `+1` companion each client opens
     * for its HTTP proxy, and the bands the clients this product's users run
     * put their local proxy in. None of them is asserted to belong to a
     * particular app, because the probe decides that, not this list.
     *
     * Order matters only for which one is offered when several answer. A port
     * that answers is preferred over a guess about who owns it.
     */
    val CANDIDATES: List<Int> = listOf(
        1819, 1820,             // measured on hardware, 2026-09-16
        10808, 10809,           // this app's own placeholder, and its companion
        1080, 1081, 1082,       // the registered SOCKS port and its neighbours
        2080, 2081,
        7890, 7891, 7892,
        8086, 8087,
        9050,                   // Tor's, which grants CONNECT and not datagrams
    )

    /** Where a scan got to. The UI shows one line per state and no spinner. */
    sealed interface State {
        /** Nothing has been asked for yet. */
        data object Idle : State

        /** Knocking. Over loopback this lasts well under a second. */
        data object Looking : State

        /** A port answered and will carry datagrams. [address] is ready to use. */
        data class Found(val address: String) : State

        /** Nothing answered, so the port has to come from the VPN app itself. */
        data object NotFound : State
    }

    /**
     * Whether one port is a SOCKS5 proxy that will carry Relay's traffic.
     *
     * Blocking, and deliberately so: it is one connect and two short exchanges,
     * and being an ordinary function is what lets a test point it at a fake
     * server without a coroutine in sight.
     *
     * **UDP ASSOCIATE is part of the question, not a bonus.** A proxy that
     * grants CONNECT and refuses datagrams would be accepted by a check that
     * only greeted it — and then every DNS lookup the PC makes would fail, so
     * nothing would work at all while the setting looked correct. Tor's
     * SocksPort is exactly that proxy, which is why it is in [CANDIDATES]: the
     * scan has to meet one and turn it down.
     */
    fun probe(host: String = HOST, port: Int, timeoutMs: Int = TIMEOUT_MS): Boolean =
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                grantsDatagrams(socket)
            }
        } catch (_: IOException) {
            // Refused, unreachable, timed out, closed mid-exchange. All of them
            // mean the same thing here: not a proxy Relay can use.
            false
        }

    /** The two exchanges, on a socket that is already connected. */
    private fun grantsDatagrams(socket: Socket): Boolean {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        // VER=5, one method, NO AUTHENTICATION.
        out.write(byteArrayOf(5, 1, 0))
        out.flush()
        val greeting = ByteArray(2)
        if (!readFully(input, greeting)) return false
        // A proxy demanding credentials is not one Relay can use: it sends
        // none, by design, because these listen on loopback on this same phone.
        if (greeting[0] != 5.toByte() || greeting[1] != 0.toByte()) return false

        // UDP ASSOCIATE for 0.0.0.0:0 — "I do not know yet which source I will
        // send from", the same request the forwarder itself makes.
        out.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
        out.flush()
        val reply = ByteArray(4)
        if (!readFully(input, reply)) return false
        if (reply[0] != 5.toByte() || reply[1] != 0.toByte()) return false

        // The bound address is drained even though the answer is already known,
        // so the proxy sees a clean close rather than a reset while it is still
        // writing.
        val rest = when (reply[3]) {
            1.toByte() -> 4
            4.toByte() -> 16
            3.toByte() -> {
                val length = input.read()
                if (length < 0) return false
                length
            }
            else -> return false
        }
        return readFully(input, ByteArray(rest + 2))
    }

    /**
     * Knocks on every candidate at once and returns the first that answers, in
     * [CANDIDATES] order, or null.
     *
     * In parallel because serially this is fifteen timeouts back to back, and a
     * person is watching. In [CANDIDATES] order because a scan that returned
     * whichever socket happened to answer first would offer a different port on
     * each run, and a setting that changes routing should not be a coin toss.
     */
    suspend fun find(
        host: String = HOST,
        ports: List<Int> = CANDIDATES,
        timeoutMs: Int = TIMEOUT_MS,
    ): String? = withContext(Dispatchers.IO) {
        coroutineScope {
            ports.map { port -> async { if (probe(host, port, timeoutMs)) port else null } }
                .awaitAll()
                .firstNotNullOfOrNull { it }
                ?.let { "$host:$it" }
        }
    }

    /** Reads exactly [into].size bytes, or gives up. */
    private fun readFully(input: InputStream, into: ByteArray): Boolean {
        var read = 0
        while (read < into.size) {
            val n = input.read(into, read, into.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }
}
