package io.relay.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Placeholder suite proving the CI unit-test pipeline runs. Phase 1 replaces
 * this with real tests driven by /shared/test-vectors.json and
 * /shared/connection-states.json.
 */
class SkeletonTest {

    @Test
    fun `supported QR payload version is 1`() {
        assertEquals(1, SUPPORTED_QR_PAYLOAD_VERSION)
    }

    companion object {
        // Mirrors "v" in /shared/qr-payload.schema.json.
        const val SUPPORTED_QR_PAYLOAD_VERSION = 1
    }
}
