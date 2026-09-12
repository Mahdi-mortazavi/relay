package io.relay.app

import io.relay.app.service.LocalLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * How a log line reaches the person who has to read it.
 *
 * A diagnostic log is only worth its space if a stranger can skim it. These
 * assert the two properties that make that possible — that failures can be
 * found without reading every line, and that values can be searched for rather
 * than quoted — plus one that nearly shipped broken.
 */
class LocalLogRenderingTest {

    private fun entry(
        level: LocalLog.Level = LocalLog.Level.INFO,
        area: LocalLog.Area = LocalLog.Area.LINK,
        message: String = "something happened",
        fields: Map<String, String> = emptyMap(),
    ) = LocalLog.Entry(12_340L, message, level, area, fields)

    @Test
    fun `the level and the area are columns, not prose`() {
        val line = entry(level = LocalLog.Level.ERROR, area = LocalLog.Area.TUNNEL).render()

        // Fixed columns are the whole point: the reason for having levels is
        // being able to run an eye down one place and find the failures.
        assertTrue(line, line.contains("ERROR"))
        assertTrue(line, line.contains("tunnel"))
        assertEquals("   12.34  ERROR tunnel  something happened", line)
    }

    @Test
    fun `fields are rendered as key=value`() {
        val line = entry(fields = mapOf("host" to "192.168.1.5", "port" to "51820")).render()

        // So an address that appears in four places can be found by searching
        // for one string, instead of being reworded into four sentences.
        assertTrue(line, line.contains("host=192.168.1.5"))
        assertTrue(line, line.contains("port=51820"))
    }

    @Test
    fun `a value with a space in it stays one token`() {
        val line = entry(fields = mapOf("links" to "wlan0(192.168.1.5) tun0(vpn)")).render()

        // The link snapshot is exactly this shape, and unquoted it would look
        // like two fields, the second of which has no name.
        assertTrue(line, line.contains("""links="wlan0(192.168.1.5) tun0(vpn)""""))
    }

    @Test
    fun `no fields means no trailing clutter`() {
        assertFalse(entry().render().endsWith(" "))
        assertFalse(entry().render().contains("="))
    }

    @Test
    fun `times do not follow the phone's locale`() {
        // This one nearly shipped. String.format uses the default locale, so on
        // a phone set to Persian -- which is most of the people this app is
        // built for -- every timestamp in a shared report would have come out in
        // Persian-Indic digits with a different decimal separator. A report the
        // maintainer cannot read is a report that was not sent.
        val default = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("fa-IR"))
            val line = entry().render()
            assertTrue("locale leaked into the timestamp: $line", line.contains("12.34"))
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test
    fun `the compact form drops the columns and keeps the fields`() {
        val compact = entry(
            level = LocalLog.Level.ERROR,
            fields = mapOf("host" to "192.168.1.5"),
        ).renderCompact()

        // On the Advanced screen the level is shown as colour instead, because
        // that list is a 160dp box and the columns would cost most of a line.
        assertFalse(compact, compact.contains("ERROR"))
        assertTrue(compact, compact.contains("host=192.168.1.5"))
    }
}
