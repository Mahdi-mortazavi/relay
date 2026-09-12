package io.relay.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.Locale

/**
 * In-memory, local-only diagnostic log (zero-telemetry: never persisted, never
 * leaves the device). A bounded ring buffer surfaced in Advanced settings so a
 * user can see what happened without any data going anywhere.
 *
 * Every line used to be a bare sentence. That is readable and close to useless
 * once a report is eighty lines long and the person who has to read it is not
 * the person who wrote the sentences: there was no way to find the failures
 * without reading all of it, no way to tell a networking line from an update
 * line, and every value was buried inside prose where it could not be searched
 * for. A real report arrived with four `Reply path check:` lines disagreeing
 * with each other and nothing to sort them by.
 *
 * So a line now carries a [Level], an [Area] and named [Entry.fields]. The
 * levels are what make a long report skimmable; the fields are what make a
 * value greppable instead of quotable.
 */
object LocalLog {
    private const val CAPACITY = 200

    /** How much attention a line deserves. */
    enum class Level {
        /** Something happened. Most lines. */
        INFO,

        /** Working, but not the way it should be, or not for long. */
        WARN,

        /** This is why it did not work. The line someone is looking for. */
        ERROR,
    }

    /** Which part of Relay is speaking, so one concern can be followed through. */
    enum class Area {
        /** Starting, stopping, the foreground service, state changes. */
        SERVICE,

        /** Interfaces, addresses, which way the PC is reached. */
        LINK,

        /** The beacon, the pairing server, approving a PC. */
        PAIRING,

        /** The WireGuard endpoint and its peer. */
        TUNNEL,

        /** Checking for, fetching and installing a new version. */
        UPDATE,
    }

    /**
     * @param fields values a reader might want to search for or compare across
     *   lines, kept out of the prose. Rendered as `key=value`, so an address
     *   that appears in four places can be found by looking for one string.
     */
    data class Entry(
        val elapsedMs: Long,
        val message: String,
        val level: Level = Level.INFO,
        val area: Area = Area.SERVICE,
        val fields: Map<String, String> = emptyMap(),
    ) {
        /**
         * The line as a person reads it, in a report or on the Advanced screen.
         *
         * Fixed-width columns, because the reason for having levels at all is
         * being able to run an eye down them. A value with a space in it is
         * quoted so `key=value` stays one token to a reader and to a grep.
         */
        fun render(): String {
            val head = "%8.2f  %-5s %-7s %s".format(
                Locale.ROOT, elapsedMs / 1000.0, level.name, area.name.lowercase(Locale.ROOT), message,
            )
            return head + tail()
        }

        /**
         * The short form, for the list on the Advanced screen.
         *
         * That list is a 160dp box on a phone, where the level and area columns
         * would cost most of a line's width and push the message into wrapping.
         * On screen the level is shown as colour instead; the full form is what
         * goes into a shared report, where width is not scarce and whoever is
         * reading it is not the person who was there.
         */
        fun renderCompact(): String =
            "%6.1fs  %s".format(Locale.ROOT, elapsedMs / 1000.0, message) + tail()

        private fun tail(): String {
            if (fields.isEmpty()) return ""
            return "  " + fields.entries.joinToString(" ") { (key, value) ->
                if (value.any { it.isWhitespace() }) "$key=\"$value\"" else "$key=$value"
            }
        }
    }

    private val start = System.currentTimeMillis()
    private val buffer = ArrayDeque<Entry>(CAPACITY)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun info(area: Area, message: String, vararg fields: Pair<String, String>) =
        add(Level.INFO, area, message, fields)

    fun warn(area: Area, message: String, vararg fields: Pair<String, String>) =
        add(Level.WARN, area, message, fields)

    fun error(area: Area, message: String, vararg fields: Pair<String, String>) =
        add(Level.ERROR, area, message, fields)

    @Synchronized
    private fun add(
        level: Level,
        area: Area,
        message: String,
        fields: Array<out Pair<String, String>>,
    ) {
        if (buffer.size >= CAPACITY) buffer.removeFirst()
        buffer.addLast(
            Entry(
                elapsedMs = System.currentTimeMillis() - start,
                message = message,
                level = level,
                area = area,
                // Last one wins rather than throwing: a duplicate key is a
                // typo at a call site, and a log must never be the thing that
                // crashes while something else is already going wrong.
                fields = fields.toMap(),
            )
        )
        _entries.value = buffer.toList()
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
    }
}
