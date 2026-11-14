/**
 * DuoLink desktop capture (the offscreen page).
 *
 * Why this exists: the mirror used to call desktopCapturer.getSources() once
 * per frame. That API re-enumerates every screen and window and re-snapshots
 * thumbnails on each call, so it caps out in the low teens - which is exactly
 * why the mirror looked like a slideshow over a perfectly healthy LAN link.
 *
 * Chrome Remote Desktop and every browser "share this screen" flow do it a
 * different way: acquire the desktop as a MediaStream ONCE, then repeatedly
 * paint it to a canvas and encode that canvas.
 *
 * Frame source: MediaStreamTrackProcessor reads the video track directly.
 * That matters because a hidden window's compositor only PRESENTS about 20
 * frames a second, and every compositor-bound loop (requestVideoFrameCallback
 * or a timer keyed to video.currentTime) silently capped the mirror at ~20fps
 * no matter what rate the settings asked for. Reading the track skips the
 * compositor entirely, so the OS duplicator's full rate reaches the encoder.
 *
 * Backpressure is end-to-end: the reader keeps only the newest frame (older
 * ones are closed immediately), so if an encode is slow the mirror drops
 * frames instead of drifting behind reality.
 */
/* global captureApi */

const video = document.getElementById('v');
const canvas = document.getElementById('c');
const ctx = canvas.getContext('2d', { alpha: false, willReadFrequently: false });

// Ceiling requested from the OS at acquire time; the live fps setting only
// throttles on top of it, so raising fps later never needs a re-acquire.
const SOURCE_FPS_CEILING = 60;

let cfg = { fps: 30, quality: 60, maxDimension: 1280, displayW: 0, displayH: 0 };
let sourceId = null;
// The constraint box actually used at acquire time; when it no longer matches
// what the current settings demand, the stream is re-acquired (see below).
let acquiredReqW = 0;
let acquiredReqH = 0;
let stream = null;
let running = false;
let seq = 0;
let targetW = 0;
let targetH = 0;
let lastEncodeAt = 0;
let frameSent = false;

// Latest available frame (track-reader mode) and the two pumps that trade it.
let pendingFrame = null;
let reader = null;
let encoding = false;
// 1 = full configured size; shrinks when encode time exceeds the frame budget.
let dynScale = 1;

// Fallback loop state (no MediaStreamTrackProcessor: Chromium < 94).
let timer = null;
let lastCapturedTime = -1;
let encodeMs = 0;

/**
 * Acquire the desktop stream. Called only when the source id changes -
 * re-acquiring costs hundreds of milliseconds of black frames, so everything
 * else (fps, quality, size) is applied live to the running loop.
 */
async function ensureStream(id) {
  let reqW = cfg.maxDimension;
  let reqH = cfg.maxDimension;
  if (cfg.displayW > 0 && cfg.displayH > 0) {
    const s = Math.min(1, cfg.maxDimension / Math.max(cfg.displayW, cfg.displayH));
    reqW = Math.max(2, Math.round(cfg.displayW * s));
    reqH = Math.max(2, Math.round(cfg.displayH * s));
  }
  if (stream && id === sourceId && reqW === acquiredReqW && reqH === acquiredReqH) return true;
  stopStream();
  sourceId = id;
  // Chromium CENTER-CROPS the desktop to the requested maxWidth x maxHeight
  // box instead of scaling it into it, so the box must have the DISPLAY's
  // aspect ratio or the left/right (and top/bottom) edges are sliced off -
  // a square maxDimension x maxDimension box cropped a 16:9 screen to a
  // centre square. The downscale to maxDimension's long side happens in
  // computeTarget() below, where the full frame is still intact.
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      audio: false,
      video: {
        mandatory: {
          chromeMediaSource: 'desktop',
          chromeMediaSourceId: id,
          maxWidth: reqW,
          maxHeight: reqH,
          maxFrameRate: SOURCE_FPS_CEILING
        }
      }
    });
  } catch (e) {
    sourceId = null;
    captureApi.fail(`Desktop capture failed: ${(e && e.message) || e}`);
    return false;
  }
  acquiredReqW = reqW;
  acquiredReqH = reqH;
  video.srcObject = stream;
  try {
    await video.play();
  } catch (_) {
    // Only used as a fallback source; a rejection here is not fatal.
  }
  startReader();
  return true;
}

/** Read the track directly, keeping only the newest frame. */
function startReader() {
  if (!stream || typeof MediaStreamTrackProcessor === 'undefined') return false;
  const track = stream.getVideoTracks()[0];
  if (!track) return false;
  try {
    const processor = new MediaStreamTrackProcessor({ track });
    reader = processor.readable.getReader();
  } catch (_) {
    reader = null;
    return false;
  }
  (async () => {
    while (running && reader) {
      let result;
      try {
        result = await reader.read();
      } catch (_) {
        break;
      }
      if (result.done) break;
      if (!running) {
        try { result.value.close(); } catch (_) {}
        break;
      }
      // Newest wins: an older undrawn frame is worth nothing once a newer one
      // exists, and holding it is exactly how a stream drifts into the past.
      if (pendingFrame) {
        try { pendingFrame.close(); } catch (_) {}
      }
      pendingFrame = result.value;
      pumpEncode();
    }
  })();
  return true;
}

