package io.relay.app

import io.relay.app.core.TypedCode
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypedCodeTest {

    private val typedCodes =
        SharedContracts.json("test-vectors.json").jsonObject.getValue("typedCodes").jsonObject

    @Test
    fun `valid vectors encode to the canonical code`() {
        for (vector in typedCodes.getValue("valid").jsonArray) {
            val v = vector.jsonObject
            assertEquals(
                v.getValue("code").jsonPrimitive.content,
                TypedCode.encode(
                    v.getValue("host").jsonPrimitive.content,
                    v.getValue("port").jsonPrimitive.int,
                ),
            )
        }
    }

    @Test
    fun `valid vectors decode back to host and port`() {
        for (vector in typedCodes.getValue("valid").jsonArray) {
            val v = vector.jsonObject
            val decoded = TypedCode.decode(v.getValue("code").jsonPrimitive.content)
            assertEquals(v.getValue("host").jsonPrimitive.content, decoded?.host)
            assertEquals(v.getValue("port").jsonPrimitive.int, decoded?.port)
        }
    }

    @Test
    fun `input is case-insensitive and separator-tolerant`() {
        val code = typedCodes.getValue("valid").jsonArray.first()
            .jsonObject.getValue("code").jsonPrimitive.content
        val relaxed = code.lowercase().chunked(4).joinToString("-")
        assertEquals(TypedCode.decode(code), TypedCode.decode(relaxed))
    }

    @Test
    fun `invalid codes are rejected`() {
        for (vector in typedCodes.getValue("invalid").jsonArray) {
            val v = vector.jsonObject
            assertNull(
                "${v["description"]}",
                TypedCode.decode(v.getValue("code").jsonPrimitive.content),
            )
        }
    }

    @Test
    fun `hosts outside 192-168 are not encodable`() {
        assertNull(TypedCode.encode("10.0.0.1", 1080))
        assertNull(TypedCode.encode("172.16.5.1", 1080))
    }
}
