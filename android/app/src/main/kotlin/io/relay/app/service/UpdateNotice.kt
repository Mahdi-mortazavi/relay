package io.relay.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.relay.app.MainActivity
import io.relay.app.R

/**
 * Tells someone a new version exists, without needing them to open the app.
 *
 * The in-app banner only reaches a person who was already opening Relay, which
 * is precisely the person least likely to be stuck on an old build. Someone who
 * set sharing up once and now starts it from the tile would never see it.
 *
 * Its own low-importance channel, so it can be silenced without silencing the
 * sharing notification the foreground service depends on — and so a courtesy
 * never buzzes a phone.
 */
object UpdateNotice {

    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 2

    /**
     * Shows the notice, or does nothing if the person has not granted
     * notifications. Never throws: this is a courtesy on a background path.
     */
    fun show(context: Context, version: String) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.update_channel),
                        // LOW: it appears in the shade and never makes a sound.
                        // An update is worth knowing about, not worth being
                        // interrupted for.
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }

            // Opens the app rather than starting the download straight from the
            // notification. Downloading is tens of megabytes and Android will
            // ask about installing anyway, so the honest place for both is a
            // screen the person is looking at.
            //
            // Built inline, like the sharing notification's, so the component
            // is visibly explicit at the call: an analyser that loses track of
            // it through a local variable reads this as an implicit intent
            // handed to an unknown app, and it is not wrong to be strict about
            // that -- a PendingIntent is a capability someone else can hold.
            val pending = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shortcut_start)
                .setContentTitle(context.getString(R.string.update_available, version))
                .setContentText(context.getString(R.string.update_notice_body))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    /** Clears it once the update has been taken. */
    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }
}
