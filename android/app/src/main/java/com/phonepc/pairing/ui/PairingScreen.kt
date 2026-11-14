package com.phonepc.pairing.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.gson.JsonObject
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.phonepc.pairing.PairingDeepLink
import com.phonepc.pairing.data.PairingPayload
import com.phonepc.pairing.data.SessionCredentials
import com.phonepc.pairing.network.Protocol
import com.phonepc.pairing.network.TlsPinningSocket
import com.phonepc.pairing.pairing.DeviceNames
import com.phonepc.pairing.pairing.KeystoreStorage
import com.phonepc.pairing.pairing.TokenParser
import com.phonepc.pairing.service.PairingForegroundService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun PairingScreen(
    onPairingSuccess: () -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    var pairingStatus by remember { mutableStateOf("Point the camera at the QR on your PC") }
    var confirmationCode by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var manualUriText by remember { mutableStateOf("") }
    // Spec §7 Pair tab: paired-PC card + Unpair with confirmation
    var pairedCreds by remember { mutableStateOf(KeystoreStorage(context).getCredentials()) }
    var showUnpairConfirm by remember { mutableStateOf(false) }

    // Atomic scan lock to completely prevent duplicate triggers from 30fps camera frames
    val scanLock = remember { AtomicBoolean(false) }
    var activeSocketRef by remember { mutableStateOf<TlsPinningSocket?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    DisposableEffect(Unit) {
        onDispose {
            try { activeSocketRef?.close() } catch (_: Exception) {}
        }
    }

    fun resetPairingState() {
        try {
            activeSocketRef?.close()
        } catch (_: Exception) {}
        activeSocketRef = null
        scanLock.set(false)
        isConnecting = false
        confirmationCode = null
        errorMessage = null
        pairingStatus = "Point the camera at the QR on your PC"
    }

    fun initiatePairing(payload: PairingPayload) {
        isConnecting = true
        errorMessage = null
        pairingStatus = "Connecting securely to ${payload.host}:${payload.port}..."

        val keystore = KeystoreStorage(context)
        val deviceId = keystore.getOrCreateDeviceId()
        // Spec §9: clean marketing name ("Infinix Hot 50 Pro+") + raw model code
        val deviceName = DeviceNames.displayName(context)
        val modelCode = DeviceNames.modelCode

        try {
            activeSocketRef?.close()
        } catch (_: Exception) {}

        val socket = TlsPinningSocket(payload.fingerprintSha256, object : TlsPinningSocket.SocketEventListener {
            override fun onConnected() {
                pairingStatus = "TLS Handshake Verified. Requesting pairing..."
                val req = JsonObject().apply {
                    addProperty("token", payload.token)
                    addProperty("deviceName", deviceName)
                    addProperty("modelCode", modelCode)
                    addProperty("deviceId", deviceId)
                }
                activeSocketRef?.send(Protocol.createEnvelope(Protocol.TYPE_PAIR_REQUEST, req))
            }

            override fun onMessage(message: Protocol.Envelope) {
                when (message.type) {
                    Protocol.TYPE_PAIR_CONFIRM_CODE -> {
                        val code = message.payload.get("confirmationCode")?.asString ?: ""
                        confirmationCode = code
                        pairingStatus = "Verify code on your PC screen"
                    }

                    Protocol.TYPE_PAIR_RESULT -> {
                        val success = message.payload.get("success")?.asBoolean ?: false
                        if (success) {
                            confirmationCode = null
                            val sessionId = message.payload.get("sessionId")?.asString ?: ""
                            val sessionSecret = message.payload.get("sessionSecret")?.asString ?: ""
                            val pcDeviceName = message.payload.get("deviceName")?.asString ?: "Windows PC"

                            keystore.saveCredentials(
                                SessionCredentials(
                                    deviceId = deviceId,
                                    sessionId = sessionId,
                                    sessionSecret = sessionSecret,
                                    pcHost = payload.host,
                                    pcPort = payload.port,
                                    pcFingerprint = payload.fingerprintSha256,
                                    pcDeviceName = pcDeviceName
                                )
                            )

                            // Start persistent background link service
                            val serviceIntent = Intent(context, PairingForegroundService::class.java).apply {
                                action = PairingForegroundService.ACTION_START_LINK
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }

                            activeSocketRef?.close()
                            activeSocketRef = null
                            onPairingSuccess()
                        } else {
                            errorMessage = message.payload.get("error")?.asString ?: "Pairing declined by PC"
                            isConnecting = false
                            scanLock.set(false)
                        }
                    }
                }
            }

            override fun onClosed(code: Int, reason: String) {
                // If awaiting confirmation code, keep dialog open unless PC explicitly declined
                if (confirmationCode == null) {
                    isConnecting = false
                    scanLock.set(false)
                }
            }

            override fun onFailure(t: Throwable) {
                if (confirmationCode == null) {
                    errorMessage = "Connection error: ${t.localizedMessage ?: "Could not reach PC"}"
                    isConnecting = false
                    scanLock.set(false)
                }
            }
        })

        activeSocketRef = socket
        socket.connect(payload.host, payload.port)
    }

    // phonepair:// deep link (or a link re-delivered after recreation): start
    // pairing exactly as a QR scan would. snapshotFlow covers both the
    // already-arrived and while-composed cases.
    LaunchedEffect(Unit) {
        snapshotFlow { PairingDeepLink.pending.value }.collect { uri ->
            if (uri == null) return@collect
            PairingDeepLink.pending.value = null
            if (confirmationCode == null && !isConnecting) {
                TokenParser.parse(uri).onSuccess { payload ->
                    scanLock.set(true)
                    initiatePairing(payload)
                }.onFailure { err ->
                    errorMessage = "Invalid pairing link: ${err.message}"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header (mockup: plain words — no special glyphs, spec bug #1)
        Text(
            text = "Pair with PC",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Direct Wi-Fi · TLS fingerprint pinned",
            fontSize = 13.sp,
            color = TextMuted,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        if (confirmationCode != null) {
            // Confirmation Code Card
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Confirmation Code",
                        fontSize = 16.sp,
                        color = TextMuted
                    )
                    Text(
                        text = confirmationCode ?: "000000",
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = PrimaryCyan,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                    Text(
                        text = "Check your laptop screen!\nClick 'Approve Pairing' on your PC to complete pairing.",
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    CircularProgressIndicator(
                        modifier = Modifier.padding(vertical = 16.dp),
                        color = PrimaryCyan
                    )
                    GlassButton(subtle = true, 
                        onClick = { resetPairingState() },
                        tint = Color.White
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cancel / Scan Again")
                    }
                }
            }
        } else if (!hasCameraPermission) {
            // Camera Permission Explainer
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = "Camera",
                        tint = PrimaryCyan,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Camera Access Required",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "To scan the one-time cryptographic pairing QR on your computer screen, allow camera permission.",
                        fontSize = 13.sp,
                        color = TextMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    GlassButton(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        tint = PrimaryCyan,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Grant Camera Permission")
                    }
                }
            }
        } else {
            // Live camera viewfinder (mockup: rounded dark frame with cyan
            // corner brackets and a sweeping scan line).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF0A0C14))
                    .border(1.dp, Color(0x3322D3EE), RoundedCornerShape(24.dp))
            ) {
                CameraPreviewScanner(scanLock = scanLock) { qrString ->
                    if (scanLock.compareAndSet(false, true)) {
                        TokenParser.parse(qrString).onSuccess { payload ->
                            initiatePairing(payload)
                        }.onFailure { err ->
                            scanLock.set(false)
                            isConnecting = false
                            errorMessage = "Invalid QR: ${err.message}"
                        }
                    }
                }
                ScannerOverlay(modifier = Modifier.matchParentSize())
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Status & Error
        if (errorMessage != null) {
            Text(
                text = errorMessage ?: "",
                color = DangerRed,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            GlassButton(subtle = true, 
                onClick = { resetPairingState() },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = PrimaryCyan, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Retry Scan", color = PrimaryCyan)
            }
        } else {
            Text(
                text = pairingStatus,
                color = TextMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }

        // Spacing before manual entry (a weight(1f) spacer is illegal inside
        // a vertically scrollable column, so use a fixed gap).
        Spacer(modifier = Modifier.height(24.dp))

        // Manual Entry Option
        GlassButton(subtle = true,
            onClick = { showManualDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Edit, contentDescription = null, tint = PrimaryCyan)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Enter pairing link manually")
        }

        // Paired-PC row sits UNDER the scanner (mockup order / user request):
        // gradient icon tile + name + status + red Unpair pill.
        if (pairedCreds != null) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(Brush.linearGradient(listOf(BrandCyan, BrandViolet))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Computer,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = pairedCreds?.pcDeviceName ?: "Windows PC",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        val linkState by PairingForegroundService.connectionState.collectAsState()
                        Text(
                            text = if (linkState == com.phonepc.pairing.data.ConnectionState.CONNECTED)
                                "Paired · connected now"
                            else "Paired · reconnects securely over TLS",
                            fontSize = 12.sp,
                            color = TextMuted,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    GlassButton(
                        subtle = true,
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { showUnpairConfirm = true },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Unpair", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (showManualDialog) {
        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            title = { Text("Manual Pairing URI") },
            text = {
                OutlinedTextField(
                colors = glassTextFieldColors(),
                shape = RoundedCornerShape(16.dp),
                    value = manualUriText,
                    onValueChange = { manualUriText = it },
                    placeholder = { Text("phonepair://pair?host=...") },
                    singleLine = false,
                    maxLines = 4
                )
            },
            confirmButton = {
                GlassButton(onClick = {
                    showManualDialog = false
                    TokenParser.parse(manualUriText).onSuccess { payload ->
                        scanLock.set(true)
                        initiatePairing(payload)
                    }.onFailure { err ->
                        errorMessage = "URI Parse Error: ${err.message}"
                    }
                }) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Confirm before Unpairing (spec §11)
    if (showUnpairConfirm) {
        AlertDialog(
            onDismissRequest = { showUnpairConfirm = false },
            title = { Text("Unpair this PC?") },
            text = {
                Text("DuoLink will forget this PC and disconnect. To pair again, scan the QR code shown on the computer.")
            },
            confirmButton = {
                GlassButton(
                    tint = DangerRed,
                    onClick = {
                        showUnpairConfirm = false
                        try { PairingForegroundService.activeServiceInstance?.disconnectManually() } catch (_: Exception) {}
                        KeystoreStorage(context).clearCredentials()
                        pairedCreds = null
                    }
                ) {
                    Text("Unpair")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Mockup scanner chrome: four cyan corner brackets + a horizontal scan line
 * that sweeps top-to-bottom (frozen mid-frame when Reduce motion is on).
 * Purely decorative — the ML Kit pipeline below still sees every frame.
 */
@Composable
fun ScannerOverlay(modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "scanline")
    val frac = if (reduceMotion) {
        0.5f
    } else {
        transition.animateFloat(
            initialValue = 0.12f,
            targetValue = 0.88f,
            animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse),
            label = "scanfrac"
        ).value
    }
    Canvas(modifier.padding(20.dp)) {
        val w = size.width
        val h = size.height
        val len = 34.dp.toPx()
        val sw = 5.dp.toPx()
        val cyan = androidx.compose.ui.graphics.Color(0xFF22D3EE)
        // Corner brackets (8 strokes)
        drawLine(cyan, Offset(0f, 0f), Offset(len, 0f), sw, StrokeCap.Round)
        drawLine(cyan, Offset(0f, 0f), Offset(0f, len), sw, StrokeCap.Round)
        drawLine(cyan, Offset(w, 0f), Offset(w - len, 0f), sw, StrokeCap.Round)
        drawLine(cyan, Offset(w, 0f), Offset(w, len), sw, StrokeCap.Round)
        drawLine(cyan, Offset(0f, h), Offset(len, h), sw, StrokeCap.Round)
        drawLine(cyan, Offset(0f, h), Offset(0f, h - len), sw, StrokeCap.Round)
        drawLine(cyan, Offset(w, h), Offset(w - len, h), sw, StrokeCap.Round)
        drawLine(cyan, Offset(w, h), Offset(w, h - len), sw, StrokeCap.Round)
        // Sweeping scan line with soft ends
        val y = h * frac
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(cyan.copy(alpha = 0f), cyan.copy(alpha = 0.9f), cyan.copy(alpha = 0f))
            ),
            start = Offset(10.dp.toPx(), y),
            end = Offset(w - 10.dp.toPx(), y),
            strokeWidth = 2.5.dp.toPx()
        )
    }
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun CameraPreviewScanner(
    scanLock: AtomicBoolean,
    onBarcodeDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val scanner = BarcodeScanning.getClient()
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    if (scanLock.get()) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                if (scanLock.get()) return@addOnSuccessListener
                                for (barcode in barcodes) {
                                    val rawValue = barcode.rawValue
                                    if (rawValue != null && rawValue.startsWith("phonepair://")) {
                                        onBarcodeDetected(rawValue)
                                        break
                                    }
                                }
                            }
                            .addOnCompleteListener {
                                imageProxy.close()
                            }
                    } else {
                        imageProxy.close()
                    }
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}
