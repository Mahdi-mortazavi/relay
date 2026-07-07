package io.relay.app

import io.relay.app.core.ConnectionRules
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionRulesTest {

    private val shared = SharedContracts.json("connection-states.json").jsonObject

    @Test
    fun `states and initial state match the shared contract`() {
        val sharedStates = shared.getValue("states").jsonArray
            .map { it.jsonPrimitive.content }.toSet()
        assertEquals(sharedStates, ConnectionRules.states)
        assertEquals(shared.getValue("initial").jsonPrimitive.content, ConnectionRules.initial)
    }

    @Test
    fun `transition table matches the shared contract exactly`() {
        val sharedTransitions = shared.getValue("transitions").jsonArray.associate {
            val t = it.jsonObject
            (t.getValue("from").jsonPrimitive.content to t.getValue("on").jsonPrimitive.content) to
                t.getValue("to").jsonPrimitive.content
        }
        assertEquals(sharedTransitions, ConnectionRules.transitions)
    }

    @Test
    fun `undefined transitions are illegal`() {
        assertFalse(ConnectionRules.canTransition("Idle", "clientConnected"))
        assertFalse(ConnectionRules.canTransition("Connected", "ready"))
        assertFalse(ConnectionRules.canTransition("Error", "stop"))
    }
}
