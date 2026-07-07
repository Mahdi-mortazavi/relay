package io.relay.app

import io.relay.app.core.DecodeResult
import io.relay.app.core.QrPayload
import io.relay.app.core.QrPayloadCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPayloadCodecTest {

    private val vectors = SharedContracts.json("test-vectors.json").jsonObject
    private val json = Json { explicitNulls = false }

    @Test
    fun `valid vectors decode to the expected payload`() {
        for (vector in vectors.getValue("valid").jsonArray) {
            val v = vector.jsonObject
            val expected = json.decodeFromJsonElement(QrPayload.serializer(), v.getValue("payload"))
            val result = QrPayloadCodec.decode(v.getValue("encoded").jsonPrimitive.content)
            assertTrue("${v["description"]}: $result", result is DecodeResult.Ok)
            assertEquals("${v["description"]}", expected, (result as DecodeResult.Ok).payload)
        }
    }

    @Test
    fun `valid vectors round-trip through encode`() {
        for (vector in vectors.getValue("valid").jsonArray) {
            val v = vector.jsonObject
            val payload = json.decodeFromJsonElement(QrPayload.serializer(), v.getValue("payload"))
            val result = QrPayloadCodec.decode(QrPayloadCodec.encode(payload))
            assertEquals("${v["description"]}", DecodeResult.Ok(payload), result)
        }
    }

    @Test
    fun `invalid payloads are rejected with the exact shared reason`() {
        for (vector in vectors.getValue("invalid").jsonArray) {
            val v = vector.jsonObject
            val result = QrPayloadCodec.validate(v.getValue("payload").jsonObject)
            assertTrue("${v["description"]}: expected Invalid, got $result", result is DecodeResult.Invalid)
            assertEquals(
                "${v["description"]}",
                v.getValue("reason").jsonPrimitive.content,
                (result as DecodeResult.Invalid).reason,
            )
        }
    }

    @Test
    fun `malformed encodings are rejected as decode-error`() {
        for (vector in vectors.getValue("invalidEncoded").jsonArray) {
            val v = vector.jsonObject
            val result = QrPayloadCodec.decode(v.getValue("encoded").jsonPrimitive.content)
            assertEquals(
                "${v["description"]}",
                DecodeResult.Invalid(v.getValue("reason").jsonPrimitive.content),
                result,
            )
        }
    }

    @Test
    fun `qr prefix is accepted and produced`() {
        val encoded = vectors.getValue("valid").jsonArray.first()
            .jsonObject.getValue("encoded").jsonPrimitive.content
        val bare = QrPayloadCodec.decode(encoded)
        val prefixed = QrPayloadCodec.decode(QrPayloadCodec.QR_PREFIX + encoded)
        assertEquals(bare, prefixed)
        assertTrue(
            QrPayloadCodec.encodeForQr((bare as DecodeResult.Ok).payload)
                .startsWith(QrPayloadCodec.QR_PREFIX)
        )
    }
}
