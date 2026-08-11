package io.relay.app

import io.relay.app.net.wg.GoWgForwarder
import io.relay.app.net.wg.WgForwarderException
import io.relay.app.net.wg.WgForwarderProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WgForwarderProviderTest {

    // These run on the JVM. The AAR is on the classpath now that the app ships
    // it, but its native half cannot load off a device -- which makes this the
    // honest stand-in for the case that actually bites a user: the library is
    // there, and it will not load on their ABI. Availability has to be decided
    // by whether the native side comes up, never by whether a class exists.

    @Test
    fun `full mode is unavailable when the go library cannot load`() {
        // If availability were decided by the Kotlin bridge class -- which ships
        // in every build -- this build would offer Full Mode and every attempt
        // would end in WG_START_FAILED. That is the exact defect the mode
        // toggle was fixed for once already.
        assertFalse(
            "Full Mode must not be offered where relaywg cannot run",
            WgForwarderProvider.isAvailable,
        )
    }

    @Test
    fun `create returns nothing when the library cannot load`() {
        assertNull(WgForwarderProvider.create())
    }

    @Test
    fun `constructing the bridge without a working library fails with a readable reason`() {
        // "ClassNotFoundException: relaywg.Relaywg" tells a user nothing. The
        // message has to say what is missing and why the mode cannot run.
        val failure = assertThrows(WgForwarderException::class.java) { GoWgForwarder() }
        val message = failure.message ?: ""
        assertTrue("unhelpful message: $message", message.contains("Full Mode"))
        assertTrue("unhelpful message: $message", message.contains("relaywg"))
    }
}
