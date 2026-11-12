package io.relay.app

import io.relay.app.core.PeerPool
import io.relay.app.core.WgConfig
import io.relay.app.service.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerPoolTest {

    private val keys = WgConfig.KeySet(
        serverPrivateKey = "P2GJ1aWQb5UrrLNLl/iwYX/AA8K6C/BS9L1e2Jnewpo=",
        serverPublicKey = "a1yn3fwDEBl6ZPc+KD67rwGn0oia5dlFElp9lkWxGI4=",
        clientPrivateKey = "6dE0jvo6/PbUqznG29Xkno78HyGGwtH1UYl3WXQEkZQ=",
        clientPublicKey = "alMc1KaITw/vbYW3PmNUub6swP8d+rw3I0fu01FVefc=",
    )

    @Test
    fun `peer pool allocates distinct ips and keys per pc`() {
        var counter = 0
        val pool = PeerPool(keys.serverPublicKey, keys.endpointPort, keys) {
            counter++
            "priv$counter" to "pub$counter"
        }

        val (pc1, created1) = pool.allocateFor("192.168.43.10")
        val (pc1Again, created1Again) = pool.allocateFor("192.168.43.10")
        val (pc2, created2) = pool.allocateFor("192.168.43.11")

        assertTrue(created1)
        assertFalse(created1Again)
        assertTrue(created2)
        assertEquals(pc1, pc1Again)
        assertEquals("10.13.37.3", pc1.ipv4)
        assertEquals("fd13:37::3", pc1.ipv6)
        assertEquals("10.13.37.4", pc2.ipv4)
        assertEquals("fd13:37::4", pc2.ipv6)
        assertNotEquals(pc1.clientPublicKey, pc2.clientPublicKey)

        val params = pool.toWgParams(pc2, dns = "9.9.9.9", dohUrl = "https://dns.quad9.net/dns-query")
        assertEquals("10.13.37.4/32", params.clientAddress)
        assertEquals("fd13:37::4/128", params.clientIpv6)
        assertEquals("fd13:37::1", params.dnsIpv6)
        assertEquals("https://dns.quad9.net/dns-query", params.dohUrl)
        assertEquals("0.0.0.0/0,::/0", params.allowedIps)
    }

    @Test
    fun `inbound forward rule parser accepts valid rules and rejects invalid ones`() {
        val rule = Settings.parseInboundForwardRule("tcp:8080->10.13.37.2:8080")
        assertEquals("tcp", rule?.proto)
        assertEquals(8080, rule?.listenPort)
        assertEquals("10.13.37.2", rule?.targetHost)
        assertEquals(8080, rule?.targetPort)

        assertNull(Settings.parseInboundForwardRule("icmp:8080->10.13.37.2:80"))
        assertNull(Settings.parseInboundForwardRule("tcp:99999->10.13.37.2:80"))
        assertNull(Settings.parseInboundForwardRule("bad"))
    }
}
