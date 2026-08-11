package io.relay.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.relay.app.MainActivity
import io.relay.app.R
import io.relay.app.core.ConnectionState
import io.relay.app.core.DirectPairingStrategy
import io.relay.app.core.ErrorCode
import io.relay.app.core.QrPayload
import io.relay.app.core.ReconnectPolicy
import io.relay.app.core.TransportMode
import io.relay.app.core.WarningCode
import io.relay.app.core.WgConfig
import io.relay.app.net.LocalAddress
import io.relay.app.core.PairingCode
import io.relay.app.net.Beacon
import io.relay.app.net.Socks5Server
import io.relay.app.net.VpnStatus
import io.relay.app.net.wg.WgForwarder
import io.relay.app.net.wg.WgForwarderException
import io.relay.app.net.wg.WgForwarderProvider
import io.relay.app.net.wg.WgKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Foreground service owning the SOCKS5 server (ADR-0003). Runs with a
 * persistent notification from the first frame; holds a partial WakeLock only
 * while at least one client is transferring.
 */
class SharingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pairing = DirectPairingStrategy()
    private val settings by lazy { Settings(this) }
    private var server: Socks5Server? = null
    private var beacon: Beacon? = null
    private var shortCode: String? = null

    /**
     * What actually protects a two-digit code: the person holding the phone.
     * See /shared/pairing-beacon.md -- the code selects, the human consents.
     */
    private val clientGate get() = ConnectionRepository.clientGate
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastTrafficPush = 0L
    private var currentHost: String? = null
    private var hotspotWatcher: Job? = null

    // Full Mode session (ADR-0008): the userspace forwarder + this pairing's keys.
    private var mode = TransportMode.FAST
    private var wgForwarder: WgForwarder? = WgForwarderProvider.create()
    private var wgKeys: WgConfig.KeySet? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope.launch {
            ConnectionRepository.state.collectLatest { state ->
                if (state !is ConnectionState.Idle) {
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // Null intent = START_STICKY restart after a system kill: resume sharing.
            ACTION_START, null -> {
                // Must be synchronous: startForegroundService() gives us a few
                // seconds to post the notification or the process is killed.
                startInForeground()
                // Everything else is blocking IO — enumerating interfaces (slow
                // with a tun up, which is Relay's normal case) and binding up to
                // four sockets. onStartCommand runs on the main thread, so doing
                // it here froze the UI on "Starting…" and risked an ANR.
                scope.launch {
                    if (ConnectionRepository.state.value is ConnectionState.Idle) startSharing()
                }
            }
            ACTION_STOP -> stopSharing()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        teardown()
        // A destroy without user stop (system kill) leaves state consistent:
        ConnectionRepository.dispatch("stop") { ConnectionState.Idle }
        scope.cancel()
        super.onDestroy()
    }

    // --- lifecycle steps -----------------------------------------------------

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(ConnectionRepository.state.value), type,
        )
    }

    private fun startSharing() {
        if (!ConnectionRepository.dispatch("start") { ConnectionState.Preparing }) return
        LocalLog.add("Starting sharing")
        ConnectionRepository.clearWarnings()

        // Non-blocking advisory: sharing works either way, but the user may have
        // meant to share a VPN that isn't on (docs/errors.md → NO_VPN_ACTIVE).
        ConnectionRepository.setWarning(
            WarningCode.NO_VPN_ACTIVE, !VpnStatus.isVpnActive(this),
        )

        // Works on the phone's hotspot OR a shared Wi-Fi/LAN the laptop is also on.
        val host = LocalAddress.findAdvertisableIpv4()
        if (host == null) {
            LocalLog.add("No usable Wi-Fi/hotspot interface found")
            fail(ErrorCode.HOTSPOT_OFF)
            return
        }

        mode = TransportMode.fromSetting(settings.transportMode)
        LocalLog.add("Mode: ${mode.name}")
        val payload = when (mode) {
            TransportMode.FAST -> prepareFast(host)
            TransportMode.FULL -> prepareFull(host)
        } ?: return // preparation dispatched the appropriate error

        currentHost = host

        // Drawn once per sharing session and kept for its life, so the number on
        // screen never changes under someone who is mid-way through typing it.
        //
        // Drawn blind: surveying the network for codes already in use would
        // block this thread for over a second, and this runs on the main thread
        // from onStartCommand. A collision is rare and already has an answer --
        // the PC shows both device names and asks which one -- so paying an ANR
        // risk to make it rarer is the wrong trade.
        val code = PairingCode.draw()
        shortCode = code
        beacon = Beacon(
            code = code,
            mode = payload.mode,
            host = payload.host,
            port = payload.port,
            deviceName = payload.name,
        ).also { it.start() }
        LocalLog.add("Pairing code: $code")

        ConnectionRepository.dispatch("ready") {
            ConnectionState.Advertising(payload, pairing.issueTypedCode(payload), code)
        }
        startHotspotWatcher()
    }

    /** Fast Mode: bind the SOCKS server and return its socks5 payload, or null on failure. */
    private fun prepareFast(host: String): QrPayload? {
        val boundPort = bindServer()
        if (boundPort < 0) {
            LocalLog.add("All candidate ports busy")
            fail(ErrorCode.PORT_IN_USE)
            return null
        }
        LocalLog.add("Advertising SOCKS on $host:$boundPort")
        return pairing.issuePayload(
            mode = QrPayload.MODE_SOCKS5, host = host, port = boundPort,
            deviceName = Build.MODEL.take(64),
        )
    }

    /** Full Mode: mint per-pairing keys, start the userspace WG endpoint, return its wireguard payload. */
    private fun prepareFull(host: String): QrPayload? {
        val forwarder = wgForwarder
        if (forwarder == null) {
            LocalLog.add("WireGuard forwarder unavailable")
            fail(ErrorCode.WG_START_FAILED)
            return null
        }
        val keys = WgKeys.generate()
        try {
            forwarder.start(WgConfig.serverConfig(keys))
        } catch (e: WgForwarderException) {
            LocalLog.add("WireGuard endpoint failed: ${e.message}")
            fail(ErrorCode.WG_START_FAILED)
            return null
        }
        wgKeys = keys
        LocalLog.add("WireGuard endpoint up on $host:${keys.endpointPort}")
        return pairing.issuePayload(
            mode = QrPayload.MODE_WIREGUARD, host = host, port = keys.endpointPort,
            deviceName = Build.MODEL.take(64), wg = WgConfig.toWgParams(keys),
        )
    }

    /** Binds the SOCKS server; preferred port (Advanced) first, then the fallback list. Returns the bound port or -1. */
    private fun bindServer(): Int {
        val candidates = buildList {
            settings.preferredPort.takeIf { it in 1..65535 }?.let { add(it) }
            addAll(CANDIDATE_PORTS)
        }.distinct()
        for (candidate in candidates) {
            val attempt = Socks5Server(candidate, serverListener, clientGate)
            try {
                attempt.start()
                server = attempt
                return attempt.boundPort
            } catch (_: IOException) {
                // Port taken — try the next one; the client learns the port from
                // the QR. bind() allocates the descriptor before it throws, so
                // the half-open server has to be closed or each PORT_IN_USE
                // failure leaks one fd.
                attempt.stop()
            }
        }
        return -1
    }

    /**
     * Watches for the hotspot interface disappearing while sharing and drives
     * the bounded reconnect policy (ADR-0007): a brief drop is absorbed
     * silently; only exhausting the budget surfaces HOTSPOT_LOST.
     */
    private fun startHotspotWatcher() {
        hotspotWatcher?.cancel()
        hotspotWatcher = scope.launch {
            while (isActive) {
                delay(HOTSPOT_POLL_MS)
                val state = ConnectionRepository.state.value
                if (state !is ConnectionState.Advertising && state !is ConnectionState.Connected) continue
                if (LocalAddress.findAdvertisableIpv4() != null) continue
                if (!runReconnect()) return@launch // exhausted → Error, stop watching
            }
        }
    }

    /** Returns true when the hotspot came back (session restored), false when the budget was exhausted. */
    private suspend fun runReconnect(): Boolean {
        LocalLog.add("Hotspot dropped — reconnecting")
        ConnectionRepository.annotateReconnecting(true)
        for ((attempt, wait) in ReconnectPolicy.attemptDelaysMs.withIndex()) {
            delay(wait)
            val host = LocalAddress.findAdvertisableIpv4()
            if (host != null) {
                LocalLog.add("Network back after attempt ${attempt + 1}")
                ConnectionRepository.annotateReconnecting(false)
                if (host != currentHost) rebind(host)
                return true
            }
        }
        LocalLog.add("Reconnect budget exhausted")
        fail(ErrorCode.HOTSPOT_LOST)
        return false
    }

    /** Hotspot returned on a different IP: re-advertise (and, for Fast, rebind the socket). */
    private fun rebind(host: String) {
        currentHost = host
        val payload = when (mode) {
            TransportMode.FAST -> {
                server?.stop()
                server = null
                val boundPort = bindServer()
                if (boundPort < 0) {
                    LocalLog.add("No free port after rebind")
                    fail(ErrorCode.PORT_IN_USE)
                    return
                }
                LocalLog.add("Re-advertising SOCKS on $host:$boundPort")
                pairing.issuePayload(
                    mode = QrPayload.MODE_SOCKS5, host = host, port = boundPort,
                    deviceName = Build.MODEL.take(64),
                )
            }
            // Full Mode: the endpoint listens on a UDP port regardless of interface,
            // so it stays up; only the advertised host changes, keys unchanged.
            TransportMode.FULL -> {
                val keys = wgKeys ?: return
                LocalLog.add("Re-advertising WireGuard on $host:${keys.endpointPort}")
                pairing.issuePayload(
                    mode = QrPayload.MODE_WIREGUARD, host = host, port = keys.endpointPort,
                    deviceName = Build.MODEL.take(64), wg = WgConfig.toWgParams(keys),
                )
            }
        }
        // Present the fresh payload in place; the client count (if any) is stale
        // after a rebind, so drop back to Advertising until a client reconnects.
        ConnectionRepository.reissue(payload, pairing.issueTypedCode(payload))
    }

    /**
     * Surfaces [code] and shuts the service down.
     *
     * Setting the Error state alone used to leave a *running foreground service*
     * behind an ongoing, non-dismissible "Sharing stopped" notification: the user
     * dismissed the error in the app, the UI went back to Idle, and the
     * notification stayed pinned to the shade forever with no way to remove it
     * short of force-stopping the app. The Error state itself lives in
     * [ConnectionRepository], which outlives the service, so the app still shows
     * the error after the service is gone.
     */
    private fun fail(code: ErrorCode) {
        teardown()
        ConnectionRepository.dispatch("failure") { ConnectionState.Error(code) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopSharing() {
        teardown()
        ConnectionRepository.dispatch("stop") { ConnectionState.Idle }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        hotspotWatcher?.cancel()
        hotspotWatcher = null
        server?.stop()
        server = null
        // Announce the stop so a PC drops this phone at once rather than waiting
        // out the staleness window, then forget who was approved: those answers
        // were about this network, and the next session may be a different one.
        // Direct, not through this scope: teardown often runs while the scope
        // is already being cancelled, and a launch there would never execute --
        // leaving the beacon broadcasting after the user pressed Stop.
        beacon?.stop()
        beacon = null
        shortCode = null
        clientGate.reset()
        // Full Mode: stop the endpoint and drop the per-pairing keys (§4.2).
        runCatching { wgForwarder?.stop() }
        wgKeys = null
        currentHost = null
        releaseWakeLock()
        ConnectionRepository.clearWarnings()
    }

    // --- server callbacks ----------------------------------------------------

    private val serverListener = object : Socks5Server.Listener {
        override fun onClientsChanged(devices: Int) {
            LocalLog.add("Clients: $devices")
            if (devices > 0) {
                acquireWakeLock()
                ConnectionRepository.dispatch("clientConnected") { current ->
                    val advertising = current as ConnectionState.Advertising
                    ConnectionState.Connected(
                        advertising.payload,
                        advertising.typedCode,
                        advertising.shortCode,
                        devices,
                    )
                }
                ConnectionRepository.dispatch("clientCountChanged") { current ->
                    (current as ConnectionState.Connected).copy(clientCount = devices)
                }
            } else {
                releaseWakeLock()
                ConnectionRepository.dispatch("lastClientDisconnected") { current ->
                    val connected = current as ConnectionState.Connected
                    ConnectionState.Advertising(connected.payload, connected.typedCode, connected.shortCode)
                }
            }
        }

        override fun onTraffic(bytesUp: Long, bytesDown: Long) {
            // Throttle: at most one state push per second.
            val now = System.currentTimeMillis()
            if (now - lastTrafficPush < 1000) return
            lastTrafficPush = now
            ConnectionRepository.dispatch("clientCountChanged") { current ->
                (current as ConnectionState.Connected).copy(bytesUp = bytesUp, bytesDown = bytesDown)
            }
        }
    }

    // --- wake lock (held only during active transfer, ADR-0003) --------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "relay:transfer")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    // --- notification ---------------------------------------------------------

    private val notificationManager: NotificationManager
        get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun buildNotification(state: ConnectionState): Notification {
        val text = when (state) {
            is ConnectionState.Idle, ConnectionState.Preparing -> getString(R.string.notification_starting)
            is ConnectionState.Advertising ->
                if (state.reconnecting) getString(R.string.notification_reconnecting)
                else getString(R.string.notification_waiting)
            is ConnectionState.Connected ->
                if (state.reconnecting) getString(R.string.notification_reconnecting)
                else resources.getQuantityString(
                    R.plurals.notification_connected, state.clientCount, state.clientCount,
                )
            is ConnectionState.Error -> getString(R.string.notification_error)
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SharingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_START = "io.relay.app.action.START"
        const val ACTION_STOP = "io.relay.app.action.STOP"
        const val CHANNEL_ID = "sharing"
        const val NOTIFICATION_ID = 1
        const val HOTSPOT_POLL_MS = 2000L

        /** Client discovers the port via the QR, so any of these is fine. */
        val CANDIDATE_PORTS = listOf(1080, 1081, 10800)

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, SharingService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SharingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
