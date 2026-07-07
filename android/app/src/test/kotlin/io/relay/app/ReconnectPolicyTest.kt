package io.relay.app

import io.relay.app.core.ReconnectPolicy
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {

    private val reconnect =
        SharedContracts.json("test-vectors.json").jsonObject.getValue("reconnect").jsonObject

    @Test
    fun `schedule matches the shared contract`() {
        val expected = reconnect.getValue("attemptDelaysMs").jsonArray.map { it.jsonPrimitive.long }
        assertEquals(expected, ReconnectPolicy.attemptDelaysMs.toList())
    }

    @Test
    fun `attempt count matches the shared contract`() {
        assertEquals(reconnect.getValue("attempts").jsonPrimitive.int, ReconnectPolicy.attempts)
    }

    @Test
    fun `total recovery bound matches the shared contract`() {
        assertEquals(reconnect.getValue("totalBoundMs").jsonPrimitive.long, ReconnectPolicy.totalBoundMs)
    }
}
