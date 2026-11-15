package com.phonepc.pairing.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ContentValues
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import com.phonepc.pairing.data.CommandResult
import com.phonepc.pairing.data.PcStats
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import java.io.File
import java.io.OutputStream
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.phonepc.pairing.MainActivity
import com.phonepc.pairing.control.PhoneAccessibilityService
import com.phonepc.pairing.control.ScreenCaptureService
import com.phonepc.pairing.feature.CallController
import com.phonepc.pairing.feature.MediaBrowser
import com.phonepc.pairing.feature.PhoneNotificationListener
import com.phonepc.pairing.feature.SmsHelper
import com.phonepc.pairing.feature.WebcamService
import com.phonepc.pairing.R
import com.phonepc.pairing.data.ConnectionState
import com.phonepc.pairing.data.SessionCredentials
import com.phonepc.pairing.network.Protocol
import com.phonepc.pairing.network.TlsPinningSocket
import com.phonepc.pairing.pairing.KeystoreStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One entry returned by the PC file browser. Declared at file scope so the
 * Compose screen can name the type directly (a class nested in a companion
 * object is not reachable through the outer class name in Kotlin).
 */
data class PcFsEntry(
    val name: String,
    val path: String,
    val dir: Boolean,
    val size: Long,
    val sizeLabel: String,
    val ext: String,
    val place: String,
    val modified: Long = 0L
)

data class PcFsResult(
    val op: String,
    val requestId: String,
    val ok: Boolean,
    val error: String,
    val path: String?,
    val parent: String?,
    val entries: List<PcFsEntry>,
    val places: Boolean,
    val fullAccess: Boolean = false,
    val copied: Int = 0,
    val moved: Int = 0,
    val failed: Int = 0,
    val truncated: Boolean = false
)

class PairingForegroundService : Service(), TlsPinningSocket.SocketEventListener {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var socket: TlsPinningSocket? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var keystoreStorage: KeystoreStorage
    private var reconnectAttempt = 0
    private var isManualDisconnect = false
    private var reconnectJob: Job? = null
    private var latencyJob: Job? = null

