package io.relay.app

import io.relay.app.net.ClientGate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClientGateTest {

    @Test
    fun `allowing lets the client through`() = runTest {
        val gate = ClientGate()
        val request = async { gate.authorize("192.168.1.5") }
        waitForPrompt(gate, "192.168.1.5")
        gate.resolve("192.168.1.5", allowed = true)
        assertTrue(request.await())
    }

    @Test
    fun `denying closes the client out`() = runTest {
        val gate = ClientGate()
        val request = async { gate.authorize("192.168.1.6") }
        waitForPrompt(gate, "192.168.1.6")
        gate.resolve("192.168.1.6", allowed = false)
        assertFalse(request.await())
    }

    @Test
    fun `an approved address is not asked about twice`() = runTest {
        val gate = ClientGate()
        val first = async { gate.authorize("192.168.1.7") }
        waitForPrompt(gate, "192.168.1.7")
        gate.resolve("192.168.1.7", allowed = true)
        assertTrue(first.await())

        // Every later connection from the same PC must go straight through, or
        // opening ten tabs asks the person ten times.
        withTimeout(1_000) { assertTrue(gate.authorize("192.168.1.7")) }
        assertEquals("no prompt should be showing", null, gate.pending.value)
    }

    @Test
    fun `a denied address is refused without asking again`() = runTest {
        val gate = ClientGate()
        val first = async { gate.authorize("10.0.0.9") }
        waitForPrompt(gate, "10.0.0.9")
        gate.resolve("10.0.0.9", allowed = false)
        assertFalse(first.await())
        withTimeout(1_000) { assertFalse(gate.authorize("10.0.0.9")) }
    }

    @Test
    fun `silence is a denial`() = runTest {
        // A phone in a pocket has to fail closed: "I never saw the prompt" must
        // not mean "anyone on this network may use my data".
        val gate = ClientGate(timeoutMs = 50)
        assertFalse(gate.authorize("192.168.1.8"))
    }

    @Test
    fun `two clients do not race to replace each other's prompt`() = runTest {
        val gate = ClientGate()
        val first = async { gate.authorize("192.168.1.10") }
        waitForPrompt(gate, "192.168.1.10")

        val second = async { gate.authorize("192.168.1.11") }
        delay(50)
        // The second must wait its turn rather than overwrite the dialog the
        // person is currently looking at.
        assertEquals("192.168.1.10", gate.pending.value?.address)

        gate.resolve("192.168.1.10", allowed = true)
        assertTrue(first.await())

        waitForPrompt(gate, "192.168.1.11")
        gate.resolve("192.168.1.11", allowed = false)
        assertFalse(second.await())
    }

    @Test
    fun `reset forgets decisions and releases anyone waiting`() = runTest {
        val gate = ClientGate()
        val allowed = async { gate.authorize("192.168.1.12") }
        waitForPrompt(gate, "192.168.1.12")
        gate.resolve("192.168.1.12", allowed = true)
        assertTrue(allowed.await())
        assertTrue(gate.isKnown("192.168.1.12"))

        gate.reset()

        // Stopping and restarting sharing must ask again: a decision made on
        // one network should not carry to the next one.
        assertFalse(gate.isKnown("192.168.1.12"))
        assertEquals(null, gate.pending.value)
    }

    @Test
    fun `reset denies a request that is still waiting`() = runTest {
        val gate = ClientGate()
        val waiting = async { gate.authorize("192.168.1.13") }
        waitForPrompt(gate, "192.168.1.13")
        gate.reset()
        assertFalse("a pending request must not be left hanging", waiting.await())
    }

    private suspend fun waitForPrompt(gate: ClientGate, address: String) {
        withTimeout(2_000) {
            while (gate.pending.value?.address != address) delay(5)
        }
    }
}
