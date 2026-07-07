package io.relay.app

import io.relay.app.core.DirectPairingStrategy
import io.relay.app.core.QrPayload
import io.relay.app.core.QrPayloadCodec
import io.relay.app.core.DecodeResult
import io.relay.app.core.WgConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WgConfigTest {

    // Format-valid keys (from /shared/test-vectors.json), not real ones.
    private val keys = WgConfig.KeySet(
        serverPrivateKey = "P2GJ1aWQb5UrrLNLl/iwYX/AA8K6C/BS9L1e2Jnewpo=",
        serverPublicKey = "a1yn3fwDEBl6ZPc+KD67rwGn0oia5dlFElp9lkWxGI4=",
        clientPrivateKey = "6dE0jvo6/PbUqznG29Xkno78HyGGwtH1UYl3WXQEkZQ=",
        clientPublicKey = "alMc1KaITw/vbYW3PmNUub6swP8d+rw3I0fu01FVefc=",
    )

    @Test
    fun `wg params carry the server public and client private keys`() {
        val params = WgConfig.toWgParams(keys)
        assertEquals(keys.serverPublicKey, params.serverPublicKey)
        assertEquals(keys.clientPrivateKey, params.clientPrivateKey)
        assertEquals(WgConfig.CLIENT_ALLOWED_IPS, params.allowedIps)
        assertEquals(WgConfig.DEFAULT_ENDPOINT_PORT, params.endpointPort)
    }

    @Test
    fun `server config lists the client peer at its tunnel ip`() {
        val config = WgConfig.serverConfig(keys)
        assertTrue(config.contains("PrivateKey = ${keys.serverPrivateKey}"))
        assertTrue(config.contains("ListenPort = ${keys.endpointPort}"))
        assertTrue(config.contains("PublicKey = ${keys.clientPublicKey}"))
        assertTrue(config.contains("AllowedIPs = ${WgConfig.CLIENT_TUNNEL_IP}/32"))
    }

    @Test
    fun `issued wireguard payload passes the shared codec validation`() {
        val payload = DirectPairingStrategy { 1730000000L }.issuePayload(
            mode = QrPayload.MODE_WIREGUARD,
            host = "192.168.43.1",
            port = WgConfig.DEFAULT_ENDPOINT_PORT,
            deviceName = "Pixel",
            wg = WgConfig.toWgParams(keys),
        )
        val roundTrip = QrPayloadCodec.decode(QrPayloadCodec.encode(payload))
        assertTrue(roundTrip is DecodeResult.Ok)
        assertEquals(payload, (roundTrip as DecodeResult.Ok).payload)
    }

    @Test
    fun `typed code is not offered for full mode`() {
        val payload = DirectPairingStrategy().issuePayload(
            QrPayload.MODE_WIREGUARD, "192.168.43.1", 51820, "Pixel", WgConfig.toWgParams(keys),
        )
        assertNull(DirectPairingStrategy().issueTypedCode(payload))
    }
}