    companion object {
        const val CHANNEL_ID = "phonepc_link_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_LINK = "ACTION_START_LINK"
        const val ACTION_DISCONNECT = "ACTION_DISCONNECT"
        const val ACTION_SEND_CLIPBOARD = "ACTION_SEND_CLIPBOARD"
        const val EXTRA_START_SHARE = "EXTRA_START_SHARE"
        const val SHARE_NOTIFICATION_ID = 1002

        private val _pcStats = MutableStateFlow<PcStats?>(null)
        /** Latest CPU / RAM / uptime from the PC (null until the first update). */
        val pcStats: StateFlow<PcStats?> = _pcStats

        private val _linkLatency = MutableStateFlow(-1)
        /** Phone -> PC WebSocket round trip in ms for the Home Link strip; -1 = link down / not measured. */
        val linkLatency: StateFlow<Int> = _linkLatency

        @Volatile
        private var lastPingSentAt = 0L

        private val _commandResult = MutableStateFlow<CommandResult?>(null)
        val commandResult: StateFlow<CommandResult?> = _commandResult
        private var commandSeq = 0L

        private val _fileReceived = MutableStateFlow("")
        /** Latest file saved from the PC, e.g. "photo.jpg saved to Downloads/PhonePC". */
        val fileReceived: StateFlow<String> = _fileReceived

        // content:// URI of the file that just finished downloading from the
        // PC. PC Files uses it to open the file in the device's viewer right
        // after the transfer completes (tap a file -> it opens).
        private val _lastReceivedUri = MutableStateFlow<String?>(null)
        val lastReceivedUri: StateFlow<String?> = _lastReceivedUri
        fun clearLastReceivedUri() { _lastReceivedUri.value = null }

        private val _screenShareRequest = MutableStateFlow(false)
        val screenShareRequest: StateFlow<Boolean> = _screenShareRequest
        fun requestScreenShare() { _screenShareRequest.value = true }
        fun clearScreenShareRequest() { _screenShareRequest.value = false }

        private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
        val connectionState: StateFlow<ConnectionState> = _connectionState

        private val _incomingChatFlow = MutableStateFlow<String?>(null)
        val incomingChatFlow: StateFlow<String?> = _incomingChatFlow

        var activeServiceInstance: PairingForegroundService? = null
            private set

        /** True while a verified session socket is live (webcam gates frames on this). */
        fun hasActiveSession(): Boolean =
            activeServiceInstance != null && _connectionState.value == ConnectionState.CONNECTED

        // ---- Remote PC screen: remember intent so a dropped link auto-resumes ----
        @Volatile private var pcScreenWanted = false
        @Volatile private var pcScreenWantedPayload: JsonObject? = null

        private val _pcScreenFrame = MutableStateFlow<Frame?>(null)
        val pcScreenFrame: StateFlow<Frame?> = _pcScreenFrame
        /**
         * Latest PC-screen frame. Holds raw JPEG bytes (not base64): the bytes
         * arrive on the binary channel, so decoding them to a String and back
         * would be pure waste. This StateFlow is a single-slot mailbox - a new
         * frame overwrites the previous one, so a slow decode drops frames
         * instead of queueing them and replaying a stale backlog later.
         */
        data class Frame(val seq: Long, val jpeg: ByteArray, val width: Int, val height: Int)

        private val _pcScreenAck = MutableStateFlow<ScreenAck?>(null)
        val pcScreenAck: StateFlow<ScreenAck?> = _pcScreenAck
        data class ScreenAck(val seq: Long, val ok: Boolean, val error: String, val fps: Int, val quality: Int, val maxDimension: Int)
        private var pcAckSeq = 0L

        private val _pcScreenActive = MutableStateFlow(false)
        val pcScreenActive: StateFlow<Boolean> = _pcScreenActive

        /**
         * Fullscreen-mirror flag shared with the app shell. While the PC screen
         * owns the display, the top bar, the Trackpad/Screen segment switcher
         * and the bottom nav all stand down so nothing covers the stream —
         * only the PC picture is visible. Back gesture/button exits.
         */
        private val _pcScreenImmersive = MutableStateFlow(false)
        val pcScreenImmersive: StateFlow<Boolean> = _pcScreenImmersive
        fun setPcScreenImmersive(on: Boolean) { _pcScreenImmersive.value = on }

        // ---- PC file browser -------------------------------------------------
        // The phone never guesses a path: every folder it shows came back from
        // the PC, and the PC validates the path again on every request.
        private val _pcFsResult = MutableStateFlow<PcFsResult?>(null)
        /** Last PC_FS_RESULT (listing or action result); null until the first reply. */
        val pcFsResult: StateFlow<PcFsResult?> = _pcFsResult

        private var pcFsSeq = 0L

        /**
         * Sends a PC_FS_* request. Returns false when the link is down, so the
         * UI can show "connect first" instead of waiting forever.
         */
        fun requestPcFs(
            op: String,
            path: String?,
            name: String? = null,
            extra: JsonObject? = null
        ): Boolean {
            val svc = activeServiceInstance ?: return false
            val requestId = "pcfs-${System.currentTimeMillis()}-${++pcFsSeq}"
            val payload = JsonObject().apply {
                addProperty("requestId", requestId)
                if (path != null) addProperty("path", path)
                if (name != null) addProperty("name", name)
                // Search text, copy/move selections and the permanent-delete
                // flag are added by the caller; the PC validates everything.
                extra?.entrySet()?.forEach { (k, v) -> add(k, v) }
            }
            return svc.sendRawEnvelope(op, payload)
        }

        private fun parsePcFsResult(p: JsonObject): PcFsResult {
            val entries = ArrayList<PcFsEntry>()
            p.getAsJsonArray("entries")?.forEach { element ->
                val e = element.asJsonObject
                entries.add(PcFsEntry(
                    name = e.get("name")?.asString ?: "",
                    path = e.get("path")?.asString ?: "",
                    dir = e.get("dir")?.asBoolean ?: false,
                    size = e.get("size")?.asLong ?: 0L,
                    sizeLabel = e.get("sizeLabel")?.asString ?: "",
                    ext = e.get("ext")?.asString ?: "",
                    place = e.get("place")?.asString ?: "",
                    modified = e.get("modified")?.asLong ?: 0L
                ))
            }
            return PcFsResult(
                op = p.get("op")?.asString ?: "",
                requestId = p.get("requestId")?.asString ?: "",
                ok = p.get("ok")?.asBoolean ?: false,
                error = p.get("error")?.asString ?: "",
                path = p.get("path")?.takeIf { !it.isJsonNull }?.asString,
                parent = p.get("parent")?.takeIf { !it.isJsonNull }?.asString,
                entries = entries,
                places = p.get("places")?.asBoolean ?: false,
                fullAccess = p.get("fullAccess")?.asBoolean ?: false,
                copied = p.get("copied")?.asInt ?: 0,
                moved = p.get("moved")?.asInt ?: 0,
                failed = p.get("failed")?.asInt ?: 0,
                truncated = p.get("truncated")?.asBoolean ?: false
            )
        }

        /**
         * Clear the mirror's running flag for a stop the phone itself initiated.
         *
         * The desktop does not answer PC_SCREEN_STOP, so the flag stayed true
         * after a stop: the UI sat on "● Streaming" with a "Stop" button while
         * the stream was already dead, which made restarting it impossible.
         */
        fun notePcScreenStopped() {
            _pcScreenActive.value = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
        keystoreStorage = KeystoreStorage(this)
        createNotificationChannel()
        acquireLocks()
        try {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {}

        // Self-start the link here, not only from the Activity's LaunchedEffect.
        // START_STICKY restarts (system kill, reboot of the process) deliver a
        // null intent, and OEM task-clears skip MainActivity entirely — that is
        // why the phone "only connects when the app is opened". Any path that
        // brings this service up now reconnects on its own, as long as the
        // device is still paired (manual disconnect clears creds, so it stays
        // off).
        maybeAutoStartLink()
    }

    /** Kick off the link if paired and not manually disconnected. Idempotent. */
    private fun maybeAutoStartLink() {
        if (isManualDisconnect) return
        val creds = keystoreStorage.getCredentials() ?: return
        val st = _connectionState.value
        if (socket != null && (st == ConnectionState.CONNECTED || st == ConnectionState.CONNECTING)) return
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Connecting to ${creds.pcDeviceName}..."))
        } catch (_: Exception) {}
        connectWithCredentials(creds)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_LINK
        when (action) {
            ACTION_DISCONNECT -> {
                disconnectManually()
            }
            ACTION_SEND_CLIPBOARD -> {
                // Trigger clipboard read & send
                val clipText = intent?.getStringExtra("clipboard_text") ?: ""
                sendClipboardToPc(clipText)
            }
            ACTION_START_LINK -> {
                isManualDisconnect = false
                val creds = keystoreStorage.getCredentials()
                if (creds != null) {
                    val st = _connectionState.value
                    if (socket != null && (st == ConnectionState.CONNECTED || st == ConnectionState.CONNECTING)) {
                        return START_STICKY // already linked; don't tear down a healthy socket
                    }
                    startForeground(NOTIFICATION_ID, buildNotification("Connecting to ${creds.pcDeviceName}..."))
                    connectWithCredentials(creds)
                }
            }
        }
        return START_STICKY
    }

    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhonePC::LinkWakeLock").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // 12 hours max
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PhonePC::LinkWifiLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    /**
     * Home Link strip (spec §7): a real phone -> PC WebSocket round trip.
     * The PC already answers HEARTBEAT_PING with HEARTBEAT_PONG (sockets.js),
     * so this needs no protocol change - just measure the echo every 3s.
     */
    private fun startLatencyPingLoop() {
        if (latencyJob?.isActive == true) return
        latencyJob = serviceScope.launch {
            while (isActive) {
                val up = _connectionState.value == ConnectionState.CONNECTED && socket != null
                if (up) {
                    lastPingSentAt = SystemClock.elapsedRealtime()
                    socket?.send(Protocol.createEnvelope(Protocol.TYPE_HEARTBEAT_PING, JsonObject()))
                } else {
                    lastPingSentAt = 0L
                    _linkLatency.value = -1
                }
                delay(3000L)
            }
        }
    }

    private fun connectWithCredentials(creds: SessionCredentials) {
        _connectionState.value = ConnectionState.CONNECTING
        socket?.close()

        socket = TlsPinningSocket(creds.pcFingerprint, this)
        socket?.connect(creds.pcHost, creds.pcPort)
    }

    override fun onConnected() {
        // Stay CONNECTING until the PC verifies our challenge response
        updateNotification("Authenticating with PC...")

        // Authenticate via reconnect challenge
        val creds = keystoreStorage.getCredentials() ?: return
        val payload = JsonObject().apply {
            addProperty("deviceId", creds.deviceId)
            addProperty("sessionId", creds.sessionId)
        }
        socket?.send(Protocol.createEnvelope(Protocol.TYPE_RECONNECT_CHALLENGE_REQUEST, payload))
    }

    // ---- Null-safe payload accessors -------------------------------------
    // `payload.get(k)?.strOrNull()` only guards a MISSING key. When the PC sends
    // an explicit JSON null (PC_SCREEN_START answers with `error: null`),
    // get() returns a JsonNull instance and getAsString() throws
    // UnsupportedOperationException - OkHttp then reports onFailure and closes
    // the socket, which is what showed up as a1006 disconnect a second after
    // every start. These return null for absent-or-null so the caller's elvis
    // default applies instead of throwing.
    private fun JsonElement.strOrNull(): String? = if (isJsonNull) null else asString
    private fun JsonElement.boolOrNull(): Boolean? = if (isJsonNull) null else asBoolean
    private fun JsonElement.intOrNull(): Int? = if (isJsonNull) null else asInt
    private fun JsonElement.longOrNull(): Long? = if (isJsonNull) null else asLong

    /**
     * Row ids from a media delete request: the PC may send a whole selection as
     * `ids: [...]` (bulk delete) or a single tile as `id`.
     */
    private fun mediaIdsFrom(p: JsonObject): List<String> {
        val out = ArrayList<String>()
        val arr = p.get("ids") as? com.google.gson.JsonArray
        if (arr != null) for (el in arr) el.strOrNull()?.let { out.add(it) }
        if (out.isEmpty()) p.get("id")?.strOrNull()?.let { out.add(it) }
        return out
    }

    /**
     * Entry point for every envelope the PC sends.
     *
     * Nothing thrown in here may escape: OkHttp turns any exception out of
     * onMessage into onFailure, which closes the socket. One malformed field
     * used to take the whole link down; now it drops that message only.
     */
    override fun onMessage(message: Protocol.Envelope) {
        try {
            dispatchMessage(message)
        } catch (e: Exception) {
            android.util.Log.w(
                "PairingLink",
                "dropped ${message.type}: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun dispatchMessage(message: Protocol.Envelope) {
        when (message.type) {
            Protocol.TYPE_RECONNECT_CHALLENGE_RESPONSE -> {
                val nonce = message.payload.get("challengeNonce")?.strOrNull() ?: return
                val creds = keystoreStorage.getCredentials() ?: return
                val proof = TlsPinningSocket.computeHmacSha256(creds.sessionSecret, nonce)

                val responsePayload = JsonObject().apply {
                    addProperty("deviceId", creds.deviceId)
                    addProperty("proof", proof)
                }
                socket?.send(Protocol.createEnvelope(Protocol.TYPE_RECONNECT_CHALLENGE_RESPONSE, responsePayload))
            }

            Protocol.TYPE_RECONNECT_RESULT -> {
                val success = message.payload.get("success")?.boolOrNull() ?: false
                if (success) {
                    reconnectAttempt = 0
                    _connectionState.value = ConnectionState.CONNECTED
                    updateNotification("Link active & verified")

                    // V2: start live call-state monitoring + push initial status so
                    // the PC's panes hydrate immediately after every (re)connect
                    // instead of showing stale/empty state.
                    try { CallController.startListening(this) } catch (_: Exception) {}
                    sendPhoneVolumeState()
                    lastBatteryPayload?.let { sendRawEnvelope(Protocol.TYPE_PHONE_BATTERY, it) }
                    startLatencyPingLoop()

                    // V2: re-establish the PC screen stream if it was active when
                    // the link dropped, so the phone's PC-view recovers on its own.
                    pcScreenWantedPayload?.let { saved ->
                        if (pcScreenWanted) sendRawEnvelope(Protocol.TYPE_PC_SCREEN_START, saved)
                    }
                } else {
                    val err = message.payload.get("error")?.strOrNull() ?: ""
                    _connectionState.value = ConnectionState.ERROR
                    if (err.contains("Unknown device", ignoreCase = true)) {
                        // PC no longer knows this phone: re-pair is genuinely required
                        updateNotification("Session revoked. Re-pair required.")
                        isManualDisconnect = true
                        keystoreStorage.clearCredentials()
                        socket?.close()
                    } else {
                        updateNotification("Auth failed: $err")
                        socket?.close()
                        scheduleReconnect()
                    }
                }
            }

            Protocol.TYPE_CHAT_MESSAGE -> {
                val text = message.payload.get("text")?.strOrNull() ?: ""
                _incomingChatFlow.value = text
            }

            Protocol.TYPE_HEARTBEAT_PING -> {
                socket?.send(Protocol.createEnvelope(Protocol.TYPE_HEARTBEAT_PONG, JsonObject()))
            }

            Protocol.TYPE_HEARTBEAT_PONG -> {
                // Reply to our own PING (the PC echoes it) -> real round trip for the Link strip.
                val sent = lastPingSentAt
                if (sent > 0L) {
                    _linkLatency.value = (SystemClock.elapsedRealtime() - sent).toInt()
                    lastPingSentAt = 0L
                }
            }

            // ---- PC -> Phone: Jarvis telemetry, command results, find-my-phone ----
            Protocol.TYPE_SYSTEM_STATS -> {
                val p = message.payload
                _pcStats.value = PcStats(
                    cpu = p.get("cpu")?.intOrNull() ?: 0,
                    memPct = p.get("memPct")?.intOrNull() ?: 0,
                    memUsedGb = p.get("memUsedGb")?.asFloat ?: 0f,
                    memTotalGb = p.get("memTotalGb")?.asFloat ?: 0f,
                    uptimeSec = p.get("uptimeSec")?.longOrNull() ?: 0L,
                    host = p.get("host")?.strOrNull() ?: "PC",
                    diskPct = p.get("diskPct")?.intOrNull() ?: -1,
                    diskUsedGb = p.get("diskUsedGb")?.asFloat ?: 0f,
                    diskTotalGb = p.get("diskTotalGb")?.asFloat ?: 0f,
                    gpuPct = p.get("gpuPct")?.intOrNull() ?: -1
                )
            }
            Protocol.TYPE_PC_COMMAND_RESULT -> {
                val p = message.payload
                _commandResult.value = CommandResult(
                    seq = ++commandSeq,
                    ok = p.get("ok")?.boolOrNull() ?: false,
                    message = p.get("message")?.strOrNull() ?: ""
                )
            }
            Protocol.TYPE_FIND_PHONE -> {
                if (message.payload.get("on")?.boolOrNull() == true) startRinging() else stopRinging()
            }

            // ---- PC -> Phone: PC file browser reply ----
            Protocol.TYPE_PC_FS_RESULT -> {
                _pcFsResult.value = parsePcFsResult(message.payload)
            }

            // ---- PC -> Phone: file transfer ----
            Protocol.TYPE_FILE_META -> handleFileMeta(message.payload)
            Protocol.TYPE_FILE_CHUNK -> handleFileChunk(message.payload)
            Protocol.TYPE_FILE_CANCEL -> {
                val id = message.payload.get("transferId")?.strOrNull() ?: return
                incomingFiles.remove(id)?.abort()
            }

            // ---- PC -> Phone: screen stream + remote control ----
            Protocol.TYPE_PHONE_SCREEN_START -> {
                // Android requires an on-screen consent dialog, so it can't start silently.
                _screenShareRequest.value = true
                // Only bridge through a notification when the UI is OFF screen;
                // if the user is already in the app the system consent dialog
                // pops directly and a heads-up banner would just be a popup.
                if (!MainActivity.isForeground) postShareNotification()
            }


            Protocol.TYPE_PHONE_SCREEN_STOP -> {
                _screenShareRequest.value = false
                startService(Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_STOP_CAPTURE
                })
            }

            Protocol.TYPE_PHONE_INPUT_GESTURE -> {
                val svc = PhoneAccessibilityService.instance
                if (svc == null) { onControlUnavailable(); return }
                val p = message.payload
                fun f(k: String) = p.get(k)?.asFloat ?: 0f
                val dur = (p.get("durationMs")?.longOrNull() ?: 300L).coerceIn(80L, 1500L)
                val ok = when (p.get("type")?.strOrNull()?.uppercase()) {
                    "TAP" -> svc.performTap(f("startX"), f("startY"), 60)
                    "LONG_PRESS" -> svc.performTap(f("startX"), f("startY"), 700)
                    "SWIPE" -> svc.performSwipe(f("startX"), f("startY"), f("endX"), f("endY"), dur)
                    else -> true
                }
                if (!ok) reportControl("GESTURE_REJECTED")
            }

            Protocol.TYPE_PHONE_KEY_ACTION -> {
                val svc = PhoneAccessibilityService.instance
                if (svc == null) { onControlUnavailable(); return }
                val ok = svc.performGlobal(message.payload.get("action")?.strOrNull() ?: return)
                if (!ok) reportControl("GESTURE_REJECTED")
            }

            // The pop-out viewer just opened: force an IDR so it paints a real
            // picture on the next frame instead of waiting for the encoder's
            // periodic keyframe (which showed up as "Reconnecting…").
            Protocol.TYPE_PHONE_SCREEN_KEYFRAME -> {
                com.phonepc.pairing.control.ScreenCaptureService.activeServiceInstance
                    ?.requestKeyframeFromPc()
            }

            // "Type on Phone": the PC keyboard drives this phone's focused
            // field, and clicking the PC's typing box pops the IME open here.
            Protocol.TYPE_PHONE_TYPE_TEXT -> {
                val svc = PhoneAccessibilityService.instance
                if (svc == null) { onControlUnavailable(); return }
                val p = message.payload
                val op = p.get("op")?.strOrNull() ?: "append"
                val text = p.get("text")?.strOrNull() ?: ""
                val count = p.get("count")?.intOrNull() ?: 1
                val ok = when (op) {
                    "show" -> svc.showKeyboard()
                    "hide" -> { svc.hideKeyboard(); true }
                    else -> svc.applyTypeText(op, text.take(4000), count)
                }
                // No editable field on screen: the PC toast tells the user to
                // tap a field first (diagnostics keep the reason visible).
                if (!ok) reportControl("TYPE_NO_FIELD")
            }

            // ---- Phone power: Lock / Restart / Shut down (PC -> Phone) ----
            Protocol.TYPE_PHONE_POWER -> {
                val action = message.payload.get("action")?.strOrNull() ?: ""
                val (ok, msg) = handlePhonePower(action)
                sendRawEnvelope(Protocol.TYPE_PHONE_POWER_RESULT, JsonObject().apply {
                    addProperty("ok", ok)
                    addProperty("message", msg)
                })
            }

            // ================= V2: REMOTE PC SCREEN (phone views/controls PC) =================
            Protocol.TYPE_PC_SCREEN_START -> {
                val p = message.payload
                _pcScreenAck.value = ScreenAck(
                    ++pcAckSeq,
                    p.get("ok")?.boolOrNull() ?: false,
                    p.get("error")?.strOrNull() ?: "",
                    p.get("fps")?.intOrNull() ?: 0,
                    p.get("quality")?.intOrNull() ?: 0,
                    p.get("maxDimension")?.intOrNull() ?: 0
                )
                _pcScreenActive.value = p.get("ok")?.boolOrNull() ?: false
            }

            Protocol.TYPE_PC_SCREEN_FRAME -> {
                // Legacy JSON frames. The desktop now sends video on the binary
                // channel (see onBinaryFrame below), so this only remains as a
                // fallback for an older PC build.
                val p = message.payload
                val b64 = p.get("jpegBase64")?.strOrNull() ?: return
                _pcScreenFrame.value = Frame(
                    p.get("seq")?.longOrNull() ?: 0L,
                    Base64.decode(b64, Base64.NO_WRAP),
                    p.get("width")?.intOrNull() ?: 0,
                    p.get("height")?.intOrNull() ?: 0
                )
            }

            // ================= V2: PHONE WEBCAM (PC drives the phone camera) =================
            Protocol.TYPE_WEBCAM_START -> {
                val p = message.payload
                try {
                    startService(Intent(this, WebcamService::class.java).apply {
                        action = WebcamService.ACTION_START
                        putExtra(WebcamService.EXTRA_CAMERA, p.get("camera")?.strOrNull() ?: "back")
                        putExtra(WebcamService.EXTRA_WIDTH, p.get("width")?.intOrNull() ?: 1280)
                        putExtra(WebcamService.EXTRA_HEIGHT, p.get("height")?.intOrNull() ?: 720)
                        putExtra(WebcamService.EXTRA_FPS, p.get("fps")?.intOrNull() ?: 15)
                        putExtra(WebcamService.EXTRA_QUALITY, p.get("quality")?.intOrNull() ?: 70)
                        putExtra(WebcamService.EXTRA_TORCH, p.get("torch")?.boolOrNull() ?: false)
                    })
                } catch (e: Exception) {
                    sendRawEnvelope(Protocol.TYPE_WEBCAM_STATUS, JsonObject().apply {
                        addProperty("active", false)
                        addProperty("error", e.message ?: "Could not start camera service")
                    })
                }
            }

            Protocol.TYPE_WEBCAM_STOP -> WebcamService.stopFrom(this)

            Protocol.TYPE_WEBCAM_TORCH -> {
                val on = message.payload.get("on")?.boolOrNull() ?: false
                android.util.Log.i("DuoLink", "WEBCAM_TORCH on=$on")
                try {
                    startService(Intent(this, WebcamService::class.java).apply {
                        action = WebcamService.ACTION_TORCH
                        putExtra(WebcamService.EXTRA_TORCH, on)
                    })
                } catch (t: Exception) {
                    android.util.Log.w("DuoLink", "torch start failed: ${t.message}")
                }
            }

            // ================= V2: CALLS =================
            Protocol.TYPE_CALL_ACTION -> {
                val action = message.payload.get("action")?.strOrNull() ?: ""
                val number = message.payload.get("number")?.strOrNull() ?: ""
                val error = CallController.handleAction(this, action, number)
                sendRawEnvelope(Protocol.TYPE_CALL_ACTION_RESULT, JsonObject().apply {
                    addProperty("action", action)
                    addProperty("ok", error == null)
                    if (error != null) addProperty("error", error)
                })
            }

            Protocol.TYPE_CALL_RECENTS_REQ -> {
                CallController.sendRecents(this, message.payload.get("limit")?.intOrNull() ?: 50)
            }

            // ================= Apps: catalog + launch =================
            Protocol.TYPE_APP_CATALOG_REQ -> sendAppCatalog()
            Protocol.TYPE_APP_LAUNCH -> {
                val pkg = message.payload.get("pkg")?.strOrNull()
                val result = JsonObject()
                if (pkg.isNullOrBlank()) {
                    result.addProperty("ok", false)
                    result.addProperty("error", "Missing package")
                } else {
                    val launched = launchApp(pkg)
                    result.addProperty("ok", launched == null)
                    if (launched != null) result.addProperty("error", launched)
                    result.addProperty("pkg", pkg)
                }
                sendRawEnvelope(Protocol.TYPE_APP_LAUNCH_RESULT, result)
            }

            Protocol.TYPE_CALL_LOG_DELETE -> {
                val id = message.payload.get("id")?.strOrNull()
                if (id.isNullOrBlank()) {
                    sendRawEnvelope(Protocol.TYPE_CALL_LOG_DELETE_RESULT, JsonObject().apply {
                        addProperty("id", "")
                        addProperty("ok", false)
                        addProperty("error", "Missing call log id")
                    })
                } else {
                    CallController.deleteCall(this, id)
                }
            }

            // ================= V2: NOTIFICATIONS =================
            Protocol.TYPE_NOTIF_LIST -> {
                val listener = PhoneNotificationListener.instance
                val items = com.google.gson.JsonArray()
                listener?.snapshot()?.forEach { items.add(it) }
                sendRawEnvelope(Protocol.TYPE_NOTIF_LIST, JsonObject().apply {
                    add("items", items)
                    addProperty("hiddenCount", PhoneNotificationListener.hiddenCount)
                })
            }

            Protocol.TYPE_NOTIF_DISMISS -> {
                val key = message.payload.get("key")?.strOrNull() ?: return
                val listener = PhoneNotificationListener.instance
                if (listener == null) {
                    sendRawEnvelope(Protocol.TYPE_NOTIF_REMOVED, JsonObject().apply {
                        addProperty("key", key)
                        addProperty("error", "Notification access not granted on phone")
                    })
                } else {
                    val err = listener.dismiss(key)
                    if (err != null) {
                        sendRawEnvelope(Protocol.TYPE_NOTIF_REMOVED, JsonObject().apply {
                            addProperty("key", key)
                            addProperty("error", err)
                        })
                    }
                    // success path: listener's onNotificationRemoved reports it
                }
            }

            Protocol.TYPE_NOTIF_REPLY -> {
                val key = message.payload.get("key")?.strOrNull() ?: return
                val text = message.payload.get("text")?.strOrNull() ?: return
                val listener = PhoneNotificationListener.instance
                val error = listener?.reply(key, text) ?: "Notification access not granted on phone"
                if (error != null) {
                    sendRawEnvelope(Protocol.TYPE_NOTIF_REMOVED, JsonObject().apply {
                        addProperty("key", key)
                        addProperty("error", error)
                    })
                }
            }

            // ================= V2: SMS =================
            Protocol.TYPE_SMS_REQ -> {
                val p = message.payload
                val limit = (p.get("limit")?.intOrNull() ?: 100).coerceIn(1, 1000)
                when (p.get("op")?.strOrNull()) {
                    "messages" -> SmsHelper.sendMessages(this, p.get("address")?.strOrNull() ?: "", limit)
                    "delete" -> SmsHelper.deleteSms(this, p.get("id")?.strOrNull() ?: "")
                    else -> SmsHelper.sendThreads(this, limit)
                }
            }

            Protocol.TYPE_SMS_SEND -> {
                SmsHelper.sendSms(
                    this,
                    message.payload.get("address")?.strOrNull() ?: "",
                    message.payload.get("text")?.strOrNull() ?: ""
                )
            }

            // ================= V2: PHONE MEDIA =================
            Protocol.TYPE_MEDIA_REQ -> {
                val p = message.payload
                when (p.get("op")?.strOrNull()) {
                    "thumb" -> MediaBrowser.thumbnail(this, p.get("id")?.strOrNull() ?: return)
                    "get" -> MediaBrowser.streamToPc(this, p.get("id")?.strOrNull() ?: return)
                    // Share: the PICKER is shown on the PC (share_targets), the
                    // chosen app is launched by package here (share). Android's
                    // share sheet never appears on the phone.
                    "share_targets" -> MediaBrowser.shareTargets(
                        this,
                        mediaIdsFrom(p),
                        p.get("mime")?.strOrNull() ?: "",
                        (p.get("ids")?.let { if (it.isJsonArray) it.asJsonArray.size() else 1 } ?: 1) > 1
                    )
                    "share" -> MediaBrowser.share(
                        this,
                        mediaIdsFrom(p),
                        p.get("target")?.strOrNull() ?: ""
                    )
                    "delete" -> MediaBrowser.delete(this, mediaIdsFrom(p))
                    else -> MediaBrowser.list(
                        this,
                        p.get("kind")?.strOrNull() ?: "images",
                        p.get("search")?.strOrNull() ?: "",
                        p.get("sort")?.strOrNull() ?: "date_desc",
                        (p.get("limit")?.intOrNull() ?: 200).coerceIn(1, 500)
                    )
                }
            }

            // ================= V2: PHONE VOLUME =================
            Protocol.TYPE_PHONE_VOLUME -> applyPhoneVolume(message.payload)

            // ================= V2: DIAGNOSTICS =================
            Protocol.TYPE_DIAG_REQ -> sendDiagnostics()
        }
    }

    override fun onClosed(code: Int, reason: String) {
        // Stop camera capture immediately when the link drops (no frames can be sent)
        try { WebcamService.stopFrom(this) } catch (_: Exception) {}
        _pcScreenActive.value = false
        if (!isManualDisconnect) {
            scheduleReconnect()
        } else {
            _connectionState.value = ConnectionState.IDLE
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onFailure(t: Throwable) {
        if (!isManualDisconnect) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        _connectionState.value = ConnectionState.RECONNECTING
        reconnectAttempt++
        // Exponential backoff: 1s, 2s, 4s, 8s, up to 30s
        val delayMs = (1000L * (1 shl (reconnectAttempt.coerceAtMost(5)))).coerceAtMost(30000L)
        updateNotification("Reconnecting in ${delayMs / 1000}s (Attempt $reconnectAttempt)...")

        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(delayMs)
            val creds = keystoreStorage.getCredentials()
            if (creds != null && !isManualDisconnect) {
                connectWithCredentials(creds)
            }
        }
    }

    /**
     * Launchable third-party apps with their real label and a 96px icon, so the
     * PC can draw the phone's app grid without any desktop-side guessing.
     */
    private fun sendAppCatalog() {
        val out = JsonObject()
        try {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(intent, 0)
            val seen = HashSet<String>()
            val arr = com.google.gson.JsonArray()
            for (ri in resolved) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == packageName || !seen.add(pkg)) continue
                arr.add(JsonObject().apply {
                    addProperty("pkg", pkg)
                    addProperty("label", ri.loadLabel(pm)?.toString() ?: pkg)
                    appIconBase64(pkg)?.let { addProperty("icon", it) }
                })
            }
            out.add("apps", arr)
        } catch (t: Throwable) {
            out.addProperty("error", t.message ?: "Could not list the phone's apps")
        }
        sendRawEnvelope(Protocol.TYPE_APP_CATALOG, out)
    }

    /** Icon as a 96px PNG in base64 (null when the app exposes nothing usable). */
    private fun appIconBase64(pkg: String): String? = try {
        val d = packageManager.getApplicationIcon(pkg)
        val bmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        d.setBounds(0, 0, 96, 96)
        d.draw(canvas)
        val baos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, baos)
        bmp.recycle()
        Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    } catch (_: Throwable) { null }

