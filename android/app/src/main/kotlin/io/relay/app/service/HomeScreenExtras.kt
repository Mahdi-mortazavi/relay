package io.relay.app.service

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build

/**
 * The two places Relay can put itself that are not the app drawer: the Quick
 * Settings shade, and the home screen.
 *
 * Both are *offers*, not installs — Android will not let an app place either
 * one silently, and it should not. What differs is how much of the asking the
 * platform will do for us, and that differs by version, which is the whole
 * reason this file exists rather than the caller branching on SDK ints:
 *
 *  - The widget can be offered by a system dialog from Android 8 onwards, so
 *    almost every phone that runs Relay can be handed a one-tap "add it".
 *  - The tile cannot be offered until Android 13. Below that the only honest
 *    thing is to say where the button is, which the caller does with a string
 *    rather than a dialog.
 *
 * Callers ask [canOfferTile] / [canOfferWidget] first and show instructions
 * instead when the answer is no, so the UI never presents a button that does
 * nothing.
 */
object HomeScreenExtras {

    /** True when the system will show its own "add this tile?" dialog. */
    fun canOfferTile(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Asks the system to offer the tile. No-op below Android 13 — guarded
     * rather than throwing, because the caller has already checked and a second
     * failure mode here would only be reachable by a bug.
     */
    fun offerTile(context: Context) {
        if (!canOfferTile()) return
        runCatching {
            val manager = context.getSystemService(android.app.StatusBarManager::class.java) ?: return
            manager.requestAddTileService(
                ComponentName(context, SharingTileService::class.java),
                context.getString(io.relay.app.R.string.app_name),
                android.graphics.drawable.Icon.createWithResource(
                    context, io.relay.app.R.drawable.ic_shortcut_start,
                ),
                {},
                {},
            )
        }
    }

    /**
     * True when the launcher will show its own "add this widget?" dialog.
     * Some launchers report false — Android is explicit that this is optional —
     * and then the honest answer is instructions, not a dead button.
     */
    fun canOfferWidget(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            AppWidgetManager.getInstance(context)?.isRequestPinAppWidgetSupported == true
        }.getOrDefault(false)
    }

    fun offerWidget(context: Context) {
        if (!canOfferWidget(context)) return
        runCatching {
            AppWidgetManager.getInstance(context)?.requestPinAppWidget(
                ComponentName(context, SharingWidgetProvider::class.java),
                null,
                null,
            )
        }
    }

    /** Whether a widget is already on a home screen, so the offer can be hidden. */
    fun widgetPlaced(context: Context): Boolean = runCatching {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        manager.getAppWidgetIds(ComponentName(context, SharingWidgetProvider::class.java)).isNotEmpty()
    }.getOrDefault(false)

    /**
     * Whether the tile has been added. Only knowable from Android 13 up, where
     * the system tracks it; below that this returns false and the step stays on
     * offer, which is the safe direction — telling someone to add a tile they
     * already have costs a moment, hiding one they do not have costs the
     * feature.
     */
    @Suppress("UNUSED_PARAMETER")
    fun tilePlaced(context: Context): Boolean = false
}
