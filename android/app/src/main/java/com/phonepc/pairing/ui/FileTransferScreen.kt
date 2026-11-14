package com.phonepc.pairing.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonObject
import com.phonepc.pairing.network.Protocol
import com.phonepc.pairing.service.PairingForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun FileTransferScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var statusMessage by remember { mutableStateOf("Ready to send files or clipboard") }
    var uploadProgress by remember { mutableStateOf(0) }
    var isUploading by remember { mutableStateOf(false) }

    // Files arriving from the PC
    val receivedStatus by PairingForegroundService.fileReceived.collectAsState()
    LaunchedEffect(receivedStatus) {
        if (receivedStatus.isNotEmpty()) statusMessage = receivedStatus
    }

    // Streams ONE file to the PC. Blocking work — call it on Dispatchers.IO.
    fun sendOneFile(uri: Uri): String {
        var fileName = "file_${System.currentTimeMillis()}.bin"
        var fileSize = 0L

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx != -1) fileName = cursor.getString(nameIdx)
                if (sizeIdx != -1) fileSize = cursor.getLong(sizeIdx)
            }
        }

        val transferId = UUID.randomUUID().toString()

        // Send FILE_META
        val metaPayload = JsonObject().apply {
            addProperty("transferId", transferId)
            addProperty("filename", fileName)
            addProperty("totalSize", fileSize)
        }
        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(
            Protocol.TYPE_FILE_META,
            metaPayload
        )

        // Chunked transfer (64KB chunks)
        val buffer = ByteArray(64 * 1024)
        var bytesRead: Int
        var totalSent = 0L

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                val slice = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
                val b64 = Base64.encodeToString(slice, Base64.NO_WRAP)

                val chunkPayload = JsonObject().apply {
                    addProperty("transferId", transferId)
                    addProperty("chunkBase64", b64)
                }
                PairingForegroundService.activeServiceInstance?.sendRawEnvelope(
                    Protocol.TYPE_FILE_CHUNK,
                    chunkPayload
                )

                totalSent += bytesRead
                if (fileSize > 0) {
                    uploadProgress = ((totalSent * 100) / fileSize).toInt().coerceIn(0, 100)
                }
            }
        }
        return fileName
    }

    // Multi-send: the picker returns ANY number of documents; they go out one
    // after another in a single coroutine so progress can't interleave.
    fun sendFilesToPc(uris: List<Uri>) {
        if (uris.isEmpty()) return
        coroutineScope.launch {
            isUploading = true
            for ((i, uri) in uris.withIndex()) {
                uploadProgress = 0
                statusMessage = if (uris.size > 1) "Sending file ${i + 1} of ${uris.size}…" else "Reading file..."
                val name = withContext(Dispatchers.IO) { sendOneFile(uri) }
                statusMessage = "Sent '$name' (${i + 1}/${uris.size})"
            }
            isUploading = false
            statusMessage = if (uris.size > 1) "Sent ${uris.size} files to the PC" else "File sent to the PC"
        }
    }

    // OpenMultipleDocuments lets the user tick MANY files in the system
    // picker (GetContent could only ever return a single uri).
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris != null && uris.isNotEmpty()) {
            sendFilesToPc(uris)
        }
    }

    fun sendCurrentClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val text = clipData.getItemAt(0).coerceToText(context).toString()
            PairingForegroundService.activeServiceInstance?.sendClipboardToPc(text)
            statusMessage = "Clipboard text (${text.length} chars) sent to PC!"
        } else {
            statusMessage = "Clipboard is empty"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Files & Clipboard Sync",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Local Wi-Fi file transfers (up to 5GB) straight to the PC's Downloads\\DuoLink folder.",
            fontSize = 12.sp,
            color = TextMuted,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                GlassButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    tint = PrimaryCyan
                ) {
                    Text("Select Files & Send to PC (multi)")
                }

                if (isUploading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { uploadProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = PrimaryCyan
                    )
                    Text(
                        text = "Transferring: $uploadProgress%",
                        fontSize = 12.sp,
                        color = PrimaryCyan,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Clipboard Sync",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = "Android 10+ restricts background clipboard access. Tap below to push your phone clipboard to your PC.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                GlassButton(
                    onClick = { sendCurrentClipboard() },
                    modifier = Modifier.fillMaxWidth(),
                    tint = Aqua
                ) {
                    Text("Send Current Phone Clipboard")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = statusMessage,
            color = TextMuted,
            fontSize = 13.sp
        )
    }
}
