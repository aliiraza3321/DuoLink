package com.phonepc.pairing.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

object Protocol {
    const val VERSION = 1

    // Phase 1: Pairing & Session Management
    const val TYPE_PAIR_REQUEST = "PAIR_REQUEST"
    const val TYPE_PAIR_CONFIRM_CODE = "PAIR_CONFIRM_CODE"
    const val TYPE_PAIR_RESULT = "PAIR_RESULT"
    const val TYPE_RECONNECT_CHALLENGE_REQUEST = "RECONNECT_CHALLENGE_REQUEST"
    const val TYPE_RECONNECT_CHALLENGE_RESPONSE = "RECONNECT_CHALLENGE_RESPONSE"
    const val TYPE_RECONNECT_RESULT = "RECONNECT_RESULT"
    const val TYPE_DISCONNECT = "DISCONNECT"
    const val TYPE_HEARTBEAT_PING = "HEARTBEAT_PING"
    const val TYPE_HEARTBEAT_PONG = "HEARTBEAT_PONG"

    // Phase 2: Chat, Files, Clipboard
    const val TYPE_CHAT_MESSAGE = "CHAT_MESSAGE"
    const val TYPE_CLIPBOARD_PUSH = "CLIPBOARD_PUSH"
    const val TYPE_FILE_META = "FILE_META"
    const val TYPE_FILE_CHUNK = "FILE_CHUNK"
    const val TYPE_FILE_ACK = "FILE_ACK"
    const val TYPE_FILE_CANCEL = "FILE_CANCEL"

    // Phase 2b: PC file browser. The phone asks, the PC replies with
    // PC_FS_RESULT tagged by requestId + op so replies can be matched.
    const val TYPE_PC_FS_LIST = "PC_FS_LIST"
    const val TYPE_PC_FS_MKDIR = "PC_FS_MKDIR"
    const val TYPE_PC_FS_RENAME = "PC_FS_RENAME"
    const val TYPE_PC_FS_DELETE = "PC_FS_DELETE"
    const val TYPE_PC_FS_DOWNLOAD = "PC_FS_DOWNLOAD"
    const val TYPE_PC_FS_COPY = "PC_FS_COPY"
    const val TYPE_PC_FS_MOVE = "PC_FS_MOVE"
    const val TYPE_PC_FS_SEARCH = "PC_FS_SEARCH"
    const val TYPE_PC_FS_RESULT = "PC_FS_RESULT"

    // Phase 3: Phone -> PC Input & Remote Media
    const val TYPE_PC_MOUSE_MOVE = "PC_MOUSE_MOVE"
    const val TYPE_PC_MOUSE_CLICK = "PC_MOUSE_CLICK"
    const val TYPE_PC_MOUSE_SCROLL = "PC_MOUSE_SCROLL"
    const val TYPE_PC_KEY_INPUT = "PC_KEY_INPUT"
    const val TYPE_PC_MEDIA_ACTION = "PC_MEDIA_ACTION"
    const val TYPE_PC_SYSTEM_ACTION = "PC_SYSTEM_ACTION"

    // Phase 4: PC -> Phone Control & Screen Mirror
    const val TYPE_PHONE_SCREEN_START = "PHONE_SCREEN_START"
    const val TYPE_PHONE_SCREEN_STOP = "PHONE_SCREEN_STOP"
    const val TYPE_PHONE_SCREEN_FRAME = "PHONE_SCREEN_FRAME"
    const val TYPE_PHONE_INPUT_GESTURE = "PHONE_INPUT_GESTURE"
    const val TYPE_PHONE_KEY_ACTION = "PHONE_KEY_ACTION"
    const val TYPE_PHONE_SCREEN_KEYFRAME = "PHONE_SCREEN_KEYFRAME"  // PC -> phone: force an IDR now
    // PC -> phone: { op: show|hide|append|delete|replace, text?, count? } —
    // "Type on Phone": popping the phone keyboard open and filling whatever
    // editable field it has focused, straight from the PC keyboard.
    const val TYPE_PHONE_TYPE_TEXT = "PHONE_TYPE_TEXT"
    const val TYPE_PHONE_POWER = "PHONE_POWER"              // PC -> phone: { action: LOCK|RESTART|SHUTDOWN }
    const val TYPE_PHONE_POWER_RESULT = "PHONE_POWER_RESULT" // phone -> PC: { ok, message }

