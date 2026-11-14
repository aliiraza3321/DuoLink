package com.phonepc.pairing.feature

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat.getMainExecutor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.gson.JsonObject
import com.phonepc.pairing.R
import com.phonepc.pairing.network.Protocol
import com.phonepc.pairing.service.PairingForegroundService
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * V2: Phone webcam (PC drives the phone's camera).
 *
 * Runs as a `camera`-type foreground service (required on Android 14+), binds
 * CameraX, converts analysis frames to JPEG and sends WEBCAM_FRAME over the
 * WSS link. Status transitions always report WEBCAM_STATUS so the PC's pane
 * shows "Live" or a precise error instead of hanging on "Starting…".
 *
 * A plain Service is not a LifecycleOwner, so this class owns a
 * LifecycleRegistry and drives its state (CREATED -> RESUMED -> DESTROYED),
 * which is exactly what androidx.lifecycle.LifecycleService does internally.
 * That keeps the dependency surface unchanged while satisfying CameraX.
 */
class WebcamService : android.app.Service(), LifecycleOwner, ImageAnalysis.Analyzer {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    companion object {
        const val ACTION_START = "ACTION_WEBCAM_START"
        const val ACTION_STOP = "ACTION_WEBCAM_STOP"
        const val EXTRA_CAMERA = "camera"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_TORCH = "torch"
        const val ACTION_TORCH = "com.phonepc.pairing.TORCH"

        private const val CHANNEL_ID = "duolink_webcam_channel"
        private const val NOTIF_ID = 1004
        private const val TAG = "WebcamService"

        @Volatile var isActive: Boolean = false
        @Volatile var activeCamera: String = "back"
        @Volatile private var startingCamera: String? = null
        private var provider: ProcessCameraProvider? = null
        private val seq = AtomicLong(0)
        private var lastSentAt = 0L
        private var minIntervalMs = 16L // ~60fps default (1000/60)
        private var quality = 70

        fun stopFrom(context: Context) {
            try {
                context.startService(Intent(context, WebcamService::class.java).apply {
                    action = ACTION_STOP
                })
            } catch (_: Exception) {
                context.stopService(Intent(context, WebcamService::class.java))
            }
        }
    }

    private fun sendStatus(active: Boolean, error: String?) {
        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(
            Protocol.TYPE_WEBCAM_STATUS,
            JsonObject().apply {
                addProperty("active", active)
                addProperty("camera", activeCamera)
                if (!error.isNullOrBlank()) addProperty("error", error)
            }
        )
    }

    private fun send(type: String, payload: JsonObject): Boolean =
        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(type, payload) ?: false

    // Reused rotation target: rotating needs a same-sized copy, and allocating
    // a fresh ~1.4 MB buffer 15x/second just to feed the GC is wasteful.
    private var rotBuf: ByteArray? = null

    // Reused NV21 output, U/V staging planes and JPEG sink. Every frame used to
    // allocate a fresh ~1 MB ByteArray here; at 60fps that is 60 MB/s of garbage
    // handed straight to the GC, which showed up as multi-frame stalls.
    private var nv21Buf: ByteArray? = null
    // ONE analyzer thread for the service's lifetime. The old code created a
    // brand-new single-thread executor on every camera bind (every front/back
    // switch) and never shut it down, leaking a live thread each time — the
    // steady accumulation is what made repeated camera switching unstable.
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private var uStaging: ByteArray? = null
    private var vStaging: ByteArray? = null
    private val jpegOut = java.io.ByteArrayOutputStream(256 * 1024)
    private var strideLogged = false

    // ---- Liveness watchdog -------------------------------------------------
    // Another client (the system DeviceAsWebcam service, a camera app) can take
    // the camera device away without any callback reaching us. That used to
    // leave isActive=true with a dead use case, so every later WEBCAM_START
    // hit the "already live" guard and silently no-opped: no frames, no
    // WEBCAM_STATUS, and the PC sat on "Starting..." forever. Now we timestamp
    // analyzed frames and re-bind when the stream goes quiet while we still own
    // the foreground service.
    private var lastFrameAt = 0L
    private var boundWidth = 1280
    private var boundHeight = 720
    private var boundTorch = false
    private var boundCamera: androidx.camera.core.Camera? = null
    private var rebindAttempts = 0
    private val watchdog = android.os.Handler(android.os.Looper.getMainLooper())
    private val watchdogTick = object : Runnable {
        override fun run() {
            if (!isActive) return
            val starting = startingCamera != null
            val idleMs = System.currentTimeMillis() - lastFrameAt
            // lastFrameAt==0 means we never produced a frame since binding, so
            // give the initial bind its own grace period instead of thrashing.
            val neverFramed = lastFrameAt == 0L
            val stale = !starting && lastFrameAt > 0 && idleMs > 3500
            val failedToStart = !starting && neverFramed && System.currentTimeMillis() - bindStartedAt > 6000
            if (stale || failedToStart) {
                if (rebindAttempts < 3) {
                    rebindAttempts++
                    android.util.Log.w(TAG, "stream idle ${if (stale) idleMs else "(no frames)"}ms - rebind attempt $rebindAttempts")
                    val cam = activeCamera
                    try { provider?.unbindAll() } catch (_: Exception) {}
                    startingCamera = cam
                    watchdog.postDelayed({ bindCamera(cam, Size(boundWidth, boundHeight), boundTorch) }, 300)
                } else {
                    android.util.Log.e(TAG, "camera stayed dead after $rebindAttempts rebinds - stopping")
                    sendStatus(false, "Camera stopped sending frames. Another app may be using it.")
                    updateNotif("Camera failed")
                    stopCapture()
                    return
                }
            }
            watchdog.postDelayed(this, 2000)
        }
    }
    private var bindStartedAt = 0L

    // ---- Encode timing (diagnostics): where does each frame's CPU time go? ----
    private var perfFrames = 0
    private var perfConvertNs = 0L
    private var perfRotateNs = 0L
    private var perfJpegNs = 0L
    private var perfConvStartNs = 0L
    private var perfWindowStartNs = System.nanoTime()

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            val entryNs = System.nanoTime()
            // Any analysis callback proves the camera device is still alive.
            lastFrameAt = now
            if (!isActive || now - lastSentAt < minIntervalMs) return
            if (!PairingForegroundService.hasActiveSession()) return
            // Skip the whole convert + JPEG pipeline when the link is backed
            // up: encoding frames nobody receives is pure heat and battery.
            // Keep the webcam off a busy link: the mirror shares this socket,
            // so queueing webcam frames behind it is what makes both pictures
            // arrive seconds late. A shallow queue keeps the newest frame live.
            // Shallow queue only: four frames in flight at most. The gate used
            // to be one frame, which starved the webcam down to ~14fps whenever
            // the mirror shared the socket; 64 KB still bounds latency (~0.3 s
            // at the link's real rate) while letting the camera run at its own
            // pace.
            val queued = PairingForegroundService.activeServiceInstance?.sendQueuedBytes() ?: 0L
            if (queued > 64_000L) return
            perfConvStartNs = System.nanoTime()

            val w = image.width
            val h = image.height
            // CameraX hands frames in *sensor* orientation (landscape on almost
            // every phone). Without applying rotationDegrees the PC renders the
            // feed sideways. Normalize to upright before JPEG-ing.
            val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
            val swap = rotation == 90 || rotation == 270
            val outW = if (swap) h else w
            val outH = if (swap) w else h

            if (!strideLogged) {
                strideLogged = true
                val p = image.planes
                android.util.Log.i(
                    TAG, "strides: ${w}x${h} y=${p[0].rowStride}/${p[0].pixelStride} " +
                        "u=${p[1].rowStride}/${p[1].pixelStride} v=${p[2].rowStride}/${p[2].pixelStride} " +
                        "direct=${p[0].buffer.isDirect} rot=$rotation"
                )
            }
            val nv21 = yuv420ToNv21(image) ?: return
            val tConv = System.nanoTime()
            val oriented = if (rotation == 0) nv21 else rotateNv21(nv21, w, h, rotation) ?: return
            val tRot = System.nanoTime()

            val yuv = YuvImage(oriented, ImageFormat.NV21, outW, outH, null)
            jpegOut.reset()
            yuv.compressToJpeg(Rect(0, 0, outW, outH), quality, jpegOut)
            val jpeg = jpegOut.toByteArray()
            if (jpeg.isEmpty()) return

            // Timing aggregate: reported every 60 frames so we can see whether
            // YUV conversion, rotation, or JPEG compression is the bottleneck.
            val tJpeg = System.nanoTime()
            perfFrames++
            perfConvertNs += (tConv - perfConvStartNs)
            perfRotateNs += (tRot - tConv)
            perfJpegNs += (tJpeg - tRot)
            if (perfFrames >= 60) {
                val n = perfFrames
                val wallMs = (tJpeg - perfWindowStartNs) / 1e6
                android.util.Log.i(
                    TAG, "perf: ${"%.1f".format(n * 1000.0 / wallMs)}fps " +
                        "convert=${perfConvertNs / n / 1000}us rotate=${perfRotateNs / n / 1000}us " +
                        "jpeg=${perfJpegNs / n / 1000}us perFrame=${(tJpeg - perfWindowStartNs) / n / 1000}us " +
                        "enc=${outW}x${outH} q$quality"
                )
                perfFrames = 0; perfConvertNs = 0; perfRotateNs = 0; perfJpegNs = 0
                // Reset only with the window: updating this every frame made the
                // window one frame wide, so the reported fps was nonsense.
                perfWindowStartNs = tJpeg
            }

            lastSentAt = now
            val n = seq.incrementAndGet()
            // Binary frame: 9-byte header + raw JPEG. No base64 (which inflated
            // every frame by 33%) and no JSON escaping, so the PC no longer pays
            // a Gson parse on each frame - this is what makes 60fps viable.
            //
            // Freshness over completeness: when Wi-Fi is still draining the
            // previous frames (sendQueuedBytes > 1 MB), DROP this frame instead
            // of queueing a backlog. Queued frames arrive late — latency that
            // keeps growing while the picture keeps coming is exactly the
            // "laggy stream" users see. The sequence number still advances, so
            // drops stay visible to telemetry instead of hiding.
            val svc = PairingForegroundService.activeServiceInstance
            if (svc != null && svc.sendQueuedBytes() < 64_000L) {
                svc.sendRawBinary(
                    Protocol.encodeBinaryFrame(Protocol.BIN_KIND_WEBCAM, n, outW, outH, jpeg)
                )
            }
        } catch (_: Exception) {
            // Drop the frame; one bad frame must never kill the stream.
        } finally {
            image.close()
        }
    }

    /**
     * Rotates an NV21 buffer by 90/180/270 degrees clockwise.
     *
     * NV21 is a full-resolution Y plane followed by an interleaved VU plane at
     * half resolution. Both planes rotate with the same coordinate mapping, just
     * at different scales. Returns null if the requested rotation is not a
     * right multiple of 90 (the caller then just drops the frame).
     */
    private fun rotateNv21(src: ByteArray, w: Int, h: Int, rotation: Int): ByteArray? {
        val rot = ((rotation % 360) + 360) % 360
        if (rot == 0) return src
        if (rot != 90 && rot != 180 && rot != 270) return null

        val ySize = w * h
        val need = ySize * 3 / 2
        var dst = rotBuf
        if (dst == null || dst.size != need) {
            dst = ByteArray(need)
            rotBuf = dst
        }

        when (rot) {
            90 -> {
                // (x, y) -> (h-1-y, x); result is h wide, w tall.
                val dstW = h
                for (y in 0 until h) {
                    val srcRow = y * w
                    val dstCol = h - 1 - y
                    var x = 0
                    while (x < w) {
                        dst[x * dstW + dstCol] = src[srcRow + x]
                        x++
                    }
                }
                val cw = w / 2
                val ch = h / 2
                val srcOff = ySize
                val dstCW = ch
                for (cy in 0 until ch) {
                    val srcRow = cy * cw
                    val dstCol = ch - 1 - cy
                    var cx = 0
                    while (cx < cw) {
                        val si = srcOff + (srcRow + cx) * 2
                        val di = ySize + (cx * dstCW + dstCol) * 2
                        dst[di] = src[si]         // V
                        dst[di + 1] = src[si + 1] // U
                        cx++
                    }
                }
            }
            270 -> {
                // (x, y) -> (y, w-1-x); result is h wide, w tall.
                val dstW = h
                for (y in 0 until h) {
                    val srcRow = y * w
                    val dstRowBase = (w - 1 - y)
                    var x = 0
                    while (x < w) {
                        dst[dstRowBase * dstW + x] = src[srcRow + x]
                        x++
                    }
                }
                val cw = w / 2
                val ch = h / 2
                val srcOff = ySize
                val dstCW = ch
                for (cy in 0 until ch) {
                    val srcRow = cy * cw
                    val dstRow = cw - 1 - cy
                    var cx = 0
                    while (cx < cw) {
                        val si = srcOff + (srcRow + cx) * 2
                        val di = ySize + (dstRow * dstCW + cx) * 2
                        dst[di] = src[si]
                        dst[di + 1] = src[si + 1]
                        cx++
                    }
                }
            }
            180 -> {
                // (x, y) -> (w-1-x, h-1-y); dims unchanged.
                var y = h - 1
                while (y >= 0) {
                    val srcRow = y * w
                    val dstRow = h - 1 - y
                    var x = w - 1
                    while (x >= 0) {
                        dst[dstRow * w + (w - 1 - x)] = src[srcRow + x]
                        x--
                    }
                    y--
                }
                val cw = w / 2
                val ch = h / 2
                val srcOff = ySize
                var cy = ch - 1
                while (cy >= 0) {
                    val srcRow = cy * cw
                    val dstRow = ch - 1 - cy
                    var cx = cw - 1
                    while (cx >= 0) {
                        val si = srcOff + (srcRow + cx) * 2
                        val di = ySize + (dstRow * cw + (cw - 1 - cx)) * 2
                        dst[di] = src[si]
                        dst[di + 1] = src[si + 1]
                        cx--
                    }
                    cy--
                }
            }
        }
        return dst
    }

    /**
     * Copy one YUV_420_888 plane into a reusable heap array with a single bulk
     * read. CameraX hands out *direct* ByteBuffers, so a per-byte `get()` costs a
     * JNI round-trip (~110ns): the old converter issued ~345k of them per frame on
     * the chroma planes alone, which measured at 38ms - the entire frame budget.
     *
     * Returns the number of bytes actually copied, or -1 on a short buffer.
     */
    private fun dumpPlane(
        proxy: androidx.camera.core.ImageProxy.PlaneProxy,
        reusable: ByteArray?
    ): Pair<ByteArray, Int>? {
        val buf = proxy.buffer
        buf.rewind()
        val avail = buf.remaining()
        if (avail <= 0) return null
        val dst = if (reusable == null || reusable.size < avail) ByteArray(avail) else reusable
        try {
            buf.get(dst, 0, avail)
        } catch (_: Exception) {
            return null
        }
        return dst to avail
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray? {
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0 || w % 2 != 0 || h % 2 != 0) return null
        val ySize = w * h
        val need = ySize + ySize / 2
        val out = nv21Buf
            ?.takeIf { it.size == need }
            ?: ByteArray(need).also { nv21Buf = it }

        // ---- Y plane: one bulk memcpy (or one per row when the stride pads) ----
        val yBuf = y.buffer
        val yRowStride = y.rowStride
        val yPixStride = y.pixelStride
        try {
            yBuf.rewind()
            if (yPixStride == 1) {
                if (yRowStride == w) {
                    yBuf.get(out, 0, ySize)
                } else {
                    var row = 0
                    while (row < h) {
                        yBuf.position(row * yRowStride)
                        yBuf.get(out, row * w, w)
                        row++
                    }
                }
            } else {
                var pos = 0
                for (row in 0 until h) {
                    val rowStart = row * yRowStride
                    for (col in 0 until w) out[pos++] = yBuf.get(rowStart + col * yPixStride)
                }
            }
        } catch (_: Exception) {
            return null
        }

        // ---- Chroma: bulk-dump both planes, then interleave plain array bytes ----
        val vDump = dumpPlane(v, vStaging) ?: return null
        val uDump = dumpPlane(u, uStaging) ?: return null
        val vs = vDump.first
        val us = uDump.first
        vStaging = vs
        uStaging = us
        val vLimit = vDump.second
        val uLimit = uDump.second

        val chromaW = w / 2
        val chromaH = h / 2
        val vRowStride = v.rowStride
        val uRowStride = u.rowStride
        val vPix = v.pixelStride
        val uPix = u.pixelStride

        // Highest index we touch - bail out rather than throw if the driver
        // handed back a plane shorter than its advertised strides suggest.
        val vMax = (chromaH - 1) * vRowStride + (chromaW - 1) * vPix
        val uMax = (chromaH - 1) * uRowStride + (chromaW - 1) * uPix
        if (vMax >= vLimit || uMax >= uLimit) return null

        var pos = ySize
        var row = 0
        while (row < chromaH) {
            val vb = row * vRowStride
            val ub = row * uRowStride
            var col = 0
            while (col < chromaW) {
                out[pos] = vs[vb + col * vPix]
                out[pos + 1] = us[ub + col * uPix]
                pos += 2
                col++
            }
            row++
        }
        return out
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Live torch: flip the flash on the bound camera session. Re-binding
        // the camera to change the flash made the preview disappear.
        if (intent != null && intent.action == ACTION_TORCH) {
            val on = intent.getBooleanExtra(EXTRA_TORCH, false)
            boundTorch = on
            val cam = boundCamera
            try {
                cam?.cameraControl?.enableTorch(on)
                android.util.Log.i("WebcamService", "torch=$on bound=${cam != null}")
            } catch (t: Throwable) {
                android.util.Log.w("WebcamService", "torch=$on failed: ${t.message}")
            }
            return START_STICKY
        }

        // Lifecycle must be RESUMED before CameraX binds to this owner.
        if (lifecycleRegistry.currentState != Lifecycle.State.RESUMED) {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val camera = intent.getStringExtra(EXTRA_CAMERA) ?: "back"
                var width = intent.getIntExtra(EXTRA_WIDTH, 1280)
                var height = intent.getIntExtra(EXTRA_HEIGHT, 720)
                // "Native" (width/height <= 0) => match the phone's own screen
                // resolution so the webcam feed is captured at the same size as
                // the live mirror, not a fixed 720p/1080p.
                if (width <= 0 || height <= 0) {
                    val dm = resources.displayMetrics
                    width = dm.widthPixels
                    height = dm.heightPixels
                }
                val fps = intent.getIntExtra(EXTRA_FPS, 30).coerceIn(1, 60)
                quality = intent.getIntExtra(EXTRA_QUALITY, 78).coerceIn(20, 95)
                val torch = intent.getBooleanExtra(EXTRA_TORCH, false)
                minIntervalMs = 1000L / fps
                createChannel()
                // Promote to a camera-typed foreground service first. Android 14+
                // validates FGS-type eligibility at the instant startForeground()
                // runs - not when the service was started - and a camera type is
                // only accepted while this app is in an eligible state (visible /
                // top). If the phone is locked or the user is elsewhere at that
                // exact moment the system throws, and an uncaught
                // SecurityException on the main looper kills the whole process:
                // the WebSocket drops, the desktop reports the phone as
                // "disconnected" and the mirror dies with it. That is precisely
                // the "webcam stops / phone disconnects a few seconds in" bug.
                // So promotion can never be allowed to escape uncaught, and when
                // it is refused we pull our own UI forward (which makes the
                // process eligible) and retry before giving up.
                pending = PendingStart(camera, Size(width, height), torch)
                startAttempts = 0
                if (promoteToFgs()) {
                    pending = null
                    startCapture(camera, Size(width, height), torch)
                } else {
                    retryPromotion()
                }
            }
        }
        return START_STICKY
    }

    private data class PendingStart(val camera: String, val size: Size, val torch: Boolean)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pending: PendingStart? = null
    private var startAttempts = 0

    /** Never lets a startForeground failure reach the main looper. */
    private fun promoteToFgs(): Boolean = try {
        startForeground(NOTIF_ID, buildNotification("Starting camera…"))
        true
    } catch (e: SecurityException) {
        android.util.Log.e(TAG, "camera FGS refused: ${e.message}")
        false
    } catch (e: IllegalStateException) {
        android.util.Log.e(TAG, "startForeground failed: ${e.message}")
        false
    }

    /**
     * Second chance after a refusal: bring DuoLink itself to the front, which
     * moves the process back to an eligible (TOP) state, then retry. If the
     * phone stays locked or DuoLink stays hidden the retry fails too - in that
     * case tell the PC exactly what to do and stop cleanly instead of crashing.
     */
    private fun retryPromotion() {
        if (startAttempts >= 2) {
            val give = pending
            pending = null
            sendStatus(false, "DuoLink is idle on the phone - open the app there to start the camera")
            if (give != null) stopSelf()
            return
        }
        startAttempts++
        try {
            startActivity(Intent(this, com.phonepc.pairing.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        } catch (e: Exception) {
            android.util.Log.w(TAG, "could not bring app forward: ${e.message}")
        }
        mainHandler.postDelayed({
            val p = pending ?: return@postDelayed
            if (promoteToFgs()) {
                pending = null
                startCapture(p.camera, p.size, p.torch)
            } else {
                retryPromotion()
            }
        }, 900)
    }

    private fun startCapture(cameraName: String, size: Size, torch: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            sendStatus(false, "Camera permission not granted on phone")
            updateNotif("Camera permission missing")
            stopSelf()
            return
        }

        // A start for a lens that is genuinely mid-bind is a no-op: re-binding
        // while the previous Camera2 session is still closing makes the HAL
        // reject the open ("Unable to configure camera N, timeout!").
        if (startingCamera == cameraName) return

        // Already live AND actually producing frames: re-assert the status (so
        // a desktop that restarted mid-stream clears its "Starting…" badge)
        // instead of silently doing nothing.
        val live = isActive && activeCamera == cameraName &&
            lastFrameAt > 0 && System.currentTimeMillis() - lastFrameAt < 3500
        if (live) {
            sendStatus(true, cameraName)
            return
        }

        // isActive was true but the stream is dead (camera evicted / device
        // closed without a callback) -> fall through and genuinely re-bind.
        activeCamera = cameraName
        startingCamera = cameraName
        isActive = true
        lastFrameAt = 0L
        rebindAttempts = 0
        boundWidth = size.width
        boundHeight = size.height
        boundTorch = torch
        bindStartedAt = System.currentTimeMillis()
        updateNotif("Starting $cameraName camera…")

        // (Re)start the watchdog whenever we own the stream.
        watchdog.removeCallbacks(watchdogTick)
        watchdog.postDelayed(watchdogTick, 3000)

        // Give the previous session (QR scanner or an older stream) a moment
        // to fully release the camera device before opening the new one.
        android.os.Handler(mainLooper).postDelayed({ bindCamera(cameraName, size, torch) }, 650)
    }

    /**
     * Pins the camera to the fastest frame rate the sensor advertises.
     *
     * CameraX 1.3 has no ImageAnalysis.setTargetFrameRate(), and because this
     * stream has no Preview use case attached the HAL is free to hand analysis
     * frames back at a sleepy 14-15fps - which is exactly what capped the
     * webcam there while the encoder was still doing only ~5ms of work per
     * frame. The AE target frame-rate range is the supported way to request a
     * rate through Camera2 interop, so we pick the highest range the device
     * publishes (60fps on most sensors, 30fps on the cheap ones) and stay
     * inside its advertised capability list instead of forcing a rate the
     * camera would reject.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun pinFastestFrameRate(camera: Camera) {
        try {
            val info = camera.cameraInfo
            val ranges = Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            // Prefer the widest upper bound; among equals prefer the narrowest
            // (highest lower) range so auto-exposure is not told to swing from
            // 1fps to 60fps and fight the stream.
            val fastest = ranges
                ?.filter { it.upper > 0 }
                ?.maxWithOrNull(compareBy({ it.upper }, { it.lower }))
            if (fastest == null) {
                android.util.Log.w(TAG, "camera publishes no AE fps ranges - keeping HAL default")
                return
            }
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fastest)
                .build()
            val future = Camera2CameraControl.from(camera.cameraControl)
                .setCaptureRequestOptions(options)
            future.addListener(
                { android.util.Log.i(TAG, "AE target fps range = $fastest (cap ${fastest.upper}fps)") },
                getMainExecutor(this)
            )
        } catch (e: Exception) {
            // Best effort: a HAL that refuses the option still streams, just
            // at whatever rate it picked for this resolution.
            android.util.Log.w(TAG, "could not pin fps range: ${e.message}")
        }
    }

    private fun bindCamera(cameraName: String, size: Size, torch: Boolean, attempt: Int = 0) {
        if (!isActive || startingCamera != cameraName) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                if (!isActive || startingCamera != cameraName) return@addListener
                val camProvider = future.get()
                provider = camProvider

                lastFrameAt = System.currentTimeMillis() // bind succeeded; frames expected from here
                val selector = if (cameraName == "front")
                    CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

                val analyzerBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    size,
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                )
                            )
                            .build()
                    )
                    // KEEP_ONLY_LATEST: when we cannot keep up, drop to the
                    // newest frame instead of queueing - latency stays bounded
                    // and the stream never replays a backlog.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // Let CameraX rotate the buffer itself (fast, native path).
                    // With this on, imageInfo.rotationDegrees comes back 0 and
                    // our per-byte software rotation pass is skipped entirely.
                    .setOutputImageRotationEnabled(true)
                val analyzer = analyzerBuilder.build()
                analyzer.setAnalyzer(analyzerExecutor, this)

                camProvider.unbindAll()
                val binding = camProvider.bindToLifecycle(this, selector, analyzer)
                boundCamera = binding
                pinFastestFrameRate(binding)

                if (torch) {
                    try { binding.cameraControl.enableTorch(true) } catch (_: Exception) { /* unsupported */ }
                }
                startingCamera = null
                // Only report "Live" once frames can actually flow: reporting
                // earlier left the PC showing a live badge with a dead stream.
                sendStatus(true, null)
                updateNotif("Streaming $cameraName camera")
            } catch (e: Exception) {
                if (attempt < 1) {
                    // One retry: the previous camera session may still be
                    // closing; an early reopen is rejected by several HALs
                    // with a configure timeout instead of a clean error.
                    android.os.Handler(mainLooper).postDelayed(
                        { bindCamera(cameraName, size, torch, attempt + 1) }, 900
                    )
                } else {
                    startingCamera = null
                    sendStatus(false, "Could not open camera: ${e.message}")
                    updateNotif("Camera failed")
                    stopCapture()
                }
            }
        }, getMainExecutor(this))
    }

    private fun stopCapture() {
        // A start that is still waiting for its eligibility retry must not fire
        // after an explicit stop.
        pending = null
        mainHandler.removeCallbacksAndMessages(null)
        watchdog.removeCallbacks(watchdogTick)
        isActive = false
        startingCamera = null
        try { provider?.unbindAll() } catch (_: Exception) {}
        provider = null
        sendStatus(false, null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "DuoLink Webcam",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows while the phone camera is streamed to your PC." }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 5,
            Intent(this, com.phonepc.pairing.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DuoLink Webcam")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_link)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotif(text: String) {
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) { /* best effort */ }
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        createChannel()
    }

    override fun onDestroy() {
        pending = null
        mainHandler.removeCallbacksAndMessages(null)
        watchdog.removeCallbacks(watchdogTick)
        isActive = false
        try { provider?.unbindAll() } catch (_: Exception) {}
        provider = null
        sendStatus(false, null)
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }
}
