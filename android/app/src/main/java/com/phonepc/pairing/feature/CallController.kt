package com.phonepc.pairing.feature

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.phonepc.pairing.network.Protocol
import com.phonepc.pairing.service.PairingForegroundService
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executor

/**
 * V2: Phone calls.
 *
 *  - Pushes live call state (RINGING / OFFHOOK / IDLE) to the PC so it can show
 *    an incoming-call card and answer/reject/end remotely.
 *  - Reads the call log for the "Recents" list.
 *
 * Every failure path reports WHY (permission missing / telephony unavailable)
 * back to the PC instead of failing silently.
 */
object CallController {

    @Volatile private var listener: TelephonyCallback? = null
    @Volatile private var listening = false

    val isListening: Boolean get() = listening

    private fun send(type: String, payload: JsonObject): Boolean =
        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(type, payload) ?: false

    // ================= DELETE A CALL-LOG ROW (from the PC) =================

    /**
     * Removes one entry from the phone's call log on the PC's behalf.
     *
     * Deleting needs WRITE_CALL_LOG, which is a separate dangerous permission
     * from the READ one this feature already holds - so if it is missing we ask
     * for it through the activity (PermissionGate) and only report failure once
     * the user has actually answered the dialog.
     */
    fun deleteCall(context: Context, id: String) {
        fun reply(ok: Boolean, error: String?) {
            send(Protocol.TYPE_CALL_LOG_DELETE_RESULT, JsonObject().apply {
                addProperty("id", id)
                addProperty("ok", ok)
                error?.let { addProperty("error", it) }
            })
        }

        if (id.isBlank()) {
            reply(false, "Missing call log id")
            return
        }
        if (hasPermission(context, Manifest.permission.WRITE_CALL_LOG)) {
            deleteNow(context, id, ::reply)
            return
        }

        com.phonepc.pairing.PermissionGate.request(arrayOf(Manifest.permission.WRITE_CALL_LOG)) { res ->
            if (res[Manifest.permission.WRITE_CALL_LOG] == true) {
                deleteNow(context, id, ::reply)
            } else {
                reply(
                    false,
                    "Call log write permission declined. On the phone: Settings → Apps → DuoLink → " +
                        "Permissions → Call logs → Allow."
                )
            }
        }
    }