    // Phase 5: Jarvis assistant
    const val TYPE_PC_COMMAND = "PC_COMMAND"
    const val TYPE_PC_COMMAND_RESULT = "PC_COMMAND_RESULT"
    const val TYPE_SYSTEM_STATS = "SYSTEM_STATS"
    const val TYPE_FIND_PHONE = "FIND_PHONE"
    const val TYPE_PHONE_CONTROL_STATUS = "PHONE_CONTROL_STATUS"

    // --- V2 FEATURES (additive on protocol v1) ---

    // Remote PC screen (phone views/controls the Windows desktop)
    const val TYPE_PC_SCREEN_START = "PC_SCREEN_START"
    const val TYPE_PC_SCREEN_STOP = "PC_SCREEN_STOP"
    const val TYPE_PC_SCREEN_FRAME = "PC_SCREEN_FRAME"
    const val TYPE_PC_MOUSE_MOVE_ABS = "PC_MOUSE_MOVE_ABS"
    const val TYPE_PC_KEY_COMBOS = "PC_KEY_COMBOS"
    const val TYPE_PC_MOUSE_DOWN = "PC_MOUSE_DOWN"
    const val TYPE_PC_MOUSE_UP = "PC_MOUSE_UP"

    // Phone webcam
    const val TYPE_WEBCAM_START = "WEBCAM_START"
    const val TYPE_WEBCAM_STOP = "WEBCAM_STOP"
    // Flip the torch on the camera that is already streaming, without
    // rebinding: restarting the camera to change the flash felt like a crash.
    const val TYPE_WEBCAM_TORCH = "WEBCAM_TORCH"
    const val TYPE_WEBCAM_FRAME = "WEBCAM_FRAME"
    const val TYPE_WEBCAM_STATUS = "WEBCAM_STATUS"

    // Calls
    const val TYPE_CALL_STATE = "CALL_STATE"
    const val TYPE_CALL_ACTION = "CALL_ACTION"
    const val TYPE_CALL_ACTION_RESULT = "CALL_ACTION_RESULT"
    const val TYPE_CALL_RECENTS_REQ = "CALL_RECENTS_REQ"
    const val TYPE_CALL_RECENTS = "CALL_RECENTS"

    // App catalog: the phone's launchable apps (label + icon), opened from the PC
    const val TYPE_APP_CATALOG_REQ = "APP_CATALOG_REQ"
    const val TYPE_APP_CATALOG = "APP_CATALOG"
    const val TYPE_APP_LAUNCH = "APP_LAUNCH"
    const val TYPE_APP_LAUNCH_RESULT = "APP_LAUNCH_RESULT"

    // App windows: one app on a display of its own, streamed to its own PC window
    const val TYPE_APP_WINDOW_FRAME = "APP_WINDOW_FRAME"
    const val TYPE_APP_WINDOW_CLOSED = "APP_WINDOW_CLOSED"
    const val TYPE_APP_WINDOW_INPUT = "APP_WINDOW_INPUT"
    const val TYPE_APP_WINDOW_CLOSE = "APP_WINDOW_CLOSE"
    const val TYPE_APP_WINDOW_READY = "APP_WINDOW_READY"

