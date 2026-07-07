package io.relay.app.core

/**
 * 8-character typed-code fallback. Contract: /shared/typed-code.md — any change
 * there first. Covers socks5 payloads with hosts in 192.168.0.0/16 only.
 */
object TypedCode {
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LENGTH = 8

    /** Returns the code, or null when host/port are outside the encodable range. */
    fun encode(host: String, port: Int): String? {
        val octets = host.split(".")
        if (octets.size != 4 || octets[0] != "192" || octets[1] != "168") return null
        val o3 = octets[2].toIntOrNull() ?: return null
        val o4 = octets[3].toIntOrNull() ?: return null
        if (o3 !in 0..255 || o4 !in 0..255 || port !in 1..65535) return null

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

    /** Decodes user input (case-insensitive, separators tolerated); null on any failure. */
    fun decode(input: String): Decoded? {
        val clean = input.uppercase().filter { it != '-' && !it.isWhitespace() }
        if (clean.length != LENGTH) return null
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
