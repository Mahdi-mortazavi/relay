package io.relay.app

import io.relay.app.core.ConnectionState
import io.relay.app.core.QrPayload
import io.relay.app.service.ConnectionRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionRepositoryTest {

    private fun payload(host: String, port: Int = 1080) = QrPayload(
        v = 1,
        mode = QrPayload.MODE_SOCKS5,
        host = host,
        port = port,
        name = "Test Phone",
    )

    private fun advertise(code: String?) {
        ConnectionRepository.dispatch("start") { ConnectionState.Preparing }
        ConnectionRepository.dispatch("ready") {
            ConnectionState.Advertising(payload("192.168.43.1"), "FNASJQE2", code)
        }
    }

    @After
    fun tearDown() {
        ConnectionRepository.dispatch("stop") { ConnectionState.Idle }
    }

    @Test
    fun `re-advertising on a new address keeps the code on screen`() {
        // The two-digit code is drawn once and kept for the life of the
        // session, precisely so the number never changes under someone who is
        // part-way through reading it onto a keyboard. Dropping it here made
        // the phone fall back to displaying the eight-character code the
        // instant the hotspot changed address — while the PC, which had been
        // updated, was asking for two digits. The two screens disagreed and
        // there was nothing the person holding them could do about it.
        advertise("42")
        ConnectionRepository.reissue(payload("192.168.1.77"), "AAA99952", "42")

        val state = ConnectionRepository.state.value as ConnectionState.Advertising
        assertEquals("42", state.shortCode)
        assertEquals("192.168.1.77", state.payload.host)
        assertEquals("AAA99952", state.typedCode)
    }

    @Test
    fun `re-advertising from Connected drops back to Advertising`() {
        advertise("42")
        ConnectionRepository.dispatch("clientConnected") {
            val advertising = it as ConnectionState.Advertising
            ConnectionState.Connected(advertising.payload, advertising.typedCode, advertising.shortCode, 1)
        }

        ConnectionRepository.reissue(payload("192.168.1.77"), "AAA99952", "42")

        // The client count is stale after a rebind: whoever was connected is
        // connected to an address that no longer answers.
        val state = ConnectionRepository.state.value as ConnectionState.Advertising
        assertEquals("42", state.shortCode)
    }

    @Test
    fun `re-advertising while idle changes nothing`() {
        ConnectionRepository.reissue(payload("192.168.1.77"), "AAA99952", "42")
        assertEquals(ConnectionState.Idle, ConnectionRepository.state.value)
    }

    @Test
    fun `a phone that could not announce itself still says so after a rebind`() {
        // Null is a real answer, not a missing one: it means the QR and the
        // long code are the only ways in, and the UI shows them instead.
        advertise(null)
        ConnectionRepository.reissue(payload("192.168.1.77"), "AAA99952", null)

        val state = ConnectionRepository.state.value as ConnectionState.Advertising
        assertNull(state.shortCode)
    }
}