    private fun deleteNow(context: Context, id: String, reply: (Boolean, String?) -> Unit) {
        try {
            val removed = context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls._ID} = ?",
                arrayOf(id)
            )
            if (removed > 0) reply(true, null)
            else reply(false, "That entry is no longer on the phone")
        } catch (se: SecurityException) {
            reply(false, "Call log permission revoked on the phone")
        } catch (e: Exception) {
            reply(false, "Could not delete: ${e.message}")
        }
    }

    private fun hasPermission(context: Context, perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    // ================= LIVE STATE =================

    @Synchronized
    fun startListening(context: Context) {
        if (listening) return
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        if (!hasPermission(context, Manifest.permission.READ_PHONE_STATE)) {
            // Tell the PC why call state won't stream rather than going quiet.
            send(Protocol.TYPE_CALL_STATE, JsonObject().apply {
                addProperty("state", "IDLE")
                addProperty("error", "READ_PHONE_STATE not granted on phone")
            })
            return
        }
        try {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    val mapped = when (state) {
                        TelephonyManager.CALL_STATE_RINGING -> "RINGING"
                        TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
                        else -> "IDLE"
                    }
                    val payload = JsonObject().apply {
                        addProperty("state", mapped)
                        addProperty("number", "")
                        addProperty("name", "")
                        addProperty("direction", "")
                    }
                    send(Protocol.TYPE_CALL_STATE, payload)
                }
            }
            val executor: Executor = ContextCompat.getMainExecutor(context)
            tm.registerTelephonyCallback(executor, cb)
            listener = cb
            listening = true
        } catch (_: Exception) {
            listening = false
        }
    }

    @Synchronized
    fun stopListening(context: Context) {
        val cb = listener ?: return
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.unregisterTelephonyCallback(cb)
        } catch (_: Exception) { /* already unregistered */ }
        listener = null
        listening = false
    }

    // ================= ACTIONS =================

    /** Returns null on success, or a human-readable error string. */
    fun handleAction(context: Context, action: String, number: String = ""): String? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return "Telephony unavailable on this device"
        return try {
            when (action.uppercase()) {
                "DIAL" -> {
                    // Place a call from the PC's dial field (digits + *# only).
                    val clean = number.filter { it.isDigit() || it in "+*#," }.take(32)
                    if (clean.isEmpty()) return "Enter a number to call"
                    if (!hasPermission(context, Manifest.permission.CALL_PHONE))
                        return "Grant 'Phone call' permission to DuoLink on the phone"
                    // ACTION_CALL from a service is refused on Android 10+
                    // (background activity starts), which surfaced as a
                    // permission error even with the permission granted.
                    // TelecomManager.placeCall needs the same permission and
                    // works from the service, so try it first.
                    try {
                        val telecom = context.getSystemService(Context.TELECOM_SERVICE)
                            as? android.telecom.TelecomManager
                        if (telecom != null) {
                            telecom.placeCall(Uri.parse("tel:$clean"), android.os.Bundle())
                            return null
                        }
                    } catch (e: SecurityException) {
                        return "Android blocked this call: allow 'Phone' + 'Call management' for DuoLink"
                    } catch (_: Exception) {
                        // Fall through to the intent path below.
                    }
                    val dial = Intent(Intent.ACTION_CALL, Uri.parse("tel:$clean")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(dial)
                        null
                    } catch (e: Exception) {
                        "Could not start the call: ${e.message}"
                    }
                }
                "ANSWER" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        if (!hasPermission(context, Manifest.permission.ANSWER_PHONE_CALLS))
                            return "Grant 'Answer calls' permission to DuoLink on the phone"
                        val tcm = context.getSystemService(Context.TELECOM_SERVICE)
                            as? android.telecom.TelecomManager
                            ?: return "Telephony dialer unavailable on this device"
                        tcm.acceptRingingCall()
                        null
                    } else {
                        "Answering calls requires Android 8.0+"
                    }
                }
                "REJECT", "END" -> {
                    if (!hasPermission(context, Manifest.permission.READ_PHONE_STATE))
                        return "Grant 'Phone' permission to DuoLink on the phone"
                    // endCall() works for both rejecting and hanging up on O+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val tcm = context.getSystemService(Context.TELECOM_SERVICE)
                            as? android.telecom.TelecomManager
                            ?: return "Telephony dialer unavailable on this device"
                        tcm.endCall()
                        null
                    } else suppressEndCall(tm)
                }
                else -> "Unknown call action: $action"
            }
        } catch (se: SecurityException) {
            "Permission denied: ${se.message}"
        } catch (e: Exception) {
            e.message ?: "Call action failed"
        }
    }

    @Suppress("DEPRECATION")
    private fun suppressEndCall(tm: TelephonyManager): String? = try {
        val m = TelephonyManager::class.java.getMethod("endCall")
        m.invoke(tm)
        null
    } catch (_: Exception) {
        "Could not end the call on this device"
    }

    // ================= RECENTS =================

    fun sendRecents(context: Context, limit: Int) {
        val out = JsonArray()
        var error: String? = null

        if (!hasPermission(context, Manifest.permission.READ_CALL_LOG)) {
            error = "Call log permission not granted. On the phone: Settings → Apps → DuoLink → Permissions → Call logs."
        } else {
            try {
                val uri: Uri = CallLog.Calls.CONTENT_URI
                val projection = arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                )
                // NB: some OEM CallLog providers reject "LIMIT n" inside the
                // sortOrder (SQLite error: "Invalid token LIMIT"), so sort the
                // full log by date and cap the row count while reading instead.
                val max = limit.coerceIn(1, 500)
                context.contentResolver.query(
                    uri, projection, null, null,
                    "${CallLog.Calls.DATE} DESC"
                )?.use { c ->
                    while (out.size() < max && c.moveToNext()) out.add(readCallRow(c))
                }
            } catch (se: SecurityException) {
                error = "Call log permission revoked on the phone"
            } catch (e: Exception) {
                error = "Could not read call log: ${e.message}"
            }
        }

        send(Protocol.TYPE_CALL_RECENTS, JsonObject().apply {
            add("calls", out)
            error?.let { addProperty("error", it) }
        })
    }

    private fun readCallRow(c: Cursor): JsonObject {
        val idIdx = c.getColumnIndex(CallLog.Calls._ID)
        val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
        val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
        val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
        val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
        val durIdx = c.getColumnIndex(CallLog.Calls.DURATION)

        val type = if (typeIdx >= 0) c.getInt(typeIdx) else 0
        val typeName = when (type) {
            CallLog.Calls.MISSED_TYPE -> "missed"
            CallLog.Calls.OUTGOING_TYPE -> "outgoing"
            CallLog.Calls.INCOMING_TYPE -> "incoming"
            CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
            else -> "incoming"
        }
        val date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
        val dur = if (durIdx >= 0) c.getLong(durIdx) else 0L

        return JsonObject().apply {
            addProperty("id", if (idIdx >= 0) c.getLong(idIdx).toString() else java.util.UUID.randomUUID().toString())
            addProperty("name", if (nameIdx >= 0) c.getString(nameIdx) ?: "" else "")
            addProperty("number", if (numIdx >= 0) c.getString(numIdx) ?: "" else "")
            addProperty("type", typeName)
            addProperty("date", date)
            addProperty("dateLabel", DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(date)))
            addProperty("durationSec", dur)
        }
    }
}
