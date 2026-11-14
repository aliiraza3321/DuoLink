/**
 * VORTEX - Remote PC Screen streaming (phone views the Windows desktop).
 *
 * Capture lives in an offscreen window (see capture/renderer.js): the desktop
 * is acquired once as a MediaStream and re-encoded from a canvas at the
 * requested rate - the same shape Chrome Remote Desktop and every browser
 * "share screen" flow use. This module is the sink side: it resolves the
 * source id once per stream, pushes configuration into that window, and
 * forwards each encoded frame to the requesting phone over the existing
 * authenticated WSS session.
 *
 * Input (absolute mouse, relative move, keys, scroll) reuses the existing
 * PC_MOUSE_* / PC_KEY_INPUT / PC_KEY_COMBOS handlers, so the remote-control
 * permission gate and kill switch still apply unchanged.
 *
 * Frames are dropped (not queued) while the socket is still buffering the
 * previous one, so a slow link degrades to a lower effective fps instead of
 * accumulating latency.
 */

const { BinaryFrame, encodeBinaryFrame } = require('./protocol');

const MIN_DIM = 160;
// 1920 was plenty for a JPEG stream but visibly soft on a 1440p desktop: the
// capture window keeps the desktop's own aspect and the phone decoder shows
// the full picture, so the cap is what limited sharpness.
// 1920 (not 2560): on a congested link every extra pixel is latency, and a
// 1440p capture made the phone's PC-screen view stutter far more than the
// sharpness was worth.
const MAX_DIM = 1920;
const MAX_FPS = 60;
const SOCKET_BACKPRESSURE_BYTES = 256 * 1024;
// Give up on an abandoned stream after this long with no live socket so the
// offscreen capturer (and the phone's camera privacy LED, when mirrored back)
// is released instead of lingering forever.
const NO_SOCKET_GIVEUP_MS = 30000;

class RemotePcStreamer {
  /**
   * @param {object} deps
   * @param {Function} deps.getSocket - () => WebSocket | null (the live phone session)
   * @param {object} deps.desktopCapturer - Electron desktopCapturer (source id only)
   * @param {object} deps.screen - Electron screen module
   * @param {{configure: Function, stop: Function}} deps.capture - offscreen capture window adapter
   */
  constructor({ getSocket, desktopCapturer, screen, capture }) {
    this.getSocket = getSocket;
    this.desktopCapturer = desktopCapturer;
    this.screen = screen;
    this.capture = capture || null;

    this.streaming = false;
    this.seq = 0;
    this._sourceId = null;
    this._configPushed = false;
    this._noSocketSince = 0;

    // Default stream settings (phone can override per session)
    this.fps = 30;
    this.quality = 55;
    this.maxDimension = 1280;

    // A monitor added/removed or a resolution change makes the cached source id
    // stale; re-resolve it so a long-running mirror follows the display.
    const invalidate = () => {
      if (!this.streaming) return;
      this._sourceId = null;
      this._configPushed = false;
      this._prepareSource();
    };
    try {
      screen.on('display-metrics-changed', invalidate);
      screen.on('display-added', invalidate);
      screen.on('display-removed', invalidate);
    } catch (_) {
      // Non-fatal: we simply keep using the source id we already resolved.
    }
  }

  /** True while a stream is active (used for the visible "streaming" indicator). */
  get active() {
    return this.streaming;
  }

  /**
   * Starts (or reconfigures) the stream to the current phone session.
   * Returns synchronously because the WSS handler replies on this tick; the
   * source id resolves in the background and frames start once it lands.
   * @param {{fps?: number, quality?: number, maxDimension?: number}} settings
   * @returns {{ok: boolean, error?: string}}
   */
  start(settings = {}) {
    this.fps = clampInt(settings.fps, 1, MAX_FPS, this.fps);
    this.quality = clampInt(settings.quality, 20, 90, this.quality);
    this.maxDimension = clampInt(settings.maxDimension, MIN_DIM, MAX_DIM, this.maxDimension);

    if (this.streaming) {
      // Already running: push the new knobs without tearing the stream down.
      this._pushConfig();
      return { ok: true };
    }

    this.streaming = true;
    this._noSocketSince = 0;
    this._sourceId = null;
    this._configPushed = false;
    this._prepareSource();
    console.log(
      `[RemotePC] Stream started: ${this.fps}fps q${this.quality} max${this.maxDimension}px`
    );
    return { ok: true };
  }