    /** Returns null on success, or the failure reason. */
    private fun launchApp(pkg: String): String? = try {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
            ?: return "That app has no launch screen"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        null
    } catch (t: Throwable) {
        "${t.javaClass.simpleName}: ${t.message ?: "the phone refused to start the app"}"
    }

    fun sendRawEnvelope(type: String, payload: JsonObject): Boolean {
        // Track PC-screen intent so a dropped link can be transparently resumed.
        when (type) {
            Protocol.TYPE_PC_SCREEN_START -> {
                pcScreenWanted = true
                pcScreenWantedPayload = payload
            }
            Protocol.TYPE_PC_SCREEN_STOP -> {
                pcScreenWanted = false
                pcScreenWantedPayload = null
            }
        }
        return socket?.send(Protocol.createEnvelope(type, payload)) ?: false
    }

    /**
     * Sends a raw binary frame (webcam -> PC). No envelope, no JSON: the wire
     * format is the 9-byte header + JPEG defined in Protocol.encodeBinaryFrame.
     */
    fun sendRawBinary(bytes: ByteArray): Boolean {
        return socket?.sendBinary(bytes) ?: false
    }

    /**
     * Outbound bytes still queued on the socket (0 when the link is clear).
     * Screen mirroring checks this before sending a frame: a stalled link
     * must drop the newest frame rather than queue a backlog, otherwise the
     * PC freezes and then fast-forwards through stale frames while audio
     * chunks wait behind them.
     */
    fun sendQueuedBytes(): Long = socket?.queuedBytes() ?: 0L

