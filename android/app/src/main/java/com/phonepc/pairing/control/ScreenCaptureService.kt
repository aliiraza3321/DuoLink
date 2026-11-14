package com.phonepc.pairing.control

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.widget.Toast
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.core.app.NotificationCompat
import com.phonepc.pairing.R
import com.phonepc.pairing.network.Protocol
import com.phonepc.pairing.service.PairingForegroundService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream

/**
 * PhonePC Pair - Screen Capture Service (Phase 4 Foundation)
 * Captures screen frames via MediaProjection at the phone's native resolution,
 * encoded at up to 60 fps with newest-frame-wins backpressure.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var imageReader: ImageReader? = null

    /**
     * Frame nudge from the ImageReader. Conflated on purpose: if several frames
     * land while the previous one is still being encoded, they collapse into a
     * single pending wake-up and the pump picks the NEWEST image. That is the
     * whole low-latency contract — never replay an old frame to catch up.
     */
    private val frameSignal = Channel<Unit>(Channel.CONFLATED)

    /** Install the nudge listener on a freshly created reader. */
    private fun watchReader(reader: ImageReader) {
        try {
            reader.setOnImageAvailableListener({ frameSignal.trySend(Unit) }, null)
        } catch (_: Throwable) { /* reader already closed */ }
    }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // --- Rotation support -------------------------------------------------
    // The VirtualDisplay/ImageReader used to be sized ONCE at start, so when
    // the phone rotated (a landscape game like PUBG) the stream stayed
    // portrait: the game was letterboxed tiny inside a portrait JPEG, frame
    // dims never flipped, and the desktop pop-out never switched to
    // landscape. These watch the display and rebuild the capture at the new
    // size so every frame carries true landscape width/height.
    @Volatile private var targetW = 0
    @Volatile private var targetH = 0
    private var pumpJob: Job? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var rebuilding = false
    private val mainHandler = Handler(Looper.getMainLooper())
    // Stall watchdog: when the reader stops yielding frames while we are still
    // capturing (a wedged ImageReader after a rotation, a driver hiccup while
    // a heavy game loads), the capture is rebuilt instead of streaming nothing.
    // Bounded to 3 attempts per streak of silence: a phone sitting on a STATIC
    // screen legitimately produces no frames, so this must never loop.
    @Volatile private var lastFrameAtMs = 0L
    @Volatile private var lastRepairAtMs = 0L
    @Volatile private var stallRepairCount = 0

    // --- H.264 path -------------------------------------------------------
    // JPEG cannot carry a native-resolution 60 fps mirror: 1080x2400 at q55 is
    // 150-250 KB per frame, i.e. 70-120 Mbit/s. The hardware encoder emits the
    // same picture at 8-12 Mbit/s on the GPU's video block, so full resolution
    // and 60 fps fit the same Wi-Fi. When MediaCodec refuses (rare, old
    // drivers), [h264Active] stays false and the JPEG pump below runs exactly
    // as before.
    private var videoEncoder: MediaCodec? = null
    private var encoderSurface: android.view.Surface? = null
    private var h264Thread: Thread? = null
    @Volatile private var h264Active = false
    @Volatile private var h264Frames = 0
    private var h264Seq = 0L

    // --- Link-latency guard -----------------------------------------------
    // The mirror shares one WebSocket with the webcam. If the encoder is given
    // more bitrate than the Wi-Fi actually carries, OkHttp queues the bytes and
    // the PC ends up painting a picture that is seconds old - or nothing at all
    // once the queue is deeper than the socket. These fields watch that queue
    // and steer MediaCodec's bitrate so it never turns into a backlog.
    @Volatile private var curBitrate = 3_500_000
    private var bitrateCeiling = 4_500_000
    private var lastRateChangeAt = 0L
    private var queueClearSince = 0L
    private var lastQueueLogAt = 0L

    private var isCapturing = false
    private var isFrameBusy = false
    private var stopped = false
    private var frameSeq = 0L
    private var reusableBitmap: Bitmap? = null
    private val jpegStream = ByteArrayOutputStream(256 * 1024)

    // --- Adaptive encode ladder (lag control) -----------------------------
    // Native-resolution JPEG of a game scene is big and noisy: at 30fps it can
    // demand tens of MB/s, more than a home Wi-Fi link drains. Frames back up,
    // latency climbs and the mirror stutters ("streaming is too laggy"). While
    // frames are being dropped the encoder steps DOWN (quality, then scale,
    // then cadence); once the link runs clear it climbs back, so idle UI stays
    // sharp while a heavy game gets a stream that actually fits the Wi-Fi.
    private class EncodeStep(val quality: Int, val scale: Float, val delayMs: Long)
    // 60 fps first: a 6.7" pane on the desktop is ~270 px wide, so half-scale
    // (540x1200 on a 1080x2400 phone) is already sharper than the pane can
    // show — and small JPEGs are what let 60 fps fit through home Wi-Fi.
    // Every rung keeps the 16 ms cadence: frame rate is the thing the desktop
    // asked for (60 fps), so a link that cannot carry the picture gets a
    // smaller picture instead of a slower one. Scale shrinks ~15% per rung and
    // the last rung is small enough (~270 px wide) to stream at 60 fps even on
    // a crowded 2.4 GHz network.
    private val encodeLadder = arrayOf(
        EncodeStep(84, 1.00f, 32L),   // full quality at the capture size
        EncodeStep(76, 1.00f, 32L),
        EncodeStep(68, 1.00f, 32L),
        EncodeStep(58, 1.00f, 32L)
    )
    @Volatile private var ladderStep = 0
    @Volatile private var encQuality = 84
    @Volatile private var encScale = 1.00f
    @Volatile private var encDelayMs = 32L
    private var dropStreak = 0
    private var clearStreak = 0
    // Per-second truth for the ladder: what was encoded, sent and dropped.
    private var statSent = 0
    private var statDropped = 0
    private var cleanWindows = 0
    // Achieved-rate watchdog: the drop ladder above only reacts when the socket
    // queue backs up. A phone that simply cannot encode fast enough never backs
    // up, so it used to sit at step 0 delivering ~15 fps forever. Measuring what
    // actually left the device lets the ladder trade resolution for frame rate.
    private var fpsWindowStartMs = 0L
    private var fpsWindowFrames = 0
    private var fpsPoorStreak = 0
    private var fpsGoodStreak = 0
    private var reusableScaled: Bitmap? = null
    private val scalePaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

    private fun applyEncodeStep(step: Int) {
        ladderStep = step.coerceIn(0, encodeLadder.lastIndex)
        val s = encodeLadder[ladderStep]
        encQuality = s.quality
        encScale = s.scale
        encDelayMs = s.delayMs
    }

    /** Down-scales a frame into a reused buffer (the ladder's scale step). */
    private fun scaleFrame(src: Bitmap): Bitmap {
        val sw = maxOf(1, (src.width * encScale).toInt())
        val sh = maxOf(1, (src.height * encScale).toInt())
        var dst = reusableScaled
        if (dst == null || dst.width != sw || dst.height != sh) {
            dst?.recycle()
            dst = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            reusableScaled = dst
        }
        android.graphics.Canvas(dst).drawBitmap(
            src, null, android.graphics.Rect(0, 0, sw, sh), scalePaint
        )
        return dst
    }

    // Backpressure: if this many bytes are already queued for send, the link
    // is stalled - skip the frame entirely. Newest-frame-wins keeps latency
    // bounded; queueing them would replay later as the "frozen screen, then
    // burst" artifact and would delay audio chunks riding the same socket.
    // Kept TIGHT (256KB): OkHttp's queue is only half of the hidden backlog —
    // whatever the OS socket buffer already absorbed is invisible to
    // queueSize(), so a generous gate let latent delay pile up before any drop
    // registered. A small queue means each delivered frame is young, so the
    // picture tracks the phone instead of trailing it by a beat.
    // ~2 frames at 60 fps: enough to ride a scheduling hiccup, small enough
    // that a slow link can never replay a visible backlog.
    private val maxQueuedBytes = 160L * 1024L

    // Phone sound -> PC: internal playback captured through the SAME
    // MediaProjection (Android 10+), streamed as PCM16LE binary frames so the
    // desktop can play whatever the phone is playing — video, music, calls'  speaker,  the  lot.
    private var audioRecord: AudioRecord? = null
    @Volatile private var audioRunning = false
    private var audioThread: Thread? = null

    // Keeps the CPU awake while mirroring so the stream (and the audio pump)
    // keeps flowing even when the phone screen is turned off / goes to sleep.
    // Without this the CPU can idle and the frame pump stalls on a black reader.
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenOffRegistered = false

    companion object {
        const val CHANNEL_ID = "phonepc_screen_channel"
        const val NOTIFICATION_ID = 2002
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val ACTION_STOP_CAPTURE = "ACTION_STOP_CAPTURE"

        /** Live screen-mirror state, surfaced in the PC's diagnostics pane. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Running service, so the PC can ask for a keyframe (pop-out opens). */
        @Volatile
        var activeServiceInstance: ScreenCaptureService? = null
            private set
    }

    /** Forces the next encoded frame to be an IDR (called from the PC side). */
    fun requestKeyframeFromPc() {
        requestH264Keyframe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent?.action == ACTION_STOP_CAPTURE) {
            stopCapture()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            try {
                // Explicit mediaProjection type is required on Android 14+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(
                        this, NOTIFICATION_ID, buildScreenNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, buildScreenNotification())
                }
                initMediaProjection(resultCode, resultData)
            } catch (e: Throwable) {
                Log.e("ScreenCapture", "Failed to start screen capture", e)
                Toast.makeText(this, "Screen share failed: ${e.javaClass.simpleName}: ${e.message}", Toast.LENGTH_LONG).show()
                stopCapture()
            }
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)
        // Android 14+: a callback MUST be registered before createVirtualDisplay()
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopCapture() }
        }, android.os.Handler(android.os.Looper.getMainLooper()))

        // The VirtualDisplay runs at the phone's native resolution and the
        // encode ladder scales it down before JPEG: 60 fps needs small frames,
        // and the desktop pane never shows more than ~270 CSS px anyway. Read
        // from the display itself so the rotation watcher compares identical
        // numbers later; fall back to app metrics if the lookup fails.
        val metrics = DisplayMetrics()
        try {
            (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
        } catch (_: Throwable) {}
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            metrics.setTo(resources.displayMetrics)
        }
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        targetW = width
        targetH = height

        // Encoder first (measured): the same link that carried 1080x2436 at
        // 54-60 fps through H.264 drops to 10-18 fps at 345-540 px through
        // per-frame JPEG, because JPEG spends 10-20x the bytes for the same
        // picture. The earlier "camera got slower" symptom was the link being
        // saturated by mirroring, not the encoder: with the bitrate H.264 needs,
        // both streams fit. JPEG stays as the automatic fallback below for
        // phones whose encoder never emits a buffer.
        if (startH264(width, height, density)) {
            isCapturing = true
            isRunning = true
            lastFrameAtMs = android.os.SystemClock.elapsedRealtime()
            lastRepairAtMs = 0L
            stallRepairCount = 0
            acquireWakeLock()
            watchDisplayRotation()
            startAudioCapture(mediaProjection!!)
            return
        }

        // Capture at most 720 px wide. The VirtualDisplay is what fills every
        // frame, so asking it for 1080x2436 meant copying ~10 MB of ARGB and
        // JPEG-ing it 30 times a second - the phone got hot and the CPU stayed
        // above 60%. A 720-wide capture is still sharper than anything this
        // link can carry, at rough a third of the cost.
        val capScale = minOf(1f, 720f / width.toFloat())
        val capW = maxOf(2, (width * capScale).toInt())
        val capH = maxOf(2, (height * capScale).toInt())
        targetW = capW
        targetH = capH
        imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        imageReader?.let { watchReader(it) }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "PhonePCScreenStream",
            capW, capH, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        isCapturing = true
        isRunning = true
        lastFrameAtMs = android.os.SystemClock.elapsedRealtime()
        lastRepairAtMs = 0L
        stallRepairCount = 0
        acquireWakeLock()
        pumpJob = startFramePump(capW, capH)
        watchDisplayRotation()
        startAudioCapture(mediaProjection!!)
    }

    /**
     * Watches the default display for rotation so a landscape app (PUBG and
     * friends) rebuilds the capture at the new size while mirroring runs.
     */
    private fun watchDisplayRotation() {
        if (displayListener != null) return
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) checkDisplaySize()
            }
        }
        displayListener = listener
        try {
            dm.registerDisplayListener(listener, mainHandler)
        } catch (_: Throwable) {}
    }


    // ---------------- H.264 mirror (native resolution, 60 fps) ----------------

    /**
     * Starts the hardware encoder and mirrors the display into its input
     * surface. Returns false (having cleaned up) when this device/driver cannot
     * do it, so the caller falls back to the JPEG reader untouched.
     */
    private fun startH264(width: Int, height: Int, density: Int): Boolean {
        val mp = mediaProjection ?: return false
        return try {
            val codec = createH264Encoder(width, height)
            val surface = codec.createInputSurface()
            codec.start()
            val display = mp.createVirtualDisplay(
                "PhonePCScreenStream",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )
            videoEncoder = codec
            encoderSurface = surface
            virtualDisplay = display
            h264Active = true
            h264Thread = Thread { drainH264() }.apply { name = "duolink-h264"; start() }
            Log.i("ScreenCapture", "H.264 mirror started ${width}x$height @60")
            // Some drivers accept the input surface but never emit a buffer.
            // Rather than leaving the PC on a black mirror, fall back to the
            // JPEG pump (the path that always worked) after a short grace.
            mainHandler.postDelayed({
                if (h264Active && h264Frames == 0) {
                    Log.w("ScreenCapture", "H.264 emitted nothing - falling back to JPEG")
                    startJpegMirror(width, height)
                }
            }, 3500)
            true
        } catch (t: Throwable) {
            Log.w("ScreenCapture", "H.264 unavailable (${t.javaClass.simpleName}: ${t.message}) - using JPEG")
            stopH264()
            false
        }
    }

    private fun createH264Encoder(width: Int, height: Int): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        bitrateCeiling = h264BitrateFor(width, height)
        curBitrate = minOf(bitrateCeiling, 2_000_000)
        format.setInteger(MediaFormat.KEY_BIT_RATE, curBitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        try {
            format.setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )
        } catch (_: Throwable) { /* pre-L encoders: leave the default mode */ }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    /**
     * Bitrate budget for the mirror. The pixel-based figure is what native
     * resolution would like, but a home link (and especially the 2.4 GHz /
     * repeater paths phones often sit on) carries far less, so it is capped: a
     * slightly softer picture that arrives now beats a sharp one that arrives
     * three seconds late.
     */
    private fun h264BitrateFor(width: Int, height: Int): Int {
        val px = width.toLong() * height.toLong()
        return (px * 60L * 5L / 100L).coerceIn(1_500_000L, 3_200_000L).toInt()
    }

    /** Live bitrate change: the encoder keeps running, only its budget moves. */
    private fun applyBitrate(rate: Int) {
        val clamped = rate.coerceIn(900_000, bitrateCeiling)
        if (clamped == curBitrate) return
        curBitrate = clamped
        try {
            videoEncoder?.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, clamped)
            })
        } catch (_: Throwable) {}
    }

    /**
     * Keeps the socket queue short. A queue of N bytes drains at the link's real
     * speed, so N bytes is N/speed seconds of extra latency: the desktop paints
     * the past. When the queue builds the encoder's budget is cut, and it climbs
     * back once the link is clear, so the mirror stays live instead of late.
     */
    private fun steerBitrate(queued: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastQueueLogAt > 2000L) {
            lastQueueLogAt = now
            Log.i(
                "ScreenCapture",
                "h264 queue=${queued / 1024}KB bitrate=${curBitrate / 1000}kbps"
            )
        }
        if (queued > 120_000L) {
            queueClearSince = 0L
            if (now - lastRateChangeAt > 700L) {
                lastRateChangeAt = now
                applyBitrate((curBitrate * 3) / 4)
            }
        } else if (queued < 40_000L) {
            if (queueClearSince == 0L) queueClearSince = now
            else if (now - queueClearSince > 4000L) {
                lastRateChangeAt = now
                queueClearSince = now
                applyBitrate((curBitrate * 5) / 4)
            }
        } else {
            queueClearSince = 0L
        }
    }

    private fun readCsd(format: MediaFormat, key: String): ByteArray? {
        return try {
            val buf = format.getByteBuffer(key) ?: return null
            val out = ByteArray(buf.remaining())
            buf.get(out)
            out
        } catch (_: Throwable) { null }
    }

    /** Config payload: 4-byte big-endian SPS length, SPS, PPS. */
    private fun sendH264Config(csd0: ByteArray, csd1: ByteArray) {
        val svc = PairingForegroundService.activeServiceInstance ?: return
        val out = ByteArray(4 + csd0.size + csd1.size)
        out[0] = ((csd0.size ushr 24) and 0xff).toByte()
        out[1] = ((csd0.size ushr 16) and 0xff).toByte()
        out[2] = ((csd0.size ushr 8) and 0xff).toByte()
        out[3] = (csd0.size and 0xff).toByte()
        System.arraycopy(csd0, 0, out, 4, csd0.size)
        System.arraycopy(csd1, 0, out, 4 + csd0.size, csd1.size)
        h264Seq++
        svc.sendRawBinary(
            Protocol.encodeBinaryFrame(
                Protocol.BIN_KIND_PHONE_H264_CONFIG, h264Seq, targetW, targetH, out
            )
        )
    }

    private fun sendH264Frame(nal: ByteArray) {
        val svc = PairingForegroundService.activeServiceInstance ?: return
        h264Frames++
        h264Seq++
        svc.sendRawBinary(
            Protocol.encodeBinaryFrame(
                Protocol.BIN_KIND_PHONE_H264, h264Seq, targetW, targetH, nal
            )
        )
    }

    /**
     * Drains the encoder. Encoded frames are NOT dropped: an H.264 stream is a
     * dependency chain, so skipping one corrupts everything after it until the
     * next keyframe. The 1 s keyframe interval bounds recovery instead.
     */
    private fun drainH264() {
        val codec = videoEncoder ?: return
        val info = MediaCodec.BufferInfo()
        var configSent = false
        while (h264Active) {
            try {
                val index = codec.dequeueOutputBuffer(info, 20_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = codec.outputFormat
                    val csd0 = readCsd(format, "csd-0")
                    val csd1 = readCsd(format, "csd-1")
                    if (csd0 != null) {
                        sendH264Config(csd0, csd1 ?: ByteArray(0))
                        configSent = true
                    }
                    continue
                }
                if (index < 0) continue
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(bytes, 0, info.size)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        if (!configSent) {
                            sendH264Config(bytes, ByteArray(0))
                            configSent = true
                        }
                    } else {
                        val svc = PairingForegroundService.activeServiceInstance
                        val queued = svc?.sendQueuedBytes() ?: 0L
                        steerBitrate(queued)
                        if (queued > 180_000L) {
                            // The link is behind. Sending this delta would add to a
                            // backlog the PC replays later as a frozen picture and
                            // then a burst; ask for a keyframe and stay fresh.
                            requestH264Keyframe()
                        } else {
                            sendH264Frame(bytes)
                        }
                    }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (t: Throwable) {
                if (h264Active) Log.w("ScreenCapture", "H.264 drain stopped: ${t.message}")
                break
            }
        }
    }

    /** Forces the next encoded access unit to be an IDR (clean resume point). */
    private fun requestH264Keyframe() {
        val codec = videoEncoder ?: return
        try {
            codec.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Throwable) {}
    }

    private fun stopH264() {
        h264Active = false
        try { h264Thread?.interrupt() } catch (_: Throwable) {}
        h264Thread = null
        try { videoEncoder?.stop() } catch (_: Throwable) {}
        try { videoEncoder?.release() } catch (_: Throwable) {}
        videoEncoder = null
        try { encoderSurface?.release() } catch (_: Throwable) {}
        encoderSurface = null
    }

    /**
     * Fallback path: reuse the live VirtualDisplay but feed an ImageReader, so
     * mirroring keeps working on phones whose encoder never produces output.
     */
    private fun startJpegMirror(width: Int, height: Int) {
        try {
            stopH264()
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            watchReader(reader)
            val display = virtualDisplay
            if (display != null) {
                display.surface = reader.surface
            } else {
                val mp = mediaProjection ?: return
                virtualDisplay = mp.createVirtualDisplay(
                    "PhonePCScreenStream",
                    width, height, resources.displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, null
                )
            }
            imageReader = reader
            isCapturing = true
            isRunning = true
            lastFrameAtMs = android.os.SystemClock.elapsedRealtime()
            if (pumpJob == null) pumpJob = startFramePump(width, height)
            Log.i("ScreenCapture", "JPEG mirror running at ${width}x$height")
        } catch (t: Throwable) {
            Log.e("ScreenCapture", "JPEG fallback failed", t)
        }
    }

    /** Rotation while the encoder runs: new surface at the new size. */
    private fun rebuildH264(w: Int, h: Int) {
        val mp = mediaProjection ?: return
        if (rebuilding) return
        rebuilding = true
        scope.launch {
            try {
                val display = virtualDisplay
                stopH264()
                val codec = createH264Encoder(w, h)
                val surface = codec.createInputSurface()
                codec.start()
                if (display != null) {
                    val density = resources.displayMetrics.densityDpi
                    try {
                        display.resize(w, h, density)
                        display.surface = surface
                    } catch (t: Throwable) {
                        Log.w("ScreenCapture", "H.264 display resize failed: ${t.message}")
                    }
                }
                videoEncoder = codec
                encoderSurface = surface
                targetW = w
                targetH = h
                h264Active = true
                h264Thread = Thread { drainH264() }.apply { name = "duolink-h264"; start() }
                Log.i("ScreenCapture", "H.264 rebuilt at ${w}x$h")
            } catch (t: Throwable) {
                Log.e("ScreenCapture", "H.264 rotation rebuild failed", t)
            } finally {
                rebuilding = false
            }
        }
    }

    private fun checkDisplaySize() {
        if (!isCapturing || rebuilding) return
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val disp = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val m = DisplayMetrics()
        try { disp.getRealMetrics(m) } catch (_: Throwable) { return }
        val w = m.widthPixels
        val h = m.heightPixels
        if (w <= 0 || h <= 0 || (w == targetW && h == targetH)) return
        // A rotation is a fresh risk window: give it a new stall-repair budget
        // so repairs spent before the rotation can't strand the new orientation.
        stallRepairCount = 0
        rebuildCapture(w, h)
    }

    /** Tear down and re-create reader + VirtualDisplay at the rotated size. */
    private fun rebuildCapture(w: Int, h: Int) {
        val mp = mediaProjection ?: return
        if (h264Active) { rebuildH264(w, h); return }
        if (rebuilding) return
        rebuilding = true
        Log.i("ScreenCapture", "Rotation: rebuilding capture ${targetW}x$targetH -> ${w}x$h")
        scope.launch {
            try {
                try { pumpJob?.cancelAndJoin() } catch (_: Throwable) {}
                if (!isCapturing) return@launch

                val oldReader = imageReader
                val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
                watchReader(reader)
                val density = resources.displayMetrics.densityDpi

                // Resize the existing VirtualDisplay instead of creating a new one.
                // Android 14+ allows a single VirtualDisplay per MediaProjection:
                // the old code released it and called createVirtualDisplay() again,
                // which throws on 14/15 — exactly when the phone rotates into a
                // landscape game, so the mirror died instead of following the
                // rotation. resize() + a fresh surface keeps the one display alive.
                var vd = virtualDisplay
                var attached = false
                if (vd != null) {
                    try {
                        vd.resize(w, h, density)
                        vd.surface = reader.surface
                        attached = true
                    } catch (t: Throwable) {
                        Log.w(
                            "ScreenCapture",
                            "VirtualDisplay resize failed: ${t.javaClass.simpleName}: ${t.message}"
                        )
                    }
                }

                if (!attached) {
                    // Older devices where resize() refuses: rebuild the display.
                    try { vd?.release() } catch (_: Throwable) {}
                    vd = createCaptureDisplay(mp, reader, w, h, density)
                    if (vd == null) {
                        // Mid-rotation the display manager may refuse once; retry
                        // a beat later before giving up on this size.
                        delay(280)
                        vd = createCaptureDisplay(mp, reader, w, h, density)
                    }
                    if (vd == null) {
                        try { reader.close() } catch (_: Throwable) {}
                        throw IllegalStateException("VirtualDisplay unavailable after rotation")
                    }
                }

                imageReader = reader
                virtualDisplay = vd
                targetW = w
                targetH = h
                // Close the previous reader only once the new surface is fed, so
                // the pump never waits on an abandoned reader.
                try { oldReader?.close() } catch (_: Throwable) {}
                // Fresh grace window: the stall watchdog must measure the NEW
                // reader's silence, not the old one's.
                lastFrameAtMs = android.os.SystemClock.elapsedRealtime()
                pumpJob = startFramePump(w, h)
            } catch (t: Throwable) {
                Log.e("ScreenCapture", "Rotation rebuild failed", t)
            } finally {
                rebuilding = false
                // A rotation signalled while we were rebuilding was dropped
                // (checkDisplaySize bails while rebuilding): re-check now so
                // the capture settles at the display's true final size.
                mainHandler.post { if (isCapturing) checkDisplaySize() }
            }
        }
    }

    /** Create a capture VirtualDisplay, returning null instead of throwing. */
    private fun createCaptureDisplay(
        mp: MediaProjection,
        reader: ImageReader,
        w: Int,
        h: Int,
        density: Int
    ): VirtualDisplay? = try {
        mp.createVirtualDisplay(
            "PhonePCScreenStream", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
    } catch (t: Throwable) {
        Log.w("ScreenCapture", "createVirtualDisplay failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /**
     * Streams the phone's own playback (media, games, notifications' audio
     * streams) to the PC. AudioPlaybackCapture reuses the projection the video
     * already rides, so there is no second consent dialog. Android 9 and
     * below have no such API — video-only there, silently.
     */
    private fun startAudioCapture(mp: MediaProjection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("ScreenCapture", "RECORD_AUDIO not granted - mirroring without phone sound")
            return
        }
        try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            // 44.1kHz mono PCM16 = ~88KB/s over LAN — trivial next to video,
            // and any device can feed 44.1k through the playback-capture mixer.
            val sampleRate = 44100
            val channelMask = AudioFormat.CHANNEL_IN_MONO
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return

            val rec = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, 8192) * 2)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                Log.w("ScreenCapture", "AudioRecord did not initialize - video only")
                return
            }

            audioRecord = rec
            audioRunning = true
            rec.startRecording()

            audioThread = Thread({
                // 4096 bytes = ~46ms per packet: small enough for low latency,
                // big enough that per-packet overhead stays negligible.
                val buf = ByteArray(4096)
                var seq = 0L
                while (audioRunning) {
                    val read = rec.read(buf, 0, buf.size)
                    if (read > 0) {
                        seq++
                        // Audio shares the socket with video, so it checks the
                        // queue too: a stalled link must skip a stale 46ms
                        // chunk (inaudible gap) rather than build a delay that
                        // makes every later chunk arrive seconds late - that was
                        // the "audio is slow" artifact. 256KB ≈ 3s of PCM.
                        val svc = PairingForegroundService.activeServiceInstance
                        if (svc != null && svc.sendQueuedBytes() < 256L * 1024L) {
                            // Header re-purposed for audio: width = sampleRate,
                            // height = channel count; payload = raw PCM16LE.
                            svc.sendRawBinary(
                                Protocol.encodeBinaryFrame(
                                    Protocol.BIN_KIND_AUDIO,
                                    seq,
                                    sampleRate,
                                    1,
                                    if (read == buf.size) buf else buf.copyOf(read)
                                )
                            )
                        }
                    } else if (read < 0) {
                        break // recorder died / released - stop cleanly
                    }
                }
            }, "phonepc-audio").apply {
                isDaemon = true
                start()
            }
            Log.i("ScreenCapture", "Phone audio -> PC started (44.1k mono)")
        } catch (t: Throwable) {
            // No playback-capture on this device/config: video must not care.
            Log.e("ScreenCapture", "Audio capture unavailable: ${t.javaClass.simpleName}: ${t.message}")
            audioRunning = false
            try { audioRecord?.release() } catch (_: Throwable) {}
            audioRecord = null
        }
    }

    /**
     * Screen-off keepalive.
     *
     * Android stops composing the display once the panel powers down, so a
     * mirrored stream would freeze on its last frame - whether the screen timed
     * out or the user hit the power button. Whenever that broadcast arrives
     * while mirroring we immediately ask the display to come back on (dim), so
     * the stream never pauses; the panel itself stays dark enough to leave the
     * phone in a pocket.
     */
    private val screenOffReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            if (!isRunning) return
            Log.i("ScreenCapture", "Screen off during mirroring - waking display to keep the stream live")
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "DuoLink:screenoff-keepalive"
                )
                wl.acquire(6000L)
            } catch (t: Throwable) {
                Log.w("ScreenCapture", "Could not wake display: ${t.message}")
            }
            // The projection can come back blank after a panel power cycle;
            // force an IDR so the PC has a clean frame right away.
            requestH264Keyframe()
        }
    }

    private fun registerScreenOffReceiver() {
        try {
            android.content.IntentFilter(Intent.ACTION_SCREEN_OFF).let { f ->
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(screenOffReceiver, f, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    registerReceiver(screenOffReceiver, f)
                }
            }
            screenOffRegistered = true
        } catch (t: Throwable) {
            Log.w("ScreenCapture", "screen-off receiver unavailable: ${t.message}")
        }
    }

    private fun acquireWakeLock() {
        registerScreenOffReceiver()
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                // CPU only. A SCREEN_DIM lock also holds the display, but it
                // pins brightness at its minimum - the phone looked switched
                // off while mirroring. The screen-off receiver below wakes the
                // panel (at normal brightness) whenever it sleeps, so frames
                // keep flowing without turning the phone into a dark slab.
                @Suppress("DEPRECATION")
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "DuoLink:screenmirror"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(60 * 60 * 1000L) // safety auto-release after 1h
            Log.i("ScreenCapture", "Wake lock acquired - display kept alive for mirroring")
        } catch (t: Throwable) {
            Log.w("ScreenCapture", "Wake lock unavailable: ${t.message}")
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
        if (screenOffRegistered) {
            try { unregisterReceiver(screenOffReceiver) } catch (_: Throwable) {}
            screenOffRegistered = false
        }
    }

    private fun stopAudioCapture() {
        audioRunning = false
        val rec = audioRecord
        audioRecord = null
        audioThread = null
        try { rec?.stop() } catch (_: Throwable) {}
        // stop() unblocks any pending read(); the thread sees audioRunning=false and exits.
        try { rec?.release() } catch (_: Throwable) {}
    }

    private fun startFramePump(targetW: Int, targetH: Int): Job = scope.launch {
        var lastPollAt = 0L
        while (isCapturing) {
            // Wake on the next frame instead of sleeping a fixed interval first:
            // that alone removed up to one frame of latency per picture. The
            // timeout keeps the old 30 fps ceiling (the ladder may stretch it to
            // 40-50 ms on a congested link) and doubles as the fallback path
            // when a device never fires the listener.
            withTimeoutOrNull(encDelayMs) { frameSignal.receive() }
            // Rotation fallback: some devices only reflect the new size here,
            // so poll the display twice a second as well as listening.
            val nowMs = android.os.SystemClock.elapsedRealtime()
            if (nowMs - lastPollAt > 700) {
                lastPollAt = nowMs
                mainHandler.post { if (isCapturing) checkDisplaySize() }
                // No frame for 2.5s while supposedly capturing = wedged reader
                // (this is what blanked both PC views when PUBG launched in
                // landscape). Rebuild to unstick it — at most 3 times until a
                // frame flows again, so an idle static screen never loops.
                if (lastFrameAtMs > 0 && nowMs - lastFrameAtMs > 2500 &&
                    stallRepairCount < 3 && nowMs - lastRepairAtMs > 3000
                ) {
                    lastRepairAtMs = nowMs
                    stallRepairCount++
                    Log.w("ScreenCapture", "Frame stall detected - rebuilding capture ($stallRepairCount/3)")
                    mainHandler.post { if (isCapturing) rebuildCapture(targetW, targetH) }
                }
            }
            if (isFrameBusy) continue

            val reader = imageReader ?: continue
            val image = try { reader.acquireLatestImage() } catch (_: Throwable) { null } ?: continue
            isFrameBusy = true
            lastFrameAtMs = android.os.SystemClock.elapsedRealtime()
            stallRepairCount = 0

                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * targetW

                    val fullW = targetW + rowPadding / pixelStride
                    var bitmap = reusableBitmap
                    if (bitmap == null || bitmap.width != fullW || bitmap.height != targetH) {
                        bitmap?.recycle()
                        bitmap = Bitmap.createBitmap(fullW, targetH, Bitmap.Config.ARGB_8888)
                        reusableBitmap = bitmap
                    }
                    bitmap.copyPixelsFromBuffer(buffer)
                    image.close()

                    // Crop out padding if needed (shares the reused bitmap's pixels)
                    val cleanBitmap = if (rowPadding != 0) {
                        Bitmap.createBitmap(bitmap, 0, 0, targetW, targetH)
                    } else bitmap

                    // No live link: skip the encode entirely — JPEGing frames
                    // nobody can receive only heats the phone during a game.
                    val svc = PairingForegroundService.activeServiceInstance
                    if (svc != null && svc.sendQueuedBytes() >= maxQueuedBytes) {
                        // Link is behind: drop BEFORE the expensive work. This is
                        // the difference between a phone that stays cool and one
                        // that compresses thirty useless frames a second.
                        statDropped++
                        dropStreak++
                        if (dropStreak >= 8 && ladderStep < encodeLadder.lastIndex) {
                            dropStreak = 0
                            applyEncodeStep(ladderStep + 1)
                        }
                        try { image.close() } catch (_: Throwable) {}
                        continue
                    }
                    if (svc != null) {
                        // Scale first when the ladder says so: a smaller picture
                        // is both cheaper to encode and far cheaper to send —
                        // exactly what a congested link needs.
                        val source = if (encScale < 0.999f) scaleFrame(cleanBitmap) else cleanBitmap

                        jpegStream.reset()
                        // Ladder-controlled quality (starts at q50 — screen
                        // content compresses well; game scenes are noisy, and
                        // that is when the ladder steps down to fit the link).
                        source.compress(Bitmap.CompressFormat.JPEG, encQuality, jpegStream)
                        val jpegBytes = jpegStream.toByteArray()

                        // Binary channel (kind 4): raw JPEG with the 9-byte header -
                        // no base64 (+33% size), no JSON stringify/Gson parse on
                        // either end. Drop instead of queue when the socket backs up.
                        // --- achieved-fps watchdog (once per second) ---
                        val tickMs = android.os.SystemClock.elapsedRealtime()
                        if (fpsWindowStartMs == 0L) fpsWindowStartMs = tickMs
                        fpsWindowFrames++
                        val windowMs = tickMs - fpsWindowStartMs
                        if (windowMs >= 1000) {
                            val achievedFps = (fpsWindowFrames * 1000L / windowMs).toInt()
                            fpsWindowStartMs = tickMs
                            fpsWindowFrames = 0
                            if (achievedFps < 20) {
                                fpsPoorStreak++
                                fpsGoodStreak = 0
                            } else if (achievedFps >= 28) {
                                fpsGoodStreak++
                                fpsPoorStreak = 0
                            } else {
                                fpsPoorStreak = 0
                                fpsGoodStreak = 0
                            }
                            android.util.Log.i(
                                "ScreenCapture",
                                "mirror fps=$achievedFps sent=$statSent dropped=$statDropped " +
                                    "q=$encQuality scale=$encScale queue=${PairingForegroundService.activeServiceInstance?.sendQueuedBytes() ?: -1L}"
                            )
                            val droppedWindow = statDropped
                            statSent = 0
                            statDropped = 0
                            if (droppedWindow == 0) cleanWindows++ else cleanWindows = 0
                            if (cleanWindows >= 3 && ladderStep > 0) {
                                cleanWindows = 0
                                applyEncodeStep(ladderStep - 1)
                                Log.i("ScreenCapture", "Encode ladder up -> step $ladderStep (clean link)")
                            }
                            if (fpsPoorStreak >= 2 && ladderStep < encodeLadder.lastIndex) {
                                fpsPoorStreak = 0
                                applyEncodeStep(ladderStep + 1)
                                Log.i("ScreenCapture", "FPS ${achievedFps} below target - ladder down -> step $ladderStep (q$encQuality, scale $encScale)")
                            } else if (fpsGoodStreak >= 4 && ladderStep > 0) {
                                fpsGoodStreak = 0
                                applyEncodeStep(ladderStep - 1)
                                Log.i("ScreenCapture", "FPS ${achievedFps} holding - ladder up -> step $ladderStep")
                            }
                        }

                        if (svc.sendQueuedBytes() < maxQueuedBytes) {
                            frameSeq++
                            svc.sendRawBinary(
                                Protocol.encodeBinaryFrame(
                                    Protocol.BIN_KIND_PHONE_SCREEN,
                                    frameSeq,
                                    source.width,
                                    source.height,
                                    jpegBytes
                                )
                            )
                            statSent++
                            // Link is draining: hold the current step, and after a
                            // few clear seconds climb back toward native quality.
                            dropStreak = 0
                            clearStreak++
                            dropStreak = 0
                        } else {
                            // Link is backing up: step down after a short streak
                            // so one slow frame can't downgrade the whole stream.
                            statDropped++
                            clearStreak = 0
                            dropStreak++
                            if (dropStreak >= 8 && ladderStep < encodeLadder.lastIndex) {
                                dropStreak = 0
                                applyEncodeStep(ladderStep + 1)
                                Log.i("ScreenCapture", "Encode ladder down -> step $ladderStep")
                            }
                        }
                    }
                } catch (_: Throwable) {
                    try { image.close() } catch (_: Throwable) {}
                } finally {
                    isFrameBusy = false
                }
            }
        }

    private fun buildScreenNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Mirroring Active",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP_CAPTURE },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Mirroring to PC")
            .setContentText("Your phone screen is being shared live.")
            .setSmallIcon(R.drawable.ic_link)
            .addAction(R.drawable.ic_close, "Stop Share", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun stopCapture() {
        if (stopped) return
        stopped = true
        isCapturing = false
        isRunning = false
        displayListener?.let {
            try {
                (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it)
            } catch (_: Throwable) {}
        }
        displayListener = null
        stopAudioCapture()
        releaseWakeLock()
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        stopH264()
        try { imageReader?.close() } catch (_: Throwable) {}
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        scope.cancel()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
    }

    override fun onDestroy() {
        stopCapture()
        if (activeServiceInstance === this) activeServiceInstance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
