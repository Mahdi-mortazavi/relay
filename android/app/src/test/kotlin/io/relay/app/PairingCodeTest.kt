package io.relay.app

import io.relay.app.core.PairingCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {

    @Test
    fun `every drawn code is two digits in range`() {
        repeat(500) {
            val code = PairingCode.draw()
            assertEquals("length", 2, code.length)
            val value = code.toInt()
            assertTrue("$code below range", value >= PairingCode.MIN)
            assertTrue("$code above range", value <= PairingCode.MAX)
        }
    }

    @Test
    fun `no drawn code starts with zero`() {
        // The reason the range starts at 10: a leading zero makes a person
        // wonder whether to type it, and half of them will not.
        repeat(500) { assertNotEquals('0', PairingCode.draw()[0]) }
    }

    @Test
    fun `draw avoids codes already in use`() {
        // Everything except 42 is taken, so 42 is the only answer it can give.
        val taken = (PairingCode.MIN..PairingCode.MAX).map { it.toString() }.toSet() - "42"
        repeat(50) { assertEquals("42", PairingCode.draw(taken, attempts = 200)) }
    }

    @Test
    fun `draw still returns a code when every value is taken`() {
        // Refusing to share because the room is full of phones would be a worse
        // outcome than a collision, which the PC resolves by asking which one.
        val taken = (PairingCode.MIN..PairingCode.MAX).map { it.toString() }.toSet()
        val code = PairingCode.draw(taken)
        assertEquals(2, code.length)
    }

    @Test
    fun `normalize accepts what a person actually types`() {
        assertEquals("42", PairingCode.normalize("42"))
        assertEquals("42", PairingCode.normalize(" 42 "))
        assertEquals("42", PairingCode.normalize("4 2"))
        assertEquals("99", PairingCode.normalize("99"))
    }

    @Test
    fun `normalize rejects everything else`() {
        assertNull("empty", PairingCode.normalize(""))
        assertNull("null", PairingCode.normalize(null))
        assertNull("one digit", PairingCode.normalize("4"))
        assertNull("three digits", PairingCode.normalize("421"))
        assertNull("leading zero", PairingCode.normalize("04"))
        assertNull("letters", PairingCode.normalize("4a"))
        assertNull("symbols", PairingCode.normalize("4-2"))
    }

    @Test
    fun `isValid agrees with normalize`() {
        // These two disagreeing is exactly the bug that shipped once already in
        // the eight-character code box: input the UI accepted, the decoder did
        // not. Whatever normalize takes, isValid must take.
        val inputs = listOf("42", " 42 ", "4 2", "04", "4", "421", "4a", "", "99", "10")
        for (input in inputs) {
            assertEquals(
                "disagreement on '$input'",
                PairingCode.normalize(input) != null,
                PairingCode.isValid(input),
            )
        }
    }

    @Test
    fun `codes are not sequential across draws`() {
        // A counter would pass every test above and be trivially predictable.
        val drawn = (1..200).map { PairingCode.draw() }
        assertTrue("all draws identical — not random", drawn.toSet().size > 10)
        assertFalse(
            "draws look sequential",
            drawn.zipWithNext().all { (a, b) -> b.toInt() == a.toInt() + 1 },
        )
    }
}