    /**
     * Binary channel from the PC. PC-screen frames land in the single-slot
     * mailbox above; anything else is ignored (forward compatibility).
     */
    // Receive-side telemetry for the mirror: the PC's own counter only proves
    // frames were handed to the socket, so this reports what actually landed on
    // the phone (and whether Wi-Fi or the renderer is dropping any).
    private var pcRxFrames = 0
    private var pcRxWindowStart = 0L
    private var pcRxLastSeq = -1L
    private var pcRxDrops = 0

    override fun onBinaryFrame(frame: Protocol.BinFrame) {
        if (frame.kind == Protocol.BIN_KIND_PC_SCREEN) {
            _pcScreenFrame.value = Frame(frame.seq, frame.jpeg, frame.width, frame.height)
            // Real loss = a seq number we never saw (the renderer intentionally
            // skips frames via the single-slot StateFlow; that is not loss).
            if (pcRxLastSeq >= 0 && frame.seq > pcRxLastSeq + 1) {
                pcRxDrops += (frame.seq - pcRxLastSeq - 1).toInt()
            }
            pcRxLastSeq = frame.seq
            pcRxFrames++
            val now = SystemClock.elapsedRealtime()
            if (pcRxWindowStart == 0L) pcRxWindowStart = now
            if (now - pcRxWindowStart >= 3000) {
                val n = pcRxFrames
                val ms = now - pcRxWindowStart
                android.util.Log.i(
                    "PcScreen",
                    "perf: ${"%.1f".format(n * 1000f / ms)}fps rx=$n lost=$pcRxDrops ${frame.width}x${frame.height}"
                )
                pcRxFrames = 0
                pcRxDrops = 0
                pcRxWindowStart = now
            }
        }
    }

