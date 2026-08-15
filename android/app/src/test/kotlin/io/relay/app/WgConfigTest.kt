package io.relay.app

import io.relay.app.core.DirectPairingStrategy
import io.relay.app.core.QrPayload
import io.relay.app.core.TypedCode
import io.relay.app.core.QrPayloadCodec
import io.relay.app.core.DecodeResult
import io.relay.app.core.WgConfig
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun `server config is exactly what the Go endpoint is given`() {
        // Byte-for-byte against /shared/test-vectors.json, which the Go suite
        // also feeds to a real wireguard-go device. Asserting "contains
        // PrivateKey =" here is what let this ship as wg-quick INI: every
        // assertion passed and no device would have taken the file.
        val vector = SharedContracts.json("test-vectors.json")
            .jsonObject.getValue("wgServerConfig").jsonObject
        val expected = vector.getValue("ipc").jsonPrimitive.content
        assertEquals(expected, WgConfig.serverConfig(keys))
    }

    @Test
    fun `the tunnel addresses match the endpoint and the Windows client`() {
        // Three copies of these two constants exist. When this file said
        // 10.7.0.x, the peer's packets were dropped on cryptokey routing: a
        // tunnel that handshakes and carries nothing.
        val vector = SharedContracts.json("test-vectors.json")
            .jsonObject.getValue("wgServerConfig").jsonObject
        assertEquals(vector.getValue("serverTunnelIp").jsonPrimitive.content, WgConfig.SERVER_TUNNEL_IP)
        assertEquals(vector.getValue("clientTunnelIp").jsonPrimitive.content, WgConfig.CLIENT_TUNNEL_IP)
    }

    @Test
    fun `hex conversion refuses anything that is not a 32-byte key`() {
        // A short key encodes happily and produces a device that never
        // handshakes, so this has to fail loudly at assembly time.
        assertThrows(IllegalArgumentException::class.java) { WgConfig.toHex("c2hvcnQ=") }
        assertThrows(IllegalArgumentException::class.java) { WgConfig.toHex("not base64!") }
        assertThrows(IllegalArgumentException::class.java) { WgConfig.toHex("") }
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

    /**
     * The eight-character code is offered now, where it used to be withheld.
     *
     * It was Fast Mode only, because Full Mode's keys do not fit in something a
     * person can type and an address alone could not build a tunnel. Since
     * ADR-0009 an address is enough: the PC dials the pairing port there and the
     * keys come over that exchange.
     *
     * Withholding it is no longer a safe default, which is the real reason this
     * test flipped rather than being deleted. The two-digit code is already
     * withheld when the phone cannot announce itself, so a phone that refused
     * this one too would show *no code at all* on a network without broadcast --
     * shareable by QR alone, with nothing on screen explaining why.
     */
    @Test
    fun `the typed code is offered, because an address is now enough`() {
        val payload = DirectPairingStrategy().issuePayload(
            QrPayload.MODE_WIREGUARD, "192.168.43.1", 51820, "Pixel", WgConfig.toWgParams(keys),
        )
        assertEquals(
            TypedCode.encode("192.168.43.1", 51820),
            DirectPairingStrategy().issueTypedCode(payload),
        )
    }
}
