package io.relay.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Whether a VPN transport is currently active on the phone (for the NO_VPN_ACTIVE advisory). */
object VpnStatus {
    fun isVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true
        }
        return false
    }

    /**
     * Whether *Relay's own* traffic goes through a VPN — not whether one is up.
     *
     * The two differ on exactly the phones this matters for. An Android VPN
     * routes by UID, and the default network the platform hands an app is the
     * one for that app's UID: the VPN for an app inside it, the underlying
     * Wi-Fi or mobile network for an app the VPN excluded. So this is the
     * phone telling Relay, with no guessing, whether a plain socket from here
     * reaches the VPN (ADR-0011).
     */
    fun isRelayInsideVpn(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}
