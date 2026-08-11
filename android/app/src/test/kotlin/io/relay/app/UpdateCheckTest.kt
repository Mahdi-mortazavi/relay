package io.relay.app

import io.relay.app.core.UpdateCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {

    @Test
    fun `newer versions are offered`() {
        assertTrue(UpdateCheck.isNewer("1.3.2", "1.3.1"))
        assertTrue(UpdateCheck.isNewer("1.4.0", "1.3.9"))
        assertTrue(UpdateCheck.isNewer("2.0.0", "1.99.99"))
        assertTrue(UpdateCheck.isNewer("v1.3.2", "1.3.1"))
    }

    @Test
    fun `the version you already have is not an update`() {
        // The single most annoying way this feature fails: a prompt on every
        // launch offering the build already installed.
        assertFalse(UpdateCheck.isNewer("1.3.1", "1.3.1"))
        assertFalse(UpdateCheck.isNewer("v1.3.1", "1.3.1"))
        assertFalse(UpdateCheck.isNewer("1.3", "1.3.0"))
    }

    @Test
    fun `an older version is never offered`() {
        assertFalse(UpdateCheck.isNewer("1.3.0", "1.3.1"))
        assertFalse(UpdateCheck.isNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun `ten is greater than nine`() {
        // String comparison says "1.10.0" < "1.9.0", which would hide every
        // update after the ninth. This is the bug this class exists to avoid.
        assertTrue(UpdateCheck.isNewer("1.10.0", "1.9.0"))
        assertFalse(UpdateCheck.isNewer("1.9.0", "1.10.0"))
        assertTrue(UpdateCheck.isNewer("1.3.10", "1.3.9"))
    }

    @Test
    fun `a pre-release compares as its base version`() {
        assertFalse(UpdateCheck.isNewer("1.3.1-rc1", "1.3.1"))
        assertTrue(UpdateCheck.isNewer("1.4.0-rc1", "1.3.1"))
    }

    @Test
    fun `nonsense is not an update`() {
        // Silence beats a wrong prompt: if either side cannot be read, say no.
        assertFalse(UpdateCheck.isNewer(null, "1.3.1"))
        assertFalse(UpdateCheck.isNewer("1.3.1", null))
        assertFalse(UpdateCheck.isNewer("latest", "1.3.1"))
        assertFalse(UpdateCheck.isNewer("1.3.1", "unknown"))
        assertFalse(UpdateCheck.isNewer("", "1.3.1"))
        assertFalse(UpdateCheck.isNewer("1", "1.3.1"))
        assertFalse(UpdateCheck.isNewer("1.2.3.4.5", "1.3.1"))
        assertFalse(UpdateCheck.isNewer("1.-2.3", "1.3.1"))
    }

    @Test
    fun `parse keeps every component`() {
        assertEquals(listOf(1, 3, 1), UpdateCheck.parse("1.3.1"))
        assertEquals(listOf(1, 3), UpdateCheck.parse("v1.3"))
        assertEquals(listOf(1, 3, 1, 2), UpdateCheck.parse("1.3.1.2"))
        assertNull(UpdateCheck.parse("x.y.z"))
    }

    @Test
    fun `comparison treats a missing component as zero`() {
        assertEquals(0, UpdateCheck.compare(listOf(1, 3), listOf(1, 3, 0)))
        assertTrue(UpdateCheck.compare(listOf(1, 3, 1), listOf(1, 3)) > 0)
    }
}
