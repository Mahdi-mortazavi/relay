package io.relay.app.core

/**
 * Version comparison for "is there a newer release?".
 *
 * Kept as pure functions with no network and no Android types, because the part
 * that goes wrong is the comparing — an app that offers 1.10.0 as older than
 * 1.9.0, or that offers you the version you already have, every single launch.
 *
 * Contract note: Relay cannot update itself silently. Android only allows that
 * for system apps and store installs; a sideloaded app can download an APK and
 * open the installer, which always asks. That is a floor set by the platform,
 * not a choice made here, and the UI says so rather than promising otherwise.
 */
object UpdateCheck {

    /**
     * Splits "1.3.1" or "v1.3.1" into comparable parts, or null for anything
     * else. Null rather than a guess: a misparse either offers a downgrade or
     * hides a real update, and both are worse than not asking.
     */
    fun parse(version: String?): List<Int>? {
        val text = version?.trim()?.trimStart('v', 'V')?.takeIf { it.isNotEmpty() } ?: return null
        // A pre-release suffix compares as its base version: 1.4.0-rc1 == 1.4.0.
        val base = text.substringBefore('-')
        val parts = base.split('.')
        if (parts.size !in 2..4) return null
        return parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
    }

    /** Positive when [a] is newer than [b]. Missing components count as zero. */
    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    /**
     * True when [latest] is worth telling someone about. Unparseable input on
     * either side means no: silence beats a wrong prompt.
     */
    fun isNewer(latest: String?, current: String?): Boolean {
        val l = parse(latest) ?: return false
        val c = parse(current) ?: return false
        return compare(l, c) > 0
    }
}
