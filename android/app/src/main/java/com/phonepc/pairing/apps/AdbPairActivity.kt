package com.phonepc.pairing.apps

import android.os.Bundle
import android.provider.Settings
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonepc.pairing.service.PairingForegroundService
import com.phonepc.pairing.ui.DuoLinkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where the six digits are typed: the PC asks for a pairing, this screen opens
 * from the notification, and the phone pairs with its own wireless debugging.
 * The pairing is loopback-only and short-lived; nothing is entered twice.
 */
class AdbPairActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DuoLinkTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AdbPairScreen(onClose = { finish() })
                }
            }
        }
    }
}

@Composable
private fun AdbPairScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var paired by remember { mutableStateOf(false) }

    fun submit() {
        if (busy || paired || code.length != 6) return
        if (!AdbBridge.wirelessDebuggingOn(context)) {
            error = "Wireless debugging is off. Turn it on in Developer options, then try again."
            return
        }
        busy = true
        error = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) { AdbBridge.pair(context, code) }
            if (result.isSuccess) {
                // Open the shell link right away so the first app window does
                // not pay for the connection.
                withContext(Dispatchers.IO) { runCatching { AdbBridge.ensureConnected(context) } }
                AdbPairPrompt.clear(context)
                PairingForegroundService.reportAdbPair(true, "")
                paired = true
            } else {
                val message = when (result.exceptionOrNull()?.message) {
                    "nocode" -> "Android has no pairing code open. On the phone: Settings → Developer options → Wireless debugging → Pair device with pairing code, then type that code here."
                    else -> result.exceptionOrNull()?.message ?: "Pairing failed"
                }
                error = message
                PairingForegroundService.reportAdbPair(false, message)
            }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = if (paired) "Paired" else "Pair with wireless debugging",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = if (paired) {
                "The phone is paired with its own wireless debugging. This PC can open your apps in windows now."
            } else {
                "On this phone open Developer options → Wireless debugging → Pair device with pairing code. " +
                    "Android shows a six-digit code — type it here. No IP address or port is needed."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!paired) {
            OutlinedTextField(
                value = code,
                onValueChange = { input ->
                    code = input.filter { it.isDigit() }.take(6)
                    if (error.isNotEmpty()) error = ""
                },
                singleLine = true,
                label = { Text("6-digit code") },
                textStyle = TextStyle(
                    textAlign = TextAlign.Center,
                    fontSize = 26.sp,
                    letterSpacing = 8.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )

            if (error.isNotEmpty()) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Button(
                onClick = { submit() },
                enabled = code.length == 6 && !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    Text("Pairing…")
                } else {
                    Text("Pair this phone")
                }
            }

            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    }
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Open Developer options")
            }
        } else {
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }
    }
}
