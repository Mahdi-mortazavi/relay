package io.relay.app

import io.relay.app.core.ConnectionState
import io.relay.app.core.ErrorCode
import io.relay.app.core.QrPayload
import io.relay.app.service.DiagnosticReport
import io.relay.app.service.LocalLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class DiagnosticReportTest {

    private fun entries(vararg messages: String) =
        messages.mapIndexed { index, message -> LocalLog.Entry(index * 1000L, message) }

    @Test
    fun `names the error rather than only the screen`() {
        // "Error" alone tells a developer nothing. The code is the whole point.
        val report = DiagnosticReport.build(
            ConnectionState.Error(ErrorCode.PORT_IN_USE), entries("Starting sharing"),
        )
        assertTrue(report.contains("PORT_IN_USE"))
        assertTrue(report.contains("Starting sharing"))
    }

    @Test
    fun `says so when the log is empty`() {
        // A blank section reads as a truncated file; "(empty)" reads as a fact.
        val report = DiagnosticReport.build(ConnectionState.Idle, emptyList())
        assertTrue(report.contains("(empty)"))
    }

    @Test
    fun `states that nothing was uploaded`() {
        // Someone about to paste this into a public issue should be able to read
        // the promise the log has always made.
        val report = DiagnosticReport.build(ConnectionState.Idle, emptyList())
        assertTrue(report.contains("nothing was uploaded automatically"))
    }

    @Test
    fun `issue url keeps the newest lines when it trims`() {
        // The end of a log explains a failure; the start is boot noise. Trimming
        // the wrong end throws away the only part worth having.
        val many = (0 until 2000).map { LocalLog.Entry(it * 10L, "line-$it-${"x".repeat(20)}") }
        val url = DiagnosticReport.issueUrl(
            DiagnosticReport.build(ConnectionState.Error(ErrorCode.HOTSPOT_LOST), many),
        )
        val decoded = URLDecoder.decode(url, "UTF-8")
        assertTrue("newest line missing", decoded.contains("line-1999"))
        assertFalse("oldest line should have been trimmed", decoded.contains("line-0-"))
        assertTrue(decoded.contains("earlier lines trimmed"))
    }

    @Test
    fun `issue url stays short enough to open`() {
        // Past roughly 8k a URL stops working, and it fails by doing nothing —
        // which looks like a broken button.
        val many = (0 until 5000).map { LocalLog.Entry(it.toLong(), "y".repeat(50)) }
        val url = DiagnosticReport.issueUrl(DiagnosticReport.build(ConnectionState.Idle, many))
        assertTrue("URL is ${url.length} characters", url.length < 8000)
    }

    @Test
    fun `escapes characters that would break the url`() {
        val url = DiagnosticReport.issueUrl(
            DiagnosticReport.build(ConnectionState.Idle, entries("host=1.2.3.4&port=1080 #1 100%")),
        )
        // A raw & truncates the body at that point and silently loses the rest.
        val body = url.substringAfter("&body=")
        assertFalse("unescaped & in body", body.contains("&"))
        assertTrue(url.contains("%23")) // #
        assertTrue(url.contains("%25")) // %
    }

    @Test
    fun `counts the failures so they do not have to be found by reading`() {
        val report = DiagnosticReport.build(
            ConnectionState.Error(ErrorCode.HOTSPOT_OFF),
            listOf(
                LocalLog.Entry(0, "Starting sharing"),
                LocalLog.Entry(1000, "no link", LocalLog.Level.ERROR, LocalLog.Area.LINK),
                LocalLog.Entry(2000, "port busy", LocalLog.Level.WARN, LocalLog.Area.PAIRING),
            ),
        )

        // A report is pasted into a chat and answered by somebody scrolling on a
        // phone. The first line they need is how much of it is worth reading.
        assertTrue(report, report.contains("1 error(s), 1 warning(s) in 3 line(s)"))
    }

    @Test
    fun `does not claim the clock starts when sharing does`() {
        // It said "seconds since sharing started" and counted from process
        // start, so a report from a phone that had been open ninety minutes
        // opened at 4999.58 and read like it came from another machine. A log's
        // own header is the one line a reader trusts before reading anything.
        val report = DiagnosticReport.build(ConnectionState.Idle, entries("Starting sharing"))

        assertFalse(report, report.contains("since sharing started"))
        assertTrue(report, report.contains("since the app started"))
    }

    @Test
    fun `describes a connected session with its client count`() {
        val payload = QrPayload(
            v = 1, mode = QrPayload.MODE_SOCKS5, host = "192.168.1.5", port = 1080,
            name = null, issuedAt = 0,
        )
        val report = DiagnosticReport.build(
            ConnectionState.Connected(payload, null, "42", clientCount = 2), emptyList(),
        )
        assertTrue(report.contains("Connected"))
        assertTrue(report.contains("2 client"))
    }
}
