package com.phonepc.pairing.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonepc.pairing.R

/*
 * PhonePC Pair - Liquid Glass kit.
 * Aurora backdrop + glass surfaces + liquid-glass buttons. Pure Compose (no blur APIs),
 * so it looks the same on every Android version from 8.0 up.
 *
 * Colour tokens (Ink, Violet, Aqua, Rose, GlassText, DarkBg, ...) now live in
 * Theme.kt — the single design-token file for this platform.
 */

/** Slowly drifting aurora behind the whole app. Content is drawn on top.
 *  Dark: vivid glows over #0B0D14. Light: the same three glows at ~18% over
 *  #F6F7FB (spec 4.2). Reduce-motion freezes the drift but keeps the glows. */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val isDark = LocalIsDark.current
    val bg = if (isDark) BgDark else BgLight
    val blobA = if (isDark) 0.55f else 0.18f
    val blobB = if (isDark) 0.38f else 0.18f
    val blobC = if (isDark) 0.26f else 0.18f

    val t = rememberInfiniteTransition(label = "aurora")
    val a = if (reduceMotion) remember { mutableFloatStateOf(0.5f) }
            else t.animateFloat(0f, 1f, infiniteRepeatable(tween(19000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b = if (reduceMotion) remember { mutableFloatStateOf(0.5f) }
            else t.animateFloat(0f, 1f, infiniteRepeatable(tween(27000, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val c = if (reduceMotion) remember { mutableFloatStateOf(0.5f) }
            else t.animateFloat(0f, 1f, infiniteRepeatable(tween(33000, easing = LinearEasing), RepeatMode.Reverse), label = "c")

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bg)
            .drawBehind {
                val w = size.width
                val h = size.height
                fun blob(color: Color, center: Offset, radius: Float, alpha: Float) {
                    drawRect(
                        Brush.radialGradient(
                            listOf(color.copy(alpha = alpha), Color.Transparent),
                            center = center, radius = radius
                        )
                    )
                }
                blob(Violet, Offset(w * (0.05f + 0.55f * a.value), h * (0.08f + 0.12f * b.value)), w * 1.0f, blobA)
                blob(Aqua, Offset(w * (0.95f - 0.5f * b.value), h * (0.45f + 0.15f * c.value)), w * 0.95f, blobB)
                blob(Rose, Offset(w * (0.25f + 0.4f * c.value), h * (0.98f - 0.1f * a.value)), w * 0.8f, blobC)
            }
    ) {
        // Light mode: the shared blue-glass wallpaper (same image the desktop
        // uses) sits on top of the flat bg; the glass panels then float over
        // it exactly like the PC app.
        if (!isDark) {
            Image(
                painter = painterResource(id = R.drawable.light_bg),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        CompositionLocalProvider(LocalContentColor provides (if (isDark) GlassText else TextLight)) { content() }
    }
}

private val glassFillDark = Brush.linearGradient(
    listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.09f))
)
private val glassEdgeDark = Brush.linearGradient(
    listOf(Color.White.copy(alpha = 0.60f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.22f))
)

/** Frosted glass panel. Drop-in replacement for Card.
 *  Light theme: white at 70%, 1px border, soft shadow (spec 4.2). */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = LocalIsDark.current
    val reduceMotion = LocalReduceMotion.current
    val contentColor = if (isDark) GlassText else TextLight

    // Animated sheen sweep for the light-mode glass. Frozen to a mid position
    // when reduce-motion is on (glows stay, drift stops — spec 4.2).
    val sheen: Float = if (reduceMotion) {
        remember { mutableFloatStateOf(0.5f) }.value
    } else {
        val t = rememberInfiniteTransition(label = "glassSheen")
        t.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(6500, easing = LinearEasing), RepeatMode.Restart),
            label = "sheenPos"
        ).value
    }

    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(
            modifier = modifier
                // Drop-shadow only in dark, where the fill is opaque and cannot
                // bleed through. In light mode a translucent fill + shadow is
                // exactly what faked a dark "sub-container" ring around the
                // text, so light glass carries depth via a rim + inner sheen.
                .then(
                    if (isDark) Modifier.shadow(16.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                    else Modifier
                )
                .clip(shape)
                .then(
                    if (isDark) Modifier.background(
                        Brush.verticalGradient(listOf(Color(0xFF181F35), Color(0xFF121829), Color(0xFF151C30)))
                    )
                    else Modifier
                        // Translucent white glass — aurora backdrop shows through.
                        // A soft top-lit gradient (whiter at the top) reads as a
                        // premium frosted panel rather than a flat white box.
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.78f),
                                    Color.White.copy(alpha = 0.60f),
                                    Color.White.copy(alpha = 0.52f)
                                )
                            )
                        )
                        .drawBehind {
                            // Subtle diagonal highlight sweeping across the panel.
                            val w = size.width
                            val cx = (sheen * 1.6f - 0.3f) * w
                            drawRect(
                                brush = Brush.linearGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = 0.32f),
                                        Color.Transparent
                                    ),
                                    start = Offset(cx - w * 0.35f, 0f),
                                    end = Offset(cx + w * 0.15f, size.height)
                                ),
                                size = size
                            )
                        }
                )
                .then(
                    if (isDark) Modifier.border(1.dp, glassEdgeDark, shape)
                    else Modifier.border(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.95f),
                                Color.White.copy(alpha = 0.55f),
                                Color(0x140F121E) // faint silver hairline at the base
                            )
                        ),
                        shape
                    )
                ),
            content = content
        )
    }
}

