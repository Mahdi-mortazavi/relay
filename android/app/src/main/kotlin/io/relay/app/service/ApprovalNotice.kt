package io.relay.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.relay.app.R

/**
 * Asks the person to allow a computer, when they are not looking at the app.
 *
 * The approval prompt was a dialog inside the app's own screen and nothing
 * else. That works for the person who is holding the phone and watching it, and
 * for nobody else -- and nobody else is the normal case: you press Connect on
 * the laptop, because that is where you are looking, while the phone sits on
 * the desk showing the launcher or nothing at all. Then the prompt appears on a
 * screen no one can see, times out, and the connection is refused.
 *
 * Verified on a device before this existed: with Relay in the background the
 * request timed out after twenty seconds having posted nothing whatsoever --
 * no dialog, no notification, no sound.
 *
 * So it is asked here too, with the answer available from the shade. High
 * importance on purpose: this is a question that expires, and one the person
 * has to answer for anything to work. It is also the only notification Relay
 * sends that is allowed to interrupt.
 */
object ApprovalNotice {

    private const val CHANNEL_ID = "approval"
    private const val NOTIFICATION_ID = 3

    /** Shows the request, or does nothing if notifications are not granted. */
    fun show(context: Context, address: String) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.approval_channel),
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shortcut_start)
                .setContentTitle(context.getString(R.string.approval_title))
                .setContentText(context.getString(R.string.approval_body, address))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                // Not dismissible by a swipe: a question that vanishes when
                // brushed past is one the person will be told they answered.
                .setOngoing(true)
                .setAutoCancel(false)
                .addAction(
                    0,
                    context.getString(R.string.approval_deny),
                    answer(context, SharingService.ACTION_DENY_CLIENT, address, 1),
                )
                .addAction(
                    0,
                    context.getString(R.string.approval_allow),
                    answer(context, SharingService.ACTION_ALLOW_CLIENT, address, 2),
                )
                .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    /** Clears it once the question has been answered, however it was answered. */
    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    /**
     * One tap, carrying the address it is about.
     *
     * The address travels in the intent rather than being read from whatever is
     * pending at the moment the tap lands: two computers can arrive together,
     * and answering the question you were shown must not answer a different one
     * that replaced it.
     *
     * Distinct request codes, or Android would hand the second action the first
     * one's intent and both buttons would mean the same thing.
     */
    private fun answer(context: Context, action: String, address: String, code: Int) =
        PendingIntent.getService(
            context,
            code,
            Intent(context, SharingService::class.java)
                .setAction(action)
                .putExtra(SharingService.EXTRA_CLIENT_ADDRESS, address),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
