package com.phonepc.pairing.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonepc.pairing.data.ConnectionState
import com.phonepc.pairing.pairing.KeystoreStorage
import com.phonepc.pairing.service.PairingForegroundService

/**
 * Phase 3 shells (spec §7): five tabs — Home, Control, Share, Pair, Settings.
 *
 *  - LinkStrip: the brand signature line  Phone ──●── PC  with live latency
 *    (measured phone -> PC WebSocket round trip, see startLatencyPingLoop).
 *  - SegmentedTabs: Trackpad | Screen and Files | Chat switches with the
 *    cyan→violet brand gradient on the active segment.
 *  - ControlScreen / ShareScreen: wrap the existing feature screens so every
 *    current function keeps working inside the new five-tab navigation.
 */

/** Phone ──●── PC strip with connection state and latency chip (spec §7). */
@Composable
fun LinkStrip(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by PairingForegroundService.connectionState.collectAsState()
    val latency by PairingForegroundService.linkLatency.collectAsState()
    val connected = state == ConnectionState.CONNECTED
    val pcStats by PairingForegroundService.pcStats.collectAsState()
    val pcName = KeystoreStorage(context).getCredentials()?.pcDeviceName
        ?.takeIf { it.isNotBlank() }
        ?: pcStats?.host
        ?: "Windows PC"

    val wireBrush = if (connected) {
        Brush.horizontalGradient(listOf(BrandCyan, BrandViolet))
    } else {
        Brush.horizontalGradient(listOf(Color.Gray.copy(alpha = 0.35f), Color.Gray.copy(alpha = 0.35f)))
    }
    val labelColor = MaterialTheme.colorScheme.onSurface
    val chipText = when {
        !connected -> "Offline"
        latency in 1..99999 -> "$latency ms"
        else -> "measuring…"
    }
    val chipColor = if (connected) BrandCyan else Color.Gray
    val onSurface = MaterialTheme.colorScheme.onSurface

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Phone", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = labelColor)
        // ──●── wire with glowing dot in the middle
        Box(
            modifier = Modifier.weight(1f).height(20.dp).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(wireBrush)
            )
            Box(
                modifier = Modifier
                    .size(if (connected) 18.dp else 14.dp)
                    .clip(CircleShape)
                    .background(if (connected) BrandCyan.copy(alpha = 0.18f) else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(if (connected) 9.dp else 7.dp)
                        .clip(CircleShape)
                        .background(chipColor)
                )
            }
        }
        Text(pcName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = labelColor)
        Box(
            modifier = Modifier
                .padding(start = 10.dp)
                .clip(RoundedCornerShape(50))
                .background(chipColor.copy(alpha = 0.12f))
                .border(1.dp, chipColor.copy(alpha = 0.45f), RoundedCornerShape(50))
                .padding(horizontal = 9.dp, vertical = 3.dp)
        ) {
            Text(
                text = chipText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (connected) chipColor else onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

/** Pill segmented control; active segment uses the brand gradient (spec §7). */
@Composable
fun SegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackColor = MaterialTheme.colorScheme.surface.copy(alpha = if (MaterialTheme.colorScheme.surface.luminanceCompat() > 0.5f) 1f else 0.55f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(trackColor)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    // The selected pill was painted by a Box inside a
                    // Crossfade with matchParentSize, which resolved to the
                    // TEXT's box instead of the pill - so the cyan gradient
                    // only covered the label and the dark label looked black
                    // on a dark bar. Paint it on the pill itself.
                    .background(
                        if (selected) Brush.horizontalGradient(listOf(BrandCyan, Color(0xFF3AA8E8)))
                        else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                    )
                    .clickable { onSelect(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) OnGradient else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )
            }
        }
    }
}

private fun Color.luminanceCompat(): Float =
    (0.299f * red + 0.587f * green + 0.114f * blue)

/** Control tab: Trackpad | Screen segmented switch over the existing screens. */
@Composable
fun ControlScreen() {
    var mode by rememberSaveable { mutableStateOf(0) } // 0 = trackpad, 1 = screen mirror
    // Fullscreen mirror must be ONLY the PC screen: while immersive, even the
    // Trackpad/Screen switcher stands down (back exits fullscreen).
    val pcImmersive by PairingForegroundService.pcScreenImmersive.collectAsState()
    Column(modifier = Modifier.fillMaxSize()) {
        if (!pcImmersive) {
            SegmentedTabs(
                options = listOf("Trackpad", "Screen"),
                selectedIndex = mode,
                onSelect = { mode = it },
                modifier = Modifier.padding(horizontal = 14.dp)
            )
            Spacer(Modifier.height(8.dp))
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = mode,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val forward = targetState > initialState
                    val enter = slideInHorizontally(tween(260)) { if (forward) it / 8 else -it / 8 } + fadeIn(tween(200))
                    val exit = fadeOut(tween(140))
                    enter togetherWith exit
                },
                label = "control-mode"
            ) { m ->
                if (m == 0) TouchpadScreen() else RemotePcScreen()
            }
        }
    }
}

/** Share tab: Files | Chat segmented switch — Chat and Files merged (spec §7). */
@Composable
fun ShareScreen() {
    var mode by rememberSaveable { mutableStateOf(0) } // 0 = files, 1 = chat
    Column(modifier = Modifier.fillMaxSize()) {
        SegmentedTabs(
            options = listOf("Files", "Chat", "PC Files"),
            selectedIndex = mode,
            onSelect = { mode = it },
            modifier = Modifier.padding(horizontal = 14.dp)
        )
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = mode,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val forward = targetState > initialState
                    val enter = slideInHorizontally(tween(260)) { if (forward) it / 8 else -it / 8 } + fadeIn(tween(200))
                    val exit = fadeOut(tween(140))
                    enter togetherWith exit
                },
                label = "share-mode"
            ) { m ->
                when (m) {
                    0 -> FileTransferScreen()
                    1 -> ChatScreen()
                    else -> PcFilesScreen()
                }
            }
        }
    }
}
