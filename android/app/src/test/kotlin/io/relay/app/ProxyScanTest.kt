package io.relay.app

import io.relay.app.net.ProxyScan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The scan that fills in the one setting nobody can be expected to know.
 *
 * Over real loopback sockets, against servers small enough to read — the same
 * discipline as `PairingServerTest`, and for the same reason: what matters is
 * what goes on the wire, not what a mock agreed to.
 *
 * The case that drives the whole design is [Answers] versus [ConnectOnly]. A
 * proxy that grants CONNECT and refuses datagrams looks identical to a working
 * one until the PC tries to resolve a name — and then nothing works at all,
 * while the setting reads correctly. Tor's SocksPort is that proxy, and it is
 * in `ProxyScan.CANDIDATES` precisely so the scan has to meet one and say no.
 */
class ProxyScanTest {

    /** A server that speaks just enough of RFC 1928 to be probed. */
    private class Fake(
        private val greet: Byte?,
        private val associateReply: Byte?,
        private val silent: Boolean = false,
    ) : Closeable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        private val thread = Thread { serve() }.apply { isDaemon = true; start() }

        private fun serve() {
            while (true) {
                val client = try { socket.accept() } catch (_: Exception) { return }
                Thread { handle(client) }.apply { isDaemon = true }.start()
            }
        }

        private fun handle(client: Socket) {
            client.use {
                try {
                    val input = client.getInputStream()
                    val out = client.getOutputStream()

                    // The greeting: VER, count, then that many methods.
                    val head = ByteArray(2)
                    if (input.read(head) < 2) return
                    input.read(ByteArray(head[1].toInt()))
                    // A server that accepts and never answers. One real port did
                    // exactly this, and without a read timeout it hangs the scan.
                    if (silent) {
                        Thread.sleep(30_000)
                        return
                    }
                    val method = greet ?: return
                    out.write(byteArrayOf(5, method))
                    out.flush()
                    if (method != 0.toByte()) return

                    // The request: VER, CMD, RSV, ATYP, addr, port.
                    val request = ByteArray(10)
                    if (input.read(request) < 10) return
                    val rep = associateReply ?: return
                    out.write(byteArrayOf(5, rep, 0, 1, 127, 0, 0, 1, 0x30, 0x39))
                    out.flush()
                    Thread.sleep(200)
                } catch (_: Exception) {
                    // A probe that hangs up early is not this server's problem.
                }
            }
        }

        override fun close() {
            try { socket.close() } catch (_: Exception) {}
            thread.interrupt()
        }
    }

    /** No auth, and datagrams granted — the proxy this whole feature wants. */
    private fun answers() = Fake(greet = 0.toByte(), associateReply = 0.toByte())

    /** No auth, CONNECT fine, UDP ASSOCIATE refused. Tor, in other words. */
    private fun connectOnly() = Fake(greet = 0.toByte(), associateReply = 0x07.toByte())

    @Test
    fun `a proxy that grants datagrams is found`() {
        answers().use { proxy ->
            assertTrue(
                "the one arrangement the setting exists for was not recognised",
                ProxyScan.probe(port = proxy.port),
            )
            // And the address comes back ready to paste into the field, not as
            // a bare number the caller has to assemble.
            val found = runBlocking { ProxyScan.find(ports = listOf(proxy.port)) }
            assertEquals("127.0.0.1:${proxy.port}", found)
        }
    }

    @Test
    fun `a proxy that refuses datagrams is turned down, not offered`() {
        // The failure this prevents is silent: every TCP connection would work
        // and every DNS lookup would not, so the PC would sit there with a
        // tunnel up and nothing resolving, while the setting looked right.
        connectOnly().use { proxy ->
            assertFalse(ProxyScan.probe(port = proxy.port))
            assertNull(runBlocking { ProxyScan.find(ports = listOf(proxy.port)) })
        }
    }

    @Test
    fun `a proxy demanding credentials is turned down`() {
        // Relay sends none, by design: these listen on loopback on this same
        // phone, so a username field would protect nothing.
        Fake(greet = 0x02.toByte(), associateReply = 0.toByte()).use { proxy ->
            assertFalse(ProxyScan.probe(port = proxy.port))
        }
    }

    @Test
    fun `a port that accepts and never answers does not hang the scan`() {
        // Measured on hardware: the VPN app listened on 1819 and 1820, and 1820
        // accepted TCP and said nothing. Serially that is one dead port taking
        // the whole scan with it.
        Fake(greet = null, associateReply = null, silent = true).use { proxy ->
            val started = System.currentTimeMillis()
            assertFalse(ProxyScan.probe(port = proxy.port, timeoutMs = 300))
            val took = System.currentTimeMillis() - started
            assertTrue("the probe took ${took}ms and should have given up", took < 3_000)
        }
    }

    @Test
    fun `nothing listening means nothing found, not an error`() {
        // A phone with no VPN at all is the common case, and it must come back
        // quietly rather than throwing on fifteen refused connections.
        val free = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        assertFalse(ProxyScan.probe(port = free))
        assertNull(runBlocking { ProxyScan.find(ports = listOf(free)) })
    }

    @Test
    fun `when several answer, the one offered is the same on every scan`() {
        // Two proxies on one phone is normal — a VPN client and a test tunnel.
        // Returning whichever socket answered first would offer a different
        // port each run, and this setting changes where the PC's packets go.
        answers().use { first ->
            answers().use { second ->
                val order = listOf(first.port, second.port)
                val results = (1..4).map { runBlocking { ProxyScan.find(ports = order) } }
                assertEquals(List(4) { "127.0.0.1:${first.port}" }, results)
            }
        }
    }

    @Test
    fun `every candidate is a real port and the measured ones are in the list`() {
        ProxyScan.CANDIDATES.forEach {
            assertTrue("candidate $it is not a port", it in 1..65535)
        }
        assertEquals(
            "a duplicate candidate is a wasted probe",
            ProxyScan.CANDIDATES.size,
            ProxyScan.CANDIDATES.distinct().size,
        )
        // 1819 was measured on an SM-A307FN running Oblivion; 10808 is what the
        // field's own placeholder has suggested since the setting shipped. If
        // either leaves this list, the two grounded entries are gone and the
        // rest are guesses.
        assertTrue(ProxyScan.CANDIDATES.containsAll(listOf(1819, 10808)))
    }
}
