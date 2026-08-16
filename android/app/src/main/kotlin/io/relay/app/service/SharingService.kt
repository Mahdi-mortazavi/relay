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
import io.relay.app.core.WarningCode
import io.relay.app.core.WgConfig
import io.relay.app.net.LocalAddress
import io.relay.app.core.PairingCode
import io.relay.app.net.Beacon
import io.relay.app.net.PairingServer
import io.relay.app.net.VpnCapture
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
 * Foreground service owning the WireGuard endpoint (ADR-0003, ADR-0009). Runs with a
 * persistent notification from the first frame; holds a partial WakeLock only
 * while at least one client is transferring.
 */
class SharingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pairing = DirectPairingStrategy()
    private val settings by lazy { Settings(this) }
    private var beacon: Beacon? = null

    /** Null when the port could not be bound, which makes this phone QR-only. */
    private var pairingServer: PairingServer? = null

    /**
     * The code this session announces. Kept even when it is not shown, so a
     * rebind re-announces the same number rather than drawing a new one.
     */
    private var pairingCode: String? = null

    /** The code the UI shows — null when announcing cannot work here (see [pairingCode]). */
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
    private var peerWatcher: Job? = null

    // The session's forwarder and this pairing's keys (ADR-0008).
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

        // One transport since ADR-0009. Preparation dispatches its own error.
        val payload = prepareFull(host) ?: return

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
        val announcer = Beacon(
            code = code,
            mode = payload.mode,
            host = payload.host,
            port = payload.port,
            deviceName = payload.name,
            pairingPort = pairingServer?.boundPort,
        ).also { it.start() }
        beacon = announcer

        // Only show the two digits if they can actually be found. They carry no
        // address of their own, so on a network where this phone can neither
        // broadcast nor answer a probe they are a dead end: the PC searches for
        // them forever and the person is left comparing two screens that look
        // right. Withholding the short code makes the UI fall back to the
        // eight-character code, which describes the address itself and needs no
        // discovery — and makes the PC's "my phone shows a longer code" link
        // true rather than aspirational.
        val announced = announcer.canAnnounce
        pairingCode = code
        shortCode = code.takeIf { announced }
        LocalLog.add(
            if (announced) "Pairing code: $code"
            else "Cannot announce on this network; showing the 8-character code instead",
        )

        ConnectionRepository.dispatch("ready") {
            ConnectionState.Advertising(payload, pairing.issueTypedCode(payload), shortCode)
        }
        startHotspotWatcher()
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
        startPeerWatcher(forwarder)
        startPairingServer()
        return pairing.issuePayload(
            mode = QrPayload.MODE_WIREGUARD, host = host, port = keys.endpointPort,
            deviceName = Build.MODEL.take(64), wg = WgConfig.toWgParams(keys),
        )
    }

    /**
     * Opens the port a laptop asks on when it has only two digits and no camera
     * (ADR-0009). Best effort by design: a phone that cannot bind it is still a
     * phone you can pair by QR, so the beacon simply omits `pairingPort` and the
     * PC says to scan — which is true — rather than reporting a correct code as
     * invalid.
     */
    private fun startPairingServer() {
        pairingServer?.stop()
        val server = PairingServer(
            preferredPort = PairingServer.DEFAULT_PORT,
            gate = clientGate,
            // Read at approval time, not captured: a rebind onto a new hotspot
            // address mid-session would otherwise send the laptop to where this
            // phone used to be.
            configuration = { client ->
                val keys = wgKeys
                val host = currentHost
                // Now that a PC has actually asked, its address is known, so the
                // reply path can be checked rather than guessed at. This is the
                // only moment the phone can tell that its own VPN will swallow
                // the tunnel's handshake -- and saying so here beats the laptop
                // reporting an unanswered tunnel and sending someone to look at
                // their QR code.
                if (host != null) {
                    val swallowed = VpnCapture.wouldSwallow(client = client, advertisedHost = host)
                    ConnectionRepository.setWarning(WarningCode.VPN_CAPTURES_RELAY, swallowed)
                    if (swallowed) {
                        LocalLog.add(
                            "This phone's VPN is routing replies to $client into itself; " +
                                "the tunnel will not answer"
                        )
                    }
                }
                if (keys == null || host == null) null
                else PairingServer.Configuration(
                    host = host,
                    port = keys.endpointPort,
                    wg = WgConfig.toWgParams(keys),
                )
            },
        )
        pairingServer = try {
            server.start()
            LocalLog.add("Pairing by code available on ${server.boundPort}")
            server
        } catch (e: IOException) {
            LocalLog.add("Pairing port busy; this phone is QR-only: ${e.message}")
            null
        }
    }

    /**
     * Turns the tunnel's handshake into the "a PC is here" signal that drives
     * the screen, the wake lock and the notification.
     *
     * There is nothing else to count. Its endpoint is a UDP port, which answers
     * identically whether or not a laptop is behind it, so without this the
     * phone would say "waiting for a PC" through an entire download. WireGuard
     * itself provides the answer: a peer that is really there rekeys, and the
     * client's 25-second keepalive keeps that happening even while idle.
     */
    private fun startPeerWatcher(forwarder: WgForwarder) {
        peerWatcher?.cancel()
        peerWatcher = scope.launch {
            var present = false
            while (isActive) {
                delay(PEER_POLL_MS)
                val handshake = forwarder.lastHandshakeUnix()
                val age = System.currentTimeMillis() / 1000 - handshake
                // Three minutes is what wg(8)'s own tooling treats as alive:
                // rekeying happens at two, so this leaves a minute of margin
                // without holding "connected" on screen long after the laptop
                // has closed its lid.
                val nowPresent = handshake > 0 && age in 0..PEER_ALIVE_SECONDS
                if (nowPresent != present) {
                    present = nowPresent
                    onClientsChanged(if (nowPresent) 1 else 0)
                }
                if (nowPresent) {
                    // Named from the phone's side: what the laptop sent up is
                    // what this endpoint received.
                    onTraffic(
                        bytesUp = forwarder.bytesReceived(),
                        bytesDown = forwarder.bytesSent(),
                    )
                }
            }
        }
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

    /**
     * Hotspot returned on a different IP: re-advertise.
     *
     * Nothing has to be rebound. The endpoint listens on a UDP port regardless
     * of which interface carries it, and so does the pairing port, so both stay
     * up and the keys are unchanged — only the address being advertised moves.
     */
    private fun rebind(host: String) {
        currentHost = host
        val keys = wgKeys ?: return
        LocalLog.add("Re-advertising WireGuard on $host:${keys.endpointPort}")
        val payload = pairing.issuePayload(
            mode = QrPayload.MODE_WIREGUARD, host = host, port = keys.endpointPort,
            deviceName = Build.MODEL.take(64), wg = WgConfig.toWgParams(keys),
        )
        // The beacon was built with the address the phone had a moment ago, and
        // it carries that address in every datagram. Left alone it goes on
        // announcing an address nothing answers at, so a PC that finds the code
        // connects to the old IP and fails with no visible cause. Restart it on
        // the new one, keeping the code: the number on screen must not change
        // under someone who is part-way through typing it.
        val code = pairingCode
        if (code != null) {
            beacon?.stop()
            val announcer = Beacon(
                code = code,
                mode = payload.mode,
                host = payload.host,
                port = payload.port,
                deviceName = payload.name,
                // The pairing server survives a rebind — it listens on a port,
                // not on an address — so it keeps announcing the same one.
                pairingPort = pairingServer?.boundPort,
            ).also { it.start() }
            beacon = announcer
            // The new network may be able to carry the announcement where the
            // old one could not, or the other way round. Re-ask rather than
            // keeping an answer that was true of a network we have left.
            shortCode = code.takeIf { announcer.canAnnounce }
            LocalLog.add(
                if (announcer.canAnnounce) "Re-announcing $code on ${payload.host}:${payload.port}"
                else "Cannot announce on this network; showing the 8-character code instead",
            )
        }

        // Present the fresh payload in place; the client count (if any) is stale
        // after a rebind, so drop back to Advertising until a client reconnects.
        ConnectionRepository.reissue(payload, pairing.issueTypedCode(payload), shortCode)
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
        // Announce the stop so a PC drops this phone at once rather than waiting
        // out the staleness window, then forget who was approved: those answers
        // were about this network, and the next session may be a different one.
        // Direct, not through this scope: teardown often runs while the scope
        // is already being cancelled, and a launch there would never execute --
        // leaving the beacon broadcasting after the user pressed Stop.
        beacon?.stop()
        beacon = null
        // Stop offering configurations before the keys they describe are
        // discarded, or a laptop could be handed a tunnel that no longer exists.
        pairingServer?.stop()
        pairingServer = null
        pairingCode = null
        shortCode = null
        clientGate.reset()
        // Full Mode: stop watching before stopping the endpoint, or the next
        // tick reads a torn-down session and pushes a state change after the
        // service has already said it is idle.
        peerWatcher?.cancel()
        peerWatcher = null
        // Stop the endpoint and drop the per-pairing keys (§4.2).
        runCatching { wgForwarder?.stop() }
        wgKeys = null
        currentHost = null
        releaseWakeLock()
        ConnectionRepository.clearWarnings()
    }

    // --- what the tunnel reports ---------------------------------------------

    /**
     * The peer arriving or leaving, as [startPeerWatcher] observes it.
     *
     * Plain methods rather than a listener interface: there was one
     * implementation of it and one caller, and the interface only existed
     * because a separate class used to raise these.
     */
    private fun onClientsChanged(devices: Int) {
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

    private fun onTraffic(bytesUp: Long, bytesDown: Long) {
        // Throttle: at most one state push per second.
        val now = System.currentTimeMillis()
        if (now - lastTrafficPush < 1000) return
        lastTrafficPush = now
        ConnectionRepository.dispatch("clientCountChanged") { current ->
            (current as ConnectionState.Connected).copy(bytesUp = bytesUp, bytesDown = bytesDown)
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
        const val PEER_POLL_MS = 1000L

        /**
         * How stale a handshake may be before Full Mode stops calling the peer
         * present. Matches what wg(8)'s own tooling treats as a live peer:
         * rekeying happens at two minutes, so this leaves a minute of margin.
         */
        const val PEER_ALIVE_SECONDS = 180L

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
