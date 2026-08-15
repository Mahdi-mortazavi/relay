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

    companion object {
        private const val KEY_THEME = "theme_mode"
    }
}