    // The phone's own wireless debugging (what app windows run on)
    const val TYPE_ADB_PAIR_REQ = "ADB_PAIR_REQ"
    // PC -> phone: show the pairing notification on the phone; the six digits
    // are typed there, so no address ever has to be copied to the PC.
    const val TYPE_ADB_PAIR_PROMPT = "ADB_PAIR_PROMPT"
    // PC -> phone: the wireless-debugging ports the PC saw over mDNS. Some
    // ROMs never resolve adbd's own advertisement from inside the phone, so
    // the PC (which sees it) relays the numbers instead of the phone guessing.
    const val TYPE_ADB_PORT_INFO = "ADB_PORT_INFO"
    const val TYPE_ADB_PAIR_RESULT = "ADB_PAIR_RESULT"
    const val TYPE_ADB_PAIR_STATE = "ADB_PAIR_STATE"

    // Notifications mirroring
    const val TYPE_NOTIF_POSTED = "NOTIF_POSTED"
    const val TYPE_NOTIF_REMOVED = "NOTIF_REMOVED"
    const val TYPE_NOTIF_DISMISS = "NOTIF_DISMISS"
    const val TYPE_NOTIF_REPLY = "NOTIF_REPLY"
    const val TYPE_NOTIF_LIST = "NOTIF_LIST"

    // SMS / Messages
    const val TYPE_SMS_REQ = "SMS_REQ"
    const val TYPE_SMS_RESP = "SMS_RESP"
    const val TYPE_SMS_SEND = "SMS_SEND"
    const val TYPE_SMS_SENT = "SMS_SENT"

    // Phone media browser
    const val TYPE_MEDIA_REQ = "MEDIA_REQ"
    const val TYPE_MEDIA_LIST = "MEDIA_LIST"
    const val TYPE_MEDIA_THUMB = "MEDIA_THUMB"
    const val TYPE_MEDIA_RESULT = "MEDIA_RESULT"
    // phone -> PC: share-target list for PC-side sharing (the phone never shows
    // its share sheet for an action started on the PC).
    const val TYPE_MEDIA_SHARE_TARGETS = "MEDIA_SHARE_TARGETS"
    const val TYPE_CALL_LOG_DELETE = "CALL_LOG_DELETE"
    const val TYPE_CALL_LOG_DELETE_RESULT = "CALL_LOG_DELETE_RESULT"

    // Phone volume control
    const val TYPE_PHONE_VOLUME = "PHONE_VOLUME"
    const val TYPE_PHONE_VOLUME_STATE = "PHONE_VOLUME_STATE"

    // Phone battery
    const val TYPE_PHONE_BATTERY = "PHONE_BATTERY"

    // Diagnostics
    const val TYPE_DIAG_REQ = "DIAG_REQ"
    const val TYPE_DIAG_RESP = "DIAG_RESP"

    private val gson = Gson()

    // ===================== Binary video frames =====================
    // Mirror of desktop/src/protocol.js BinaryFrame. Video frames use this
    // compact binary channel instead of base64-in-JSON: base64 inflates every
    // frame by ~33% and Gson/JSONObject parsing of a 150KB escaped string is
    // measurable main-thread work at 30-60 fps. Little-endian layout:
    //   [0]     u8  kind   (1 = PC screen, 2 = webcam, 3 = phone audio PCM)
    //   [1..4]  u32 seq
    //   [5..6]  u16 width   (audio: sample rate)
    //   [7..8]  u16 height  (audio: channel count)
    //   [9..]   raw JPEG bytes, or raw PCM16LE for kind 3
    const val BIN_KIND_PC_SCREEN = 1
    const val BIN_KIND_WEBCAM = 2
    const val BIN_KIND_AUDIO = 3
    // Kind 4: the phone's own screen mirror (phone -> PC). Previously this
    // rode base64-in-JSON, which inflated every frame ~33% and queued behind
    // Gson parsing - audio chunks shared the socket and arrived late. Now the
    // same 9-byte header carries raw JPEG, and the sender drops frames when
    // the socket backs up instead of queueing stale ones.
    const val BIN_KIND_PHONE_SCREEN = 4
    // Kind 5: H.264 codec config (SPS/PPS) for the mirror. Kind 6: one
    // encoded frame in Annex-B. The hardware encoder replaces per-frame
    // JPEG so native resolution and 60 fps fit the same Wi-Fi link.
    const val BIN_KIND_PHONE_H264_CONFIG = 5
    const val BIN_KIND_PHONE_H264 = 6
    const val BIN_HEADER_BYTES = 9

