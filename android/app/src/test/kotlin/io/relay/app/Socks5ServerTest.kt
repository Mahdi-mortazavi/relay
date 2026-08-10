package io.relay.app

import io.relay.app.net.Socks5Server
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Protocol-level tests against the real [Socks5Server] over real loopback
 * sockets — no mocks of the network layer. These are the fastest rung of the
 * pyramid described in docs/testing.md: they run in `testDebugUnitTest` on
 * every PR and cover the behaviour the emulator E2E can only sample.
 */
class Socks5ServerTest {

    private val servers = CopyOnWriteArrayList<Socks5Server>()
    private val closeables = CopyOnWriteArrayList<AutoCloseable>()
    private lateinit var listener: RecordingListener

    @Before
    fun setUp() {
        listener = RecordingListener()
    }

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.stop() } }
        closeables.forEach { runCatching { it.close() } }
    }

    // --- happy path ----------------------------------------------------------

    @Test
    fun `relays bytes in both directions through a CONNECT tunnel`() {
        val echo = startEchoServer()
        val server = startServer()

        val client = socksConnect(server.boundPort, echo.localPort)
        client.getOutputStream().write("ping".toByteArray())
        client.getOutputStream().flush()

        assertArrayEquals("ping".toByteArray(), readExactly(client.getInputStream(), 4))
    }

    @Test
    fun `start reports the port it actually bound`() {
        val server = startServer()
        assertNotEquals(0, server.boundPort)
        assertTrue(server.boundPort in 1..65535)
    }

    @Test
    fun `a bound port is reported as unavailable to the next bind`() {
        val first = startServer()
        val second = Socks5Server(first.boundPort, listener).also { servers += it }
        var threw = false
        try {
            second.start()
        } catch (_: IOException) {
            threw = true
        }
        assertTrue("binding an occupied port must throw so the service can fall back", threw)
    }

    @Test
    fun `counts one client while a tunnel is open and zero after it closes`() {
        val echo = startEchoServer()
        val server = startServer()

        val client = socksConnect(server.boundPort, echo.localPort)
        waitFor("client to be counted") { listener.devices == 1 }

        client.close()
        waitFor("client to be uncounted") { listener.devices == 0 }
    }

    // --- stop() must actually stop the traffic --------------------------------

    /**
     * Regression: `stop()` used to close only the listening socket and cancel the
     * coroutine scope. A blocking `read()` is not interruptible by coroutine
     * cancellation, so every established tunnel kept relaying — the user tapped
     * "Stop sharing", the notification went away, the wake lock was released, and
     * the laptop carried on browsing through the phone indefinitely.
     */
    @Test
    fun `stop closes tunnels that are still open`() {
        val echo = startEchoServer()
        val server = startServer()

        val client = socksConnect(server.boundPort, echo.localPort)
        client.getOutputStream().write("still here".toByteArray())
        client.getOutputStream().flush()
        readExactly(client.getInputStream(), 10)

        server.stop()

        // The peer must observe EOF (or a reset) promptly, not stay tunnelled.
        // A read *timeout* is the failure this test exists for: it means the
        // socket is still open and the tunnel survived stop().
        client.soTimeout = 5_000
        val eof = try {
            client.getInputStream().read()
        } catch (_: SocketTimeoutException) {
            throw AssertionError("tunnel survived stop(): the peer's read did not end within 5s")
        } catch (_: IOException) {
            -1 // connection reset also means the tunnel is gone
        }
        assertEquals("tunnel must be torn down by stop()", -1, eof)
    }

    @Test
    fun `stop stops accepting new connections`() {
        val server = startServer()
        val port = server.boundPort
        server.stop()

        var refused = false
        try {
            Socket().use { it.connect(InetSocketAddress(loopback, port), 2_000) }
        } catch (_: IOException) {
            refused = true
        }
        assertTrue("the listening socket must be closed by stop()", refused)
    }

    @Test
    fun `stop is idempotent`() {
        val server = startServer()
        server.stop()
        server.stop()
    }

    // --- client accounting ----------------------------------------------------

    /**
     * Regression: the per-IP connection counter was decremented in `finally` even
     * when the connection never got far enough to be counted. A browser opening a
     * connection that fails at the handshake or at CONNECT (a dead host, a probe,
     * a cancelled request — all routine) therefore zeroed the counter for an IP
     * that still had a live tunnel: the phone dropped from "Connected" back to
     * "Waiting for a device" and released the transfer wake lock mid-download.
     */
    @Test
    fun `an aborted handshake does not uncount a live tunnel from the same IP`() {
        val echo = startEchoServer()
        val server = startServer()

        val live = socksConnect(server.boundPort, echo.localPort)
        waitFor("first client to be counted") { listener.devices == 1 }

        // Same source IP (loopback), abandoned before the greeting completes.
        Socket().use { probe ->
            probe.connect(InetSocketAddress(loopback, server.boundPort), 2_000)
            probe.getOutputStream().write(byteArrayOf(0x05))
            probe.getOutputStream().flush()
        }

        // And one that completes the greeting but asks for a dead port.
        val unreachable = Socket()
        closeables += unreachable
        unreachable.connect(InetSocketAddress(loopback, server.boundPort), 2_000)
        greet(unreachable)
        requestConnect(unreachable, deadPort())
        assertEquals(REP_HOST_UNREACHABLE, readReply(unreachable.getInputStream()))
        unreachable.close()

        // Give the server room to (wrongly) publish a zero.
        Thread.sleep(300)
        assertEquals("live tunnel must still be counted", 1, listener.devices)
        assertTrue("must never have reported zero while a tunnel was open", listener.minDevicesSeen >= 1)

        // Sanity: the real tunnel is still usable.
        live.getOutputStream().write("ok".toByteArray())
        live.getOutputStream().flush()
        assertArrayEquals("ok".toByteArray(), readExactly(live.getInputStream(), 2))
    }

    @Test
    fun `a refused destination is reported and never counted as a client`() {
        val server = startServer()
        val socket = Socket()
        closeables += socket
        socket.connect(InetSocketAddress(loopback, server.boundPort), 2_000)
        greet(socket)
        requestConnect(socket, deadPort())

        assertEquals(REP_HOST_UNREACHABLE, readReply(socket.getInputStream()))
        Thread.sleep(200)
        assertEquals(0, listener.devices)
    }

    // --- malformed input (red team) ------------------------------------------

    @Test
    fun `rejects a non-SOCKS5 greeting without crashing the server`() {
        val server = startServer()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(loopback, server.boundPort), 2_000)
            socket.getOutputStream().write(byteArrayOf(0x04, 0x01, 0x00)) // SOCKS4
            socket.getOutputStream().flush()
        }
        assertServerStillServes(server)
    }

    @Test
    fun `answers NO ACCEPTABLE METHODS when the client offers only authenticated methods`() {
        val server = startServer()
        val socket = Socket()
        closeables += socket
        socket.connect(InetSocketAddress(loopback, server.boundPort), 2_000)
        socket.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x02)) // username/password only
        socket.getOutputStream().flush()

        val reply = readExactly(socket.getInputStream(), 2)
        assertArrayEquals(byteArrayOf(0x05, 0xFF.toByte()), reply)
        assertServerStillServes(server)
    }

    @Test
    fun `answers COMMAND NOT SUPPORTED for BIND`() {
        val server = startServer()
        val socket = Socket()
        closeables += socket
        socket.connect(InetSocketAddress(loopback, server.boundPort), 2_000)
        greet(socket)
        socket.getOutputStream().write(
            byteArrayOf(0x05, 0x02, 0x00, 0x01, 127, 0, 0, 1, 0x00, 0x50) // CMD=BIND
        )
        socket.getOutputStream().flush()

        assertEquals(REP_COMMAND_NOT_SUPPORTED, readReply(socket.getInputStream()))
        assertServerStillServes(server)
    }

    @Test
    fun `answers ADDRESS TYPE NOT SUPPORTED for an unknown ATYP`() {
        val server = startServer()
        val socket = Socket()
        closeables += socket
        socket.connect(InetSocketAddress(loopback, server.boundPort), 2_000)
        greet(socket)
        socket.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00, 0x09))
        socket.getOutputStream().flush()

        assertEquals(REP_ADDRESS_TYPE_NOT_SUPPORTED, readReply(socket.getInputStream()))
        assertServerStillServes(server)
    }

    @Test
    fun `survives a connection that closes mid-request`() {
        val server = startServer()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(loopback, server.boundPort), 2_000)
            greet(socket)
            socket.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 127, 0)) // truncated
            socket.getOutputStream().flush()
        }
        assertServerStillServes(server)
    }

    @Test
    fun `survives a connection that sends nothing at all`() {
        val server = startServer()
        Socket().use { it.connect(InetSocketAddress(loopback, server.boundPort), 2_000) }
        assertServerStillServes(server)
    }

    @Test
    fun `serves many concurrent tunnels`() {
        val echo = startEchoServer()
        val server = startServer()
        val workers = (1..16).map { index ->
            thread {
                val client = socksConnect(server.boundPort, echo.localPort)
                val payload = "hello-$index".toByteArray()
                client.getOutputStream().write(payload)
                client.getOutputStream().flush()
                assertArrayEquals(payload, readExactly(client.getInputStream(), payload.size))
                client.close()
            }
        }
        workers.forEach { it.join(15_000) }
        workers.forEach { assertTrue("worker did not finish", !it.isAlive) }
    }

    @Test
    fun `counts distinct tunnels from one IP as a single device`() {
        val echo = startEchoServer()
        val server = startServer()
        val a = socksConnect(server.boundPort, echo.localPort)
        val b = socksConnect(server.boundPort, echo.localPort)
        waitFor("both tunnels open") { listener.devices == 1 }

        a.close()
        Thread.sleep(300)
        assertEquals("one tunnel still open from the same IP", 1, listener.devices)

        b.close()
        waitFor("last tunnel closed") { listener.devices == 0 }
    }

    @Test
    fun `reports transferred bytes`() {
        val echo = startEchoServer()
        val server = startServer()
        val client = socksConnect(server.boundPort, echo.localPort)
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        thread { client.getOutputStream().write(payload); client.getOutputStream().flush() }

        readExactly(client.getInputStream(), payload.size)
        waitFor("byte counters to catch up") {
            listener.up.get() >= payload.size && listener.down.get() >= payload.size
        }
    }

    // --- helpers --------------------------------------------------------------

    private val loopback: InetAddress get() = InetAddress.getByName("127.0.0.1")

    private fun startServer(): Socks5Server =
        Socks5Server(0, listener).also {
            servers += it
            it.start()
        }

    /** Loopback echo server standing in for "the internet" behind the proxy. */
    private fun startEchoServer(): ServerSocket {
        val server = ServerSocket(0, 16, loopback)
        closeables += AutoCloseable { server.close() }
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
                            val input = it.getInputStream()
                            val output = it.getOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                output.flush()
                            }
                        }
                    }
                }
            }
        }
        return server
    }

    /** Full SOCKS5 greeting + CONNECT to 127.0.0.1:[destinationPort]. */
    private fun socksConnect(proxyPort: Int, destinationPort: Int): Socket {
        val socket = Socket()
        closeables += socket
        socket.connect(InetSocketAddress(loopback, proxyPort), 5_000)
        socket.tcpNoDelay = true
        greet(socket)
        requestConnect(socket, destinationPort)
        assertEquals("CONNECT must succeed", REP_SUCCEEDED, readReply(socket.getInputStream()))
        return socket
    }

    private fun greet(socket: Socket) {
        socket.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
        socket.getOutputStream().flush()
        val reply = readExactly(socket.getInputStream(), 2)
        assertArrayEquals("greeting must be accepted", byteArrayOf(0x05, 0x00), reply)
    }

    private fun requestConnect(socket: Socket, destinationPort: Int) {
        socket.getOutputStream().write(
            byteArrayOf(
                0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1,
                (destinationPort shr 8).toByte(), (destinationPort and 0xFF).toByte(),
            )
        )
        socket.getOutputStream().flush()
    }

    /** Reads the 10-byte reply and returns its REP code. */
    private fun readReply(input: InputStream): Int {
        val reply = readExactly(input, 10)
        assertEquals("reply must be 10 bytes", 10, reply.size)
        assertEquals("reply version", 5, reply[0].toInt())
        return reply[1].toInt()
    }

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

    /** A port nothing is listening on. */
    private fun deadPort(): Int = ServerSocket(0, 1, loopback).use { it.localPort }

    /** The server is still healthy after a malformed client. */
    private fun assertServerStillServes(server: Socks5Server) {
        val echo = startEchoServer()
        val client = socksConnect(server.boundPort, echo.localPort)
        client.getOutputStream().write("alive".toByteArray())
        client.getOutputStream().flush()
        assertArrayEquals("alive".toByteArray(), readExactly(client.getInputStream(), 5))
        client.close()
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private class RecordingListener : Socks5Server.Listener {
        @Volatile var devices: Int = 0
            private set

        /** Lowest count published while at least one count had been published. */
        @Volatile var minDevicesSeen: Int = Int.MAX_VALUE
            private set

        val up = AtomicLong()
        val down = AtomicLong()
        private var sawFirstClient = false

        @Synchronized
        override fun onClientsChanged(devices: Int) {
            this.devices = devices
            if (devices > 0) sawFirstClient = true
            if (sawFirstClient) minDevicesSeen = minOf(minDevicesSeen, devices)
        }

        override fun onTraffic(bytesUp: Long, bytesDown: Long) {
            up.set(bytesUp)
            down.set(bytesDown)
        }
    }

    private companion object {
        const val REP_SUCCEEDED = 0
        const val REP_HOST_UNREACHABLE = 4
        const val REP_COMMAND_NOT_SUPPORTED = 7
        const val REP_ADDRESS_TYPE_NOT_SUPPORTED = 8
    }
}