/**
 * Liquid-glass button. Drop-in replacement for Button / OutlinedButton:
 * tinted translucent body, glossy top lens, bright rim light, coloured glow,
 * and a springy squish when pressed.
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    // Cyan by default (request: buttons are cyan; violet stays for gauges,
    // the voice orb, chat bubbles and gradients).
    tint: Color = Aqua,
    subtle: Boolean = false,
    shape: Shape = RoundedCornerShape(50),
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 520f),
        label = "press"
    )
    val isDarkBtn = LocalIsDark.current
    val body = when {
        subtle && isDarkBtn -> Color.White.copy(alpha = 0.07f)
        subtle -> MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        isDarkBtn -> tint.copy(alpha = 0.58f)
        else -> tint.copy(alpha = 0.92f)
    }
    val glow = if (subtle) (if (isDarkBtn) Color.White else Color.Black) else tint

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                alpha = if (enabled) 1f else 0.45f
            }
            .shadow(
                elevation = if (pressed) 3.dp else 12.dp,
                shape = shape,
                ambientColor = glow, spotColor = glow
            )
            .clip(shape)
            .background(body)
            .then(
                if (isDarkBtn) Modifier.background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.14f))
                    )
                )
                // Light mode: a soft white top-gloss gives the button a gentle
                // frosted sheen instead of a flat fill — subtle, not glassy-heavy.
                else if (!subtle) Modifier.background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.28f), Color.Transparent)
                    )
                )
                else Modifier
            )
            .drawBehind {
                if (isDarkBtn) {
                    // glossy lens across the top half (dark glass only)
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.42f), Color.Transparent),
                            startY = 0f, endY = size.height * 0.5f
                        ),
                        topLeft = Offset(size.width * 0.06f, 2.dp.toPx()),
                        size = Size(size.width * 0.88f, size.height * 0.46f),
                        cornerRadius = CornerRadius(size.height * 0.5f)
                    )
                }
            }
            .then(
                if (isDarkBtn) Modifier.border(
                    1.dp,
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.8f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.3f))),
                    shape
                ) else Modifier.border(1.dp, Color(0x1A0F121E), shape)
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Light theme: subtle buttons carry dark text; tinted buttons keep
            // white on the solid tint so contrast never drops below 4.5:1.
            val contentColor = when {
                !isDarkBtn && subtle -> MaterialTheme.colorScheme.onSurface
                !isDarkBtn && tint == Violet -> Color.White
                !isDarkBtn -> Color.White
                else -> Color.White
            }
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                ProvideTextStyle(TextStyle(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp, fontSize = 14.sp)) {
                    content()
                }
            }
        }
    }
}

data class GlassNavItem(val icon: ImageVector, val title: String)

/** Floating glass pill navigation. */
@Composable
fun GlassNavBar(items: List<GlassNavItem>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(32.dp)
    val isDark = LocalIsDark.current
    Box(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(20.dp, shape, ambientColor = Color.Black, spotColor = Violet)
                .clip(shape)
                .background(if (isDark) Color(0xFF0B1022).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.82f))
                .then(if (isDark) Modifier.background(glassFillDark)
                      else Modifier.background(
                          Brush.verticalGradient(
                              listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.68f))
                          )
                      ))
                .then(if (isDark) Modifier.border(1.dp, glassEdgeDark, shape)
                      else Modifier.border(1.dp, Color(0x1A0F121E), shape))
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEachIndexed { i, item ->
                val selected = i == selectedIndex
                val fg by animateColorAsState(
                    if (selected) OnGradient else if (isDark) Color(0xFF9AA7C7) else MutedLight,
                    label = "navfg"
                )
                val itemShape = RoundedCornerShape(26.dp)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(itemShape)
                        // Active tab = brand gradient pill (cyan -> violet), spec §7
                        .background(
                            if (selected) Brush.horizontalGradient(listOf(BrandCyan, BrandViolet))
                            else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(i) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(item.icon, contentDescription = item.title, tint = fg, modifier = Modifier.size(22.dp))
                    // Five short labels fit comfortably (spec §7: tabs with labels)
                    Text(
                        text = item.title,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = fg,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun glassTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = if (LocalIsDark.current) GlassText else MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = if (LocalIsDark.current) GlassText else MaterialTheme.colorScheme.onSurface,
    focusedContainerColor = if (LocalIsDark.current) Color.Black.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.88f),
    unfocusedContainerColor = if (LocalIsDark.current) Color.Black.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.72f),
    focusedBorderColor = Aqua,
    unfocusedBorderColor = if (LocalIsDark.current) Color.White.copy(alpha = 0.22f) else Color(0x1A0F121E),
    cursorColor = Aqua,
    focusedPlaceholderColor = Color(0xFF9AA7C7),
    unfocusedPlaceholderColor = Color(0xFF7D89AA)
)
