package com.phonepc.pairing.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.gson.JsonObject
import com.phonepc.pairing.data.ConnectionState
import com.phonepc.pairing.network.Protocol
import com.phonepc.pairing.service.PairingForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Jarvis: tap the arc-reactor and talk to your PC.
 * Speech recognition and text-to-speech use the phone's built-in engines.
 */

/** app key (parser) -> Android package, for "... on phone" routing. */
private val PHONE_APP_PKGS = mapOf(
    "chrome" to "com.android.chrome",
    "edge" to "com.microsoft.emmx",
    "firefox" to "org.mozilla.firefox",
    "whatsapp" to "com.whatsapp",
    "spotify" to "com.spotify.music",
    "camera" to "com.android.camera",
    "store" to "com.android.vending",
    "calculator" to "com.android.calculator2"
)

@Composable
fun JarvisScreen() {
    val context = LocalContext.current
    val connection by PairingForegroundService.connectionState.collectAsState()
    val stats by PairingForegroundService.pcStats.collectAsState()
    val result by PairingForegroundService.commandResult.collectAsState()
    val online = connection == ConnectionState.CONNECTED

    var heard by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("Tap the core and say a command.") }
    var listening by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(0f) }
    // One consistent failure reply for any command the assistant can't parse.
    val apology = "Apologies, I didn't catch that. Could you repeat it?"
    // Veltrix wake-word mode: after each exchange the mic re-arms itself so
    // "Veltrix ..." can be said any time instead of retapping the core.
    var wakeMode by remember { mutableStateOf(false) }
    val wakeHandler = remember { Handler(Looper.getMainLooper()) }
    var voiceReplies by remember { mutableStateOf(true) }
    var typed by remember { mutableStateOf("") }
    var hindi by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    // Sequenced commands ("restart chrome" = close, then relaunch) need a scope
    // that outlives the single execute() call.
    val restartScope = rememberCoroutineScope()

    // ---------- Text to speech ----------
    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember { TextToSpeech(context) { status -> ttsReady = status == TextToSpeech.SUCCESS } }
    LaunchedEffect(ttsReady) { if (ttsReady) tts.language = Locale.getDefault() }
    DisposableEffect(Unit) { onDispose { tts.stop(); tts.shutdown() } }

    fun say(text: String) {
        reply = text
        if (voiceReplies && ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    // The PC answers commands asynchronously ("Locking your PC.", "Screenshot sent...")
    val firstSeq = remember { PairingForegroundService.commandResult.value?.seq }
    LaunchedEffect(result?.seq) {
        result?.let { if (it.seq != firstSeq) say(it.message) }  // don't replay an old result on re-entry
    }

    // ---------- Executing what was understood ----------
    fun sendPc(action: String, args: Map<String, String>): Boolean =
        PairingForegroundService.activeServiceInstance?.sendPcCommand(action, args) ?: false

    fun sendEnvelope(type: String, build: JsonObject.() -> Unit): Boolean =
        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(type, JsonObject().apply(build)) ?: false

    fun execute(text: String) {
        heard = text
        val needsPc = "I'm not connected to your PC yet. Pair or reconnect first."
        when (val intent = JarvisParser.parse(text)) {
            is JarvisIntent.Pc -> {
                reply = "Working on it..."
                if (!sendPc(intent.action, intent.args)) say(needsPc)
            }
            is JarvisIntent.Media -> {
                if (sendEnvelope(Protocol.TYPE_PC_MEDIA_ACTION) { addProperty("action", intent.action) }) say(intent.reply)
                else say(needsPc)
            }
            is JarvisIntent.TypeText -> {
                if (sendEnvelope(Protocol.TYPE_PC_KEY_INPUT) { addProperty("text", intent.text) }) say("Typing it on your PC.")
                else say(needsPc)
            }
            is JarvisIntent.Chat -> {
                val ok = sendEnvelope(Protocol.TYPE_CHAT_MESSAGE) {
                    addProperty("text", intent.text)
                    addProperty("senderName", "Android Phone")
                }
                say(if (ok) "Message sent to your PC." else needsPc)
            }
            is JarvisIntent.Key -> {
                if (sendEnvelope(Protocol.TYPE_PC_KEY_INPUT) { addProperty("specialKey", intent.name) }) say("Pressed ${intent.name.lowercase()}.")
                else say(needsPc)
            }
            is JarvisIntent.Click -> {
                val ok = sendEnvelope(Protocol.TYPE_PC_MOUSE_CLICK) {
                    addProperty("button", intent.button)
                    addProperty("double", intent.double)
                }
                say(if (ok) (if (intent.double) "Double clicked." else "Clicked.") else needsPc)
            }
            is JarvisIntent.Scroll -> {
                val ok = sendEnvelope(Protocol.TYPE_PC_MOUSE_SCROLL) { addProperty("deltaY", intent.delta) }
                say(if (ok) (if (intent.delta > 0) "Scrolling down." else "Scrolling up.") else needsPc)
            }
            is JarvisIntent.Torch -> {
                val done = try {
                    val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val id = cm.cameraIdList.firstOrNull {
                        cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }
                    if (id != null) { cm.setTorchMode(id, intent.on); torchOn = intent.on; true } else false
                } catch (e: Exception) { false }
                say(if (!done) "I couldn't reach the flashlight." else if (intent.on) "Flashlight on." else "Flashlight off.")
            }
            is JarvisIntent.RestartApp -> {
                reply = "Restarting ${intent.name}..."
                sendPc("CLOSE_APP", mapOf("app" to intent.app))
                // A polite close needs a moment before the relaunch, or Windows
                // just focuses the window that is still shutting down.
                restartScope.launch {
                    delay(1600)
                    if (!sendPc("LAUNCH_APP", mapOf("app" to intent.app))) say(needsPc)
                }
            }
            is JarvisIntent.PcName -> {
                val s = stats
                say(if (!online || s == null) "I can't reach your PC right now." else "Your PC is called ${s.host}.")
            }
            is JarvisIntent.PhoneVolume -> {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val stream = android.media.AudioManager.STREAM_MUSIC
                val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
                when (intent.action) {
                    "UP" -> am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_RAISE, 0)
                    "DOWN" -> am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_LOWER, 0)
                    "MUTE" -> am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_TOGGLE_MUTE, 0)
                    else -> am.setStreamVolume(stream, (intent.level * max / 100).coerceIn(0, max), 0)
                }
                val pct = am.getStreamVolume(stream) * 100 / max
                say(if (am.isStreamMute(stream)) "Phone is muted." else "Phone volume $pct percent.")
            }
            is JarvisIntent.Timer -> {
                val done = try {
                    context.startActivity(Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, intent.seconds)
                        putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    true
                } catch (e: Exception) { false }
                val s = intent.seconds
                val label = if (s % 3600 == 0) "${s / 3600} hour" + (if (s / 3600 > 1) "s" else "")
                            else if (s % 60 == 0) "${s / 60} minute" + (if (s / 60 > 1) "s" else "")
                            else "$s seconds"
                say(if (done) "Timer set for $label." else "I couldn't open the clock app.")
            }
            is JarvisIntent.Alarm -> {
                val done = try {
                    context.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, intent.hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, intent.minute)
                        putExtra(AlarmClock.EXTRA_MESSAGE, "Veltrix alarm")
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    true
                } catch (e: Exception) { false }
                val h12 = if (intent.hour % 12 == 0) 12 else intent.hour % 12
                val ap = if (intent.hour < 12) "AM" else "PM"
                val mm = intent.minute.toString().padStart(2, '0')
                say(if (done) "Alarm set for $h12:$mm $ap." else "I couldn't open the clock app.")
            }
            is JarvisIntent.Say -> say(intent.text)
            is JarvisIntent.PhoneOpen -> {
                val launched = try {
                    val i: Intent? = when {
                        intent.kind == "APP" && intent.arg == "settings" -> Intent(Settings.ACTION_SETTINGS)
                        intent.kind == "APP" && intent.arg in setOf("explorer", "downloads", "documents", "desktopfolder") ->
                            Intent("android.intent.action.VIEW_DOWNLOADS")
                        intent.kind == "APP" -> PHONE_APP_PKGS[intent.arg]
                            ?.let { context.packageManager.getLaunchIntentForPackage(it) }
                            ?: context.packageManager.getLaunchIntentForPackage(intent.arg)
                        else -> Intent(Intent.ACTION_VIEW, Uri.parse(intent.arg))
                    }
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(i)
                        true
                    } else false
                } catch (e: Exception) { false }
                say(
                    if (launched) "Opening that on your phone."
                    else "I couldn't find that app on your phone."
                )
            }
            is JarvisIntent.Call -> {
                fun dial(num: String?): Boolean = try {
                    context.startActivity(
                        Intent(Intent.ACTION_DIAL, Uri.parse(if (num.isNullOrEmpty()) "tel:" else "tel:$num"))
                            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    )
                    true
                } catch (e: Exception) { false }

                val target = intent.target.trim()
                var placed = false
                var failMsg: String? = null
                when {
                    target.isEmpty() -> placed = dial(null)
                    target.any { it.isDigit() } ->
                        placed = dial(target.filter { it.isDigit() || it == '+' })
                    else -> {
                        var num: String? = null
                        try {
                            context.contentResolver.query(
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                                arrayOf("%$target%"), null
                            )?.use { c -> if (c.moveToFirst()) num = c.getString(0) }
                        } catch (se: SecurityException) {
                            failMsg = "I need the Contacts permission to find $target."
                        }
                        if (failMsg == null) placed = !num.isNullOrEmpty() && dial(num)
                    }
                }
                say(
                    failMsg ?: when {
                        placed && target.isEmpty() -> "Opening your dialer."
                        placed -> "Calling $target."
                        else -> "I couldn't find ${if (target.isEmpty()) "anyone" else target} in your contacts."
                    }
                )
            }
            JarvisIntent.Joke -> say(listOf(
                "Why do programmers prefer dark mode? Because light attracts bugs.",
                "I would tell you a UDP joke, but you might not get it.",
                "There are only 10 kinds of people: those who understand binary and those who don't.",
                "A SQL query walks into a bar, sees two tables and asks: can I join you?",
                "My PC said it needed a break. I said: so do I."
            ).random())
            JarvisIntent.Status -> {
                val s = stats
                if (!online || s == null) say("I have no telemetry from your PC right now.")
                else {
                    val h = s.uptimeSec / 3600
                    say("${s.host} is running. CPU at ${s.cpu} percent, memory at ${s.memPct} percent, up for $h hours.")
                }
            }
            JarvisIntent.PhoneBattery -> {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                say("Your phone is at $pct percent.")
            }
            JarvisIntent.TimeNow -> say("It's " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()) + ".")
            JarvisIntent.DateToday -> say("Today is " + DateFormat.getDateInstance(DateFormat.FULL).format(Date()) + ".")
            JarvisIntent.Greeting -> {
                val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val part = if (h < 12) "Good morning" else if (h < 18) "Good afternoon" else "Good evening"
                say("$part. " + if (online) "All systems are online." else "Your PC is not connected yet.")
            }
            JarvisIntent.Thanks -> say("You're welcome.")
            JarvisIntent.Help -> say("Say Veltrix then your command — open Chrome, call Abu, write an email to Ali, lock my PC. Tap What can I say for the full list.")
            is JarvisIntent.Unknown -> say(apology)
        }
    }

    // ---------- Speech recognition ----------
    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }
    DisposableEffect(Unit) { onDispose { recognizer?.destroy() } }

    // Wake-mode restart bridge. The recognition listener (declared inside
    // startListening) needs to re-arm the mic, but Kotlin local functions can't
    // be referenced before their declaration — so the listener goes through
    // this holder instead of naming startListening directly.
    var startListeningRef: (() -> Unit)? = null
    fun scheduleWakeRestart() {
        if (!wakeMode) return
        // Delay lets the TTS reply finish (startListening() calls tts.stop()).
        wakeHandler.postDelayed({ if (wakeMode && !listening) startListeningRef?.invoke() }, 2500)
    }

    fun startListening() {
        if (recognizer == null) {
            say("Speech recognition isn't available on this phone. You can type a command instead.")
            return
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false; level = 0f }
            override fun onError(error: Int) {
                listening = false; level = 0f
                say(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear anything."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "I need microphone permission."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a connection on this phone."
                    else -> "Speech recognition failed. You can type instead."
                })
                scheduleWakeRestart()
            }
            override fun onResults(results: Bundle?) {
                listening = false; level = 0f
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) say(apology) else execute(text)
                scheduleWakeRestart()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { heard = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (hindi) "hi-IN" else Locale.getDefault().toLanguageTag())
        }
        tts.stop()
        heard = ""
        recognizer.startListening(i)
    }
    startListeningRef = { startListening() }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else say("I need microphone permission to hear you. You can still type commands.")
    }

    fun onCoreTap() {
        if (listening) {
            // Manual stop must really stop: cancel wake mode too, or the
            // scheduled restart would pop the mic right back open.
            wakeMode = false
            wakeHandler.removeCallbacksAndMessages(null)
            recognizer?.stopListening()
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // ---------- UI ----------
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ArcReactor(listening = listening, level = level, online = online, onClick = { onCoreTap() })

        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                listening -> "Listening..."
                wakeMode -> "Say \"Veltrix\" then your command"
                online -> "Online - tap the core to speak"
                else -> "Offline - connect to your PC first"
            },
            color = if (online || wakeMode) Aqua else TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium
        )

        Spacer(Modifier.height(10.dp))
        GlassButton(
            onClick = {
                wakeMode = !wakeMode
                if (!wakeMode) wakeHandler.removeCallbacksAndMessages(null)
                else if (!listening && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
            },
            tint = Aqua,
            subtle = !wakeMode,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp)
        ) {
            Text(if (wakeMode) "Veltrix: listening" else "Veltrix wake word", fontSize = 12.sp)
        }

        Spacer(Modifier.height(14.dp))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                if (heard.isNotBlank()) {
                    Text("\"$heard\"", color = TextMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                }
                Text(reply, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(14.dp))
        // ---- live PC telemetry ----
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (online && stats != null) AccentGreen else TextMuted))
                    Spacer(Modifier.width(8.dp))
                    Text(stats?.host ?: "PC telemetry", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(stats?.let { "up ${it.uptimeSec / 3600}h ${(it.uptimeSec % 3600) / 60}m" } ?: "waiting...", color = TextMuted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                val s = stats
                // All four gauges in ONE compact row: CPU · RAM · Disk · GPU
                // (disk/GPU only when the desktop actually reports them).
                val diskPct = s?.diskPct ?: -1
                val gpuPct = s?.gpuPct ?: -1
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ArcGauge("CPU", s?.cpu ?: 0, Aqua, compact = true)
                    ArcGauge("RAM", s?.memPct ?: 0, Violet, compact = true)
                    if (diskPct >= 0) ArcGauge("Disk", diskPct, Rose, compact = true)
                    if (gpuPct >= 0) ArcGauge("GPU", gpuPct, AccentGreen, compact = true)
                }
                if (s != null) {
                    Spacer(Modifier.height(4.dp))
                    // Disk is a live active-time % (Task Manager's Disk column),
                    // so the old "GB used of total" capacity line is gone — it
                    // was the wrong metric entirely.
                    val parts = mutableListOf("%.1f of %.1f GB memory in use".format(s.memUsedGb, s.memTotalGb))
                    Text(parts.joinToString("\n"), color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.Center)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        // ---- one-tap actions (they go through the same parser as voice) ----
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                "Lock" to "lock my pc",
                "Screenshot" to "take a screenshot",
                "Chrome" to "open chrome",
                "Mute" to "mute",
                "Play/Pause" to "play",
                "Sleep" to "sleep the pc"
            ).forEach { (label, phrase) ->
                GlassButton(subtle = true, onClick = { execute(phrase) }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(label, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                colors = glassTextFieldColors(),
                shape = RoundedCornerShape(16.dp),
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text("Or type a command...") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            GlassButton(onClick = { if (typed.isNotBlank()) { execute(typed.trim()); typed = "" } }, tint = Aqua) { Text("Go") }
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(
                subtle = true,
                onClick = { hindi = !hindi },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) { Text(if (hindi) "Hindi / हिंदी" else "English", fontSize = 13.sp) }
            GlassButton(
                subtle = true,
                onClick = { showHelp = !showHelp },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) { Text(if (showHelp) "Hide commands" else "What can I say?", fontSize = 13.sp) }
        }
        if (showHelp) {
            Spacer(Modifier.height(10.dp))
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(
                        "Veltrix wake word" to "\"Veltrix, open Chrome\"\nSay \"Veltrix\" alone to get \"Yes?\"\nTap the wake-word pill to keep the mic hot",
                        "Pro commands" to "open my 3 chrome profile and open fiverr in it\nwrite an email to ali@example.com message on my way\nsend a whatsapp message to 0300 1234567 saying hello\ncall Abu G\nopen chrome on phone (runs on the phone instead)",
                        "Apps & sites" to "open Chrome / Notepad / WhatsApp / Downloads\nopen YouTube / Gmail / GitHub\nclose Chrome",
                        "Search" to "search Hindi songs in YouTube\nplay Arijit Singh on YouTube\nsearch cats on Amazon\nshow images of tigers\nweather in London\ntranslate good morning to Hindi\nnavigate to Lahore Fort",
                        "Control the PC" to "lock / sleep / restart / shut down\ntake a screenshot\nshow desktop / switch window\nnew tab / close tab / refresh\ncopy / paste / press enter\nscroll down / click / double click\ntype hello world",
                        "Music & volume" to "volume up / down / mute\nvolume 40 / brightness 50\nnext song / previous song\npause",
                        "Windows" to "maximize / minimize window\nprevious tab\nnew desktop / next desktop / previous desktop\npress home / end / delete / page up\nrestart chrome (reopens just that app)",
                        "On this phone" to "flashlight on / off\nset a timer for 5 minutes\nset an alarm for 7 am\nwake me at 6:30 am\nphone volume up / down / 50\nwhat's my battery\nwhat's my pc's name\ntell me a joke\nflip a coin / roll a dice",
                        "Maths & units" to "what is 15 percent of 200\nconvert 5 km to miles\n10 kg in pounds\nrandom number between 1 and 50\nwhat's 12 times 8",
                        "Hinglish & हिंदी" to "chrome kholo\nawaaz badhao\nyoutube pe gaane chalao\nक्रोम खोलो"
                    ).forEach { (title, lines) ->
                        Column {
                            Text(title, color = Aqua, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(lines, fontSize = 13.sp, lineHeight = 19.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Spoken replies", color = TextMuted, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Switch(checked = voiceReplies, onCheckedChange = { voiceReplies = it; if (!it) tts.stop() })
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Animated arc-reactor. Spins slowly, brightens while listening and pulses with your voice level. */
@Composable
fun ArcReactor(listening: Boolean, level: Float, online: Boolean, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "reactor")
    val spinA = t.animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "a")
    val spinB = t.animateFloat(0f, 360f, infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "b")
    val pulse = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p")
    val smoothLevel by animateFloatAsState(level, tween(120), label = "level")

    Box(
        modifier = Modifier
            .size(250.dp)
            .clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2f
            val main = when {
                !online -> Color(0xFF64748B)
                listening -> Rose
                else -> Aqua
            }
            val second = if (listening) Aqua else Violet
            val boost = 1f + 0.10f * smoothLevel + 0.03f * pulse.value

            // ambient glow
            drawCircle(
                Brush.radialGradient(listOf(main.copy(alpha = 0.30f + 0.30f * smoothLevel), Color.Transparent), center = c, radius = r),
                radius = r, center = c
            )
            // outer dashed ring
            rotate(spinA.value, pivot = c) {
                drawCircle(
                    main.copy(alpha = 0.75f), radius = r * 0.94f, center = c,
                    style = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(22f, 16f)))
                )
            }
            // three counter-rotating arc segments
            rotate(-spinB.value, pivot = c) {
                for (i in 0 until 3) {
                    drawArc(
                        second.copy(alpha = 0.9f), startAngle = i * 120f + 12f, sweepAngle = 76f, useCenter = false,
                        topLeft = Offset(c.x - r * 0.78f, c.y - r * 0.78f), size = Size(r * 1.56f, r * 1.56f),
                        style = Stroke(7.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
            // thin inner ring
            drawCircle(main.copy(alpha = 0.9f), radius = r * 0.60f * boost, center = c, style = Stroke(2.dp.toPx()))
            // glowing core
            val coreR = r * 0.42f * boost
            drawCircle(
                Brush.radialGradient(listOf(Color.White, main.copy(alpha = 0.85f), Color.Transparent), center = c, radius = coreR * 1.25f),
                radius = coreR * 1.25f, center = c
            )
        }
        Icon(Icons.Default.Mic, contentDescription = "Talk to Jarvis", tint = Color(0xFF05101A), modifier = Modifier.size(40.dp))
    }
}

/** 270-degree gauge with the percentage in the middle.
 *  [compact] shrinks it so all four (CPU/RAM/Disk/GPU) fit on one row. */
@Composable
fun ArcGauge(label: String, pct: Int, color: Color, compact: Boolean = false) {
    val anim by animateFloatAsState(pct.coerceIn(0, 100) / 100f, tween(700), label = "gauge")
    val trackColor = if (LocalIsDark.current) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.12f)
    val boxSize = if (compact) 74.dp else 104.dp
    Box(Modifier.size(boxSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(if (compact) 5.dp else 8.dp)) {
            val stroke = Stroke((if (compact) 7.dp else 9.dp).toPx(), cap = StrokeCap.Round)
            drawArc(trackColor, 135f, 270f, false, style = stroke)
            if (anim > 0.005f) drawArc(color, 135f, 270f * anim, false, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$pct%", fontSize = (if (compact) 14 else 20).sp, fontWeight = FontWeight.Bold)
            Text(label, color = TextMuted, fontSize = (if (compact) 10 else 11).sp)
        }
    }
}
