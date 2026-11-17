package com.phonepc.pairing.apps

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Looper
import android.view.Surface
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The shell side of DuoLink's app windows.
 *
 * Started by the app through its own wireless debugging:
 *
 * ```
 * CLASSPATH=<base.apk> app_process /system/bin com.phonepc.pairing.apps.ShellHost <token>
 * ```
 *
 * It runs as the shell user, so it may do what an app may not: give an app a
 * display of its own and put that app on it. Each connection is one window:
 *
 *   `V <pkg> <width> <height> <dpi>`  open a display and start the app on it,
 *                                    then frames come back as
 *                                    `u32 length | u8 kind(1) | i64 timeUs | jpeg`
 *   `T <D|M|U> <x> <y>`              a touch on that display
 *   `K <keycode>`                    a key on that display
 *   `Q`                              close the window
 *
 * The token is printed to the caller as `ready <port>` once it listens on
 * loopback, and every connection must send that token first.
 */
object ShellHost {

    private const val TOKEN_TIMEOUT_MS = 10_000

    @JvmStatic
    fun main(args: Array<String>) {
        val token = args.firstOrNull() ?: return
        // ActivityThread.systemMain() builds a Handler on the calling thread, so
        // it only works on app_process' main thread, and that thread needs a
        // looper first. Build the context once here and hand it to sessions:
        // making it inside a session thread threw "Can't create handler inside
        // thread ... that has not called Looper.prepare()".
        Looper.prepareMainLooper()
        val context = shellContext()
        if (context == null) System.err.println("no shell context, app windows will fail")
        val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        println("ready ${server.localPort}")
        System.out.flush()
        while (true) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            Thread({ serve(socket, token, context) }, "app-window").apply { isDaemon = true }.start()
        }
    }

    private fun serve(socket: Socket, token: String, context: Context?) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = TOKEN_TIMEOUT_MS
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val said = readLine(input) ?: return
            if (said.trim() != token) return
            socket.soTimeout = 0
            val session = Session(
                socket,
                DataOutputStream(BufferedOutputStream(socket.getOutputStream())),
                context
            )
            while (true) {
                val line = readLine(input) ?: break
                when {
                    line.startsWith("V ") -> session.open(line.removePrefix("V ").trim())
                    line.startsWith("T ") -> session.touch(line.removePrefix("T ").trim())
                    line.startsWith("K ") -> session.key(line.removePrefix("K ").trim())
                    line == "Q" -> break
                }
            }
            session.close()
        } catch (e: Throwable) {
            System.err.println("session failed: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        } finally {
            runCatching { socket.close() }
        }
    }

    /** A line of ASCII, or null at the end of the stream. */
    private fun readLine(input: DataInputStream): String? {
        val out = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return out.takeIf { it.isNotEmpty() }?.toString()
            if (b == '\n'.code) return out.toString()
            if (out.length > 2048) return out.toString()
            out.append(b.toChar())
        }
    }

    /** One app on one display, streaming to one PC window. */
    private class Session(
        private val socket: Socket,
        private val out: DataOutputStream,
        private val context: Context?
    ) {
        private val running = AtomicBoolean(true)
        private var display: VirtualDisplay? = null
        private var reader: ImageReader? = null
        private var displayId = -1

        fun open(spec: String) {
            try {
                val parts = spec.split(' ')
                if (parts.size < 4) return
                val pkg = parts[0]
                val width = parts[1].toIntOrNull() ?: return
                val height = parts[2].toIntOrNull() ?: return
                val dpi = parts[3].toIntOrNull() ?: 200
                val context = this.context ?: run {
                    System.err.println("no shell context, can't make a display")
                    return
                }
                val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
                reader = imageReader
                val manager = displayManager(context)
                val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
                    VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT or
                    VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
                val created = manager.createVirtualDisplay(
                    "DuoLink-$pkg", width, height, dpi, imageReader.surface, flags
                )
                display = created
                displayId = created.display.displayId
                System.err.println("display $displayId for $pkg ${width}x$height")
                wake()
                launch(pkg, displayId)
                Thread({ pump(imageReader, width, height) }, "app-window-frames").apply { isDaemon = true }.start()
            } catch (e: Throwable) {
                System.err.println("open failed: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
            }
        }

        fun touch(spec: String) {
            val parts = spec.split(' ')
            if (parts.size < 3 || displayId < 0) return
            val action = when (parts[0].uppercase()) {
                "D" -> "DOWN"
                "M" -> "MOVE"
                else -> "UP"
            }
            val x = parts[1].toFloatOrNull() ?: return
            val y = parts[2].toFloatOrNull() ?: return
            shell("input -d $displayId motionevent $action ${x.toInt()} ${y.toInt()}")
        }

        fun key(spec: String) {
            if (displayId < 0) return
            shell("input -d $displayId keyevent ${spec.trim()}")
        }

        fun close() {
            running.set(false)
            runCatching { display?.release() }
            runCatching { reader?.close() }
            display = null
            reader = null
            runCatching { socket.close() }
        }

        /** Frames until the window closes, at most [MAX_FPS]. */
        private fun pump(source: ImageReader, width: Int, height: Int) {
            val frameGap = 1000L / MAX_FPS
            var last = 0L
            while (running.get() && displayId >= 0) {
                val image = runCatching { source.acquireLatestImage() }.getOrNull()
                if (image == null) {
                    Thread.sleep(8)
                    continue
                }
                try {
                    val now = System.currentTimeMillis()
                    if (now - last < frameGap) continue
                    last = now
                    val jpeg = toJpeg(image, width, height) ?: continue
                    synchronized(out) {
                        out.writeInt(1 + 8 + jpeg.size)
                        out.writeByte(KIND_FRAME)
                        out.writeLong(now * 1000)
                        out.write(jpeg)
                        out.flush()
                    }
                } catch (_: Throwable) {
                    return
                } finally {
                    runCatching { image.close() }
                }
            }
        }

        /** The frame as a JPEG, downscaled to fit [MAX_SIDE] on its long side. */
        private fun toJpeg(image: Image, width: Int, height: Int): ByteArray? = runCatching {
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val buffer = plane.buffer
            val bitmap = Bitmap.createBitmap(
                if (pixelStride == 4) rowStride / 4 else width,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            val crop = if (bitmap.width > width) Bitmap.createBitmap(bitmap, 0, 0, width, height) else bitmap
            val long = maxOf(crop.width, crop.height)
            val scale = if (long > MAX_SIDE) MAX_SIDE.toFloat() / long else 1f
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(crop, (crop.width * scale).toInt(), (crop.height * scale).toInt(), true)
            } else {
                crop
            }
            val bytes = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes)
            if (scaled !== bitmap) scaled.recycle()
            if (crop !== bitmap) crop.recycle()
            bitmap.recycle()
            bytes.toByteArray()
        }.getOrNull()

        /** Starts [pkg] on the display, through its launcher activity when one resolves. */
        private fun launch(pkg: String, id: Int) {
            // An instance already running on the phone's own screen would just be
            // brought forward there, leaving this display empty. Stop it first so
            // the app really starts on the display we made for it.
            if (shell("pidof $pkg").trim().isNotEmpty()) {
                shell("am force-stop $pkg")
                Thread.sleep(300)
            }
            val activity = resolveLauncher(pkg)
            val started = if (activity != null) {
                // Single quotes: components such as YouTube's `Shell$HomeActivity`
                // lose the `$HomeActivity` half to the shell's variable expansion.
                val component = activity.replace("'", "'\\''")
                shell("am start --display $id --windowingMode 1 -n '$component' -f 0x18000000")
            } else {
                shell("am start --display $id --windowingMode 1 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg -f 0x18000000")
            }
            val firstLine = started.trim().lineSequence().firstOrNull() ?: ""
            System.err.println("start $pkg on $id via ${activity ?: "intent"}: $firstLine")
        }

        /** `com.example/.Main` for the package's launcher activity, or null. */
        private fun resolveLauncher(pkg: String): String? = shell(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER $pkg"
        )
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("$pkg/") && !it.contains(' ') }

        /** Keeps the phone awake so the app on its own display keeps drawing. */
        private fun wake() {
            runCatching { shell("input keyevent KEYCODE_WAKEUP") }
        }

        private fun shell(command: String): String = runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val text = process.inputStream.bufferedReader().readText() +
                process.errorStream.bufferedReader().readText()
            process.waitFor()
            text
        }.getOrDefault("")
    }

    /**
     * The system context, speaking as the shell package (the helper has no
     * package of its own): what the display manager expects when it is asked
     * for a display by the shell.
     */
    /**
     * A DisplayManager bound to our own shell-package context. The one from
     * getSystemService belongs to the system context and reports package
     * "android", so DisplayManagerService answers
     * "packageName must match the calling uid" for uid 2000. The constructor is
     * package-private in the SDK stubs, so it is taken by reflection.
     */
    private fun displayManager(context: Context): DisplayManager = runCatching {
        val type = Class.forName("android.hardware.display.DisplayManager")
        val ctor = type.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        ctor.newInstance(context) as DisplayManager
    }.onFailure {
        report("display manager ctor failed", it)
    }.getOrElse {
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    private fun shellContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val systemMain = activityThread.getDeclaredMethod("systemMain").apply { isAccessible = true }
        val thread = systemMain.invoke(null)
        val getContext = activityThread.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
        val base = getContext.invoke(thread) as Context
        object : ContextWrapper(base) {
            override fun getPackageName(): String = SHELL_PACKAGE
            override fun getOpPackageName(): String = SHELL_PACKAGE
            override fun getApplicationContext(): Context = this
        }
    }.onFailure {
        report("no shell context", it)
    }.getOrNull()

    /** Prints a reflection failure with the real cause, not the wrapper. */
    private fun report(what: String, error: Throwable) {
        var cause: Throwable? = error
        while (cause is java.lang.reflect.InvocationTargetException && cause.targetException != null) {
            cause = cause.targetException
        }
        System.err.println("$what: ${cause?.javaClass?.simpleName}: ${cause?.message}")
        cause?.stackTrace?.take(6)?.forEach { System.err.println("    at $it") }
    }

    private const val SHELL_PACKAGE = "com.android.shell"
    private const val KIND_FRAME = 1
    private const val MAX_FPS = 8L
    private const val MAX_SIDE = 900
    private const val JPEG_QUALITY = 55

    // Hidden DisplayManager flags (the shell may use them).
    private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6
    private const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 shl 7
    private const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
}
