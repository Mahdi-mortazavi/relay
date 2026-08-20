package io.relay.app.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Fetching a release's APK and proving it is the one the release published.
 *
 * The proving is the point. Relay carries a phone's whole connection, so an
 * update path that installs whatever the network returned is worth attacking.
 * Every release publishes SHA256SUMS.txt beside the APKs; nothing here hands a
 * file to the installer unless its hash appears in that listing, and a release
 * with no listing is treated as "do not install", never as "install anyway".
 *
 * Android will still show its own installer prompt — a sideloaded app cannot
 * install silently, that is a platform floor rather than a choice — so this
 * gets the bytes right and lets the system ask the last question.
 *
 * Pure except for the file it writes: no Android types, so the parsing and the
 * verifying are testable on the JVM, which is where they go wrong.
 */
object UpdateDownload {

    sealed interface Result {
        /** Verified, on disk, ready to hand to the package installer. */
        data class Ready(val file: File) : Result

        /** Offline, 404, rate-limited. Not news; try again later. */
        data object Unavailable : Result

        /** Downloaded, but not the bytes the release published. Never retry silently. */
        data object ChecksumMismatch : Result

        /** No checksums to compare against, so nothing can be trusted. */
        data object Unverifiable : Result
    }

    /**
     * Reads a sha256sum-format listing and returns the hash recorded for
     * [fileName], or null when the file is not in it.
     *
     * Matches on the file name alone, so a listing that records "./name" or
     * "*name" — which the common tools produce — still resolves.
     */
    fun hashFor(checksums: String, fileName: String): String? {
        for (raw in checksums.lineSequence()) {
            val line = raw.trim()
            if (line.length < 66) continue
            val hash = line.substring(0, 64)
            if (!hash.all { it in "0123456789abcdefABCDEF" }) continue
            val rest = line.substring(64).trimStart(' ', '*', '\t')
            if (rest.substringAfterLast('/').substringAfterLast('\\') == fileName) {
                return hash.lowercase()
            }
        }
        return null
    }

    /**
     * Which APK this device should take. The universal build works everywhere
     * but is larger, so it is the fallback rather than the default.
     *
     * [supportedAbis] is Build.SUPPORTED_ABIS, passed in so this stays testable.
     */
    fun assetFor(names: List<String>, supportedAbis: List<String>): String? {
        for (abi in supportedAbis) {
            names.firstOrNull { it.endsWith(".apk") && it.contains(abi) }?.let { return it }
        }
        return names.firstOrNull { it.endsWith("universal.apk") }
    }

    /** Hex SHA-256 of a stream, read in chunks so a 50MB APK is not held twice. */
    fun sha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        stream.use {
            while (true) {
                val read = it.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies [file] against [checksums] and deletes it when it does not match.
     *
     * Deleting matters: a rejected APK left in the cache is a file a person
     * could later be talked into opening, and it would carry Relay's name.
     */
    fun verify(file: File, checksums: String): Result {
        val expected = hashFor(checksums, file.name) ?: run {
            file.delete()
            return Result.Unverifiable
        }
        val actual = runCatching { sha256(file.inputStream()) }.getOrNull() ?: run {
            file.delete()
            return Result.Unavailable
        }
        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete()
            return Result.ChecksumMismatch
        }
        return Result.Ready(file)
    }
}
