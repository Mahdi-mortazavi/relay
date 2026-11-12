package io.relay.app.net

import java.io.File

/**
 * Probes optional root / kernel WireGuard / Shizuku acceleration and lockdown-exemption
 * capabilities on the host device without requiring elevated privileges for normal use.
 */
object AccelHelper {

    data class Capabilities(
        val suAvailable: Boolean,
        val kernelWireguardAvailable: Boolean,
        val shizukuHint: String = "",
    )

    fun probe(): Capabilities {
        val suPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/adb/magisk",
            "/data/adb/ksu",
        )
        val hasSu = suPaths.any { path ->
            runCatching { File(path).exists() }.getOrDefault(false)
        }

        val wgModulePaths = listOf(
            "/sys/module/wireguard",
            "/proc/sys/net/ipv4/ip_forward",
        )
        val hasKernelWg = runCatching { File(wgModulePaths[0]).exists() }.getOrDefault(false)

        return Capabilities(
            suAvailable = hasSu,
            kernelWireguardAvailable = hasKernelWg,
            shizukuHint = "adb shell settings put secure always_on_vpn_lockdown_whitelist io.relay.app",
        )
    }
}