    /** Encodes header + JPEG into a single wire buffer (no copies of the JPEG). */
    fun encodeBinaryFrame(kind: Int, seq: Long, width: Int, height: Int, jpeg: ByteArray): ByteArray {
        val out = ByteArray(BIN_HEADER_BYTES + jpeg.size)
        out[0] = kind.toByte()
        writeU32LE(out, 1, seq)
        writeU16LE(out, 5, width)
        writeU16LE(out, 7, height)
        // System.arraycopy into the payload region directly after the header.
        System.arraycopy(jpeg, 0, out, BIN_HEADER_BYTES, jpeg.size)
        return out
    }

    /** Parsed binary frame; [jpeg] shares the input array (no copy). */
    data class BinFrame(
        val kind: Int,
        val seq: Long,
        val width: Int,
        val height: Int,
        val jpeg: ByteArray
    )

    /**
     * Parses a binary frame. Returns null when the buffer is shorter than the
     * header or the declared JPEG length overruns the buffer - callers drop
     * malformed frames rather than throwing.
     */
    fun decodeBinaryFrame(buf: ByteArray): BinFrame? {
        if (buf.size < BIN_HEADER_BYTES) return null
        val kind = buf[0].toInt() and 0xFF
        val seq = readU32LE(buf, 1)
        val width = readU16LE(buf, 5)
        val height = readU16LE(buf, 7)
        val jpegLen = buf.size - BIN_HEADER_BYTES
        if (jpegLen <= 0) return null
        // ByteBuf.slice-equivalent: offset into the same backing array.
        return BinFrame(kind, seq, width, height, buf.copyOfRange(BIN_HEADER_BYTES, buf.size))
    }

    private fun writeU16LE(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v and 0xFF).toByte()
        dst[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun writeU32LE(dst: ByteArray, off: Int, v: Long) {
        dst[off] = (v and 0xFF).toByte()
        dst[off + 1] = ((v ushr 8) and 0xFF).toByte()
        dst[off + 2] = ((v ushr 16) and 0xFF).toByte()
        dst[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun readU16LE(src: ByteArray, off: Int): Int =
        (src[off].toInt() and 0xFF) or ((src[off + 1].toInt() and 0xFF) shl 8)

    private fun readU32LE(src: ByteArray, off: Int): Long =
        (src[off].toLong() and 0xFF) or
            ((src[off + 1].toLong() and 0xFF) shl 8) or
            ((src[off + 2].toLong() and 0xFF) shl 16) or
            ((src[off + 3].toLong() and 0xFF) shl 24)

    data class Envelope(
        val v: Int = VERSION,
        val type: String,
        val id: String = UUID.randomUUID().toString(),
        val ts: Long = System.currentTimeMillis(),
        val payload: JsonObject
    )

    fun createEnvelope(type: String, payload: JsonObject): String {
        val env = Envelope(
            v = VERSION,
            type = type,
            id = UUID.randomUUID().toString(),
            ts = System.currentTimeMillis(),
            payload = payload
        )
        return gson.toJson(env)
    }

    fun parseEnvelope(json: String): Envelope? {
        return try {
            val element = JsonParser.parseString(json).asJsonObject
            val v = element.get("v")?.asInt ?: return null
            if (v != VERSION) return null
            val type = element.get("type")?.asString ?: return null
            val id = element.get("id")?.asString ?: return null
            val ts = element.get("ts")?.asLong ?: return null
            val payload = element.getAsJsonObject("payload") ?: JsonObject()
            Envelope(v, type, id, ts, payload)
        } catch (e: Exception) {
            null
        }
    }
}
