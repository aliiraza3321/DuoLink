package com.phonepc.pairing

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonepc.pairing.control.PhoneAccessibilityService
import com.phonepc.pairing.control.ScreenCaptureService
import com.phonepc.pairing.data.ConnectionState
import com.phonepc.pairing.feature.MediaBrowser
import com.phonepc.pairing.pairing.KeystoreStorage
import com.phonepc.pairing.service.PairingForegroundService
import com.phonepc.pairing.ui.*

class MainActivity : ComponentActivity() {
    companion object {
        /** True while the UI is on screen. The pairing service checks this so
         *  PC-initiated actions never throw a notification popup at a user who
         *  is already looking at the app — the consent dialog opens directly. */
        @Volatile
        var isForeground: Boolean = false
    }


    /**
     * Gallery delete requested from the PC: Android 11+ will only erase media
     * this app does not own after the user taps Allow on a system dialog, and
     * that dialog can only be launched for a result from an Activity. Media
     * parks its request in MediaBrowser.DeleteGate; this launcher delivers it
     * and hands the answer back.
     */
    private val mediaDeleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        MediaBrowser.DeleteGate.onResult(result.resultCode == Activity.RESULT_OK)
    }

    /** Runtime permissions the PC can ask the phone to grant (e.g. Call logs). */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        PermissionGate.onResult(result)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        handlePairLink(intent)
    }

    override fun onResume() {
        super.onResume()
        PairingForegroundService.activeServiceInstance?.stopRinging()
    }

    private fun handleShareIntent(i: Intent?) {
        if (i?.getBooleanExtra(PairingForegroundService.EXTRA_START_SHARE, false) == true) {
            PairingForegroundService.requestScreenShare()
        }
    }

    /**
     * phonepair://pair deep link (manifest intent-filter): hand the URI to the
     * Pair tab so opening such a link — e.g. from a QR decoder app, a chat, or
     * `adb shell am start -d` — pairs without typing the URI manually.
     */
    private fun handlePairLink(i: Intent?) {
        val uri = i?.data?.toString()
        if (uri != null && uri.startsWith("phonepair://")) {
            PairingDeepLink.pending.value = uri
            i.data = null // consume: rotation / re-delivery must not re-fire
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        handlePairLink(intent)

        // Publish the activity-backed launchers so requests coming from the
        // background service (delete a photo, grant a permission) have
        // something to route through.
        MediaBrowser.DeleteGate.launcher = { uris ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uris.isNotEmpty()) {
                try {
                    // createDeleteRequest answers with a PendingIntent; the
                    // launcher wants its IntentSender wrapped in a request.
                    val pending = MediaStore.createDeleteRequest(contentResolver, uris)
                    mediaDeleteLauncher.launch(
                        IntentSenderRequest.Builder(pending.intentSender).build()
                    )
                } catch (e: Exception) {
                    MediaBrowser.DeleteGate.onResult(false)
                }
            } else {
                MediaBrowser.DeleteGate.onResult(false)
            }
        }
        PermissionGate.launcher = { perms -> permissionLauncher.launch(perms) }

        // Load the saved appearance BEFORE the first paint: no wrong-theme flash.
        AppearanceState.load(this)
        // Spec 4.4: edge-to-edge with theme-aware system bar icons.
        enableEdgeToEdge()
        applySystemBarColors()

        setContent {
            val mode = AppearanceState.mode
            val accent = AppearanceState.accent
            val reduceMotion = AppearanceState.reduceMotion
            DuoLinkTheme(mode = mode, accent = accent, reduceMotion = reduceMotion) {
                // Re-colour system bars whenever the resolved theme flips.
                SideEffect { applySystemBarColors() }
                // Keep the display awake while screen mirroring runs:
                // MediaProjection stops producing frames once the phone
                // sleeps, which froze the PC-side stream. With the screen
                // held on, the stream keeps working (phone stays usable).
                val mirrorContext = LocalContext.current
                LaunchedEffect(Unit) {
                    while (true) {
                        val act = mirrorContext as? android.app.Activity
                        if (act != null) {
                            val mirroring = com.phonepc.pairing.control.ScreenCaptureService.isRunning
                            if (mirroring) {
                                act.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            } else {
                                act.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            }
                        }
                        kotlinx.coroutines.delay(1200)
                    }
                }
                AuroraBackground {
                    MainAppScaffold()
                }
            }
        }
    }

    /** Light theme -> dark system-bar icons; dark theme -> light icons. */
    private fun applySystemBarColors() {        val dark = when (AppearanceState.mode) {
            com.phonepc.pairing.ui.ThemeMode.SYSTEM ->
                (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            com.phonepc.pairing.ui.ThemeMode.DARK -> true
            com.phonepc.pairing.ui.ThemeMode.LIGHT -> false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    override fun onStart() {
        super.onStart()
        isForeground = true
        // Back from the background: if the link is down (Wi-Fi sleeps with the
        // screen, or the socket died while the app was hidden) reconnect now —
        // otherwise the app sits on a stale "Disconnected" until something else
        // happens to trigger the service's backoff timer.
        val state = PairingForegroundService.connectionState.value
        if (state != ConnectionState.CONNECTED && state != ConnectionState.CONNECTING &&
            KeystoreStorage(this).getCredentials() != null
        ) {
            runCatching {
                val intent = Intent(this, PairingForegroundService::class.java).apply {
                    action = PairingForegroundService.ACTION_START_LINK
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
            }
        }
    }

    override fun onStop() {
        isForeground = false
        super.onStop()
    }

    override fun onDestroy() {
        // The launchers die with the activity: stop handing requests to a
        // destroyed instance and answer whoever was waiting.
        MediaBrowser.DeleteGate.launcher = null
        MediaBrowser.DeleteGate.cancel()
        PermissionGate.launcher = null
        PermissionGate.cancel()
        super.onDestroy()
    }
}

/**
 * A runtime permission the PC asked for that the phone has not granted yet.
 *
 * Requesting must happen from an Activity, so services park the call here and
 * MainActivity supplies the launcher. With no activity alive the caller gets
 * `false` back and can tell the PC what to do instead of failing silently.
 */
object PermissionGate {
    @Volatile var launcher: ((Array<String>) -> Unit)? = null
    private var pending: ((Map<String, Boolean>) -> Unit)? = null

    fun request(perms: Array<String>, onDone: (Map<String, Boolean>) -> Unit) {
        val l = launcher
        if (l == null) {
            onDone(perms.associateWith { false })
            return
        }
        synchronized(this) { pending = onDone }
        try {
            l(perms)
        } catch (e: Exception) {
            synchronized(this) { pending = null }
            onDone(perms.associateWith { false })
        }
    }

    fun onResult(result: Map<String, Boolean>) {
        val cb = synchronized(this) { pending.also { pending = null } }
        cb?.invoke(result)
    }

    fun cancel() {
        val cb = synchronized(this) { pending.also { pending = null } }
        cb?.invoke(emptyMap())
    }
}

enum class Screen(val title: String, val icon: ImageVector) {
    // Spec §7: five tabs — Home, Control, Share, Pair, Settings.
    // Jarvis (orb + quick actions + command field) is the Home content;
    // Touchpad/PC-view live under Control; Files/Chat are merged into Share.
    HOME("Home", Icons.Default.Home),
    CONTROL("Control", Icons.Default.Mouse),
    SHARE("Share", Icons.Default.Folder),
    PAIR("Pair", Icons.Default.QrCodeScanner),
    SETTINGS("Settings", Icons.Default.Settings)
}

/** A phonepair:// URI delivered via deep link, waiting for the Pair tab. */
object PairingDeepLink {
    val pending = mutableStateOf<String?>(null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScaffold() {
    val context = LocalContext.current
    // Saveable: rotating the phone used to recreate the activity and drop the
    // user back on the Home tab while they were watching the PC screen.
    var currentScreen by rememberSaveable { mutableStateOf(Screen.PAIR) }

    val connectionState by PairingForegroundService.connectionState.collectAsState()
    // Fullscreen PC-screen mirror stands the whole shell down: top bar and
    // bottom nav hide so only the PC picture is on the display.
    val pcImmersive by PairingForegroundService.pcScreenImmersive.collectAsState()
    // Set when the link died while the app was not on screen (OEM freezer).
    val frozenHint by PairingForegroundService.frozenHint.collectAsState()
    val keystore = remember { KeystoreStorage(context) }
    val savedCreds = remember { keystore.getCredentials() }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
    val startScreenShare: () -> Unit = {
        val mp = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mp.createScreenCaptureIntent())
    }

    // PC asked to start the stream: show the consent dialog as soon as the app is on screen
    val shareRequested by PairingForegroundService.screenShareRequest.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) resumed = true
            else if (e == Lifecycle.Event.ON_PAUSE) resumed = false
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(shareRequested, resumed) {
        if (shareRequested && resumed) {
            PairingForegroundService.clearScreenShareRequest()
            startScreenShare()
        }
    }

    // phonepair:// deep link: show the Pair tab so the user sees the scanner
    // and the confirmation code while PairingScreen initiates the connection.
    val pendingPairLink by PairingDeepLink.pending
    LaunchedEffect(pendingPairLink) {
        if (pendingPairLink != null) currentScreen = Screen.PAIR
    }

    // If already paired on startup, default to Home (the orb)
    LaunchedEffect(Unit) {
        if (savedCreds != null) {
            // Only default to Home on a genuine cold start — never stomp a tab
            // restored after a configuration change (rotation).
            if (currentScreen == Screen.PAIR) currentScreen = Screen.HOME
            // Start background service to reconnect
            val intent = Intent(context, PairingForegroundService::class.java).apply {
                action = PairingForegroundService.ACTION_START_LINK
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (!pcImmersive) Column {
                if (frozenHint) FreezerHintBanner(
                    onAllow = { openOemBackgroundSettings(context) },
                    onDismiss = { PairingForegroundService.dismissFrozenHint() },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
                TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth().padding(end = 16.dp)
                    ) {
                        Text("DuoLink", fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)

                        // Status Badge
                        val (statusText, statusBg) = when (connectionState) {
                            ConnectionState.CONNECTED -> "Connected" to AccentGreen
                            ConnectionState.CONNECTING -> "Connecting..." to Color(0xFFF59E0B)
                            ConnectionState.RECONNECTING -> "Reconnecting..." to Color(0xFFF59E0B)
                            ConnectionState.AWAITING_CONFIRMATION -> "Confirm Code" to PrimaryCyan
                            ConnectionState.ERROR -> "Error" to DangerRed
                            else -> "Disconnected" to Color(0xFF64748B)
                        }

                        Surface(
                            color = statusBg.copy(alpha = 0.22f),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, statusBg.copy(alpha = 0.55f))
                        ) {
                            Text(
                                text = "● $statusText",
                                color = statusBg,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        },
        bottomBar = {
            if (!pcImmersive) GlassNavBar(
                items = Screen.values().map { GlassNavItem(it.icon, it.title) },
                selectedIndex = currentScreen.ordinal,
                onSelect = { currentScreen = Screen.values()[it] }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                // Edge-to-edge is on, so adjustResize cannot shrink the window:
                // without imePadding the keyboard simply covered the text field
                // (typing a Jarvis command hid the box and the text with it).
                .imePadding()
        ) {
            // Tab switch: a short cross-fade with a small vertical slide —
            // minimal, quick, and it makes which tab you landed on obvious.
            AnimatedContent(
                targetState = currentScreen,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    (fadeIn(tween(200)) +
                        slideInVertically(tween(260)) { it / 14 }) togetherWith
                        fadeOut(tween(140))
                },
                label = "tab-switch"
            ) { screen ->
                when (screen) {
                    Screen.HOME -> Column(Modifier.fillMaxSize()) {
                        // Brand Link strip: phone ──●── PC with live latency (spec §7)
                        LinkStrip()
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            JarvisScreen()
                        }
                    }
                    Screen.CONTROL -> ControlScreen()
                    Screen.SHARE -> ShareScreen()
                    Screen.PAIR -> PairingScreen(
                        onPairingSuccess = { currentScreen = Screen.HOME }
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        onStartScreenShare = startScreenShare,
                        onUnpair = {
                            PairingForegroundService.activeServiceInstance?.disconnectManually()
                            keystore.clearCredentials()
                            currentScreen = Screen.PAIR
                        }
                    )
                }
            }
        }
    }
}

/**
 * One-line hint shown after the link died in the background: on ROMs that freeze
 * apps (Infinix's Hiber and friends) the fix lives in the phone's own battery
 * settings, so offer that shortcut instead of a silent "Disconnected".
 */
@Composable
private fun FreezerHintBanner(onAllow: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = Color(0xFFF59E0B).copy(alpha = 0.16f),
        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 2.dp)
        ) {
            Text(
                "Your phone froze DuoLink in the background and cut the link.",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onAllow, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Allow", fontSize = 12.sp, color = BrandCyan)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(15.dp))
            }
        }
    }
}

/**
 * Opens this phone's own battery / auto-start manager.
 *
 * Some ROMs freeze a background app and destroy its sockets — Infinix XOS
 * "Hiber" does exactly that, which is what ends the link when the app is
 * minimised. Only the phone's own power manager can exempt an app, so open the
 * right screen directly; if none of the known managers exist, fall back to this
 * app's info page, where Battery → Unrestricted lives.
 */
fun openOemBackgroundSettings(context: Context) {
    // Infinix / Tecno / itel (XOS) first, then the common OEM managers.
    val targets = listOf(
        "com.transsion.batterylab" to "com.android.settings.batterysave.BatteryLab\$TranAppSavingActivity",
        "com.transsion.batterylab" to "com.android.settings.batterysave.BatteryLab\$TranOtherPowerSaveSettingsActivity",
        "com.transsion.batterylab" to "com.transsion.batterylab.BatteryLabHomeActivity",
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity"
    )
    for ((pkg, cls) in targets) {
        try {
            context.startActivity(
                Intent().setComponent(ComponentName(pkg, cls))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        } catch (_: Exception) {
            // Not this phone's manager, or it refuses to be opened: try the next.
        }
    }
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {}
}

@Composable
fun SettingsScreen(onStartScreenShare: () -> Unit, onUnpair: () -> Unit) {
    val context = LocalContext.current
    var isAccessibilityEnabled by remember { mutableStateOf(PhoneAccessibilityService.isServiceRunning) }

    fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            }
        }
    }

    fun openBackgroundSettings() = openOemBackgroundSettings(context)

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        context.startActivity(intent)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            "Device Settings & Permissions",
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))

        // ---------- Appearance: theme / accent / reduce motion ----------
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Appearance", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Theme applies instantly — the link stays connected. System follows your phone's dark mode.",
                    color = TextMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                // Segmented: System | Light | Dark
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        ThemeMode.SYSTEM to "System",
                        ThemeMode.LIGHT to "Light",
                        ThemeMode.DARK to "Dark"
                    ).forEach { (m, label) ->
                        val selected = AppearanceState.mode == m
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) Brush.horizontalGradient(listOf(BrandCyan, BrandViolet))
                                    else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                                )
                                .clickable { AppearanceState.setMode(context, m) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selected) OnGradient else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text("Accent", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AccentPreset.entries.forEach { a ->
                        val selected = AppearanceState.accent == a
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { AppearanceState.setAccent(context, a) }
                                .padding(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(a.dark)
                                    .then(
                                        if (selected) Modifier.border(
                                            2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(50)
                                        ) else Modifier
                                    )
                            )
                            Text(
                                a.id.replaceFirstChar { it.uppercase() },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Reduce motion", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Switch(checked = AppearanceState.reduceMotion, onCheckedChange = {
                        AppearanceState.setReduceMotion(context, it)
                    })
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Battery Optimization Card
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Background Link Keepalive", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Many phones freeze a background app and cut its network (Infinix's Hiber does), which disconnects DuoLink when you minimise it. Allow DuoLink to keep running in the background on both screens below.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                GlassButton(subtle = true, onClick = { requestBatteryExemption() }) {
                    Text("Allow background activity")
                }
                Spacer(Modifier.height(8.dp))
                GlassButton(subtle = true, onClick = { openBackgroundSettings() }) {
                    Text("Open battery & auto-start settings")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Accessibility Service Card
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Control Phone (from the PC)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Required only if you want to click, swipe, and type on this phone from your PC.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                GlassButton(
                    onClick = { openAccessibilitySettings() },
                    tint = Aqua
                ) {
                    Text(if (isAccessibilityEnabled) "Service Active (Tap to review)" else "Enable Accessibility Service")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Screen Share Card
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Live Screen Mirroring", fontWeight = FontWeight.Bold)
                Text(
                    "Stream phone screen to your PC window over local Wi-Fi — 1080p at 60 fps.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                GlassButton(
                    onClick = onStartScreenShare,
                    tint = PrimaryCyan
                ) {
                    Text("Start Live Screen Share")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ---------- Master feature-permission checklist ----------
        val permissionTick = remember { mutableStateOf(0) }
        fun listenerEnabled(): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            return flat != null && flat.contains(context.packageName)
        }
        val notifListenerOn = remember { mutableStateOf(listenerEnabled()) }

        fun has(p: String): Boolean {
            permissionTick.value // recompose when the tick changes
            return ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        }

        val mediaPerms = if (Build.VERSION.SDK_INT >= 33) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)

        val smsPerms = listOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            // Names, not numbers: the PC shows the saved contact for a thread.
            // Denying it only costs the name — the number is still shown.
            Manifest.permission.READ_CONTACTS
        )

        val allRuntimePerms = (mediaPerms + smsPerms + listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ) + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()).toTypedArray()

        fun refresh() {
            permissionTick.value++
            notifListenerOn.value = listenerEnabled()
        }

        val permLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { refresh() }

        val checklistOwner = LocalLifecycleOwner.current
        DisposableEffect(checklistOwner) {
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) refresh()
            }
            checklistOwner.lifecycle.addObserver(obs)
            onDispose { checklistOwner.lifecycle.removeObserver(obs) }
        }

        val notifReady = notifListenerOn.value &&
            (Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS))
        val smsReady = smsPerms.all { has(it) }
        val callReady = has(Manifest.permission.READ_PHONE_STATE) && has(Manifest.permission.READ_CALL_LOG)
        val contactsReady = has(Manifest.permission.READ_CONTACTS)
        val camReady = has(Manifest.permission.CAMERA)
        val mediaReady = mediaPerms.all { has(it) }
        val micReady = has(Manifest.permission.RECORD_AUDIO)
        val controlReady = PhoneAccessibilityService.isServiceRunning

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Feature Permissions", fontWeight = FontWeight.Bold)
                Text(
                    "Every DuoLink feature needs its own permission on the phone. Green = ready, red = tap Grant. Nothing works from the PC until these are green.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                PermissionRow("Notifications pushed to PC", notifReady) {
                    if (!notifListenerOn.value) {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    } else {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        })
                    }
                }
                PermissionRow("SMS read + send from PC", smsReady) { permLauncher.launch(smsPerms.toTypedArray()) }
                PermissionRow("Call log + dial from PC", callReady) {
                    permLauncher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG))
                }
                PermissionRow("Contacts lookup", contactsReady) { permLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS)) }
                PermissionRow("Phone webcam", camReady) { permLauncher.launch(arrayOf(Manifest.permission.CAMERA)) }
                PermissionRow("Photos, videos & music browser", mediaReady) { permLauncher.launch(mediaPerms.toTypedArray()) }
                PermissionRow("Microphone (webcam audio / voice)", micReady) { permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
                PermissionRow("PC → Phone click, swipe & type", controlReady) {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }

                Spacer(modifier = Modifier.height(10.dp))
                GlassButton(
                    onClick = { permLauncher.launch(allRuntimePerms) },
                    modifier = Modifier.fillMaxWidth(),
                    tint = Aqua
                ) {
                    Text("Grant All Feature Permissions")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---------- About DuoLink ----------
        AboutCard(context)

        Spacer(modifier = Modifier.height(16.dp))

        GlassButton(
            onClick = onUnpair,
            modifier = Modifier.fillMaxWidth(),
            tint = DangerRed
        ) {
            Text("Unpair & Forget This PC")
        }
    }
}

