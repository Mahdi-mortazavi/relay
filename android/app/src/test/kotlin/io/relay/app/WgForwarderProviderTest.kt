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

    @Test
    fun `full mode is unavailable when the go library is absent`() {
        // The bridge class ships in every build; the Go library does not. If
        // availability were decided by the bridge, this build would offer Full
        // Mode and every attempt would end in WG_START_FAILED -- which is the
        // exact defect the mode toggle was fixed for once already.
        //
        // These unit tests run on the JVM with no AAR, so the honest answer
        // here is false.
        assertFalse(
            "Full Mode must not be offered by a build with no relaywg library",
            WgForwarderProvider.isAvailable,
        )
    }

    @Test
    fun `create returns nothing when the library is missing`() {
        assertNull(WgForwarderProvider.create())
    }

    @Test
    fun `constructing the bridge without the library fails with a readable reason`() {
        // "ClassNotFoundException: relaywg.Relaywg" tells a user nothing. The
        // message has to say what is missing and why the mode cannot run.
        val failure = assertThrows(WgForwarderException::class.java) { GoWgForwarder() }
        val message = failure.message ?: ""
        assertTrue("unhelpful message: $message", message.contains("Full Mode"))
        assertTrue("unhelpful message: $message", message.contains("relaywg"))
    }
}
