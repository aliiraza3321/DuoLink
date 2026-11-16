package com.phonepc.pairing.apps

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.WindowManager
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Phone apps in windows of their own on the PC.
 *
 * Each window is a display the shell helper made for that app, running only
 * that app; its picture comes back here frame by frame and goes to the PC
 * window the user opened. Ten apps open means ten displays, ten streams and
 * ten windows, and none of them shows the phone's own screen.
 */
object AppWindows {

    private const val TAG = "AppWindows"

    /** What the service does with a window's frames and its close. */
    interface Listener {
        fun onFrame(id: String, pkg: String, width: Int, height: Int, jpeg: ByteArray)
        fun onClosed(id: String, pkg: String, reason: String)
    }

    class Session(
        val id: String,
        val pkg: String,
        val width: Int,
        val height: Int,
        /** null while the helper is still starting; frames wait for it. */
        val waiting: Boolean,
        internal val socket: Socket?,
        internal val writer: OutputStream?,
    )

    @Volatile
    var listener: Listener? = null

    private val sessions = ConcurrentHashMap<String, Session>()
    private val frames = ConcurrentHashMap<String, java.io.DataInputStream>()

    /** True when the phone can run app windows (wireless debugging paired). */
    fun available(context: Context): Boolean = AdbBridge.isPaired(context)

    /**
     * Opens [pkg] in a window: a display of its own, the app started on it and
     * its frames sent to [listener]. Returns the session, or null with a reason
     * when the phone can't (not paired, or the helper didn't start).
     */
    suspend fun open(context: Context, pkg: String): Pair<Session?, String?> {
        val (width, height, dpi) = displaySize(context)
        val socket = AdbBridge.openWindow(context, pkg, width, height, dpi)
            ?: return null to "Wireless debugging isn't connected. Open Apps → Advanced and pair it once."
        val id = UUID.randomUUID().toString().take(8)
        val session = Session(id, pkg, width, height, waiting = false, socket = socket, writer = socket.getOutputStream())
        sessions[id] = session
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 1 shl 16))
        frames[id] = input
        Thread({ read(id, session, input) }, "app-window-$id").apply { isDaemon = true }.start()
        return session to null
    }

    /** The PC's touch on that window: `D`own, `M`ove, `U`p, in display pixels. */
    fun input(id: String, action: String, x: Float, y: Float) {
        val session = sessions[id] ?: return
        val writer = session.writer ?: return
        val letter = when (action.uppercase()) {
            "DOWN", "D" -> "D"
            "MOVE", "M" -> "M"
            else -> "U"
        }
        runCatching {
            synchronized(writer) {
                writer.write("T $letter $x $y\n".toByteArray(Charsets.US_ASCII))
                writer.flush()
            }
        }.onFailure { close(id) }
    }

    /** A key on that window (Android keycode). */
    fun key(id: String, keycode: Int) {
        val writer = sessions[id]?.writer ?: return
        runCatching {
            synchronized(writer) {
                writer.write("K $keycode\n".toByteArray(Charsets.US_ASCII))
                writer.flush()
            }
        }.onFailure { close(id) }
    }

    fun close(id: String) {
        val session = sessions.remove(id) ?: return
        frames.remove(id)
        runCatching { session.writer?.let { w -> synchronized(w) { w.write("Q\n".toByteArray(Charsets.US_ASCII)); w.flush() } } }
        runCatching { session.socket?.close() }
        listener?.onClosed(id, session.pkg, "closed")
    }

    fun closeAll() {
        sessions.keys.toList().forEach(::close)
        AdbBridge.stopHelper()
    }

    fun isOpen(id: String): Boolean = sessions.containsKey(id)

    /** Reads the helper's frames off [input] until the window closes. */
    private fun read(id: String, session: Session, input: DataInputStream) {
        try {
            while (true) {
                val length = input.readInt()
                if (length < 9 || length > 8 shl 20) break
                input.readUnsignedByte() // kind: 1 = JPEG frame
                input.readLong()          // time, microseconds
                val jpeg = ByteArray(length - 9)
                input.readFully(jpeg)
                listener?.onFrame(id, session.pkg, session.width, session.height, jpeg)
            }
        } catch (e: Throwable) {
            Log.d(TAG, "an app window ended", e)
        } finally {
            if (sessions.remove(id) != null) {
                frames.remove(id)
                runCatching { session.socket?.close() }
                listener?.onClosed(id, session.pkg, "closed")
            }
        }
    }

    /** The phone's screen shape, scaled down to what a window streams well. */
    private fun displaySize(context: Context): Triple<Int, Int, Int> {
        val windows = context.getSystemService(WindowManager::class.java)
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windows.maximumWindowMetrics.bounds
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windows.defaultDisplay.getRealMetrics(metrics)
            android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        val w = bounds.width().coerceAtLeast(320)
        val h = bounds.height().coerceAtLeast(320)
        val long = maxOf(w, h)
        val scale = if (long > 900) 900f / long else 1f
        val dpi = (context.resources.displayMetrics.densityDpi * scale).roundToInt().coerceIn(120, 480)
        return Triple((w * scale).roundToInt(), (h * scale).roundToInt(), dpi)
    }
}
