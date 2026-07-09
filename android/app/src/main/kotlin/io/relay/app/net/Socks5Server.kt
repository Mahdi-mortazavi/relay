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

    /** @throws BindException when [port] is taken. */
    @Throws(IOException::class)
    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port), BACKLOG)
        serverSocket = socket
        scope.launch { acceptLoop(socket) }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        scope.cancel()
        clients.clear()
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
        try {
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = client.getInputStream()
            val output = client.getOutputStream()

            if (!handshake(input, output)) return
            remote = request(input, output) ?: return
            client.soTimeout = 0

            trackClient(clientIp, +1)
            relayBothDirections(client, remote)
        } catch (_: IOException) {
            // Connection failures surface to the peer via socket close.
        } finally {
            runCatching { remote?.close() }
            runCatching { client.close() }
            trackClient(clientIp, -1)
        }
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
        listener.onClientsChanged(clients.size)
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
