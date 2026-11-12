package io.relay.app.core

/**
 * Typed-code fallback for all RFC1918 private LAN addresses. Contract: /shared/typed-code.md.
 *
 * - 8 characters (`XXXX-XXXX`): `192.168.0.0/16` (5-bit CRC-8 + 3 zero pad bits)
 * - 10 characters (`XXXXX-XXXXX`): `10.0.0.0/8` (tag 1) and `172.16.0.0/12` (tag 2)
 *   with full 8-bit CRC-8 across the 6 payload bytes.
 */
object TypedCode {
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LENGTH = 8
    const val EXTENDED_LENGTH = 10

    private const val TAG_10 = 1
    private const val TAG_172 = 2

    /** Returns the canonical code (8 chars for 192.168/16, 10 chars for 10/8 & 172.16-31/16), or null. */
    fun encode(host: String, port: Int): String? {
        if (port !in 1..65535) return null
        val octets = host.split(".")
        if (octets.size != 4) return null
        val o1 = octets[0].toIntOrNull() ?: return null
        val o2 = octets[1].toIntOrNull() ?: return null
        val o3 = octets[2].toIntOrNull() ?: return null
        val o4 = octets[3].toIntOrNull() ?: return null
        if (o1 !in 0..255 || o2 !in 0..255 || o3 !in 0..255 || o4 !in 0..255) return null

        if (o1 == 192 && o2 == 168) {
            val bytes = byteArrayOf(o3.toByte(), o4.toByte(), (port shr 8).toByte(), (port and 0xFF).toByte())
            val check = crc8(bytes) and 0x1F
            var v = 0L
            for (b in bytes) v = (v shl 8) or (b.toLong() and 0xFF)
            v = (v shl 8) or ((check shl 3).toLong())

            return buildString(LENGTH) {
                for (i in 0 until LENGTH) {
                    append(ALPHABET[((v shr (35 - 5 * i)) and 31L).toInt()])
                }
            }
        }

        val (tag, h0) = when {
            o1 == 10 -> TAG_10 to o2
            o1 == 172 && o2 in 16..31 -> TAG_172 to (o2 - 16)
            else -> return null
        }

        val bytes = byteArrayOf(
            tag.toByte(),
            h0.toByte(),
            o3.toByte(),
            o4.toByte(),
            (port shr 8).toByte(),
            (port and 0xFF).toByte(),
        )
        val check = crc8(bytes)
        val v = (tag.toLong() shl 48) or
            (h0.toLong() shl 40) or
            (o3.toLong() shl 32) or
            (o4.toLong() shl 24) or
            (port.toLong() shl 8) or
            check.toLong()

        return buildString(EXTENDED_LENGTH) {
            for (i in 0 until EXTENDED_LENGTH) {
                append(ALPHABET[((v shr (45 - 5 * i)) and 31L).toInt()])
            }
        }
    }

    /** Formats an 8-char code as XXXX-XXXX or a 10-char code as XXXXX-XXXXX. */
    fun format(code: String): String {
        val clean = code.uppercase().filter { it != '-' && !it.isWhitespace() }
        val group = if (clean.length == EXTENDED_LENGTH) 5 else 4
        return clean.chunked(group).joinToString("-")
    }

    /** Decodes user input (case-insensitive, separators tolerated); null on any failure. */
    fun decode(input: String): Decoded? {
        val clean = input.uppercase().filter { it != '-' && !it.isWhitespace() }
        return when (clean.length) {
            LENGTH -> decode8(clean)
            EXTENDED_LENGTH -> decode10(clean)
            else -> null
        }
    }

    private fun decode8(clean: String): Decoded? {
        var v = 0L
        for (ch in clean) {
            val idx = ALPHABET.indexOf(ch)
            if (idx < 0) return null
            v = (v shl 5) or idx.toLong()
        }
        if ((v and 0b111L) != 0L) return null // padding bits must be zero

        val bytes = byteArrayOf(
            ((v shr 32) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
        )
        val check = ((v shr 3) and 0x1F).toInt()
        if ((crc8(bytes) and 0x1F) != check) return null

        val port = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        if (port == 0) return null
        return Decoded(
            host = "192.168.${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}",
            port = port,
        )
    }

    private fun decode10(clean: String): Decoded? {
        var v = 0L
        for (ch in clean) {
            val idx = ALPHABET.indexOf(ch)
            if (idx < 0) return null
            v = (v shl 5) or idx.toLong()
        }

        val tag = ((v shr 48) and 0x3L).toInt()
        if (tag != TAG_10 && tag != TAG_172) return null

        val h0 = ((v shr 40) and 0xFF).toInt()
        val h1 = ((v shr 32) and 0xFF).toInt()
        val h2 = ((v shr 24) and 0xFF).toInt()
        val port = ((v shr 8) and 0xFFFF).toInt()
        val check = (v and 0xFF).toInt()

        if (port == 0) return null
        if (tag == TAG_172 && h0 !in 0..15) return null

        val bytes = byteArrayOf(
            tag.toByte(),
            h0.toByte(),
            h1.toByte(),
            h2.toByte(),
            (port shr 8).toByte(),
            (port and 0xFF).toByte(),
        )
        if (crc8(bytes) != check) return null

        val host = if (tag == TAG_10) "10.$h0.$h1.$h2" else "172.${16 + h0}.$h1.$h2"
        return Decoded(host = host, port = port)
    }

    /** CRC-8: poly 0x07, init 0x00, no reflection, xorout 0x00. */
    private fun crc8(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    data class Decoded(val host: String, val port: Int)
}
