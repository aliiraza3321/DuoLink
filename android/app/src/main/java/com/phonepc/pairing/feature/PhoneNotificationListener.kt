package com.phonepc.pairing.feature

import android.app.Notification
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.phonepc.pairing.network.Protocol
import com.phonepc.pairing.service.PairingForegroundService

/**
 * V2: Notification mirroring.
 *
 * Pushes every posted/removed notification to the PC (NOTIF_POSTED /
 * NOTIF_REMOVED), answers NOTIF_LIST with the active set, and performs
 * dismiss / inline-reply requested from the PC.
 *
 * This is a bound system service: it only runs once the user grants
 * "Notification access" in Android Settings (the app deep-links them there).
 * [isRunning] is surfaced in the PC's diagnostics pane so a missing grant is
 * visible rather than a silent empty list.
 */
class PhoneNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: PhoneNotificationListener? = null
            private set

        val isRunning: Boolean get() = instance != null

        /** key -> last posted payload, so NOTIF_LIST can replay the active set. */
        private val active = java.util.Collections.synchronizedMap(LinkedHashMap<String, JsonObject>())

        /** Own + ongoing notifications suppressed this session (spec 6.2.5: "N DuoLink alerts hidden"). */
        @Volatile
        var hiddenCount: Int = 0
            private set

        private fun bumpHidden() {
            hiddenCount++
        }

        /** Settings intent for the notification-access grant (deep link target). */
        fun settingsIntent(): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // The link service can be killed out from under this process (XOS
        // "Phone Master" / Game-Mode and LMK both do it — typically the moment
        // a game launches) and nothing else brings it back: the phone then sits
        // "disconnected" until the user manually opens DuoLink. That is the
        // "disconnects while I play" bug. This listener is a system-bound
        // service living in the same (effectively persistent) process, so it
        // survives those kills — watch for the link service and restart it
        // whenever it is gone while the phone is still paired.
        mainHandler.postDelayed(linkWatchdog, 5_000L)
    }

    override fun onDestroy() {
        instance = null
        mainHandler.removeCallbacks(linkWatchdog)
        super.onDestroy()
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val linkWatchdog = object : Runnable {
        override fun run() {
            try { ensureLinkAlive() } catch (_: Throwable) {}
            mainHandler.postDelayed(this, 20_000L)
        }
    }

    /** Restart the pairing link service if it died while the phone is still paired. */
    private fun ensureLinkAlive() {
        if (PairingForegroundService.activeServiceInstance != null) return
        // A manual disconnect clears credentials — a cleared pair stays off.
        val creds = try {
            com.phonepc.pairing.pairing.KeystoreStorage(this).getCredentials()
        } catch (_: Throwable) { null } ?: return
        try {
            val i = Intent(this, PairingForegroundService::class.java)
                .setAction(PairingForegroundService.ACTION_START_LINK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
            android.util.Log.w(
                "PairingLink",
                "Watchdog: link service was gone - restarting (paired to ${creds.pcDeviceName})"
            )
        } catch (_: Throwable) {}
    }

    private fun send(type: String, payload: JsonObject): Boolean =
        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(type, payload) ?: false

    private fun packageNameToLabel(sbn: StatusBarNotification): String {
        return try {
            val pm = packageManager
            val app = pm.getApplicationInfo(sbn.packageName, 0)
            pm.getApplicationLabel(app).toString()
        } catch (_: Exception) {
            sbn.packageName
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val n = sbn.notification ?: return
        val extras = n.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        // Bug #5: never mirror DuoLink's own notifications or ongoing ones
        // (the persistent "Link Active" foreground-service alert), and don't
        // replay them in NOTIF_LIST either. Counted so the PC can show
        // "N DuoLink alerts hidden".
        if (sbn.packageName == packageName ||
            (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        ) {
            bumpHidden()
            return
        }

        val key = sbn.key ?: return
        val entry = JsonObject().apply {
            addProperty("key", key)
            addProperty("appName", packageNameToLabel(sbn))
            addProperty("packageName", sbn.packageName)
            addProperty("title", title)
            addProperty("text", (bigText ?: text ?: subText ?: "").toString())
            addProperty("postTime", sbn.postTime)
            addProperty("canReply", n.actions?.any { it.remoteInputs?.isNotEmpty() == true } ?: false)
            addProperty("isOngoing", (n.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        }
        active[key] = entry
        // Bound the cache so a chatty app can't grow it unbounded
        while (active.size > 200) {
            val first = active.keys.firstOrNull() ?: break
            active.remove(first)
        }
        send(Protocol.TYPE_NOTIF_POSTED, entry)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        active.remove(key)
        send(Protocol.TYPE_NOTIF_REMOVED, JsonObject().apply {
            addProperty("key", key)
        })
    }

    /** Called when the PC asks for a full snapshot (NOTIF_LIST). */
    fun snapshot(): List<JsonObject> = synchronized(active) { active.values.toList() }

    /** Dismiss a notification on behalf of the PC. Returns null on success or an error string. */
    fun dismiss(key: String): String? {
        return try {
            cancelNotification(key)
            null
        } catch (e: Exception) {
            "Could not dismiss: ${e.message}"
        }
    }

    /** Inline-reply a notification on behalf of the PC. Returns null on success or an error string. */
    fun reply(key: String, text: String): String? {
        val sbn = try {
            getActiveNotifications().firstOrNull { it.key == key }
        } catch (_: Exception) { null } ?: return "Notification no longer exists"

        val n = sbn.notification
        val actions = n.actions ?: return "This notification can't be replied to"
        val action = actions.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            ?: return "This notification can't be replied to"

        return try {
            val results = android.os.Bundle()
            for (ri in action.remoteInputs) {
                if (ri.resultKey.isNotBlank()) results.putCharSequence(ri.resultKey, text)
            }
            val fillIn = Intent()
            android.app.RemoteInput.addResultsToIntent(action.remoteInputs, fillIn, results)
            action.actionIntent.send(applicationContext, 0, fillIn)
            null
        } catch (e: Exception) {
            "Reply failed: ${e.message}"
        }
    }

}
