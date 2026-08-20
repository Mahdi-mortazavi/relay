package io.relay.app.service

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import io.relay.app.core.UpdateDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the latest release's APK, proves it is the one the release published,
 * and hands it to the system installer.
 *
 * The proving is [UpdateDownload]'s job and it is not optional: nothing reaches
 * the installer unless its SHA-256 appears in the release's own SHA256SUMS.txt.
 * Relay carries a phone's whole connection, so an update path that installs
 * whatever the network returned is the part of this app most worth attacking.
 *
 * Android then shows its own install prompt. A sideloaded app cannot install
 * silently — that is a platform floor, not a decision made here — so this gets
 * the bytes right and lets the system ask the last question.
 */
object UpdateFetcher {

    private const val LATEST =
        "https://api.github.com/repos/Mahdi-mortazavi/relay/releases/latest"

    /** What the UI has to say afterwards. */
    sealed interface Result {
        /** Verified and handed over; Android is asking the user now. */
        data object Installing : Result

        /** Offline, rate-limited, download failed. Try later; not an alarm. */
        data object Unavailable : Result

        /** Downloaded, but not the bytes the release published. */
        data object ChecksumMismatch : Result

        /** The release published no checksums, so nothing could be verified. */
        data object Unverifiable : Result
    }

    /**
     * Downloads, verifies and launches the installer.
     *
     * Runs entirely off the main thread. Returns as soon as the system installer
     * has been asked to open — it outlives this process, so waiting on it is not
     * something that can work.
     */
    suspend fun downloadAndInstall(context: Context): Result = withContext(Dispatchers.IO) {
        val release = runCatching { JSONObject(get(LATEST)) }.getOrNull()
            ?: return@withContext Result.Unavailable

        if (release.optBoolean("draft") || release.optBoolean("prerelease")) {
            return@withContext Result.Unavailable
        }

        val assets = release.optJSONArray("assets") ?: return@withContext Result.Unavailable
        val byName = buildMap {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name").takeIf { it.isNotEmpty() } ?: continue
                put(name, asset.optString("browser_download_url"))
            }
        }

        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val apkName = UpdateDownload.assetFor(byName.keys.toList(), abis)
            ?: return@withContext Result.Unavailable
        val apkUrl = byName[apkName]?.takeIf { it.isNotEmpty() }
            ?: return@withContext Result.Unavailable
        val sumsUrl = byName["SHA256SUMS.txt"]?.takeIf { it.isNotEmpty() }
            ?: return@withContext Result.Unverifiable

        val checksums = runCatching { get(sumsUrl) }.getOrNull()
            ?: return@withContext Result.Unavailable

        // Its own directory, cleared each time: the previous attempt's APK is
        // never what this run is about to verify.
        val directory = File(context.cacheDir, "updates").apply {
            deleteRecursively()
            mkdirs()
        }
        val target = File(directory, apkName)

        val downloaded = runCatching { download(apkUrl, target) }.getOrDefault(false)
        if (!downloaded) {
            target.delete()
            return@withContext Result.Unavailable
        }

        when (val verdict = UpdateDownload.verify(target, checksums)) {
            is UpdateDownload.Result.Ready -> {
                LocalLog.add("Update $apkName verified; opening the installer")
                launchInstaller(context, verdict.file)
                Result.Installing
            }
            UpdateDownload.Result.ChecksumMismatch -> {
                // Loud, because this is the case that must never be shrugged off.
                LocalLog.add("Update REJECTED: $apkName did not match the published checksum")
                Result.ChecksumMismatch
            }
            UpdateDownload.Result.Unverifiable -> {
                LocalLog.add("Update rejected: no published checksum for $apkName")
                Result.Unverifiable
            }
            UpdateDownload.Result.Unavailable -> Result.Unavailable
        }
    }

    /**
     * Hands the APK to the package installer through a content:// URI. A file://
     * URI would throw FileUriExposedException on anything since Android 7, and
     * the installer could not read it anyway.
     */
    private fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun get(url: String): String =
        (URL(url).openConnection() as HttpURLConnection).run {
            setRequestProperty("User-Agent", "Relay-UpdateFetcher")
            connectTimeout = 10_000
            readTimeout = 15_000
            try {
                inputStream.bufferedReader().use { it.readText() }
            } finally {
                disconnect()
            }
        }

    /** Streams to disk rather than into memory: these APKs are tens of megabytes. */
    private fun download(url: String, target: File): Boolean {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "Relay-UpdateFetcher")
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        try {
            if (connection.responseCode !in 200..299) return false
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return target.length() > 0
        } finally {
            connection.disconnect()
        }
    }
}