/** About section: version, device, and what DuoLink is. */
@Composable
private fun AboutCard(context: Context) {
    val pkgInfo = remember(context) {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) { null }
    }
    val version = pkgInfo?.versionName ?: "1.0.0"
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("About DuoLink", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Two screens. One system.",
                color = PrimaryCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "DuoLink pairs this phone with your Windows PC over local Wi-Fi — remote control, screen mirroring, messages, calls, media and webcam, with no cloud and no account.",
                color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            AboutRow("Version", "$version (build ${pkgInfo?.longVersionCode ?: 1})")
            AboutRow("Device", com.phonepc.pairing.pairing.DeviceNames.displayName(context))
            AboutRow("Android", "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            AboutRow("Protocol", "TLS-pinned WebSocket on your LAN only")

            Spacer(modifier = Modifier.height(12.dp))
            DeveloperIntro(context)
        }
    }
}

/** Proper developer intro: who builds DuoLink, and a link to their work. */
@Composable
private fun DeveloperIntro(context: Context) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF7C5CFF).copy(alpha = 0.18f), Color(0xFF22D3EE).copy(alpha = 0.10f))
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Text("Developer", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Ali Raza", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "Solo builder behind DuoLink — designs, builds and maintains both the Windows and Android apps, " +
                "from the TLS-pinned pairing layer to the Compose UI you're using now.",
            color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        // Opens the developer site in the browser
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.10f))
                .clickable {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://aliiraza.netlify.app/"))
                        )
                    } catch (_: Exception) {}
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Portfolio", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.width(6.dp))
            // A drawn arrow, not the "→" glyph: the character fell back to a
            // system font that rendered a box / half arrow on some phones.
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        // Values like "TLS-pinned WebSocket on your LAN only" used to push
        // past the card edge; they now wrap and align to the end.
        Text(
            value,
            color = TextMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp)
        )
    }
}

@Composable
private fun PermissionRow(title: String, ready: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (ready) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = if (ready) "granted" else "missing",
            tint = if (ready) Color(0xFF34D399) else DangerRed,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            title,
            color = if (ready) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (!ready) {
            GlassButton(
                onClick = onGrant,
                subtle = true,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp)
            ) {
                Text("Grant", fontSize = 12.sp)
            }
        }
    }
}