/** Encode whatever the newest frame is, one at a time. */
async function pumpEncode() {
  if (encoding) return;
  encoding = true;
  try {
    while (running && pendingFrame) {
      // Frame-rate cap: track frames can arrive at 60 while the stream is set
      // to 30. Dropping (not queueing) keeps the delivered frame the freshest.
      const minGap = Math.round(1000 / Math.max(1, cfg.fps)) - 4;
      if (lastEncodeAt && Date.now() - lastEncodeAt < minGap) {
        try { pendingFrame.close(); } catch (_) {}
        pendingFrame = null;
        break;
      }
      const frame = pendingFrame;
      pendingFrame = null;
      await drawAndSend(frame);
    }
  } finally {
    encoding = false;
  }
}

/**
 * Resize the encode surface to the configured long-side cap, times the
 * adaptive scale below.
 */
function computeTarget(w, h) {
  if (!w || !h) return false;
  const limit = Math.max(160, Math.round(cfg.maxDimension * dynScale));
  const scale = Math.min(1, limit / Math.max(w, h));
  const tw = Math.max(2, Math.round((w * scale) / 2) * 2);
  const th = Math.max(2, Math.round((h * scale) / 2) * 2);
  if (tw !== targetW || th !== targetH) {
    targetW = tw;
    targetH = th;
    canvas.width = tw;
    canvas.height = th;
  }
  return true;
}

/**
 * Adaptive resolution: JPEG encoding of a 720p frame costs ~25-30ms on a
 * laptop CPU, which is a hard ceiling of ~33fps no matter what fps the user
 * picks. Rather than silently falling short of a requested 60, the encoder
 * trades pixels for rate - it shrinks the frame while encoding runs long and
 * grows it back once there is headroom. The frame keeps moving at the rate the
 * user asked for, and the phone is told the real size in each frame header.
 */
function adaptScale(encodeTimeMs) {
  const budget = 1000 / Math.max(1, cfg.fps);
  if (encodeTimeMs > budget * 0.9) dynScale = Math.max(0.6, dynScale * 0.86);
  else if (encodeTimeMs < budget * 0.6) dynScale = Math.min(1, dynScale * 1.02);
}

/** Draw one source (VideoFrame or the video element) and ship it as JPEG. */
async function drawAndSend(source) {
  const w = source.displayWidth || source.videoWidth;
  const h = source.displayHeight || source.videoHeight;
  if (!running || !computeTarget(w, h)) {
    if (source && source.close) source.close();
    return;
  }
  const startedAt = Date.now();
  lastEncodeAt = startedAt;
  try {
    ctx.drawImage(source, 0, 0, targetW, targetH);
  } catch (_) {
    if (source && source.close) source.close();
    return;
  }
  if (source && source.close) source.close();
  try {
    const blob = await new Promise((res) =>
      canvas.toBlob(res, 'image/jpeg', Math.max(0.2, Math.min(1, cfg.quality / 100)))
    );
    if (blob && running) {
      const bytes = new Uint8Array(await blob.arrayBuffer());
      seq = (seq + 1) >>> 0;
      captureApi.sendFrame(bytes, targetW, targetH, seq);
      frameSent = true;
    }
  } catch (_) {
    // One bad frame must never kill the loop.
  } finally {
    encodeMs = Date.now() - startedAt;
    adaptScale(encodeMs);
  }
}

/* --------------------------- fallback loop --------------------------- */
/* Only used when MediaStreamTrackProcessor is unavailable. It is compositor
   bound (~20fps) but keeps old Chromium working instead of streaming nothing. */

function scheduleTimer(delayMs) {
  if (!running || reader) return;
  clearTimeout(timer);
  timer = setTimeout(async () => {
    if (!running || reader) return;
    if (video.readyState >= 2 && computeTarget(video.videoWidth, video.videoHeight)) {
      const t = video.currentTime;
      if (!frameSent || t !== lastCapturedTime) {
        lastCapturedTime = t;
        await drawAndSend(video);
      }
    }
    const budget = Math.round(1000 / Math.max(1, cfg.fps));
    scheduleTimer(budget - (encodeMs || 0));
  }, Math.max(0, delayMs | 0));
}

function stopStream() {
  if (reader) {
    try { reader.cancel(); } catch (_) {}
    reader = null;
  }
  if (pendingFrame) {
    try { pendingFrame.close(); } catch (_) {}
    pendingFrame = null;
  }
  if (stream) {
    for (const t of stream.getTracks()) t.stop();
    stream = null;
  }
  video.srcObject = null;
}

function begin(next) {
  if (next.fps) cfg.fps = Math.max(1, Math.min(60, next.fps | 0));
  if (next.quality) cfg.quality = Math.max(20, Math.min(90, next.quality | 0));
  if (next.maxDimension) cfg.maxDimension = Math.max(160, Math.min(1920, next.maxDimension | 0));
  if (next.displayW > 0 && next.displayH > 0) {
    cfg.displayW = next.displayW | 0;
    cfg.displayH = next.displayH | 0;
  }

  running = true;
  dynScale = 1;
  const arm = async () => {
    // ensureStream() itself decides whether re-acquisition is needed (source
    // id change, or a constraint box that no longer matches the display
    // aspect), so it is always safe to call here.
    if (next.sourceId) {
      const ok = await ensureStream(next.sourceId);
      if (!ok) {
        running = false;
        return;
      }
    }
    // If the direct track reader could not start, fall back to the
    // compositor-bound loop so the mirror still works.
    if (!reader) scheduleTimer(0);
  };
  arm();
}

function halt() {
  running = false;
  clearTimeout(timer);
  timer = null;
  stopStream();
  sourceId = null;
  acquiredReqW = acquiredReqH = 0;
  targetW = targetH = 0;
  lastCapturedTime = -1;
  lastEncodeAt = 0;
  frameSent = false;
  encodeMs = 0;
}

captureApi.onConfigure(begin);
captureApi.onStop(halt);
