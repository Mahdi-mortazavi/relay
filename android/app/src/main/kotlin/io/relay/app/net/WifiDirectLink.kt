package io.relay.app.net

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import io.relay.app.service.LocalLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Autonomous Wi-Fi Direct Group Owner transport fallback when carrier tethering
 * or hotspot provisioning is disabled, or when an existing LAN has AP isolation.
 */
class WifiDirectLink(private val context: Context) {

    data class Group(
        val ssid: String,
        val passphrase: String,
        val ownerIp: String = DEFAULT_GO_IP,
    )

    sealed class State {
        data object Inactive : State()
        data class Active(val group: Group) : State()
        data class Error(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Inactive)
    val state: StateFlow<State> = _state.asStateFlow()

    private val manager: WifiP2pManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private var channel: WifiP2pManager.Channel? = null

    fun hasRequiredPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    suspend fun startGroup(): Group? = withContext(Dispatchers.Main) {
        if (!hasRequiredPermission()) {
            _state.value = State.Error("missing-permission")
            return@withContext null
        }
        val mgr = manager
        if (mgr == null) {
            _state.value = State.Error("unsupported")
            return@withContext null
        }
        val ch = channel ?: mgr.initialize(context.applicationContext, Looper.getMainLooper(), null)
        if (ch == null) {
            _state.value = State.Error("channel-failed")
            return@withContext null
        }
        channel = ch

        val created = CompletableDeferred<Boolean>()
        mgr.createGroup(
            ch,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    created.complete(true)
                }

                override fun onFailure(reason: Int) {
                    LocalLog.warn(
                        LocalLog.Area.LINK,
                        "Wi-Fi Direct createGroup failed",
                        "reason" to reason.toString(),
                    )
                    created.complete(false)
                }
            },
        )

        val ok = withTimeoutOrNull(GROUP_TIMEOUT_MS) { created.await() } ?: false
        if (!ok) {
            _state.value = State.Error("create-group-failed")
            return@withContext null
        }

        val infoDeferred = CompletableDeferred<WifiP2pGroup?>()
        mgr.requestGroupInfo(ch) { group -> infoDeferred.complete(group) }
        val p2pGroup = withTimeoutOrNull(INFO_TIMEOUT_MS) { infoDeferred.await() }
        val ssid = p2pGroup?.networkName?.takeIf { it.isNotBlank() } ?: "DIRECT-Relay"
        val pass = p2pGroup?.passphrase?.takeIf { it.isNotBlank() } ?: ""
        val ownerIp = LocalAddress.findAdvertisableIpv4()
            ?.takeIf { it.startsWith("192.168.49.") }
            ?: DEFAULT_GO_IP

        val group = Group(ssid = ssid, passphrase = pass, ownerIp = ownerIp)
        _state.value = State.Active(group)
        LocalLog.info(
            LocalLog.Area.LINK,
            "Wi-Fi Direct group active",
            "ssid" to ssid,
            "owner_ip" to ownerIp,
        )
        group
    }

    @SuppressLint("MissingPermission")
    fun stopGroup() {
        val mgr = manager ?: return
        val ch = channel ?: return
        runCatching {
            mgr.removeGroup(ch, null)
        }
        if (Build.VERSION.SDK_INT >= 27) {
            runCatching { ch.close() }
        }
        channel = null
        _state.value = State.Inactive
    }

    companion object {
        const val DEFAULT_GO_IP = "192.168.49.1"
        private const val GROUP_TIMEOUT_MS = 8_000L
        private const val INFO_TIMEOUT_MS = 3_000L
    }
}
