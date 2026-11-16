package io.relay.app.net

import android.content.Context

/**
 * Pre-flight diagnosis for strict Android VPN / "Block connections without VPN"
 * lockdown scenarios before or while starting sharing.
 */
object VpnPreFlight {

    enum class Finding {
        NO_VPN,
        RELAY_IN_VPN_OK,
        PROXY_AVAILABLE,
        LOCKDOWN_BLOCKING_LAN,
        STRICT_VPN_NO_PROXY,
    }

    data class Report(
        val vpnActive: Boolean,
        val relayInsideVpn: Boolean,
        val lockdown: VpnLockdown.State,
        val localProxy: String?,
        val finding: Finding,
    )

    suspend fun inspect(context: Context, configuredUpstreamProxy: String): Report {
        val vpnActive = VpnStatus.isVpnActive(context)
        val relayInVpn = if (vpnActive) VpnStatus.isRelayInsideVpn(context) else false
        val lockdown = VpnLockdown.read(context)
        val manual = configuredUpstreamProxy.trim().takeIf { it.isNotEmpty() }
        val localProxy = when {
            !vpnActive || relayInVpn -> null
            manual != null -> manual
            else -> ProxyScan.find()
        }

        val finding = when {
            !vpnActive -> Finding.NO_VPN
            lockdown == VpnLockdown.State.ON -> Finding.LOCKDOWN_BLOCKING_LAN
            relayInVpn -> Finding.RELAY_IN_VPN_OK
            localProxy != null -> Finding.PROXY_AVAILABLE
            else -> Finding.STRICT_VPN_NO_PROXY
        }

        return Report(
            vpnActive = vpnActive,
            relayInsideVpn = relayInVpn,
            lockdown = lockdown,
            localProxy = localProxy,
            finding = finding,
        )
    }
}
