package io.relay.app

import io.relay.app.net.ClientGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `resolving does not run the waiter on the caller's thread`() = runTest {
        // The bug this guards against: with a hand-resumed continuation, the
        // coroutine that was waiting continues *inline on whichever thread
        // called resolve*. That thread is the UI thread, and the coroutine it
        // resumes is the one that goes on to relay the connection -- so a tap
        // on Allow ran socket I/O on the main thread. In tests it hung the
        // thread that approved; in the app it would freeze the screen.
        val gate = ClientGate()
        val resumedOn = java.util.concurrent.atomic.AtomicReference<String>()
        val resolverThread = Thread.currentThread().name

        val request = async(Dispatchers.Default) {
            val allowed = gate.authorize("192.168.1.20")
            resumedOn.set(Thread.currentThread().name)
            allowed
        }
        withTimeout(2_000) {
            while (gate.pending.value?.address != "192.168.1.20") delay(5)
        }
        gate.resolve("192.168.1.20", allowed = true)

        assertTrue(request.await())
        assertNotEquals(
            "the waiter resumed on the thread that answered the prompt",
            resolverThread,
            resumedOn.get(),
        )
    }

    private suspend fun waitForPrompt(gate: ClientGate, address: String) {
        withTimeout(2_000) {
            while (gate.pending.value?.address != address) delay(5)
        }
    }
}
