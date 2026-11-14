package com.phonepc.pairing.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.gson.JsonObject
import com.phonepc.pairing.data.ConnectionState
import com.phonepc.pairing.network.Protocol
import com.phonepc.pairing.service.PairingForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * PC Remote Screen: view and control the Windows desktop from the phone.
 *
 * Real pipeline: PC streams JPEG frames (PC_SCREEN_FRAME) over the pinned WSS
 * session -> decoded here -> gestures map to absolute desktop coordinates via
 * PC_MOUSE_MOVE_ABS / PC_MOUSE_DOWN / PC_MOUSE_UP / PC_MOUSE_SCROLL and
 * keyboard via PC_KEY_INPUT. Input still respects the PC's "Allow Phone to
 * Control" toggle and the Ctrl+Alt+Shift+Q kill switch.
 *
 * Gestures (stacked detectors cooperate through consumption):
 *  - tap             -> move cursor there + left click
 *  - double tap      -> double click
 *  - long press      -> move cursor there + right click
 *  - one-finger drag -> cursor follows the finger; in DRAG mode the left button
 *                       is held for the whole gesture, in MOVE mode it only repositions
 *  - two fingers     -> vertical scroll (consumes events so it never fights the drag detector)
 *
 * NOTE: no shortcut chips / quick-key row on this screen by request - only the
 * stream controls, the viewport, and a plain text field remain.
 */
