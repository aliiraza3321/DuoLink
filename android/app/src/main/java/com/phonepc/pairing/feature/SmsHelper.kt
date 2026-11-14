package com.phonepc.pairing.feature

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.phonepc.pairing.network.Protocol
import com.phonepc.pairing.service.PairingForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * V2: SMS / Messages.
 *
 * Reads threads + conversation history and sends SMS from the PC. Delivery
 * outcomes come back asynchronously through [SmsSentReceiver], which forwards
 * SMS_SENT to the PC so its UI can flip "Sending…" into "Sent" or "Failed".
 *
 * Permission failures are always reported in the payload `error` field so the
 * PC shows "grant SMS permission" instead of an empty list.
 */
object SmsHelper {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Pending SMS sends keyed by a synthetic id -> address (for SMS_SENT reports). */
    private val pendingSends = java.util.Collections.synchronizedMap(mutableMapOf<Int, String>())

    internal fun sendResult(address: String, ok: Boolean, error: String?) {
        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(
            Protocol.TYPE_SMS_SENT,
            JsonObject().apply {
                addProperty("ok", ok)
                addProperty("address", address)
                if (!ok) addProperty("error", error ?: "Carrier rejected the message")
            }
        )
    }

    internal fun consumePending(part: Int): String? = pendingSends.remove(part)

    private fun send(type: String, payload: JsonObject): Boolean =
        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(type, payload) ?: false

    private fun hasReadSms(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private fun hasSendSms(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun deniedError(what: String): String =
        "$what permission not granted. On the phone: Settings → Apps → DuoLink → Permissions → SMS."

    // ================= THREADS =================

    fun sendThreads(context: Context, limit: Int) {
        scope.launch {
            val out = JsonArray()
            var error: String? = null

            if (!hasReadSms(context)) {
                error = deniedError("Read SMS")
            } else {
                try {
                    // One row per thread: latest message, grouped by address
                    val projection = arrayOf(
                        Telephony.Sms._ID,
                        Telephony.Sms.ADDRESS,
                        Telephony.Sms.BODY,
                        Telephony.Sms.DATE,
                        Telephony.Sms.READ,
                        Telephony.Sms.TYPE
                    )
                    val seen = LinkedHashMap<String, JsonObject>()
                    context.contentResolver.query(
                        Telephony.Sms.CONTENT_URI, projection, null, null,
                        "${Telephony.Sms.DATE} DESC LIMIT ${limit.coerceIn(1, 1000)}"
                    )?.use { c ->
                        val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                        val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                        val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)
                        val readIdx = c.getColumnIndex(Telephony.Sms.READ)
                        val typeIdx = c.getColumnIndex(Telephony.Sms.TYPE)

                        // Track counts/unread per address across the scanned window
                        while (c.moveToNext()) {
                            val addr = if (addrIdx >= 0) c.getString(addrIdx) ?: continue else continue
                            val body = if (bodyIdx >= 0) c.getString(bodyIdx) ?: "" else ""
                            val date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                            val read = if (readIdx >= 0) c.getInt(readIdx) == 1 else true
                            val type = if (typeIdx >= 0) c.getInt(typeIdx) else 0
                            val isIncoming = type == Telephony.Sms.MESSAGE_TYPE_INBOX

                            val entry = seen[addr]
                            if (entry == null) {
                                seen[addr] = JsonObject().apply {
                                    addProperty("address", addr)
                                    addProperty("snippet", body.take(160))
                                    addProperty("lastDate", date)
                                    addProperty("count", 1)
                                    addProperty("unread", if (isIncoming && !read) 1 else 0)
                                }
                            } else {
                                entry.addProperty("count", entry.get("count").asInt + 1)
                                if (isIncoming && !read)
                                    entry.addProperty("unread", entry.get("unread").asInt + 1)
                                // keep newest snippet/date (cursor is DESC ordered)
                            }
                        }
                    }
                    seen.values.forEach { out.add(it) }
                } catch (se: SecurityException) {
                    error = deniedError("Read SMS")
                } catch (e: Exception) {
                    error = "Could not read conversations: ${e.message}"
                }
            }

            send(Protocol.TYPE_SMS_RESP, JsonObject().apply {
                addProperty("op", "threads")
                add("threads", out)
                error?.let { addProperty("error", it) }
            })
        }
    }

    // ================= MESSAGES FOR ONE ADDRESS =================

    fun sendMessages(context: Context, address: String, limit: Int) {
        scope.launch {
            val out = JsonArray()
            var error: String? = null

            if (!hasReadSms(context)) {
                error = deniedError("Read SMS")
            } else if (address.isBlank()) {
                error = "Missing conversation address"
            } else {
                try {
                    val projection = arrayOf(
                        Telephony.Sms._ID,
                        Telephony.Sms.ADDRESS,
                        Telephony.Sms.BODY,
                        Telephony.Sms.DATE,
                        Telephony.Sms.TYPE
                    )
                    val selection = "${Telephony.Sms.ADDRESS} = ?"
                    context.contentResolver.query(
                        Telephony.Sms.CONTENT_URI, projection, selection, arrayOf(address),
                        "${Telephony.Sms.DATE} ASC LIMIT ${limit.coerceIn(1, 1000)}"
                    )?.use { c ->
                        val idIdx = c.getColumnIndex(Telephony.Sms._ID)
                        val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                        val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                        val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)
                        val typeIdx = c.getColumnIndex(Telephony.Sms.TYPE)
                        while (c.moveToNext()) {
                            val type = if (typeIdx >= 0) c.getInt(typeIdx) else 0
                            out.add(JsonObject().apply {
                                addProperty("id", if (idIdx >= 0) c.getLong(idIdx).toString() else out.size().toString())
                                addProperty("address", if (addrIdx >= 0) c.getString(addrIdx) ?: address else address)
                                addProperty("body", if (bodyIdx >= 0) c.getString(bodyIdx) ?: "" else "")
                                addProperty("date", if (dateIdx >= 0) c.getLong(dateIdx) else 0L)
                                addProperty(
                                    "type",
                                    if (type == Telephony.Sms.MESSAGE_TYPE_OUTBOX ||
                                        type == Telephony.Sms.MESSAGE_TYPE_SENT ||
                                        type == Telephony.Sms.MESSAGE_TYPE_FAILED
                                    ) "outgoing" else "incoming"
                                )
                            })
                        }
                    }
                } catch (se: SecurityException) {
                    error = deniedError("Read SMS")
                } catch (e: Exception) {
                    error = "Could not read conversation: ${e.message}"
                }
            }

            send(Protocol.TYPE_SMS_RESP, JsonObject().apply {
                addProperty("op", "messages")
                addProperty("address", address)
                add("messages", out)
                error?.let { addProperty("error", it) }
            })
        }
    }

