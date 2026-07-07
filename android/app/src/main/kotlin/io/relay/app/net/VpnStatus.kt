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
}
