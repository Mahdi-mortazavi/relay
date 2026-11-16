package io.relay.app.service

import android.content.Context

/**
 * Small persisted preferences for Advanced & Power-User controls.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("relay", Context.MODE_PRIVATE)

    /** "system" | "dark" | "light" */
    var themeMode: String
        get() = prefs.getString(KEY_THEME, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()

    var upstreamProxy: String
        get() = prefs.getString(KEY_UPSTREAM, "")?.trim() ?: ""
        set(value) = prefs.edit().putString(KEY_UPSTREAM, value.trim()).apply()

    var automaticRoute: Boolean
        get() = prefs.getBoolean(KEY_AUTOMATIC_ROUTE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTOMATIC_ROUTE, value).apply()

    var trustedDevicesJson: String?
        get() = prefs.getString(KEY_TRUSTED, null)
        set(value) = prefs.edit().putString(KEY_TRUSTED, value).apply()

    /** Custom upstream DNS server IP for tunnel DNS forwarding (default "1.1.1.1"). */
    var customDns: String
        get() = prefs.getString(KEY_CUSTOM_DNS, "1.1.1.1")?.trim()?.ifEmpty { "1.1.1.1" } ?: "1.1.1.1"
        set(value) = prefs.edit().putString(KEY_CUSTOM_DNS, value.trim()).apply()

    /** Optional DNS-over-HTTPS resolver URL inside the tunnel (empty = disabled). */
    var dohUrl: String
        get() = prefs.getString(KEY_DOH_URL, "")?.trim() ?: ""
        set(value) = prefs.edit().putString(KEY_DOH_URL, value.trim()).apply()

    /** Carrier tether-quota workaround: forces outbound socket TTL/HL to 65. */
    var tetherTtl65: Boolean
        get() = prefs.getBoolean(KEY_TETHER_TTL65, false)
        set(value) = prefs.edit().putBoolean(KEY_TETHER_TTL65, value).apply()

    /** Opt-in unprivileged ICMP echo relay for traceroute/ping through the tunnel. */
    var icmpRelay: Boolean
        get() = prefs.getBoolean(KEY_ICMP_RELAY, false)
        set(value) = prefs.edit().putBoolean(KEY_ICMP_RELAY, value).apply()

    /** Upload throttle in KB/s (0 = unlimited). */
    var uploadLimitKbps: Int
        get() = prefs.getInt(KEY_UP_LIMIT_KBPS, 0).coerceAtLeast(0)
        set(value) = prefs.edit().putInt(KEY_UP_LIMIT_KBPS, value.coerceAtLeast(0)).apply()

    /** Download throttle in KB/s (0 = unlimited). */
    var downloadLimitKbps: Int
        get() = prefs.getInt(KEY_DOWN_LIMIT_KBPS, 0).coerceAtLeast(0)
        set(value) = prefs.edit().putInt(KEY_DOWN_LIMIT_KBPS, value.coerceAtLeast(0)).apply()

    /** Hard session quota in MB (0 = unlimited). */
    var quotaMb: Int
        get() = prefs.getInt(KEY_QUOTA_MB, 0).coerceAtLeast(0)
        set(value) = prefs.edit().putInt(KEY_QUOTA_MB, value.coerceAtLeast(0)).apply()

    /** Semicolon-separated inbound port forwards: `tcp:8080->10.13.37.2:8080;udp:...`. */
    var inboundForwardsSpec: String
        get() = prefs.getString(KEY_INBOUND_FORWARDS, "")?.trim() ?: ""
        set(value) = prefs.edit().putString(KEY_INBOUND_FORWARDS, value.trim()).apply()

    /** Expose loopback WireGuard-over-TCP bridge on 127.0.0.1:51821 for ADB forwarding. */
    var adbBridgeEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADB_BRIDGE, true)
        set(value) = prefs.edit().putBoolean(KEY_ADB_BRIDGE, value).apply()

    companion object {
        private const val KEY_TRUSTED = "trusted_devices"
        private const val KEY_AUTOMATIC_ROUTE = "automatic_route"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_UPSTREAM = "upstream_proxy"
        private const val KEY_CUSTOM_DNS = "custom_dns"
        private const val KEY_DOH_URL = "doh_url"
        private const val KEY_TETHER_TTL65 = "tether_ttl65"
        private const val KEY_ICMP_RELAY = "icmp_relay"
        private const val KEY_UP_LIMIT_KBPS = "up_limit_kbps"
        private const val KEY_DOWN_LIMIT_KBPS = "down_limit_kbps"
        private const val KEY_QUOTA_MB = "quota_mb"
        private const val KEY_INBOUND_FORWARDS = "inbound_forwards"
        private const val KEY_ADB_BRIDGE = "adb_bridge_enabled"

        const val DOH_CLOUDFLARE = "https://cloudflare-dns.com/dns-query"
        const val DOH_GOOGLE = "https://dns.google/dns-query"
        const val DOH_QUAD9 = "https://dns.quad9.net/dns-query"

        fun isUsableProxy(value: String): Boolean {
            val text = value.trim()
            if (text.isEmpty()) return true
            val colon = text.lastIndexOf(':')
            if (colon < 0) return false
            val port = text.substring(colon + 1).toIntOrNull() ?: return false
            if (port !in 1..65535) return false
            val host = text.substring(0, colon)
            return host.isEmpty() || host.isNotBlank()
        }

        fun isValidDohUrl(value: String): Boolean {
            val text = value.trim()
            return text.isEmpty() || text.startsWith("https://")
        }

        data class InboundForwardRule(
            val proto: String,
            val listenPort: Int,
            val targetHost: String,
            val targetPort: Int,
        ) {
            fun encode(): String = "$proto:$listenPort->$targetHost:$targetPort"
        }

        fun parseInboundForwards(spec: String): List<InboundForwardRule> {
            if (spec.isBlank()) return emptyList()
            return spec.split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { parseInboundForwardRule(it) }
        }

        fun parseInboundForwardRule(token: String): InboundForwardRule? {
            val arrow = token.split("->")
            if (arrow.size != 2) return null
            val left = arrow[0].trim().split(":")
            val right = arrow[1].trim().split(":")
            if (left.size != 2 || right.size != 2) return null
            val proto = left[0].lowercase()
            if (proto != "tcp" && proto != "udp") return null
            val listenPort = left[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val targetHost = right[0].trim().takeIf { it.isNotEmpty() } ?: return null
            val targetPort = right[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return InboundForwardRule(proto, listenPort, targetHost, targetPort)
        }
    }
}
