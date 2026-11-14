package com.phonepc.pairing.pairing

import android.content.Context
import android.provider.Settings
import android.os.Build

/**
 * Spec §9: show the real marketing name instead of raw build codes.
 *
 * Build.MODEL on this device is "X6880" (or "Infinix X6880"), which produced
 * the duplicated "INFINIX Infinix X6880" name. We keep a small lookup table
 * (extend it over time) and fall back to MANUFACTURER + MODEL.
 *
 * The PC receives both:
 *  - displayName: what the user sees everywhere ("Infinix Hot 50 Pro+")
 *  - modelCode:   raw Build.MODEL, shown only as small text in a few panes
 */
object DeviceNames {

    /** Raw model code -> clean marketing name. Extend as new devices appear. */
    private val MODEL_NAMES = mapOf(
        "X6880" to "Infinix Hot 50 Pro+"
    )

    /** Raw Build.MODEL of this device (sent as modelCode). */
    val modelCode: String get() = Build.MODEL

    /** Clean user-visible device name (sent as displayName). */
    fun displayName(context: Context): String {
        // 1. User-set name from system settings, when available (Android 7.1+)
        val userSet = try {
            Settings.Global.getString(context.contentResolver, "device_name")
        } catch (_: Exception) { null }
        if (!userSet.isNullOrBlank()) return userSet.trim()

        // 2. Lookup table by raw model code (handles both "X6880" and "Infinix X6880")
        MODEL_NAMES[Build.MODEL]?.let { return it }
        MODEL_NAMES.entries.firstOrNull { Build.MODEL.endsWith(it.key) }?.let { return it.value }

        // 3. Fallback: manufacturer + model, de-duplicated and tidy
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercaseChar() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }
}
