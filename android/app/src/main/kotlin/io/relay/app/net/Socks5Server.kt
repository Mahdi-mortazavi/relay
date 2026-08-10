package io.relay.app.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal SOCKS5 server: CONNECT command, no authentication, TCP only
 * (Fast Mode; UDP rides WireGuard in Full Mode). Kept deliberately small and
 * auditable — see ADR-0006 for why this is written in-repo rather than pulled
 * in as a dependency.
 *
 * Outbound sockets are ordinary app sockets, so Android routes them (and DNS
 * for domain requests, resolved here on the phone) through the phone's active
 * VPN — which is the entire point of Relay.
 *
 * Backpressure: each direction of a connection is a blocking copy loop on the
 * IO dispatcher with a bounded buffer, so a slow reader naturally throttles
 * its writer. No unbounded queues anywhere.
 */
class Socks5Server(
    private val port: Int,
    private val listener: Listener,
) {
    interface Listener {
        /** [devices] = distinct client IPs with at least one open connection. */
        fun onClientsChanged(devices: Int)
        fun onTraffic(bytesUp: Long, bytesDown: Long)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null

    /** client IP -> open connection count */
    private val clients = ConcurrentHashMap<String, Int>()
    private val bytesUp = AtomicLong()
    private val bytesDown = AtomicLong()

    /**
     * Every socket belonging to a live tunnel, both the client side and the
     * remote side. [stop] closes them; see the comment there for why the scope
     * alone is not enough.
     */
    private val live: MutableSet<Socket> =
        Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    @Volatile
    private var stopped = false

    /**
     * The port the listening socket actually bound, or -1 before [start].
     * Equals the requested [port] unless it was 0 (OS-assigned, used by tests).
     */
    @Volatile
    var boundPort: Int = -1
        private set

    /** @throws BindException when [port] is taken. */
    @Throws(IOException::class)
    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port), BACKLOG)
        serverSocket = socket
        boundPort = socket.localPort
        scope.launch { acceptLoop(socket) }
    }

    /**
     * Stops listening **and tears down every tunnel already established.**
     *
     * Closing the listening socket and cancelling the scope is not enough: each
     * direction of a tunnel is parked in a blocking `read()`, which coroutine
     * cancellation cannot interrupt. Without closing the sockets by hand, an
     * established tunnel kept relaying after the user tapped "Stop sharing" —
     * the notification and the wake lock went away while the laptop carried on
     * browsing through the phone. Closing the socket is what unblocks the read.
     */
    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { serverSocket?.close() }
        // Iterate a snapshot: the copy loops remove themselves as they unwind.
        for (socket in live.toList()) runCatching { socket.close() }
        live.clear()
        clients.clear()
        scope.cancel()
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (scope.isActive) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return // closed by stop()
            }
            scope.launch { handleClient(client) }
        }
    }

    private suspend fun handleClient(client: Socket) {
        val clientIp = client.inetAddress.hostAddress ?: "?"
        client.tcpNoDelay = true
        var remote: Socket? = null
        // Only the connections that reached the relay stage are counted, so the
        // decrement below must be conditional: a handshake that aborts (a probe,
        // a cancelled request) or a CONNECT that fails (a dead host — routine
        // while browsing) shares this client's IP, and an unconditional
        // decrement zeroed the count for an IP that still had a live tunnel.
        // The phone then fell back to "waiting for a device" and dropped the
        // transfer wake lock in the middle of a download.
        var counted = false
        try {
            if (!register(client)) return
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = client.getInputStream()
            val output = client.getOutputStream()

            if (!handshake(input, output)) return
            remote = request(input, output) ?: return
            if (!register(remote)) return
            client.soTimeout = 0

            trackClient(clientIp, +1)
            counted = true
            relayBothDirections(client, remote)
        } catch (_: IOException) {
            // Connection failures surface to the peer via socket close.
        } finally {
            remote?.let { live.remove(it); runCatching { it.close() } }
            live.remove(client)
            runCatching { client.close() }
            if (counted) trackClient(clientIp, -1)
        }
    }

    /**
     * Adds [socket] to the live set, or closes it and returns false when [stop]
     * already ran — otherwise a socket registered just after stop() took its
     * snapshot would keep relaying with nothing left to close it.
     */
    private fun register(socket: Socket): Boolean {
        live.add(socket)
        if (!stopped) return true
        live.remove(socket)
        runCatching { socket.close() }
        return false
    }

    /**
     * Reads exactly [n] bytes (blocking) or fewer on EOF. `InputStream.readNBytes`
     * is API 33+ on Android; using it crashed the SOCKS handshake with
     * NoSuchMethodError on every device below Android 13, so we read manually.
     */
    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buffer = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = input.read(buffer, offset, n - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == n) buffer else buffer.copyOf(offset)
    }

    /** Greeting: VER NMETHODS METHODS… -> VER=5 METHOD=0 (no auth). */
    private fun handshake(input: InputStream, output: OutputStream): Boolean {
        val version = input.read()
        if (version != SOCKS_VERSION) return false
        val methodCount = input.read()
        if (methodCount <= 0) return false
        val methods = readExactly(input, methodCount)
        if (methods.size != methodCount || NO_AUTH !in methods.map { it.toInt() }) {
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), NO_ACCEPTABLE_METHODS.toByte()))
            return false
        }
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), NO_AUTH.toByte()))
        return true
    }

    /** Request: VER CMD RSV ATYP DST.ADDR DST.PORT -> connected remote socket or null. */
    private fun request(input: InputStream, output: OutputStream): Socket? {
        val header = readExactly(input, 4)
        if (header.size != 4 || header[0].toInt() != SOCKS_VERSION) return null
        val command = header[1].toInt()
        if (command != CMD_CONNECT) {
            reply(output, REP_COMMAND_NOT_SUPPORTED)
            return null
        }

        val address: InetAddress = when (header[3].toInt()) {
            ATYP_IPV4 -> {
                val raw = readExactly(input, 4)
                if (raw.size != 4) return null
                InetAddress.getByAddress(raw)
            }
            ATYP_DOMAIN -> {
                val length = input.read()
                if (length <= 0) return null
                val name = readExactly(input, length)
                if (name.size != length) return null
                try {
                    // Resolved on the phone -> uses the VPN's DNS.
                    InetAddress.getByName(String(name, Charsets.US_ASCII))
                } catch (_: IOException) {
                    reply(output, REP_HOST_UNREACHABLE)
                    return null
                }
            }
            ATYP_IPV6 -> {
                val raw = readExactly(input, 16)
                if (raw.size != 16) return null
                InetAddress.getByAddress(raw)
            }
            else -> {
                reply(output, REP_ADDRESS_TYPE_NOT_SUPPORTED)
                return null
            }
        }
        val portBytes = readExactly(input, 2)
        if (portBytes.size != 2) return null
        val destinationPort =
            ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        val remote = Socket()
        return try {
            remote.tcpNoDelay = true
            remote.connect(InetSocketAddress(address, destinationPort), CONNECT_TIMEOUT_MS)
            reply(output, REP_SUCCEEDED)
            remote
        } catch (_: IOException) {
            runCatching { remote.close() }
            reply(output, REP_HOST_UNREACHABLE)
            null
        }
    }

    private fun reply(output: OutputStream, code: Int) {
        runCatching {
            // BND.ADDR/PORT are zero — we never do BIND.
            output.write(
                byteArrayOf(
                    SOCKS_VERSION.toByte(), code.toByte(), 0,
                    ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0,
                )
            )
            output.flush()
        }
    }

    private suspend fun relayBothDirections(client: Socket, remote: Socket) {
        val up: Job = scope.launch { copy(client, remote, bytesUp) }
        copy(remote, client, bytesDown) // runs in the caller's IO coroutine
        up.join()
    }

    /** One direction; closing either socket unblocks the other loop. */
    private fun copy(from: Socket, to: Socket, counter: AtomicLong) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                counter.addAndGet(read.toLong())
                listener.onTraffic(bytesUp.get(), bytesDown.get())
            }
            runCatching { to.shutdownOutput() }
        } catch (_: IOException) {
            runCatching { from.close() }
            runCatching { to.close() }
        }
    }

    private fun trackClient(ip: String, delta: Int) {
        clients.compute(ip) { _, count ->
            val next = (count ?: 0) + delta
            if (next <= 0) null else next
        }
        // After stop() the tunnels unwind and would each publish a count into a
        // session the service has already torn down; the owner is not listening
        // any more, so stay quiet rather than churn its state machine.
        if (!stopped) listener.onClientsChanged(clients.size)
    }

    private companion object {
        const val SOCKS_VERSION = 5
        const val NO_AUTH = 0
        const val NO_ACCEPTABLE_METHODS = 0xFF
        const val CMD_CONNECT = 1
        const val ATYP_IPV4 = 1
        const val ATYP_DOMAIN = 3
        const val ATYP_IPV6 = 4
        const val REP_SUCCEEDED = 0
        const val REP_HOST_UNREACHABLE = 4
        const val REP_COMMAND_NOT_SUPPORTED = 7
        const val REP_ADDRESS_TYPE_NOT_SUPPORTED = 8
        const val BACKLOG = 64
        const val BUFFER_SIZE = 16 * 1024
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val CONNECT_TIMEOUT_MS = 15_000
    }
}
