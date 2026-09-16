package io.relay.app.service

import android.content.Context

/**
 * Small persisted preferences for the Advanced surface. Nothing here is
 * sensitive; it's user convenience.
 *
 * There is no transport setting any more (ADR-0009) and no preferred port: Full
 * Mode's endpoint takes a fixed UDP port and the pairing port is fixed too, so
 * neither was a choice a person could make usefully. A stale "FAST" left in
 * SharedPreferences by an older install is simply never read.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("relay", Context.MODE_PRIVATE)

    /** "system" | "dark" | "light" */
    var themeMode: String
        get() = prefs.getString(KEY_THEME, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    /**
     * Whether the first-run walkthrough has been shown.
     *
     * Once, not once-per-version: someone who has already set the phone up does
     * not want to be walked through it again because the app updated.
     */
    var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()

    /**
     * A SOCKS5 `host:port` to forward the PC's traffic through, or empty for
     * the phone's default route.
     *
     * The one setting here that changes where packets go, and it exists for a
     * trade nothing else can escape. An Android VPN captures by UID: leave
     * Relay inside the tunnel and the phone cannot answer the PC at all; take
     * Relay out of it — which is the advice every VPN gives — and the PC gets
     * the phone's connection instead of the phone's VPN. Pointing this at the
     * VPN client's own local port is the only arrangement that gives both.
     *
     * Stored as typed, validated at use. A port on its own means this phone.
     */
    var upstreamProxy: String
        get() = prefs.getString(KEY_UPSTREAM, "")?.trim() ?: ""
        set(value) = prefs.edit().putString(KEY_UPSTREAM, value.trim()).apply()

    companion object {
        private const val KEY_THEME = "theme_mode"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_UPSTREAM = "upstream_proxy"

        /**
         * Whether [upstreamProxy] is something the Go side will accept, so a
         * typo is refused on the screen where it was typed rather than
         * surfacing later as a tunnel that comes up and forwards nothing.
         *
         * Empty is valid and means the default route. Otherwise it is
         * `host:port` or a bare `:port`, with the port in range.
         */
        fun isUsableProxy(value: String): Boolean {
            val text = value.trim()
            if (text.isEmpty()) return true
            val colon = text.lastIndexOf(':')
            if (colon < 0) return false
            val port = text.substring(colon + 1).toIntOrNull() ?: return false
            if (port !in 1..65535) return false
            val host = text.substring(0, colon)
            // A bare ":1080" is what someone types for a proxy on this phone,
            // and every proxy this targets is on loopback.
            return host.isEmpty() || host.isNotBlank()
        }
    }
}
