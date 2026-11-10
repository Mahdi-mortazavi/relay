package io.relay.app

import io.relay.app.core.TrustProof
import io.relay.app.net.ClientGate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClientGateTrustTest {

    private class Remembered(val key: ByteArray, val name: String?)

    private suspend fun waitForPrompt(gate: ClientGate, address: String) {
        withTimeout(2_000) { while (gate.pending.value?.address != address) delay(5) }
    }

    @Test
    fun `an admitted address goes straight through with no prompt`() = runTest {
        val gate = ClientGate(timeoutMs = 50)
        gate.admit("192.168.1.40")
        withTimeout(1_000) { assertTrue(gate.authorize("192.168.1.40")) }
        assertNull(gate.pending.value)
        gate.reset()
        // An address decision is per session, even one that came from trust.
        assertTrue(!gate.isKnown("192.168.1.40"))
    }

    @Test
    fun `always allow on a proven key reports that key and name`() = runTest {
        val seen = mutableListOf<Remembered>()
        val gate = ClientGate(onRemember = { key, name -> seen += Remembered(key, name) })
        val pc = TestComputer()
        val request = async { gate.authorize("192.168.1.41", "MAHDI-LAPTOP", provenKey = pc.key) }
        waitForPrompt(gate, "192.168.1.41")
        assertEquals(TrustProof.encode(pc.key), gate.pending.value?.provenKey)
        gate.resolve("192.168.1.41", allowed = true, remember = true)
        assertTrue(request.await())
        assertEquals(1, seen.size)
        assertArrayEquals(pc.key, seen[0].key)
        assertEquals("MAHDI-LAPTOP", seen[0].name)
    }

    @Test
    fun `nothing is remembered unless the person asked`() = runTest {
        val seen = mutableListOf<Remembered>()
        val gate = ClientGate(onRemember = { key, name -> seen += Remembered(key, name) })
        val request = async { gate.authorize("192.168.1.42", "A", provenKey = TestComputer().key) }
        waitForPrompt(gate, "192.168.1.42")
        gate.resolve("192.168.1.42", allowed = true) // plain Allow
        assertTrue(request.await())
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a denial is never remembered, whatever was asked`() = runTest {
        val seen = mutableListOf<Remembered>()
        val gate = ClientGate(onRemember = { key, name -> seen += Remembered(key, name) })
        val request = async { gate.authorize("192.168.1.43", "A", provenKey = TestComputer().key) }
        waitForPrompt(gate, "192.168.1.43")
        gate.resolve("192.168.1.43", allowed = false, remember = true)
        assertTrue(!request.await())
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a key that was not proven cannot be remembered`() = runTest {
        val seen = mutableListOf<Remembered>()
        val gate = ClientGate(onRemember = { key, name -> seen += Remembered(key, name) })
        val request = async { gate.authorize("192.168.1.44", "A") } // no proven key
        waitForPrompt(gate, "192.168.1.44")
        assertNull(gate.pending.value?.provenKey)
        gate.resolve("192.168.1.44", allowed = true, remember = true)
        assertTrue(request.await())
        assertTrue("there was nothing proven to remember", seen.isEmpty())
    }
}
