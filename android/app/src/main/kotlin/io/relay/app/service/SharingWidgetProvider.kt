package io.relay.app.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.relay.app.MainActivity
import io.relay.app.R
import io.relay.app.core.ConnectionState

/**
 * The home screen widget: sharing state and the pairing code, without opening
 * anything.
 *
 * A widget is a different job from the tile. The tile is for acting — start,
 * stop. This is for *reading*: the two digits you have to type on the PC, big
 * enough to read from arm's length while you are looking at the laptop rather
 * than the phone.
 *
 * Tapping it opens Relay, and starts sharing when nothing is running. It does
 * not start the service directly: on Android 13+ that needs the notification
 * permission, which only an Activity can ask for, and a widget that sometimes
 * silently fails is worse than one that always opens the app.
 *
 * Updates are pushed by [refresh] from wherever the state changes, because
 * a widget cannot subscribe to a flow — the process it lives in is the
 * launcher's, not ours.
 */
class SharingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
    ) {
        val state = ConnectionRepository.state.value
        ids.forEach { manager.updateAppWidget(it, build(context, state)) }
    }

    companion object {

        /**
         * Redraws every placed widget. Safe to call from any thread and when no
         * widget exists — which is the common case, so it must be cheap and
         * must never throw into the caller.
         */
        fun refresh(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(
                    ComponentName(context, SharingWidgetProvider::class.java),
                )
                if (ids.isEmpty()) return
                val views = build(context, ConnectionRepository.state.value)
                ids.forEach { manager.updateAppWidget(it, views) }
            }
        }

        private fun build(context: Context, state: ConnectionState): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_sharing)

            // value = the thing worth reading at a glance; label = what it means
            val (value, label) = when (state) {
                is ConnectionState.Idle ->
                    "—" to context.getString(R.string.widget_tap_to_start)
                is ConnectionState.Preparing ->
                    "…" to context.getString(R.string.tile_starting)
                is ConnectionState.Advertising ->
                    (state.shortCode ?: "QR") to context.getString(R.string.tile_waiting)
                is ConnectionState.Connected ->
                    (state.shortCode ?: "✓") to context.resources.getQuantityString(
                        R.plurals.tile_connected, state.clientCount, state.clientCount,
                    )
                is ConnectionState.Error ->
                    "!" to context.getString(R.string.tile_error)
            }

            views.setTextViewText(R.id.widget_value, value)
            views.setTextViewText(R.id.widget_state, label)
            views.setContentDescription(
                R.id.widget_root,
                "${context.getString(R.string.app_name)} — $label",
            )

            val intent = Intent(context, MainActivity::class.java).apply {
                // Starting only from Idle: tapping while sharing should show the
                // code, not rotate the keys under a PC that is connected.
                action = if (state is ConnectionState.Idle) {
                    MainActivity.ACTION_START_SHARING
                } else {
                    Intent.ACTION_MAIN
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context,
                    // Distinct request codes, or the second PendingIntent reuses
                    // the first and the widget keeps the action it was built with.
                    if (state is ConnectionState.Idle) 1 else 2,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            return views
        }
    }
}