@Composable
fun RemotePcScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val view = LocalView.current

    val connectionState by PairingForegroundService.connectionState.collectAsState()
    val ack by PairingForegroundService.pcScreenAck.collectAsState()
    val streaming by PairingForegroundService.pcScreenActive.collectAsState()

    // 30fps default: the desktop picture on a2.4GHz LAN link is bandwidth-bound,
    // and 60fps here (while the phone->PC mirror also runs) starved BOTH streams
    // into visible lag. 30fps reads as smooth for desktop content; the chips
    // below still allow 60fps on a strong link.
    var fps by remember { mutableStateOf(30) }
    var quality by remember { mutableStateOf(55) }
    var maxDim by remember { mutableStateOf(960) }
    // Fit by default so the PC screen is NEVER cropped (user request); the
    // Fill/Fill chip still lets the user cover the whole viewport on demand.
    var fillMode by remember { mutableStateOf(false) }

    // Fullscreen typing: a 1dp invisible text field owns the soft keyboard.
    // Tapping the stream both clicks at that spot on the PC and raises the
    // keyboard, so typing starts right where the user tapped - no visible
    // input container floating over the mirrored desktop (previous attempt).
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun summonKeyboard() {
        try {
            focusRequester.requestFocus()
            keyboard?.show()
        } catch (_: Exception) { }
    }
    // Keyboard is summoned on DEMAND only (dedicated button below), never on
    // every click — tapping the stream is how you interact with the desktop,
    // so popping the keyboard each time was intrusive (user request).
    var dragMode by remember { mutableStateOf(false) }
    // Fullscreen lives in the shared service state: the app shell reads the
    // same flag and hides its top bar, segment switcher and bottom nav, so a
    // fullscreen mirror really is ONLY the PC screen.
    val immersive by PairingForegroundService.pcScreenImmersive.collectAsState()
    fun setImmersive(on: Boolean) = PairingForegroundService.setPcScreenImmersive(on)
    var landscapeLocked by rememberSaveable { mutableStateOf(false) }

    // Turning the phone sideways is the "show me everything" gesture: fill the
    // whole display. Rotating back drops out of fullscreen unless the user
    // explicitly pressed the Landscape toggle.
    val screenOrientation = LocalConfiguration.current.orientation
    LaunchedEffect(screenOrientation) {
        if (screenOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            setImmersive(true)
        } else if (!landscapeLocked) {
            setImmersive(false)
        }
    }
    var startError by remember { mutableStateOf("") }
    var startAttempt by remember { mutableStateOf(0) }
    var ackSeqBeforeStart by remember { mutableStateOf<Long?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var textInput by remember { mutableStateOf("") }
    // Buffered text behind the invisible fullscreen keyboard field.
    var hiddenInput by remember { mutableStateOf("") }
    // Leaving fullscreen drops the keyboard and resyncs the buffer, so the
    // next fullscreen session starts from a clean slate.
    LaunchedEffect(immersive) {
        if (!immersive && hiddenInput.isNotEmpty()) {
            hiddenInput = ""
            keyboard?.hide()
        }
    }

    val connected = connectionState == ConnectionState.CONNECTED

    // Decode loop: ONE coroutine, always the NEWEST frame.
    //
    // The previous version keyed a produceState on frame.seq, so every arriving
    // frame started its own decode coroutine. At 30-60fps on a phone those
    // coroutines stacked behind each other and then flushed in a burst - exactly
    // the "stream stops, then plays old frames very fast" artifact. The mailbox
    // (pcScreenFrame) is a single slot and StateFlow conflates, so while this
    // collector is busy decoding, intermediate frames are discarded rather than
    // queued: latency stays bounded and the display always shows the freshest
    // frame. The previous bitmap is held until the new one is ready, so there is
    // no flicker between frames.
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(Unit) {
        PairingForegroundService.pcScreenFrame.collect { f ->
            if (f == null) return@collect
            val decoded = withContext(Dispatchers.Default) {
                try {
                    BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size)
                } catch (_: Exception) {
                    null
                }
            }
            if (decoded != null) bitmap = decoded
        }
    }

    fun send(type: String, payload: JsonObject = JsonObject()) {
        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(type, payload)
    }

    fun startStream() {
        if (!connected) { startError = "PC not connected"; return }
        startError = ""
        val sent = PairingForegroundService.activeServiceInstance?.sendRawEnvelope(
            Protocol.TYPE_PC_SCREEN_START,
            JsonObject().apply {
                addProperty("fps", fps)
                addProperty("quality", quality)
                addProperty("maxDimension", maxDim)
            }
        ) ?: false
        if (!sent) {
            startError = "Could not send to PC - the link is not ready. Reconnect on the Pairing tab, then retry."
            return
        }
        ackSeqBeforeStart = ack?.seq
        startAttempt += 1
    }

    // No silent failures: if the PC never answers the start request, say so.
    LaunchedEffect(startAttempt) {
        if (startAttempt == 0) return@LaunchedEffect
        val attempt = startAttempt
        delay(5000)
        if (attempt == startAttempt && !streaming && ack?.seq == ackSeqBeforeStart) {
            startError = "PC did not answer in 5s. Check the DuoLink console on the PC for [RemotePC]/[WSS] logs, then retry."
        }
    }

    // Fullscreen owns the whole display: the system back gesture/button is the
    // exit (no on-screen chrome at all while immersive, by request).
    androidx.activity.compose.BackHandler(enabled = immersive) { setImmersive(false) }

    fun applyImmersive(on: Boolean) {
        val act = activity ?: return
        WindowInsetsControllerCompat(act.window, view).apply {
            if (on) {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    DisposableEffect(immersive) {
        applyImmersive(immersive)
        onDispose { applyImmersive(false) }
    }

    // Keep the screen on only while frames are flowing
    DisposableEffect(streaming) {
        if (streaming) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    fun stopStream() {
        send(Protocol.TYPE_PC_SCREEN_STOP)
        // Clear the flag locally - see PairingForegroundService.notePcScreenStopped.
        PairingForegroundService.notePcScreenStopped()
    }

    // Leaving the screen stops the stream and restores orientation - but a
    // rotation or activity recreation is not the user leaving. Stopping on every
    // dispose made turning the phone sideways kill the mirror, which is exactly
    // when people switch to it.
    DisposableEffect(Unit) {
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                stopStream()
                // Chrome-hidden fullscreen belongs to this screen only: hand the
                // bars back the moment the screen goes away.
                PairingForegroundService.setPcScreenImmersive(false)
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // "Full" strips every chrome row and padding so the desktop fills the
    // entire display; the viewport then has nothing around it to click by
    // accident and the touch gestures own the whole screen.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (immersive) 0.dp else 14.dp)
    ) {

        if (!immersive) {
        // ---------------- Status & stream controls ----------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (statusText, statusColor) = when {
                !connected -> "Offline" to Color(0xFF64748B)
                streaming -> "Streaming" to AccentGreen
                ack?.ok == false -> "Refused" to DangerRed
                else -> "Idle" to Color(0xFFF59E0B)
            }
            Surface(
                color = statusColor.copy(alpha = 0.20f),
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.55f))
            ) {
                Text(
                    "● $statusText",
                    color = statusColor, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }

            if (streaming) {
                Text("${fps}fps · q$quality · ${maxDim}px", color = TextMuted, fontSize = 11.sp)
            }

            Spacer(Modifier.weight(1f))

            GlassButton(
                onClick = {
                    if (streaming) stopStream() else startStream()
                },
                tint = if (streaming) DangerRed else PrimaryCyan,
                enabled = connected,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(if (streaming) "Stop" else "Start", fontSize = 12.sp)
            }

            GlassButton(
                onClick = {
                    landscapeLocked = !landscapeLocked
                    activity?.requestedOrientation = if (landscapeLocked)
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    // Turning the screen sideways is exactly when people want the
                    // whole display, so landscape also drops the chrome instead of
                    // leaving the desktop letterboxed between control rows.
                    if (landscapeLocked) setImmersive(true)
                },
                tint = if (landscapeLocked) Aqua else Color(0xFF64748B),
                subtle = true,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
            ) { Text("Landscape", fontSize = 12.sp) }

            GlassButton(
                onClick = { setImmersive(!immersive) },
                tint = if (immersive) Aqua else Color(0xFF64748B),
                subtle = true,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
            ) { Text("Full", fontSize = 12.sp) }
        }

        // Errors / hints
        val errorLine = when {
            startError.isNotEmpty() -> startError
            ack?.ok == false && !ack!!.error.isEmpty() -> "PC refused stream: ${ack!!.error}"
            !connected -> "Connect to your PC on the Pairing tab first."
            else -> ""
        }
        if (errorLine.isNotEmpty()) {
            Text(
                errorLine,
                color = if (ack?.ok == false) DangerRed else TextMuted,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else if (streaming) {
            Text(
                "Tap = click · hold = right click · two fingers = scroll. Input needs \"Allow Phone to Control\" on the PC.",
                color = TextMuted, fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        // ---------------- Stream settings (applied live while streaming) ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(480 to "480p", 720 to "720p", 1080 to "1080p", 1440 to "1440p", 1920 to "Full")
                .forEach { (dim, label) ->
                    StreamChip(label, dim == maxDim) {
                        maxDim = dim
                        if (streaming) startStream()
                    }
                }
            Spacer(Modifier.width(4.dp))
            listOf(5, 10, 15, 30, 60).forEach { f ->
                StreamChip("${f}fps", f == fps) {
                    fps = f
                    if (streaming) startStream()
                }
            }
            Spacer(Modifier.width(4.dp))
            listOf(40 to "q40", 60 to "q60", 80 to "q80").forEach { (q, label) ->
                StreamChip(label, q == quality) {
                    quality = q
                    if (streaming) startStream()
                }
            }
            Spacer(Modifier.width(4.dp))
            StreamChip(if (fillMode) "Fill" else "Fit", fillMode) { fillMode = !fillMode }
        }
        }

        // ---------------- Viewport ----------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = if (immersive) 0.dp else 10.dp)
                .clip(RoundedCornerShape(if (immersive) 0.dp else 18.dp))
                .background(Color.Black.copy(alpha = 0.85f))
                .border(
                    1.dp,
                    Color.White.copy(alpha = if (immersive) 0f else 0.14f),
                    RoundedCornerShape(if (immersive) 0.dp else 18.dp)
                )
                .onSizeChanged { viewport = it }
                .then(
                    if (bitmap != null && streaming) Modifier.screenGestures(
                        viewport = viewport,
                        imageW = bitmap!!.width,
                        imageH = bitmap!!.height,
                        fill = fillMode,
                        dragMode = dragMode,

                    ) else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null && streaming) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "PC screen",
                    contentScale = if (fillMode) ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        when {
                            !connected -> "PC is offline"
                            streaming -> "Waiting for first frame…"
                            else -> "PC Remote Screen"
                        },
                        color = if (LocalIsDark.current) GlassText else TextLight,
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (!connected) "Pair and connect on the Pairing tab, then press Start."
                        else "Press Start to stream the Windows desktop here.",
                        color = TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center
                    )
                }
            }

            // ✕ exits landscape / fullscreen. While immersive there is no chrome
            // at all (by request), so this overlay is the visible way out; it
            // also drops the landscape lock so the phone rotates back.
            if (landscapeLocked || immersive) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    // ✕ exits landscape / fullscreen. While immersive there is no
                    // chrome at all (by request), so this overlay is the visible
                    // way out; it also drops the landscape lock.
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
                            .clickable {
                                landscapeLocked = false
                                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                setImmersive(false)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✕", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    // On-demand keyboard: tap it only when you actually want to
                    // write — normal taps on the stream stay pure clicks.
                    if (immersive) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
                                .clickable { summonKeyboard() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("\u2328", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (!immersive) {
        // ---------------- Input controls (mode toggle + typing only, no shortcut chips) ----------------
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassButton(
                onClick = { dragMode = !dragMode },
                tint = if (dragMode) Rose else Aqua,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
            ) { Text(if (dragMode) "Drag on" else "Move", fontSize = 12.sp) }

            Spacer(Modifier.weight(1f))
        }
        }

        // Invisible keyboard anchor: never visible itself (no container over
        // the stream), it only holds focus so the soft keyboard can appear the
        // moment the user taps somewhere to write in fullscreen.
        if (immersive) {
            // The field itself is never seen - it exists so the IME has
            // somewhere to compose. Each change is diffed against the previous
            // buffer so typing, backspace and autocorrect all translate into
            // the right keystrokes on the PC (append -> text, delete ->
            // BACKSPACE, rewrite -> backspaces + the new tail).
            BasicTextField(
                value = hiddenInput,
                onValueChange = { new ->
                    val old = hiddenInput
                    if (new != old) {
                        val lcp = old.commonPrefixWith(new).length
                        repeat(old.length - lcp) {
                            PairingForegroundService.activeServiceInstance?.sendRawEnvelope(
                                Protocol.TYPE_PC_KEY_INPUT,
                                JsonObject().apply { addProperty("specialKey", "BACKSPACE") }
                            )
                        }
                        val insert = new.substring(lcp)
                        if (insert.isNotEmpty()) {
                            PairingForegroundService.activeServiceInstance?.sendRawEnvelope(
                                Protocol.TYPE_PC_KEY_INPUT,
                                JsonObject().apply { addProperty("text", insert) }
                            )
                        }
                        hiddenInput = new
                    }
                },
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false
                ),
                keyboardActions = KeyboardActions(onSend = {
                    PairingForegroundService.activeServiceInstance?.sendRawEnvelope(
                        Protocol.TYPE_PC_KEY_INPUT,
                        JsonObject().apply { addProperty("specialKey", "ENTER") }
                    )
                }),
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f)
                    .focusRequester(focusRequester)
            )
            return@Column
        }

        // Text typing row (portrait / chrome-visible mode only).
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                placeholder = { Text("Type on PC…", fontSize = 12.sp) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                colors = glassTextFieldColors(),
                modifier = Modifier.weight(1f)
            )
            GlassButton(
                onClick = {
                    if (textInput.isNotBlank()) {
                        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(
                            Protocol.TYPE_PC_KEY_INPUT,
                            JsonObject().apply { addProperty("text", textInput) }
                        )
                        textInput = ""
                    }
                },
                tint = PrimaryCyan,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)
            ) { Text("Send", fontSize = 12.sp) }
            GlassButton(
                onClick = {
                    PairingForegroundService.activeServiceInstance?.sendRawEnvelope(
                        Protocol.TYPE_PC_KEY_INPUT,
                        JsonObject().apply { addProperty("specialKey", "ENTER") }
                    )
                },
                subtle = true,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp)
            ) { Text("↵", fontSize = 13.sp) }
        }
    }
}

