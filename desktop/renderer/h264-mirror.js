/**
 * H.264 phone-mirror decoder (phone -> PC).
 *
 * The phone encodes its display with the hardware H.264 encoder and sends raw
 * access units; this module decodes them with WebCodecs and paints the result
 * on a canvas. That is what makes native resolution at 60 fps possible: the
 * encoded stream is ~9 Mbit/s where the old per-frame JPEG was ~90 Mbit/s.
 *
 * Both the in-app pane and the pop-out window use this; each owns its decoder.
 */
(function (global) {
  'use strict';

  /** Drop a leading Annex-B start code, keeping the NAL header byte. */
  function stripStartCode(buf) {
    if (buf.length >= 3 && buf[0] === 0 && buf[1] === 0 && buf[2] === 1) return buf.subarray(3);
    if (buf.length >= 4 && buf[0] === 0 && buf[1] === 0 && buf[2] === 0 && buf[3] === 1) return buf.subarray(4);
    return buf;
  }

  /** Slice one NAL (with its header byte) out of a concatenated buffer list. */
  function splitNals(buf) {
    const out = [];
    let start = -1;
    for (let i = 0; i + 2 < buf.length; i++) {
      if (buf[i] === 0 && buf[i + 1] === 0 && buf[i + 2] === 1) {
        if (start >= 0) out.push(buf.subarray(start, i));
        start = i + 3;
        i += 2;
      }
    }
    if (start >= 0) out.push(buf.subarray(start));
    return out;
  }

  /**
   * Chromium's AVC decoder expects AVCC sample data (each NAL prefixed with its
   * 4-byte length) once a `description` (avcC) is configured - not Annex-B
   * start codes, which is what MediaCodec emits. Feeding start codes makes the
   * decoder fail with a bare "Decoder error.".
   */
  function annexBToAvcc(bytes) {
    const nals = splitNals(bytes);
    if (!nals.length) return null;
    let total = 0;
    for (const n of nals) total += 4 + n.length;
    const out = new Uint8Array(total);
    let o = 0;
    for (const n of nals) {
      out[o++] = (n.length >>> 24) & 0xff;
      out[o++] = (n.length >>> 16) & 0xff;
      out[o++] = (n.length >>> 8) & 0xff;
      out[o++] = n.length & 0xff;
      out.set(n, o);
      o += n.length;
    }
    return out;
  }

  /**
   * Build the avcC box + codec string from the SPS/PPS the phone sent.
   * The phone may hand over one combined config buffer (SPS followed by PPS
   * when MediaCodec reports it as a buffer rather than as format keys), so
   * both shapes are accepted.
   */
  function parseConfig(sps, pps) {
    if (!sps || sps.length < 4) return null;
    let spsNal = stripStartCode(sps);
    let ppsNal = pps && pps.length ? stripStartCode(pps) : null;
    if (!ppsNal) {
      const parts = splitNals(spsNal);
      if (parts.length >= 2) {
        spsNal = parts[0];
        ppsNal = parts[1];
      }
    }
    if (!ppsNal || spsNal.length < 4) return null;
    // NAL header byte, then profile_idc / constraint flags / level_idc.
    const profile = spsNal[1];
    const compat = spsNal[2];
    const level = spsNal[3];
    const hex = (n) => n.toString(16).padStart(2, '0').toUpperCase();
    const codec = `avc1.${hex(profile)}${hex(compat)}${hex(level)}`;

    const out = [1, profile, compat, level, 0xff, 0xe1,
      (spsNal.length >> 8) & 0xff, spsNal.length & 0xff];
    const description = new Uint8Array(out.length + spsNal.length + 3 + ppsNal.length);
    description.set(out, 0);
    let o = out.length;
    description.set(spsNal, o);
    o += spsNal.length;
    description[o++] = 1;
    description[o++] = (ppsNal.length >> 8) & 0xff;
    description[o++] = ppsNal.length & 0xff;
    description.set(ppsNal, o);
    return { codec, description };
  }

  /** True when the access unit carries an IDR slice (a decodable keyframe). */
  function isKeyframe(bytes) {
    for (let i = 0; i + 3 < bytes.length; i++) {
      let start = 0;
      if (bytes[i] === 0 && bytes[i + 1] === 0 && bytes[i + 2] === 1) start = i + 3;
      else if (i + 3 < bytes.length && bytes[i] === 0 && bytes[i + 1] === 0 &&
               bytes[i + 2] === 0 && bytes[i + 3] === 1) start = i + 4;
      if (!start) continue;
      const type = bytes[start] & 0x1f;
      if (type === 5) return true;
      if (type === 1) return false;
    }
    return false;
  }

  function asBytes(value) {
    if (!value) return null;
    if (value instanceof Uint8Array) return value;
    if (value instanceof ArrayBuffer) return new Uint8Array(value);
    if (ArrayBuffer.isView(value)) return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
    if (value.type === 'Buffer' && Array.isArray(value.data)) return Uint8Array.from(value.data);
    return null;
  }

  function createH264Mirror(options) {
    const canvas = options.canvas;
    const ctx = canvas.getContext('2d');
    const onFrame = options.onFrame || function () {};
    const shouldDraw = options.shouldDraw || function () { return true; };

    let decoder = null;
    let codec = 'avc1.42E01F';
    let description = null;
    let needKey = true;
    let timestamp = 0;
    let disposed = false;

    function closeDecoder() {
      if (!decoder) return;
      try { decoder.close(); } catch (_) { /* already closed */ }
      decoder = null;
      needKey = true;
    }

    function ensureDecoder() {
      if (decoder || !description || disposed) return decoder;
      if (typeof VideoDecoder === 'undefined') return null;
      const d = new VideoDecoder({
        output(frame) {
          try {
            if (shouldDraw()) {
              const w = frame.displayWidth;
              const h = frame.displayHeight;
              if (canvas.width !== w) canvas.width = w;
              if (canvas.height !== h) canvas.height = h;
              try { ctx.drawImage(frame, 0, 0, w, h); } catch (_) { /* raced a resize */ }
            }
            onFrame({ width: frame.displayWidth, height: frame.displayHeight });
          } finally {
            frame.close();
          }
        },
        error(err) {
          // A decode error poisons the chain: start again at the next keyframe.
          console.warn('[H264] decoder error', err && err.message);
          closeDecoder();
        }
      });
      try {
        d.configure({ codec, description, optimizeForLatency: true });
        decoder = d;
      } catch (err) {
        console.warn('[H264] configure failed', err && err.message);
        try { d.close(); } catch (_) {}
        return null;
      }
      return decoder;
    }

    return {
      /** SPS/PPS from the phone: (re)builds the decoder. */
      configure(cfg) {
        const parsed = parseConfig(asBytes(cfg && cfg.sps), asBytes(cfg && cfg.pps) || new Uint8Array(0));
        if (!parsed) return false;
        closeDecoder();
        codec = parsed.codec;
        description = parsed.description;
        return true;
      },
      /** One encoded access unit (Annex-B). */
      push(nalBytes) {
        const bytes = asBytes(nalBytes);
        if (!bytes || !bytes.length || disposed) return;
        const key = isKeyframe(bytes);
        if (needKey && !key) return;             // wait for an IDR we can start from
        const d = ensureDecoder();
        if (!d) return;
        // Decode backlog means the picture on screen is the past. Four queued
        // frames is already ~70 ms of extra delay at 60 fps, so anything deeper
        // is dropped rather than played late: a skipped frame costs one flicker,
        // a full queue costs seconds of lag.
        if (d.decodeQueueSize > 2) return;
        const sample = annexBToAvcc(bytes);
        if (!sample) return;
        const chunk = new EncodedVideoChunk({
          type: key ? 'key' : 'delta',
          timestamp,
          data: sample
        });
        timestamp += 16666;
        try {
          d.decode(chunk);
          needKey = false;
        } catch (err) {
          console.warn('[H264] decode failed', err && err.message);
          closeDecoder();
          needKey = true;
        }
      },
      /** New stream (reconnect): drop the decoder, wait for a fresh config. */
      reset() {
        closeDecoder();
        description = null;
        timestamp = 0;
      },
      dispose() {
        disposed = true;
        closeDecoder();
      },
      get codecString() {
        return codec;
      },
      get supported() {
        return typeof VideoDecoder !== 'undefined';
      }
    };
  }

  global.createH264Mirror = createH264Mirror;
})(window);