  /**
   * Resolve the primary display's source id ONCE per stream.
   *
   * The old implementation called getSources() on every frame; each call
   * re-enumerates every screen/window and re-snapshots thumbnails, which is
   * what capped the mirror in the low teens. Doing it once here is the whole
   * point of the offscreen capture window.
   */
  async _prepareSource() {
    if (!this.streaming || this._sourceId || this._configPushed) return;
    try {
      const primary = this.screen.getPrimaryDisplay();
      const sources = await this.desktopCapturer.getSources({
        types: ['screen'],
        thumbnailSize: { width: 0, height: 0 },
        fetchWindowIcons: false
      });
      if (!this.streaming) return;
      const primaryId = String(primary.id);
      const source =
        sources.find((s) => s.display_id === primaryId) ||
        sources.find((s) => /^screen:/.test(s.id)) ||
        sources[0];
      if (!source) {
        console.warn('[RemotePC] No screen source available');
        this.stop();
        return;
      }
      this._sourceId = source.id;
      this._pushConfig();
    } catch (err) {
      console.error('[RemotePC] Could not resolve screen source:', err.message);
      this.stop();
    }
  }

  /** Forward the current knobs to the capture window (idempotent). */
  _pushConfig() {
    if (!this.streaming || !this.capture || !this._sourceId || this._configPushed) return;
    this._configPushed = true;
    // Display aspect has to ride along with the constraint box: Chromium
    // CENTER-CROPS the desktop to the requested maxWidth x maxHeight instead
    // of scaling it (GitHub Open-LLM-VTuber-Web#23), so a square box like
    // 960x960 slices the left/right edges off a 16:9 screen. Matching the
    // box to the display's own aspect makes the crop a no-op.
    let displayW = 0;
    let displayH = 0;
    try {
      const d = this.screen.getPrimaryDisplay();
      const s = (d.scaleFactor || 1);
      displayW = Math.round(d.size.width * s);
      displayH = Math.round(d.size.height * s);
    } catch (_) {
      // Non-fatal: the capture falls back to a square box (previous behavior).
    }
    this.capture.configure({
      sourceId: this._sourceId,
      fps: this.fps,
      quality: this.quality,
      maxDimension: this.maxDimension,
      displayW,
      displayH
    });
  }

  stop() {
    if (!this.streaming && !this._sourceId) return;
    this.streaming = false;
    this._sourceId = null;
    this._configPushed = false;
    this._noSocketSince = 0;
    if (this.capture) this.capture.stop();
    console.log('[RemotePC] Stream stopped.');
  }

  /**
   * Sink for frames produced by the capture window. Called by the main
   * process on every encoded frame.
   * @param {Uint8Array|Buffer} jpeg - encoded frame bytes
   * @param {number} width
   * @param {number} height
   */
  pushFrame(jpeg, width, height) {
    if (!this.streaming || !jpeg || !jpeg.length) return;
    const ws = this.getSocket ? this.getSocket() : null;
    if (!ws || ws.readyState !== 1 /* OPEN */) {
      // A momentary socket gap must NOT kill the stream permanently: the phone
      // reconnects on its own and re-requests the stream, and until that lands
      // we keep the capturer warm and just drop frames, so a 1-2s Wi-Fi blip no
      // longer blanks the video for good. Stop only once the gap is long enough
      // that nobody is coming back for it.
      if (!this._noSocketSince) this._noSocketSince = Date.now();
      else if (Date.now() - this._noSocketSince > NO_SOCKET_GIVEUP_MS) this.stop();
      return;
    }
    this._noSocketSince = 0;
    // Backpressure: if the previous frame is still buffered, skip this one.
    // This is the ONLY thing keeping latency bounded on a slow link.
    if (ws.bufferedAmount > SOCKET_BACKPRESSURE_BYTES) return;

    this.seq = (this.seq + 1) >>> 0;
    ws.send(encodeBinaryFrame(BinaryFrame.PC_SCREEN, this.seq, width | 0, height | 0, jpeg));
  }
}

function clampInt(value, min, max, fallback) {
  const n = parseInt(value, 10);
  if (isNaN(n)) return fallback;
  return Math.max(min, Math.min(max, n));
}

module.exports = { RemotePcStreamer, clampInt };
