package io.relay.app.service

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import io.relay.app.MainActivity
import io.relay.app.R
import io.relay.app.core.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Sharing, from the notification shade.
 *
 * The shortcut on the launcher icon still needs the launcher. This does not:
 * pull the shade down from anywhere — over another app, on the lock screen —
 * and the one thing Relay does is one tap away.
 *
 * The tile is a *view* of [ConnectionRepository.state], never a second copy of
 * it. Android only delivers [onStartListening] while the tile is on screen, so
 * the subscription lives exactly that long; a tile holding its own idea of
 * whether sharing is on is a tile that will eventually disagree with the app.
 */
@RequiresApi(Build.VERSION_CODES.N)
class SharingTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        // Cancelled in onStopListening, so this cannot outlive the shade being
        // open and leave a collector running against a dead tile.
        val created = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = created
        created.launch {
            ConnectionRepository.state.collectLatest { render(it) }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    /**
     * A tap means "do the other thing". Deliberately not a start-only action:
     * the value of the tile is being able to stop sharing without hunting for
     * the app, which is when the phone is in someone's pocket getting warm.
     */
    override fun onClick() {
        super.onClick()
        when (ConnectionRepository.state.value) {
            is ConnectionState.Idle, is ConnectionState.Error -> start()
            else -> SharingService.stop(applicationContext)
        }
    }

    /**
     * Starting needs the notification permission on Android 13+, and a tile
     * cannot ask for one. Rather than start a foreground service that may have
     * no notification to show, open the app — which already has that flow — and
     * let it start from there.
     *
     * [startActivityAndCollapse] is how a tile is allowed to launch UI; on
     * Android 14+ it must be given a PendingIntent.
     */
    private fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val intent = android.content.Intent(applicationContext, MainActivity::class.java).apply {
                action = MainActivity.ACTION_START_SHARING
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(
                        applicationContext, 0, intent,
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }
        SharingService.start(applicationContext)
    }

    private fun render(state: ConnectionState) {
        val tile = qsTile ?: return

        // The subtitle carries the pairing code when there is one, so the shade
        // answers "what do I type on the PC" without opening anything.
        val (tileState, label) = when (state) {
            is ConnectionState.Idle -> Tile.STATE_INACTIVE to getString(R.string.tile_idle)
            is ConnectionState.Preparing -> Tile.STATE_ACTIVE to getString(R.string.tile_starting)
            is ConnectionState.Advertising ->
                Tile.STATE_ACTIVE to (state.shortCode?.let { getString(R.string.tile_code, it) }
                    ?: getString(R.string.tile_waiting))
            is ConnectionState.Connected ->
                Tile.STATE_ACTIVE to resources.getQuantityString(
                    R.plurals.tile_connected, state.clientCount, state.clientCount,
                )
            is ConnectionState.Error -> Tile.STATE_INACTIVE to getString(R.string.tile_error)
        }

        tile.state = tileState
        tile.label = getString(R.string.app_name)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_shortcut_start)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = label
        } else {
            // Before Q there is no subtitle line, so the state has to ride on the
            // label itself or it is invisible.
            tile.label = label
        }
        tile.contentDescription = "${getString(R.string.app_name)} — $label"
        tile.updateTile()
    }
}