    // ================= V2: phone audio volume control =================

    private fun applyPhoneVolume(p: JsonObject) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        when (p.get("action")?.strOrNull()) {
            "UP" -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, (cur + 1).coerceAtMost(max), 0)
            "DOWN" -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, (cur - 1).coerceAtLeast(0), 0)
            "MUTE" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            "UNMUTE" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            "SET" -> {
                val v = (p.get("value")?.intOrNull() ?: cur).coerceIn(0, max)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
            }
        }
        sendPhoneVolumeState()
    }

    private fun sendPhoneVolumeState() {
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            sendRawEnvelope(Protocol.TYPE_PHONE_VOLUME_STATE, JsonObject().apply {
                addProperty("level", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                addProperty("max", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                addProperty("muted", audio.isStreamMute(AudioManager.STREAM_MUSIC))
            })
        } catch (_: Exception) {}
    }

    // ================= V2: battery status push =================

    private var lastBatteryPayload: JsonObject? = null

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
            val temp = (intent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)) / 10.0
            if (level < 0 || scale <= 0) return
            val payload = JsonObject().apply {
                addProperty("level", (level * 100 / scale))
                addProperty("charging", charging)
                addProperty("temperatureC", temp)
            }
            lastBatteryPayload = payload
            sendRawEnvelope(Protocol.TYPE_PHONE_BATTERY, payload)
        }
    }

    // ================= V2: phone power actions (desktop: Lock / Restart / Shut down) =================

    /**
     * LOCK: real lock via the accessibility service (Android 9+).
     * RESTART/SHUTDOWN: run privileged commands when the phone is rooted;
     * otherwise open the system power menu so the user taps the option on the
     * phone itself. Nothing here fakes success - the message returned to the
     * desktop always states what actually happened.
     */
    private fun handlePhonePower(action: String): Pair<Boolean, String> {
        when (action.uppercase()) {
            "LOCK" -> {
                val svc = PhoneAccessibilityService.instance
                if (svc != null && svc.performGlobal("LOCK_SCREEN")) {
                    return true to "Phone locked."
                }
                return false to "Could not lock. Enable the DuoLink Accessibility Service (Settings > Accessibility), then retry."
            }
            "RESTART" -> {
                for (cmd in listOf(listOf("su", "-c", "reboot"), listOf("reboot"))) {
                    if (runCommand(cmd)) return true to "Phone is restarting..."
                }
                val svc = PhoneAccessibilityService.instance
                if (svc != null && svc.performGlobal("POWER_DIALOG")) {
                    return true to "Power menu opened on the phone - tap Restart to confirm."
                }
                return false to "Restart needs root or the DuoLink Accessibility Service. Enable Accessibility, then tap Restart again."
            }
            "SHUTDOWN" -> {
                for (cmd in listOf(
                    listOf("su", "-c", "reboot -p"),
                    listOf("su", "-c", "svc power shutdown"),
                    listOf("svc", "power", "shutdown")
                )) {
                    if (runCommand(cmd)) return true to "Phone is shutting down..."
                }
                val svc = PhoneAccessibilityService.instance
                if (svc != null && svc.performGlobal("POWER_DIALOG")) {
                    return true to "Power menu opened on the phone - tap Power off to confirm."
                }
                return false to "Shut down needs root or the DuoLink Accessibility Service. Enable Accessibility, then tap Shut Down again."
            }
        }
        return false to "Unknown power action: $action"
    }

    /** Runs a shell command; true only if it completed with exit code 0. */
    private fun runCommand(cmd: List<String>): Boolean {
        return try {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            finished && proc.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    // ================= V2: diagnostics response =================

    private fun sendDiagnostics() {
        fun granted(perm: String): Boolean =
            checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val battery = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val payload = JsonObject().apply {
            add("permissions", JsonObject().apply {
                addProperty("camera", granted(android.Manifest.permission.CAMERA))
                addProperty("readPhoneState", granted(android.Manifest.permission.READ_PHONE_STATE))
                addProperty("answerCalls", granted(android.Manifest.permission.ANSWER_PHONE_CALLS))
                addProperty("readCallLog", granted(android.Manifest.permission.READ_CALL_LOG))
                addProperty("readSms", granted(android.Manifest.permission.READ_SMS))
                addProperty("sendSms", granted(android.Manifest.permission.SEND_SMS))
                addProperty("postNotifications", granted(android.Manifest.permission.POST_NOTIFICATIONS))
                addProperty("notificationAccess", PhoneNotificationListener.isRunning)
                addProperty(
                    "media",
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                        granted(android.Manifest.permission.READ_MEDIA_IMAGES) &&
                            granted(android.Manifest.permission.READ_MEDIA_VIDEO) &&
                            granted(android.Manifest.permission.READ_MEDIA_AUDIO)
                    else granted(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                )
            })
            add("services", JsonObject().apply {
                addProperty("link", activeServiceInstance != null)
                addProperty("accessibility", PhoneAccessibilityService.isServiceRunning)
                addProperty("webcam", WebcamService.isActive)
                addProperty("notificationListener", PhoneNotificationListener.isRunning)
                addProperty("screenMirror", ScreenCaptureService.isRunning)
            })
            add("device", JsonObject().apply {
                addProperty("batteryPct", battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY))
                addProperty("charging", battery.isCharging)
                addProperty("volumeLevel", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                addProperty("volumeMax", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                addProperty("model", android.os.Build.MODEL)
                addProperty("sdk", android.os.Build.VERSION.SDK_INT)
                addProperty("sdkRelease", android.os.Build.VERSION.RELEASE)
                addProperty("appVersion", packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown")
            })
            add("link", JsonObject().apply {
                addProperty("state", _connectionState.value.name)
                addProperty("pcScreenActive", _pcScreenActive.value)
            })
        }
        sendRawEnvelope(Protocol.TYPE_DIAG_RESP, payload)
    }

    fun sendClipboardToPc(text: String) {
        val payload = JsonObject().apply {
            addProperty("text", text)
        }
        sendRawEnvelope(Protocol.TYPE_CLIPBOARD_PUSH, payload)
    }

    fun disconnectManually() {
        isManualDisconnect = true
        _connectionState.value = ConnectionState.IDLE
        try { CallController.stopListening(this) } catch (_: Exception) {}
        try { WebcamService.stopFrom(this) } catch (_: Exception) {}
        _pcScreenActive.value = false
        socket?.send(Protocol.createEnvelope(Protocol.TYPE_DISCONNECT, JsonObject()))
        socket?.close()
        keystoreStorage.clearCredentials()
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun postShareNotification() {
        val open = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_START_SHARE, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Silent, shade-only: PC actions must never pop over the phone.
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PC wants to view your screen")
            .setContentText("Tap to allow screen sharing")
            .setSmallIcon(R.drawable.ic_link)
            .setContentIntent(open)
            .setSilent(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(SHARE_NOTIFICATION_ID, n)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DuoLink — Link Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time connection status with your paired PC."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PairingForegroundService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DuoLink — Link Active")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_link)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_close, "Disconnect", disconnectIntent)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    // ================= Jarvis: commands to the PC =================

    /** Sends a Jarvis command (LAUNCH_APP, OPEN_URL, SCREENSHOT, POWER, GET_STATS). Returns false if offline. */
    fun sendPcCommand(action: String, args: Map<String, String> = emptyMap()): Boolean {
        val payload = JsonObject().apply {
            addProperty("requestId", System.currentTimeMillis())
            addProperty("action", action)
            add("args", JsonObject().apply { args.forEach { (k, v) -> addProperty(k, v) } })
        }
        return sendRawEnvelope(Protocol.TYPE_PC_COMMAND, payload)
    }

    // ================= PC -> Phone touch control diagnostics =================

    private var lastControlReport = 0L
    private var lastAccessibilityNotice = 0L

    /** Tells the PC why a touch/navigation request failed (throttled). */
    fun reportControl(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastControlReport < 3000) return
        lastControlReport = now
        sendRawEnvelope(Protocol.TYPE_PHONE_CONTROL_STATUS, JsonObject().apply {
            addProperty("ok", false)
            addProperty("reason", reason)
        })
    }

    private fun onControlUnavailable() {
        reportControl("ACCESSIBILITY_OFF")
        val now = System.currentTimeMillis()
        if (now - lastAccessibilityNotice < 30_000) return
        lastAccessibilityNotice = now
        val open = PendingIntent.getActivity(
            this, 4,
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Your PC tried to control this phone")
            .setContentText("Turn on \"DuoLink\" in Accessibility. Tap here.")
            .setSmallIcon(R.drawable.ic_link)
            .setContentIntent(open)
            .setSilent(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1003, n)
    }

    // ================= Find My Phone =================

    private var ringtone: Ringtone? = null
    private var savedAlarmVolume = -1
    private val ringHandler = Handler(Looper.getMainLooper())
    private val stopRingRunnable = Runnable { stopRinging() }

    fun startRinging() {
        if (ringtone != null) return
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)

            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val r = RingtoneManager.getRingtone(this, uri)
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) r.isLooping = true
            r.play()
            ringtone = r

            @Suppress("DEPRECATION")
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
            } else {
                @Suppress("DEPRECATION") vib.vibrate(longArrayOf(0, 600, 400), 0)
            }
            ringHandler.removeCallbacks(stopRingRunnable)
            ringHandler.postDelayed(stopRingRunnable, 60_000) // auto-stop after a minute
        } catch (e: Exception) {
            android.util.Log.e("FindPhone", "Could not ring", e)
        }
    }

    fun stopRinging() {
        ringHandler.removeCallbacks(stopRingRunnable)
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
        try {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
            if (savedAlarmVolume >= 0) {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
                savedAlarmVolume = -1
            }
        } catch (_: Exception) {}
    }

    // ================= Incoming files from PC =================

    private class IncomingFile(
        val filename: String,
        val totalSize: Long,
        val out: OutputStream,
        val uri: Uri?,        // MediaStore entry (API 29+)
        val file: File?,      // plain file (API 26-28)
        val location: String
    ) {
        var received = 0L
        fun abort() {
            try { out.close() } catch (_: Exception) {}
            // (MediaStore row / partial file is removed by the caller)
        }
    }

    private val incomingFiles = HashMap<String, IncomingFile>()
    private val MAX_INCOMING_BYTES = 5L * 1024 * 1024 * 1024 // 5 GB (spec)

    private fun safeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .trim().trimStart('.')
        return if (base.isEmpty()) "file_${System.currentTimeMillis()}" else base.take(120)
    }

    private fun handleFileMeta(p: JsonObject) {
        val id = p.get("transferId")?.strOrNull() ?: return
        val total = p.get("totalSize")?.longOrNull() ?: 0L
        val name = safeFileName(p.get("filename")?.strOrNull() ?: "file")
        if (total > MAX_INCOMING_BYTES) {
            _fileReceived.value = "Rejected $name: larger than 5 GB"
            return
        }
        try {
            val entry: IncomingFile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PhonePC")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Could not create file in Downloads")
                val out = contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Could not open file for writing")
                IncomingFile(name, total, out, uri, null, "Downloads/PhonePC")
            } else {
                val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "PhonePC").apply { mkdirs() }
                val f = File(dir, name)
                IncomingFile(name, total, f.outputStream(), null, f, f.parent ?: "app files")
            }
            incomingFiles[id] = entry
            _fileReceived.value = "Receiving $name..."
            if (total == 0L) finishIncoming(id)
        } catch (e: Exception) {
            _fileReceived.value = "Could not save $name: ${e.message}"
        }
    }

    private fun handleFileChunk(p: JsonObject) {
        val id = p.get("transferId")?.strOrNull() ?: return
        val entry = incomingFiles[id] ?: return
        try {
            val bytes = Base64.decode(p.get("chunkBase64")?.strOrNull() ?: return, Base64.DEFAULT)
            entry.out.write(bytes)
            entry.received += bytes.size
            val pct = if (entry.totalSize > 0) (entry.received * 100 / entry.totalSize).toInt() else 100
            if (entry.received < entry.totalSize) {
                _fileReceived.value = "Receiving ${entry.filename}... $pct%"
            } else {
                finishIncoming(id)
            }
        } catch (e: Exception) {
            incomingFiles.remove(id)
            entry.abort()
            entry.uri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
            entry.file?.delete()
            _fileReceived.value = "Failed receiving ${entry.filename}: ${e.message}"
        }
    }

    private fun finishIncoming(id: String) {
        val entry = incomingFiles.remove(id) ?: return
        try { entry.out.flush(); entry.out.close() } catch (_: Exception) {}
        entry.uri?.let {
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            try { contentResolver.update(it, done, null, null) } catch (_: Exception) {}
        }
        _lastReceivedUri.value = entry.uri?.toString()
            ?: entry.file?.let { android.net.Uri.fromFile(it).toString() }
        val msg = "${entry.filename} saved to ${entry.location}"
        _fileReceived.value = msg
        android.util.Log.i("PairingService", "incoming file complete: $msg")
        // No notification: sharing a file from the PC must complete silently
        // (status still shows in File Transfer on the phone app and on the PC).
    }

    /**
     * Survive the app being swiped out of Recents. The link service is the one
     * thing that must outlive the UI, but several OEMs (Infinix/XOS among them)
     * stop a foreground service when its task is cleared. Re-post a restart so a
     * paired phone keeps its PC link even with the app closed — this is the
     * "runs in background but only connects when opened" fix.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (isManualDisconnect) return
        val creds = keystoreStorage.getCredentials() ?: return
        val st = _connectionState.value
        if (socket != null && (st == ConnectionState.CONNECTED || st == ConnectionState.CONNECTING)) return
        // Brief delay lets the OEM finish tearing the task down before we come back.
        serviceScope.launch {
            delay(1200L)
            try {
                val intent = Intent(this@PairingForegroundService, PairingForegroundService::class.java).apply {
                    action = ACTION_START_LINK
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        stopRinging()
        try { CallController.stopListening(this) } catch (_: Exception) {}
        try { WebcamService.stopFrom(this) } catch (_: Exception) {}
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        incomingFiles.values.forEach { it.abort() }
        incomingFiles.clear()
        reconnectJob?.cancel()
        activeServiceInstance = null
        releaseLocks()
        serviceScope.cancel()
        socket?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
