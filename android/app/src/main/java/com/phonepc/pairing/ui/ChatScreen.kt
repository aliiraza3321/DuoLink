package com.phonepc.pairing.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonObject
import com.phonepc.pairing.data.ChatMessage
import com.phonepc.pairing.network.Protocol
import com.phonepc.pairing.service.PairingForegroundService
import java.util.UUID

/** Matches http(s) URLs inside a chat message so they become tappable. */
private val URL_RE = Regex("https?://[^\\s)\\]>'\"]+")

/**
 * Message body: the whole bubble is SELECTABLE (SelectionContainer) and any
 * URL inside it is tappable — the tap is resolved against the laid-out text so
 * only the link itself opens, while long-press selection still works.
 */
@Composable
private fun MessageBody(text: String, color: Color) {
    val uriHandler = LocalUriHandler.current
    val annotated: AnnotatedString = remember(text) {
        buildAnnotatedString {
            var last = 0
            for (m in URL_RE.findAll(text)) {
                if (m.range.first > last) append(text.substring(last, m.range.first))
                val url = m.value.trimEnd('.', ',', ';', ':', '!', '?')
                withStyle(SpanStyle(color = PrimaryCyan, textDecoration = TextDecoration.Underline)) {
                    append(url)
                }
                last = m.range.first + url.length
            }
            if (last < text.length) append(text.substring(last))
        }
    }
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }

    SelectionContainer {
        Text(
            text = annotated,
            color = color,
            fontSize = 14.sp,
            onTextLayout = { layout = it },
            modifier = Modifier
                .pointerInput(text) {
                    detectTapGestures { pos ->
                        val l = layout ?: return@detectTapGestures
                        val offset = l.getOffsetForPosition(pos)
                        val hit = URL_RE.findAll(annotated.text)
                            .firstOrNull { it.range.first <= offset && offset <= it.range.last + 1 }
                            ?.value
                            ?.trimEnd('.', ',', ';', ':', '!', '?')
                            ?: return@detectTapGestures
                        runCatching { uriHandler.openUri(hit) }
                    }
                }
                .padding(12.dp)
        )
    }
}

@Composable
fun ChatScreen() {
    var messageText by remember { mutableStateOf("") }
    val chatHistory = remember { mutableStateListOf<ChatMessage>() }

    val incomingText by PairingForegroundService.incomingChatFlow.collectAsState()

    LaunchedEffect(incomingText) {
        val text = incomingText
        if (!text.isNullOrBlank()) {
            chatHistory.add(
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    text = text,
                    sender = "PC",
                    isFromMe = false,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun sendMessage() {
        val text = messageText.trim()
        if (text.isEmpty()) return

        val payload = JsonObject().apply {
            addProperty("text", text)
            addProperty("senderName", "Android Phone")
        }
        val sent = PairingForegroundService.activeServiceInstance?.sendRawEnvelope(
            Protocol.TYPE_CHAT_MESSAGE,
            payload
        ) ?: false

        if (sent) {
            chatHistory.add(
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    text = text,
                    sender = "Me",
                    isFromMe = true,
                    timestamp = System.currentTimeMillis()
                )
            )
            messageText = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            reverseLayout = false
        ) {
            items(chatHistory) { msg ->
                // Theme-aware bubbles: the old white-text-on-translucent-white
                // made messages unreadable in light mode. Sent keeps the brand
                // violet; received flips to a light card with dark ink.
                val isDarkChat = LocalIsDark.current
                val bg = when {
                    msg.isFromMe && isDarkChat -> Violet.copy(alpha = 0.7f)
                    msg.isFromMe -> PrimaryVioletLight
                    isDarkChat -> Color.White.copy(alpha = 0.09f)
                    else -> Color.White.copy(alpha = 0.92f)
                }
                val txtColor = when {
                    msg.isFromMe -> Color.White
                    isDarkChat -> GlassText
                    else -> TextLight
                }
                val bubbleBorder = if (isDarkChat) Color.White.copy(alpha = 0.18f) else Color(0x1E0F121E)
                val align = if (msg.isFromMe) Alignment.CenterEnd else Alignment.CenterStart

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    contentAlignment = align
                ) {
                    Surface(
                        color = bg,
                        shape = RoundedCornerShape(20.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, bubbleBorder)
                    ) {
                        MessageBody(msg.text, txtColor)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                colors = glassTextFieldColors(),
                shape = RoundedCornerShape(16.dp),
                value = messageText,
                onValueChange = { messageText = it },
                placeholder = { Text("Message to PC...") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            GlassButton(
                onClick = { sendMessage() },
                tint = PrimaryCyan
            ) {
                Text("Send")
            }
        }
    }
}
