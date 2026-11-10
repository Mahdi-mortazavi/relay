package io.relay.app

import io.relay.app.core.TrustProof
import io.relay.app.net.PairingServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The proof a PC gives when it asks to be remembered, against the shared vectors
 * (`test-vectors.json` → `trustedComputers`) that Windows asserts as well.
 *
 * The invalid half is the half that matters. A verifier that accepts what it
 * should refuse hands a stranger the phone's connection with no prompt.
 */
class TrustProofTest {

    private val vectors: JsonObject =
        SharedContracts.json("test-vectors.json").jsonObject.getValue("trustedComputers").jsonObject

    private fun bytes(case: JsonObject, field: String) =
        TrustProof.decode(case.getValue(field).jsonPrimitive.content)

    private fun verdict(case: JsonObject): Boolean {
        // Mirrors what the server does with a request: an undecodable or
        // off-curve key never reaches verify at all.
        val key = TrustProof.decodeKey(case.getValue("key").jsonPrimitive.content) ?: return false
        val challenge = bytes(case, "challenge") ?: return false
        val proof = bytes(case, "proof") ?: return false
        return TrustProof.verify(key, challenge, proof)
    }

    @Test
    fun `every valid vector verifies`() {
        for (case in vectors.getValue("valid").jsonArray.map { it.jsonObject }) {
            assertTrue(case.getValue("name").jsonPrimitive.content, verdict(case))
        }
    }

    @Test
    fun `every invalid vector is refused`() {
        for (case in vectors.getValue("invalid").jsonArray.map { it.jsonObject }) {
            assertFalse(case.getValue("name").jsonPrimitive.content, verdict(case))
        }
    }

    @Test
    fun `the domain and message layout are the contract's`() {
        assertEquals(vectors.getValue("domain").jsonPrimitive.content, TrustProof.DOMAIN)
        val key = ByteArray(65) { 7 }
        val challenge = ByteArray(32) { 9 }
        val message = TrustProof.message(challenge, key)
        assertEquals(15 + 32 + 65, message.size)
        assertArrayEquals(
            "relay-trust-v1\n".toByteArray(Charsets.US_ASCII),
            message.copyOfRange(0, 15),
        )
        assertArrayEquals(challenge, message.copyOfRange(15, 47))
        assertArrayEquals(key, message.copyOfRange(47, 112))
    }

    @Test
    fun `the fingerprint is the vector's`() {
        val fp = vectors.getValue("fingerprint").jsonObject
        val key = bytes(fp, "key")!!
        assertEquals(fp.getValue("value").jsonPrimitive.content, TrustProof.fingerprint(key))
    }

    @Test
    fun `the lines on the wire are the vector's`() {
        val first = vectors.getValue("valid").jsonArray.first().jsonObject
        val challenge = bytes(first, "challenge")!!

        // What the phone sends.
        assertEquals(
            vectors.getValue("challengeLine").jsonPrimitive.content,
            PairingServer.challengeLine(challenge),
        )
        // What the phone reads back, and what it reads out of a request.
        assertArrayEquals(
            bytes(first, "proof"),
            PairingServer.proofOf(vectors.getValue("proofLine").jsonPrimitive.content),
        )
        assertArrayEquals(
            bytes(first, "key"),
            PairingServer.requestKey(vectors.getValue("request").jsonPrimitive.content),
        )
    }

    @Test
    fun `a request line that is not ours yields no key and no proof`() {
        assertNull(PairingServer.requestKey("""{"v":1,"pair":1}"""))
        assertNull(PairingServer.requestKey("""{"v":1,"pair":1,"key":7}"""))
        assertNull(PairingServer.requestKey("""{"v":1,"pair":1,"key":"AAAA"}"""))
        assertNull(PairingServer.requestKey("not json"))
        assertNull(PairingServer.proofOf("""{"v":2,"proof":"AAAA"}"""))
        assertNull(PairingServer.proofOf("""{"v":1,"proof":"AAAA"}"""))
        assertNull(PairingServer.proofOf("""{"v":1}"""))
        assertNull(PairingServer.proofOf("not json"))
    }

    @Test
    fun `a signature made by an independent implementation verifies`() {
        val pc = TestComputer()
        val challenge = TrustProof.newChallenge()
        assertTrue(TrustProof.verify(pc.key, challenge, pc.sign(challenge)))
        assertNotNull(TrustProof.decodeKey(TrustProof.encode(pc.key)))
    }

    @Test
    fun `a signature does not carry over to another challenge or another key`() {
        val pc = TestComputer()
        val other = TestComputer()
        val challenge = TrustProof.newChallenge()
        val proof = pc.sign(challenge)
        assertFalse(TrustProof.verify(pc.key, TrustProof.newChallenge(), proof))
        assertFalse(TrustProof.verify(other.key, challenge, proof))
    }

    @Test
    fun `challenges are fresh`() {
        val seen = HashSet<String>()
        repeat(50) { assertTrue(seen.add(TrustProof.encode(TrustProof.newChallenge()))) }
    }
}
