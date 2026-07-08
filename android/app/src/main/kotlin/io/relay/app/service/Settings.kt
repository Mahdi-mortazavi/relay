package io.relay.app.service

import android.content.Context

/**
 * Small persisted preferences for the Advanced surface. Nothing here is
 * sensitive; it's user convenience (a preferred port, theme choice).
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("relay", Context.MODE_PRIVATE)

    /** Preferred SOCKS port, tried first before the fallback list; 0 = auto. */
    var preferredPort: Int
        get() = prefs.getInt(KEY_PORT, 0)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    /** "system" | "dark" | "light" */
    var themeMode: String
        get() = prefs.getString(KEY_THEME, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    /** "FAST" | "FULL" — the selected transport mode (TransportMode.name). */
    var transportMode: String
        get() = prefs.getString(KEY_MODE, "FAST") ?: "FAST"
        set(value) = prefs.edit().putString(KEY_MODE, value).apply()

    companion object {
        private const val KEY_PORT = "preferred_port"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_MODE = "transport_mode"
    }
}
