package com.phonepc.pairing.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonObject
import com.phonepc.pairing.network.Protocol
import com.phonepc.pairing.service.PairingForegroundService
import kotlin.math.abs

/**
 * Wireless touchpad & keyboard.
 *
 * Gesture model (relative, like a real laptop touchpad):
 *  - one finger drags  -> the cursor moves by the distance the finger moved,
 *                         scaled by the sensitivity slider. Nothing teleports
 *                         to the finger position, so small moves are precise.
 *  - tap               -> left click (double-tap = double click, within 320 ms)
 *  - long press (no movement) -> right click
 *  - two fingers       -> vertical scroll from the centroid
 *  - Drag (toggle)      -> holds the left button so items can be dragged
 */
@Composable
fun TouchpadScreen() {
    var textInput by remember { mutableStateOf("") }
    // Cursor gain. 1x = the finger's distance is the cursor's distance; higher
    // values cross the screen with shorter strokes. Desktop applies a gentle
    // speed curve on top, so this stays a pure multiplier.
    var sensitivity by remember { mutableStateOf(1.4f) }
    var dragMode by remember { mutableStateOf(false) }
    var acceleration by remember { mutableStateOf(true) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    fun sendMouseClick(button: String, double: Boolean = false) {
        val payload = JsonObject().apply {
            addProperty("button", button)
            addProperty("double", double)
        }
        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(Protocol.TYPE_PC_MOUSE_CLICK, payload)
    }

    fun sendMouseScroll(deltaY: Float) {
        val payload = JsonObject().apply {
            addProperty("deltaY", deltaY)
        }
        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(Protocol.TYPE_PC_MOUSE_SCROLL, payload)
    }

    fun sendKey(key: String) {
        val payload = JsonObject().apply {
            addProperty("specialKey", key)
        }
        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(Protocol.TYPE_PC_KEY_INPUT, payload)
    }

    // Modifier combos ("CTRL+C") go through PC_KEY_COMBOS, which the desktop
    // resolves against nut.Key names (LeftControl, C, ...).
    fun sendCombo(chord: String) {
        val map = mapOf(
            "CTRL" to "LeftControl", "SHIFT" to "LeftShift", "ALT" to "LeftAlt",
            "WIN" to "LeftSuper", "META" to "LeftSuper"
        )
        val keys = chord.split("+").map { part ->
            val upper = part.uppercase()
            map[upper] ?: upper
        }
        val payload = JsonObject().apply {
            com.google.gson.JsonArray().also { arr -> keys.forEach { arr.add(it) } }
                .let { add("keys", it) }
        }
        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(Protocol.TYPE_PC_KEY_COMBOS, payload)
    }

    fun sendText(text: String) {
        val payload = JsonObject().apply {
            addProperty("text", text)
        }
        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(Protocol.TYPE_PC_KEY_INPUT, payload)
    }

    fun sendMediaAction(action: String) {
        val payload = JsonObject().apply {
            addProperty("action", action)
        }
        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(Protocol.TYPE_PC_MEDIA_ACTION, payload)
    }

    fun sendSystemAction(action: String) {
        val payload = JsonObject().apply {
            addProperty("action", action)
        }
        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(Protocol.TYPE_PC_SYSTEM_ACTION, payload)
    }

    // Stable holders captured by the gesture lambda (no recomposition churn).
    val lastTapAt = remember { longArrayOf(0L) }

    // Pad height follows the device: ~46% of the screen, clamped so small phones
    // still get a usable surface and tablets/landscape don't swallow the buttons.
    val padHeight = with(LocalConfiguration.current) {
        (screenHeightDp * 0.46f).dp.coerceAtLeast(340.dp).coerceAtMost(560.dp)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Wireless Touchpad & Keyboard",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Move your finger to move the cursor • Tap = click • Two-finger tap = right click • 2 fingers = scroll",
            fontSize = 12.sp,
            color = TextMuted,
            modifier = Modifier.padding(bottom = 12.dp)
        )

// Main Touchpad Area (fixed height: weight() is illegal inside a scrollable column)
        val isDarkPad = LocalIsDark.current
        // Light theme needs an ink-toned pad: the old white-on-white fill/border
        // made the trackpad edge invisible on light backgrounds.
        val padFill = if (isDarkPad) listOf(
            Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.03f), Color.White.copy(alpha = 0.08f)
        ) else listOf(
            Color(0xFF1E293B).copy(alpha = 0.055f), Color(0xFF1E293B).copy(alpha = 0.015f), Color(0xFF1E293B).copy(alpha = 0.04f)
        )
        val padStroke = if (isDarkPad) listOf(
            Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.25f)
        ) else listOf(
            Color(0xFF1E293B).copy(alpha = 0.38f), Color(0xFF1E293B).copy(alpha = 0.12f), Color(0xFF1E293B).copy(alpha = 0.26f)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(padHeight)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(padFill)
                )
                .border(
                    1.dp,
                    androidx.compose.ui.graphics.Brush.linearGradient(padStroke),
                    RoundedCornerShape(28.dp)
                )
                .pointerInput(sensitivity, dragMode, acceleration) {
                    fun rel(dx: Float, dy: Float) {
                        if (abs(dx) < 0.01f && abs(dy) < 0.01f) return
                        val payload = JsonObject().apply {
                            addProperty("dx", dx * sensitivity)
                            addProperty("dy", dy * sensitivity)
                            addProperty("accel", acceleration)
                        }
                        PairingForegroundService.activeServiceInstance
                            ?.sendRawEnvelope(Protocol.TYPE_PC_MOUSE_MOVE, payload)
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var lastX = down.position.x
                        var lastY = down.position.y
                        var travelled = 0f
                        var twoFinger = false
                        var lastScrollY = down.position.y
                        var scrolled = false
                        var holdingLeft = false

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
                                    if (abs(dy) >= 4f) {
                                        sendMouseScroll(dy / 6f)
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

                            // Ignore the first few pixels: that is hand jitter,
                            // not an intent to move.
                            if (!twoFinger && travelled > viewConfiguration.touchSlop) {
                                if (dragMode && !holdingLeft) {
                                    val payload = JsonObject().apply { addProperty("button", "left") }
                                    PairingForegroundService.activeServiceInstance
                                        ?.sendRawEnvelope(Protocol.TYPE_PC_MOUSE_DOWN, payload)
                                    holdingLeft = true
                                }
                                rel(dx, dy)
                                pressed.first().consume()
                            }
                        }

                        if (holdingLeft) {
                            val payload = JsonObject().apply { addProperty("button", "left") }
                            PairingForegroundService.activeServiceInstance
                                ?.sendRawEnvelope(Protocol.TYPE_PC_MOUSE_UP, payload)
                        }

                        // Two fingers down and up without scrolling = right click.
                        if (twoFinger && !scrolled) {
                            sendMouseClick("right")
                        }

                        // A stationary press is a click, never a move.
                        if (!twoFinger && travelled <= viewConfiguration.touchSlop) {
                            val now = SystemClock.uptimeMillis()
                            val heldMs = now - down.uptimeMillis
                            if (heldMs >= 450L) {
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                sendMouseClick("right")
                                lastTapAt[0] = 0L
                            } else {
                                val isDouble = now - lastTapAt[0] in 1..320L
                                lastTapAt[0] = if (isDouble) 0L else now
                                sendMouseClick("left", double = isDouble)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "TRACKPAD",
                // Was a fixed dark slate, which vanished into the dark glass
                // panel ("text is black / can't read it"). Follow the theme.
                color = if (LocalIsDark.current) Color.White.copy(alpha = 0.16f) else Color(0xFF334155),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Sensitivity", fontSize = 11.sp, color = TextMuted)
            Slider(
                value = sensitivity,
                onValueChange = { sensitivity = it },
                valueRange = 0.6f..3f,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            Text(String.format("%.1fx", sensitivity), fontSize = 11.sp, color = TextMuted)
            Spacer(Modifier.width(6.dp))
            GlassButton(
                subtle = true,
                onClick = { dragMode = !dragMode },
                tint = if (dragMode) Rose else Aqua,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
            ) { Text(if (dragMode) "Drag on" else "Drag", fontSize = 11.sp) }
            Spacer(Modifier.width(4.dp))
            GlassButton(
                subtle = true,
                onClick = { acceleration = !acceleration },
                tint = if (acceleration) Aqua else Color(0xFF64748B),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
            ) { Text(if (acceleration) "Accel on" else "Accel off", fontSize = 11.sp) }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Left / Right Click Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassButton(
                onClick = { sendMouseClick("left") },
                modifier = Modifier.weight(1f).height(48.dp),
                tint = Aqua
            ) {
                Text("Left Click")
            }
            GlassButton(
                onClick = { sendMouseClick("right") },
                modifier = Modifier.weight(1f).height(48.dp),
                tint = Aqua
            ) {
                Text("Right Click")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Text & Special Keys
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                colors = glassTextFieldColors(),
                shape = RoundedCornerShape(16.dp),
                value = textInput,
                onValueChange = { textInput = it },
                placeholder = { Text("Type into PC...") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            GlassButton(
                onClick = {
                    if (textInput.isNotEmpty()) {
                        sendText(textInput)
                        textInput = ""
                    }
                },
                tint = PrimaryCyan
            ) {
                Text("Type")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Quick Keys Row
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("ENTER", "BACKSPACE", "TAB", "ESCAPE", "SPACE", "CTRL+C", "CTRL+V", "CTRL+Z").forEach { key ->
                GlassButton(
                    subtle = true,
                    onClick = { if (key.contains('+')) sendCombo(key) else sendKey(key) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(key, fontSize = 11.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Remote Media Controls Row
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            GlassButton(
                subtle = true,
                onClick = { sendMediaAction("VOLUME_DOWN") },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("Vol-", fontSize = 11.sp)
            }
            GlassButton(
                subtle = true,
                onClick = { sendMediaAction("PLAY_PAUSE") },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("⏯ Play", fontSize = 11.sp)
            }
            GlassButton(
                subtle = true,
                onClick = { sendMediaAction("VOLUME_UP") },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("Vol+", fontSize = 11.sp)
            }
            GlassButton(
                subtle = true,
                onClick = { sendMediaAction("VOLUME_MUTE") },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("Mute", fontSize = 11.sp)
            }
            GlassButton(
                onClick = { sendSystemAction("LOCK_PC") },
                tint = DangerRed,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("Lock PC", fontSize = 11.sp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