@Composable
private fun StreamChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Aqua.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.07f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(
                1.dp,
                if (selected) Color.White.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.14f),
                RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Color.White else TextMuted,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/**
 * Gesture stack for the desktop viewport: three cooperating pointerInput
 * detectors (scroll first so it can consume two-finger events before the drag
 * and tap detectors ever see them).
 */
private var lastScreenTapAt = 0L

private fun Modifier.screenGestures(
    viewport: IntSize,
    imageW: Int,
    imageH: Int,
    fill: Boolean,
    dragMode: Boolean,
    onTap: (() -> Unit)? = null
): Modifier {
    if (viewport.width <= 0 || viewport.height <= 0 || imageW <= 0 || imageH <= 0) return this

    val vw = viewport.width.toFloat()
    val vh = viewport.height.toFloat()
    val iw = imageW.toFloat()
    val ih = imageH.toFloat()

    // EXACT visible rectangle of the picture: same maths as ContentScale.Fit /
    // Crop, so a touch lands on the pixel under the finger even with black
    // letterbox bars, any PC aspect ratio and any resolution.
    val scale = if (fill) maxOf(vw / iw, vh / ih) else minOf(vw / iw, vh / ih)
    val dispW = iw * scale
    val dispH = ih * scale
    val offX = (vw - dispW) / 2f
    val offY = (vh - dispH) / 2f

    fun norm(pos: Offset): Pair<Float, Float>? {
        if (dispW <= 0f || dispH <= 0f) return null
        val nx = (pos.x - offX) / dispW
        val ny = (pos.y - offY) / dispH
        if (nx < -0.01f || nx > 1.01f || ny < -0.01f || ny > 1.01f) return null
        return nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
    }

    fun abs(x: Float, y: Float) {
        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(
            Protocol.TYPE_PC_MOUSE_MOVE_ABS,
            JsonObject().apply {
                addProperty("x", x)
                addProperty("y", y)
            }
        )
    }

    fun click(button: String, double: Boolean) {
        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(
            Protocol.TYPE_PC_MOUSE_CLICK,
            JsonObject().apply {
                addProperty("button", button)
                addProperty("double", double)
            }
        )
    }

    fun button(action: String) {
        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(
            if (action == "down") Protocol.TYPE_PC_MOUSE_DOWN else Protocol.TYPE_PC_MOUSE_UP,
            JsonObject().apply { addProperty("button", "left") }
        )
    }

    fun scroll(deltaY: Float) {
        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(
            Protocol.TYPE_PC_MOUSE_SCROLL,
            JsonObject().apply { addProperty("deltaY", deltaY) }
        )
    }

    // ONE recognizer owns the viewport. Three stacked detectors (tap + drag +
    // scroll) all wrote the cursor, which is what made it jump: a drag would
    // first be reported as a tap at the touch-down point. Now a gesture is
    // classified once, from the total travel, and only the winner talks to the PC.
    return this.pointerInput(viewport, imageW, imageH, fill, dragMode) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startedAt = down.uptimeMillis
            var lastX = down.position.x
            var lastY = down.position.y
            var travelled = 0f
            var twoFinger = false
            var lastScrollY = down.position.y
            var scrolled = false
            var holdingLeft = false

            // The cursor goes where the finger lands, once, so a tap clicks
            // that spot; subsequent movement re-aims it continuously.
            norm(down.position)?.let { (x, y) -> abs(x, y) }

            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break

                if (pressed.size >= 2) {
                    val cy = pressed.map { it.position.y }.average().toFloat()
                    if (!twoFinger) {
                        twoFinger = true
                        lastScrollY = cy
                    } else {
                        val dy = cy - lastScrollY
                        if (abs(dy) >= 3f) {
                            scroll(dy / 8f)
                            scrolled = true
                            lastScrollY = cy
                        }
                    }
                    pressed.forEach { it.consume() }
                    continue
                }

                val pos = pressed.first().position
                val dx = pos.x - lastX
                val dy = pos.y - lastY
                lastX = pos.x
                lastY = pos.y
                travelled += abs(dx) + abs(dy)

                if (!twoFinger && travelled > viewConfiguration.touchSlop) {
                    if (dragMode && !holdingLeft) {
                        button("down")
                        holdingLeft = true
                    }
                    norm(pos)?.let { (x, y) -> abs(x, y) }
                    pressed.first().consume()
                }
            }

            if (holdingLeft) button("up")

            val heldMs = SystemClock.uptimeMillis() - startedAt
            val stationary = !twoFinger && travelled <= viewConfiguration.touchSlop
            if (stationary) {
                norm(down.position)?.let { (x, y) ->
                    abs(x, y)
                    if (heldMs >= 450L) {
                        click("right", false)
                        lastScreenTapAt = 0L
                    } else {
                        val isDouble = SystemClock.uptimeMillis() - lastScreenTapAt in 1..340L
                        lastScreenTapAt = if (isDouble) 0L else SystemClock.uptimeMillis()
                        click("left", isDouble)
                        if (!isDouble) onTap?.invoke()
                    }
                }
            } else if (twoFinger && !scrolled) {
                // Two fingers tapped without scrolling = right click.
                click("right", false)
            }
        }
    }
}
