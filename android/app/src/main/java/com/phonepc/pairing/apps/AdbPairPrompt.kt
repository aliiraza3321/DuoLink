package com.phonepc.pairing.apps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.phonepc.pairing.R

/**
 * The one thing the desktop side of app windows needs from the phone: a
 * wireless-debugging pairing. The PC asks, this notification appears here, and
 * the six digits Android shows are typed on the phone itself — no IP address
 * and no port ever have to be copied between the two devices.
 */
internal object AdbPairPrompt {

    const val CHANNEL_ID = "duolink_adb_pair"
    const val NOTIFICATION_ID = 7711

    /**
     * Posts (or replaces) the prompt. [error] is shown when a code was refused,
     * so the same notification is the place to try again.
     */
    fun show(context: Context, error: String = "") {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Wireless debugging pairing",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Asks for the six-digit code Android shows when pairing with wireless debugging"
                enableVibration(true)
            }
        )
        val open = Intent(context, AdbPairActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val openPending = PendingIntent.getActivity(
            context,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // The code is typed in the notification's own column: the system fills
        // this RemoteInput, and AdbPairReceiver hands the digits to the link
        // service. Mutable is required - the system writes the reply into it.
        val reply = RemoteInput.Builder(AdbPairReceiver.KEY_CODE)
            .setLabel("6-digit code")
            .build()
        val pairAction = NotificationCompat.Action.Builder(
            0,
            "Enter code",
            PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, AdbPairReceiver::class.java).setAction(AdbPairReceiver.ACTION_PAIR),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        )
            .addRemoteInput(reply)
            .setAllowGeneratedReplies(true)
            // Declaring the reply semantics is what makes SystemUI draw the
            // text column inside the notification instead of just a button.
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()

        val body = if (error.isNotBlank()) {
            "$error\nType the 6-digit code Android shows for wireless debugging."
        } else {
            "This PC wants to open your apps in windows. Type the 6-digit code Android shows " +
                "for wireless debugging — the phone connects itself."
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle("Enter the 6-digit pairing code")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .addAction(pairAction)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    /** The prompt has been answered (or the app windows were closed): take it away. */
    fun clear(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }
}