    // ================= DELETE A MESSAGE (from the PC) =================

    /**
     * Removes one SMS row on the PC's behalf.
     *
     * The SMS provider only lets an app write when it holds WRITE_SMS (or is
     * the default SMS app), so a refusal is not a bug we can work around - it
     * gets reported verbatim with the exact setting to change, instead of the
     * message silently reappearing after a refresh.
     */
    fun deleteSms(context: Context, id: String) {
        fun reply(ok: Boolean, error: String?) {
            send(Protocol.TYPE_SMS_RESP, JsonObject().apply {
                addProperty("op", "delete")
                addProperty("id", id)
                addProperty("ok", ok)
                error?.let { addProperty("error", it) }
            })
        }

        if (id.isBlank()) {
            reply(false, "Missing message id")
            return
        }
        if (!hasReadSms(context)) {
            reply(false, deniedError("Read SMS"))
            return
        }

        scope.launch {
            try {
                val removed = context.contentResolver.delete(
                    Telephony.Sms.CONTENT_URI,
                    "${Telephony.Sms._ID} = ?",
                    arrayOf(id)
                )
                if (removed > 0) reply(true, null)
                else reply(false, "That message is no longer on the phone")
            } catch (se: SecurityException) {
                reply(
                    false,
                    "Android blocked SMS deletion. On the phone: Settings → Apps → DuoLink → " +
                        "Permissions → SMS → Allow, or set DuoLink as the default SMS app."
                )
            } catch (e: Exception) {
                reply(false, "Could not delete: ${e.message}")
            }
        }
    }

    // ================= SEND =================

    fun sendSms(context: Context, address: String, text: String) {
        val cleanAddr = address.replace(Regex("[^0-9+*#]"), "").take(32)
        if (cleanAddr.isBlank() || text.isBlank()) {
            sendResult(address, false, "Invalid number or empty message")
            return
        }
        if (!hasSendSms(context)) {
            sendResult(address, false, deniedError("Send SMS"))
            return
        }

        try {
            val sms = if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }

            val parts = sms.divideMessage(text)
            if (parts.size <= 1) {
                val pi = buildSentIntent(context, address, 0)
                sms.sendTextMessage(cleanAddr, null, text, pi, null)
                pendingSends[0] = address
            } else {
                val sentIntents = ArrayList<PendingIntent>()
                val partsList = ArrayList<String>()
                for (i in parts.indices) {
                    sentIntents.add(buildSentIntent(context, address, i))
                    partsList.add(parts[i])
                    pendingSends[i] = address
                }
                sms.sendMultipartTextMessage(cleanAddr, null, partsList, sentIntents, null)
            }
            // Outcome arrives asynchronously via SmsSentReceiver -> SMS_SENT
        } catch (se: SecurityException) {
            sendResult(address, false, deniedError("Send SMS"))
        } catch (e: Exception) {
            sendResult(address, false, e.message ?: "Could not send SMS")
        }
    }

    private fun buildSentIntent(context: Context, address: String, part: Int): PendingIntent {
        val intent = Intent(context, SmsSentReceiver::class.java).apply {
            putExtra("address", address)
            putExtra("part", part)
        }
        val req = (address.hashCode() and 0x7FFFFFFF) + part
        return PendingIntent.getBroadcast(
            context, req, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/**
 * Reports per-part SMS delivery outcomes back to the PC as SMS_SENT.
 * Registered in the manifest (exported=false); no permissions needed to receive.
 */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra("address") ?: return
        val part = intent.getIntExtra("part", 0)
        val code = resultCode

        // For single-part sends the map key is 0; only report on the final part
        // of a multipart message so the PC isn't told "sent" midway.
        val remaining = SmsHelper.consumePending(part)
        if (remaining == null && part != 0) return

        val ok = code == android.app.Activity.RESULT_OK
        val error = when (code) {
            android.app.Activity.RESULT_OK -> null
            SmsManager.RESULT_ERROR_NO_SERVICE -> "No mobile network"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "Mobile network is off"
            SmsManager.RESULT_ERROR_NULL_PDU -> "Carrier rejected the message"
            else -> "Delivery failed (code $code)"
        }
        SmsHelper.sendResult(address, ok, error)
    }
}
