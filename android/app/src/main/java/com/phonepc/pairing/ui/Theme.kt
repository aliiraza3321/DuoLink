package com.phonepc.pairing.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * DuoLink design tokens — single source of truth for colour on Android.
 * Values mirror the PC token file (renderer/tokens.css) so both apps match.
 *
 * Dark  bg #0B0D14  surface #12151F  elevated #181C29
 * Light bg #F6F7FB  surface #FFFFFF  elevated #EEF0F7
 */

// ---- Shared semantic tokens ----
val PrimaryViolet = Color(0xFF7C6CFF)
val PrimaryVioletLight = Color(0xFF6354F0)
val AccentCyan = Color(0xFF22D3EE)
val AccentCyanLight = Color(0xFF0E7490)
val SuccessGreen = Color(0xFF34D399)
val SuccessGreenLight = Color(0xFF047857)
val WarningAmber = Color(0xFFFBBF24)
val WarningAmberLight = Color(0xFFB45309)
val DangerRed = Color(0xFFF87171)
val DangerRedLight = Color(0xFFDC2626)

val TextDark = Color(0xFFE8EAF2)
val TextLight = Color(0xFF12151F)
val MutedDark = Color(0xFF9EA6BE)
val MutedLight = Color(0xFF5B6078)

val BgDark = Color(0xFF0B0D14)
val BgLight = Color(0xFFF6F7FB)
val SurfaceDark = Color(0xFF12151F)
val SurfaceLight = Color(0xFFFFFFFF)
val ElevatedDark = Color(0xFF181C29)
val ElevatedLight = Color(0xFFEEF0F7)

// Brand gradient endpoints (cyan -> violet), both themes.
val BrandCyan = Color(0xFF22D3EE)
val BrandViolet = Color(0xFF7C6CFF)
/** Text on gradient buttons — dark, per spec. */
val OnGradient = Color(0xFF06121C)

// Legacy names kept so existing screens compile unchanged; they now follow
// the active theme via [DuoLinkTheme] defaults (dark values).
val DarkBg = BgDark
val CardBg = SurfaceDark
val PrimaryCyan = AccentCyan
val AccentGreen = SuccessGreen
/** Muted body text — flips with the active theme so it stays readable on
 *  light glass too (it used to be pinned to the dark-theme grey, which the
 *  light theme rendered as near-invisible grey-on-white). */
val TextMuted: Color
    @Composable get() = if (LocalIsDark.current) MutedDark else MutedLight
val Ink = BgDark
val Violet = PrimaryViolet
val Aqua = AccentCyan
val Rose = Color(0xFFFF6AA2)
val GlassText = TextDark

/** Accent presets: name -> (primary dark, primary light). */
enum class AccentPreset(val id: String, val dark: Color, val light: Color) {
    VIOLET("violet", PrimaryViolet, PrimaryVioletLight),
    CYAN("cyan", AccentCyan, AccentCyanLight),
    GREEN("green", SuccessGreen, SuccessGreenLight),
    ORANGE("orange", Color(0xFFFB923C), Color(0xFFC2410C));

    companion object {
        fun from(id: String?): AccentPreset = entries.firstOrNull { it.id == id } ?: VIOLET
    }
}

