/**
 * VORTEX - Phone Webcam hub (phone camera -> PC).
 *
 * The phone streams JPEG frames over the authenticated WSS session
 * (WEBCAM_FRAME). This module keeps the latest frame and re-serves it as an
 * MJPEG stream on the existing HTTPS server:
 *
 *     https://<pc-ip>:<3000>/webcam.mjpeg?token=<short-lived token>
 *
 * MJPEG-over-HTTP is natively consumable by OBS Studio (Media Source),
 * browsers (<img src>), ffmpeg, and most IP-camera viewers. To use the phone
 * as a system-wide webcam in Zoom / Teams / Meet, add an OBS Browser Source
 * pointing at the URL above and enable OBS Virtual Camera - see README.
 *
 * Security: the endpoint is only reachable from private (RFC1918) addresses
 * and requires a per-session token that is only handed to the authenticated
 * phone session and the local desktop UI. Frames are never written to disk.
 */

const crypto = require('crypto');
const { isPrivateIp } = require('./network');
const { MessageType, createMessage } = require('./protocol');

const BOUNDARY = 'vortexframe';
const MAX_SUBSCRIBERS = 4;

class WebcamHub {
  constructor() {
    this.active = false;
    this.camera = 'rear';
    this.latestFrame = null; // { buffer, width, height, ts }
    this.seq = 0;
    this.subscribers = new Set();
    this.streamToken = null;
    this.tokenIssuedAt = 0;
  }

  /** Issue a short-lived token that authorizes MJPEG access. */
  issueToken() {
    this.streamToken = crypto.randomBytes(16).toString('hex');
    this.tokenIssuedAt = Date.now();
    return this.streamToken;
  }

  /** True if the token is current (tokens last 12h, plenty for one work session). */
  _tokenValid(token) {
    // ?token=a&token=b arrives as an array; Buffer.from(array) would throw.
    if (typeof token !== 'string' || !token || !this.streamToken) return false;
    if (token.length !== this.streamToken.length) return false;
    const ok = crypto.timingSafeEqual(Buffer.from(token), Buffer.from(this.streamToken));
    return ok && (Date.now() - this.tokenIssuedAt) < 12 * 3600 * 1000;
  }

  /**
   * Called for every WEBCAM_FRAME from the phone.
   * @param {object} payload - the frame envelope (width/height/seq)
   * @param {Buffer} [preDecoded] - JPEG bytes already decoded by the caller, so
   *   the frame is base64-decoded once per socket message instead of twice.
   */
  onFrame(payload, preDecoded) {
    let buffer = preDecoded;
    if (!buffer && payload && typeof payload.jpegBase64 === 'string') {
      try {
        buffer = Buffer.from(payload.jpegBase64, 'base64');
      } catch (_) {
        return;
      }
    }
    if (!buffer || !buffer.length) return;
    this.latestFrame = {
      buffer,
      width: payload.width | 0,
      height: payload.height | 0,
      ts: Date.now()
    };
    this.seq = payload.seq || (this.seq + 1);
    this._fanout();
  }

  onStatus(payload) {
    this.active = !!(payload && payload.active);
    if (payload && payload.camera) this.camera = payload.camera;
    if (!this.active) {
      this.latestFrame = null;
    }
  }

  _fanout() {
    if (this.subscribers.size === 0 || !this.latestFrame) return;
    const frame = this._framePacket(this.latestFrame.buffer);
    for (const res of this.subscribers) {
      try {
        // Freshness over completeness: a viewer whose socket is still draining
        // the previous frame SKIPS this one instead of queueing it. Queueing
        // is exactly what made slow clients watch the past — each MJPEG frame
        // is a full picture, so dropping the stale one costs one frame of
        // motion and keeps latency pinned to the wire speed instead of
        // growing without bound (the lag users see with OBS/browsers).
        if (res.writableLength > 256 * 1024) continue;
        res.write(frame);
      } catch (_) {
        this.subscribers.delete(res);
        try { res.end(); } catch (_) {}
      }
    }
  }

  _framePacket(buffer) {
    return Buffer.concat([
      Buffer.from(
        `--${BOUNDARY}\r\n` +
        `Content-Type: image/jpeg\r\n` +
        `Content-Length: ${buffer.length}\r\n\r\n`
      ),
      buffer,
      Buffer.from('\r\n')
    ]);
  }

  /**
   * Express handler factory for GET /webcam.mjpeg
   * @param {Function} getPhoneSessionIp - () => string|null (for a friendly error when no phone)
   */
  createMjpegHandler() {
    return (req, res) => {
      const clientIp = (req.socket.remoteAddress || '').replace(/^::ffff:/, '');
      if (!isPrivateIp(clientIp)) {
        return res.status(403).json({ error: 'Forbidden: Private network only' });
      }
      if (!this._tokenValid(req.query && req.query.token)) {
        return res.status(401).json({ error: 'Invalid or missing stream token' });
      }
      if (this.subscribers.size >= MAX_SUBSCRIBERS) {
        return res.status(429).json({ error: 'Too many viewers' });
      }

      res.writeHead(200, {
        'Content-Type': `multipart/x-mixed-replace; boundary=${BOUNDARY}`,
        'Cache-Control': 'no-store, no-cache, must-revalidate, private',
        'Pragma': 'no-cache',
        'X-Content-Type-Options': 'nosniff',
        'Connection': 'keep-alive'
      });

      // Send the last frame immediately so the viewer isn't blank
      if (this.latestFrame) {
        try { res.write(this._framePacket(this.latestFrame.buffer)); } catch (_) {}
      } else {
        // No frames yet: keep alive with a comment so clients don't time out
        try { res.write(`--${BOUNDARY}\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\nok\r\n`); } catch (_) {}
      }

      this.subscribers.add(res);
      const cleanup = () => {
        this.subscribers.delete(res);
        try { res.end(); } catch (_) {}
      };
      res.on('close', cleanup);
      res.on('error', cleanup);
    };
  }

  stop() {
    for (const res of this.subscribers) {
      try { res.end(); } catch (_) {}
    }
    this.subscribers.clear();
    this.active = false;
    this.latestFrame = null;
  }
}

module.exports = { WebcamHub };
