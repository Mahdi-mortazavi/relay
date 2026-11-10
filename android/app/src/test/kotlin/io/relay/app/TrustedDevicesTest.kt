package io.relay.app

import io.relay.app.core.TrustProof
import io.relay.app.service.TrustedDevices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The list of computers the phone no longer asks about (ADR-0012). */
class TrustedDevicesTest {

    /** A list over an in-memory "SharedPreferences", with a clock the test moves. */
    private class Rig(start: Long = 1_000) {
        var stored: String? = null
        var now = start
        val devices = TrustedDevices(clock = { now }).also {
            it.attach(load = { stored }, save = { json -> stored = json })
        }

        /** What a restart sees: a new list over the same storage. */
        fun restarted() = TrustedDevices(clock = { now }).also {
            it.attach(load = { stored }, save = { json -> stored = json })
        }
    }

    @Test
    fun `nobody is trusted until a person says so`() {
        val rig = Rig()
        assertFalse(rig.devices.isTrusted(TestComputer().key))
        assertTrue(rig.devices.devices.value.isEmpty())
    }

    @Test
    fun `an added computer is trusted, and stays trusted across a restart`() {
        val rig = Rig()
        val pc = TestComputer()
        rig.devices.add(pc.key, "MAHDI-LAPTOP")
        assertTrue(rig.devices.isTrusted(pc.key))

        val after = rig.restarted()
        assertTrue("the list must survive a restart", after.isTrusted(pc.key))
        assertEquals("MAHDI-LAPTOP", after.devices.value.single().name)
        assertEquals(TrustProof.fingerprint(pc.key), after.devices.value.single().fingerprint)
    }

    @Test
    fun `trust is the key, not the name`() {
        val rig = Rig()
        rig.devices.add(TestComputer().key, "MAHDI-LAPTOP")
        // Someone else calling themselves the same thing gets nothing.
        assertFalse(rig.devices.isTrusted(TestComputer().key))
    }

    @Test
    fun `removing one computer leaves the others`() {
        val rig = Rig()
        val a = TestComputer(); val b = TestComputer()
        rig.devices.add(a.key, "A"); rig.devices.add(b.key, "B")
        rig.devices.remove(TrustProof.encode(a.key))
        assertFalse(rig.devices.isTrusted(a.key))
        assertTrue(rig.devices.isTrusted(b.key))
        assertFalse("removal is persisted", rig.restarted().isTrusted(a.key))
    }

    @Test
    fun `clearing forgets everyone`() {
        val rig = Rig()
        val a = TestComputer(); val b = TestComputer()
        rig.devices.add(a.key, "A"); rig.devices.add(b.key, "B")
        rig.devices.clear()
        assertTrue(rig.devices.devices.value.isEmpty())
        assertTrue(rig.restarted().devices.value.isEmpty())
    }

    @Test
    fun `adding the same computer again updates it rather than duplicating it`() {
        val rig = Rig()
        val pc = TestComputer()
        rig.devices.add(pc.key, "OLD-NAME")
        rig.now += 500
        rig.devices.add(pc.key, "NEW-NAME")
        val only = rig.devices.devices.value.single()
        assertEquals("NEW-NAME", only.name)
        assertEquals(1_000L, only.addedAtMs)
    }

    @Test
    fun `using a computer records when, and ignores one that is not on the list`() {
        val rig = Rig()
        val pc = TestComputer()
        rig.devices.add(pc.key, "A")
        rig.now = 9_000
        rig.devices.used(pc.key, "A")
        assertEquals(9_000L, rig.devices.devices.value.single().lastUsedAtMs)

        rig.devices.used(TestComputer().key, "stranger")
        assertEquals("a stranger must not be added by being 'used'", 1, rig.devices.devices.value.size)
    }

    @Test
    fun `the list is bounded and drops the computer that paired longest ago`() {
        val rig = Rig()
        val first = TestComputer()
        rig.devices.add(first.key, "first")
        repeat(TrustedDevices.MAX) {
            rig.now += 10
            rig.devices.add(TestComputer().key, "pc-$it")
        }
        assertEquals(TrustedDevices.MAX, rig.devices.devices.value.size)
        assertFalse("the oldest is the one replaced", rig.devices.isTrusted(first.key))
    }

    @Test
    fun `a pairing keeps a computer from being the one replaced`() {
        val rig = Rig()
        val kept = TestComputer()
        rig.devices.add(kept.key, "kept")
        repeat(TrustedDevices.MAX - 1) {
            rig.now += 10
            rig.devices.add(TestComputer().key, "pc-$it")
        }
        rig.now += 10
        rig.devices.used(kept.key, "kept")
        rig.now += 10
        rig.devices.add(TestComputer().key, "newcomer")
        assertTrue(rig.devices.isTrusted(kept.key))
    }

    @Test
    fun `a store that makes no sense trusts nobody`() {
        val rig = Rig()
        for (garbage in listOf("", "   ", "not json", "{}", "[1,2,3]", "{\"key\":\"x\"}", "null")) {
            rig.stored = garbage
            assertTrue("'$garbage'", rig.restarted().devices.value.isEmpty())
        }
    }

    @Test
    fun `an entry whose key is not a real point can never match, and does not take the rest down`() {
        val rig = Rig()
        val good = TestComputer()
        rig.devices.add(good.key, "good")
        val bad = """{"key":"AAAA","name":"bad","addedAtMs":1}"""
        rig.stored = rig.stored!!.removeSuffix("]") + ",$bad]"
        val after = rig.restarted()
        assertEquals(1, after.devices.value.size)
        assertTrue(after.isTrusted(good.key))
    }

    @Test
    fun `attaching twice keeps the first storage`() {
        val first = Rig()
        val pc = TestComputer()
        first.devices.add(pc.key, "A")
        var secondLoaded = false
        first.devices.attach(load = { secondLoaded = true; null }, save = {})
        assertFalse("the second attach must be ignored", secondLoaded)
        assertTrue(first.devices.isTrusted(pc.key))
    }

    @Test
    fun `a storage that fails to write does not take the app down`() {
        val list = TrustedDevices()
        list.attach(load = { null }, save = { error("disk full") })
        val pc = TestComputer()
        list.add(pc.key, "A")
        assertTrue("it still works in memory", list.isTrusted(pc.key))
    }
}
