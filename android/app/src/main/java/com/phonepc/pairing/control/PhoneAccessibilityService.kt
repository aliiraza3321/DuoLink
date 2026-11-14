package com.phonepc.pairing.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.DisplayMetrics
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * PhonePC Pair - Accessibility Service for Remote Control (Phase 4 Foundation)
 * Dispatches simulated touch gestures and keyboard input onto the phone screen.
 * User must explicitly enable this service in Android Settings.
 */
class PhoneAccessibilityService : AccessibilityService() {

    companion object {
        var instance: PhoneAccessibilityService? = null
            private set

        val isServiceRunning: Boolean
            get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for passive logging; only used for active remote gesture execution
    }

    override fun onInterrupt() {
        // Handled when OS interrupts accessibility
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Dispatches a tap (or a long press when [durationMs] is large) at normalized coordinates (0..1). */
    fun performTap(normX: Float, normY: Float, durationMs: Long = 60): Boolean {
        val (w, h) = screenSizePx()
        val realX = (normX * w).coerceIn(1f, w - 1f)
        val realY = (normY * h).coerceIn(1f, h - 1f)
        val path = Path().apply { moveTo(realX, realY) }
        return dispatch(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
    }

    /** Dispatches a swipe between normalized coordinates (0..1). */
    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        val (w, h) = screenSizePx()
        fun px(v: Float, max: Int) = (v * max).coerceIn(1f, max - 1f)
        val path = Path().apply {
            moveTo(px(startX, w), px(startY, h))
            lineTo(px(endX, w), px(endY, h))
        }
        return dispatch(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
    }

    /**
     * Full display size, in the same space the mirrored video uses.
     *
     * `resources.displayMetrics` reports the app-window size, which excludes
     * the status and navigation bars (and on some skins the cutout): taps then
     * landed a little up and to the left of the finger on the PC - the "touch
     * is not where I click" offset. maximumWindowMetrics is the real panel.
     */
    private fun screenSizePx(): Pair<Int, Int> {
        return try {
            val wm = getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val b = wm.maximumWindowMetrics.bounds
                if (b.width() > 0 && b.height() > 0) b.width() to b.height()
                else resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
            } else {
                val p = android.graphics.Point()
                @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p)
                p.x to p.y
            }
        } catch (_: Throwable) {
            resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
        }
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCancelled(gestureDescription: GestureDescription?) {
                com.phonepc.pairing.service.PairingForegroundService.activeServiceInstance?.reportControl("GESTURE_CANCELLED")
            }
        }, null)
    }

    /** Dispatches system navigation global actions. Returns false if Android refused. */
    fun performGlobal(action: String): Boolean = when (action.uppercase()) {
        "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        // Android 9+ (API 21+ for the power dialog). Without these branches
        // Lock / Restart / Shut down silently no-oped via the else -> false.
        "LOCK_SCREEN", "LOCK" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        "POWER_DIALOG", "POWER", "POWER_MENU" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        // PC Enter key: fire the field's IME action (send in WhatsApp/Chat,
        // search in a browser) - exactly what a physical keyboard does.
        "ENTER", "IME_ENTER" -> performImeEnter()
        else -> false
    }

    /** Presses the focused field's IME action (send / search / done). */
    fun performImeEnter(): Boolean {
        val node = findEditable() ?: return false
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                node.performAction(
                    android.view.accessibility.AccessibilityNodeInfo
                        .AccessibilityAction.ACTION_IME_ENTER.id
                )
            } else false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Injects text into currently focused editable input field.
     */
    fun typeTextIntoFocusedNode(text: String) {
        val rootNode = rootInActiveWindow ?: return
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return

        if (focusedNode.isEditable) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
    }

    // ------------------------------------------------------------------
    // "Type on Phone" (PC keyboard -> this phone)
    // ------------------------------------------------------------------

    /** The editable node the IME belongs to: the focused input, else the
     *  first editable node on screen (usually the one just tapped on the PC). */
    private fun findEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }?.let { return it }
        return findFirstEditable(root)
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            val hit = findFirstEditable(child)
            if (hit != null) return hit
        }
        return null
    }

    /**
     * Pops the soft keyboard open on the phone into its focused field — this
     * is what clicking the PC's "Type on Phone" box triggers. Returns false
     * when there is no editable field anywhere, so the PC can say so.
     *
     * Android exposes NO public action constant for "show the IME" (the
     * View.ACTION_SHOW_SOFT_INPUT codes are hidden API), so this does what a
     * user's finger does: focus the field, select it, then tap its own bounds.
     * A tap inside an editable is what makes an app request its keyboard.
     */
    fun showKeyboard(): Boolean {
        val node = findEditable() ?: return false
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_SELECT)
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) {
                val dm = resources.displayMetrics
                if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                    val nx = (r.centerX().toFloat() / dm.widthPixels).coerceIn(0.02f, 0.98f)
                    val ny = (r.centerY().toFloat() / dm.heightPixels).coerceIn(0.02f, 0.98f)
                    performTap(nx, ny, 50)
                }
            }
            true
        } catch (_: Throwable) {
            true // the field IS focused — the IME follows on most builds
        }
    }

    /**
     * Leaving the PC's typing box. Android has no public "hide the IME"
     * action without a window token, and clearing focus would break the next
     * typed character — so the keyboard deliberately stays up: the whole point
     * of the feature is that it is there when the user wants to write.
     */
    fun hideKeyboard(): Boolean = true

    /**
     * Applies one typing op from the PC to the focused editable field:
     *  - "append" / "replace": set the field to current+text / text
     *  - "delete": remove `count` characters from the end (backspace)
     * ACTION_SET_TEXT replaces wholesale, so every op is computed against the
     * field's current text; the cursor ends up at the end, which is exactly
     * where typing from a PC wants it. Keeps the IME up while typing.
     */
    fun applyTypeText(op: String, text: String, count: Int): Boolean {
        val node = findEditable() ?: return false
        val current = (node.text ?: "").toString()
        val next = when (op) {
            "replace" -> text
            "delete" -> current.dropLast(count.coerceIn(0, current.length))
            else -> current + text // append
        }
        if (next == current && op != "append") return true
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next)
        }
        return try {
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            ok
        } catch (_: Throwable) {
            false
        }
    }
}