/** Theme mode: System (default) / Light / Dark. */
enum class ThemeMode(val id: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        fun from(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * Persisted appearance settings (stored unencrypted: no secrets, applied
 * before first paint on next launch). Synchronous reads so MainActivity can
 * paint the right theme on frame one.
 */
object ThemePrefs {
    private const val FILE = "duolink_appearance"
    private const val KEY_MODE = "themeMode"        // system | light | dark
    private const val KEY_ACCENT = "accent"          // violet | cyan | green | orange
    private const val KEY_REDUCE_MOTION = "reduceMotion"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun mode(context: Context): ThemeMode = ThemeMode.from(prefs(context).getString(KEY_MODE, null))
    fun accent(context: Context): AccentPreset = AccentPreset.from(prefs(context).getString(KEY_ACCENT, null))
    fun reduceMotion(context: Context): Boolean = prefs(context).getBoolean(KEY_REDUCE_MOTION, false)

    fun setMode(context: Context, mode: ThemeMode) =
        prefs(context).edit().putString(KEY_MODE, mode.id).apply()

    fun setAccent(context: Context, accent: AccentPreset) =
        prefs(context).edit().putString(KEY_ACCENT, accent.id).apply()

    fun setReduceMotion(context: Context, reduce: Boolean) =
        prefs(context).edit().putBoolean(KEY_REDUCE_MOTION, reduce).apply()
}

/**
 * Live appearance state. Loaded from [ThemePrefs] at startup (before first
 * paint), then mutated in place so switching theme never restarts the app or
 * drops the WebSocket — [DuoLinkTheme] recomposes around the running UI.
 */
object AppearanceState {
    var mode: ThemeMode by mutableStateOf(ThemeMode.SYSTEM)
    var accent: AccentPreset by mutableStateOf(AccentPreset.VIOLET)
    var reduceMotion: Boolean by mutableStateOf(false)

    fun load(context: Context) {
        mode = ThemePrefs.mode(context)
        accent = ThemePrefs.accent(context)
        reduceMotion = ThemePrefs.reduceMotion(context)
    }

    fun setMode(context: Context, value: ThemeMode) {
        ThemePrefs.setMode(context, value); mode = value
    }

    fun setAccent(context: Context, value: AccentPreset) {
        ThemePrefs.setAccent(context, value); accent = value
    }

    fun setReduceMotion(context: Context, value: Boolean) {
        ThemePrefs.setReduceMotion(context, value); reduceMotion = value
    }
}

/** True when reduce-motion is on — screens gate animations on this. */
val LocalReduceMotion = staticCompositionLocalOf { false }
/** True when the resolved theme is light — glass surfaces flip. */
val LocalIsDark = staticCompositionLocalOf { true }

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryViolet,
    onPrimary = Color(0xFF06121C),
    background = BgDark,
    onBackground = TextDark,
    surface = SurfaceDark,
    onSurface = TextDark,
    surfaceVariant = ElevatedDark,
    onSurfaceVariant = MutedDark,
    secondary = AccentCyan,
    tertiary = AccentCyan,
    error = DangerRed,
    outline = Color(0x1FFFFFFF) // rgba(255,255,255,.07)
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryVioletLight,
    onPrimary = Color(0xFFFFFFFF),
    background = BgLight,
    onBackground = TextLight,
    surface = SurfaceLight,
    onSurface = TextLight,
    surfaceVariant = ElevatedLight,
    onSurfaceVariant = MutedLight,
    secondary = AccentCyanLight,
    tertiary = AccentCyanLight,
    error = DangerRedLight,
    outline = Color(0x1A0F121E) // rgba(15,18,30,.10)
)

/** Apply an accent preset on top of a base scheme. */
private fun withAccent(base: androidx.compose.material3.ColorScheme, accent: AccentPreset, dark: Boolean) =
    base.copy(primary = if (dark) accent.dark else accent.light)

private val DuoTypography = Typography(
    titleLarge = TextStyle(
        fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp
    ),
    titleMedium = TextStyle(
        fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp
    ),
    titleSmall = TextStyle(
        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontSize = 12.sp, letterSpacing = 0.1.sp),
    labelLarge = TextStyle(
        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(fontSize = 12.sp, letterSpacing = 0.2.sp)
)

/**
 * DuoLinkTheme — chooses light/dark from [mode] (System follows the OS),
 * applies the accent preset, and publishes reduce-motion to screens.
 * No dynamic (Material You) colours: the brand must stay consistent.
 */
@Composable
fun DuoLinkTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    accent: AccentPreset = AccentPreset.VIOLET,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val scheme = withAccent(if (dark) DarkColorScheme else LightColorScheme, accent, dark)
    CompositionLocalProvider(
        LocalReduceMotion provides reduceMotion,
        LocalIsDark provides dark
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = DuoTypography,
            content = content
        )
    }
}

/** Back-compat entry point used by existing screens/previews. */
@Composable
fun PhonePCPairTheme(content: @Composable () -> Unit) {
    DuoLinkTheme(content = content)
}
