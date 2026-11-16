/**
 * PhonePC Pair - HTTPS & WSS Server
 * Runs HTTPS/WSS in the main process, validates IP, routes protocol messages.
 */

const https = require('https');
const express = require('express');
const { WebSocketServer, WebSocket } = require('ws');
const { isPrivateIp } = require('./network');
const {
  MessageType,
  BinaryFrame,
  decodeBinaryFrame,
  createMessage,
  parseAndValidateMessage
} = require('./protocol');
class WssServer {
  /**
   * @param {object} options
   * @param {string} options.cert
   * @param {string} options.key
   * @param {number} options.port
   * @param {object} options.pairingManager
   * @param {object} options.fileManager
   * @param {object} options.controlManager
   * @param {Function} options.onRendererEvent
   */
  constructor({ cert, key, port = 3000, pairingManager, fileManager, controlManager, jarvis, onRendererEvent, featureHub, remotePc, webcamHub, pcfs, sendFileToPhone }) {
    this.cert = cert;
    this.key = key;
    this.port = port;
    this.pairingManager = pairingManager;
    this.fileManager = fileManager;
    this.controlManager = controlManager;
    this.jarvis = jarvis;
    this.featureHub = featureHub || null;
    this.remotePc = remotePc || null;
    this.webcamHub = webcamHub || null;
    this.pcfs = pcfs || null;
    this.sendFileToPhone = sendFileToPhone || null;
    this.onRendererEvent = onRendererEvent || (() => {});

    // The desktop preview normally reads the local MJPEG endpoint, which Chromium
    // decodes off the UI thread. Mirroring every frame over IPC as well doubles
    // the work for a picture the renderer already has, so it stays off unless
    // the renderer asks for it (MJPEG unavailable -> legacy frame pump).
    this.previewFramesEnabled = false;
    this._webcamPrevAt = 0;

    this.app = express();
    this.server = null;
    this.wss = null;

    this._setupExpress();
  }

  /**
   * Current session state for a (re)loaded renderer.
   *
   * 'session-status-change' only fires on transitions, so a page load that
   * races the phone's reconnect (or a DevTools reload) used to leave the
   * status containers on their placeholders forever — "phone status container
   * shows no details". Same payload as the event, pulled on demand instead.
   */
  /**
   * Turn the per-frame IPC mirror for the webcam preview on or off.
   * Enabled only when the renderer's MJPEG <img> cannot connect.
   */
  setWebcamPreviewFrames(enabled) {
    this.previewFramesEnabled = !!enabled;
    this._webcamPrevAt = 0;
  }

  currentSessionStatus() {
    try {
      for (const s of this.pairingManager.activeSessions.values()) {
        // readyState 1 = OPEN: only sessions with a LIVE socket count.
        if (s && s.socket && s.socket.readyState === 1) {
          return {
            sessionId: s.sessionId,
            status: 'connected',
            deviceName: s.deviceName,
            modelCode: s.modelCode || ''
          };
        }
      }
    } catch (_) { /* fall through */ }
    return { status: 'disconnected' };
  }

  _setupExpress() {
    this.app.use(express.json());

    // Health check endpoint for LAN connectivity testing
    this.app.get('/health', (req, res) => {
      const clientIp = (req.socket.remoteAddress || '').replace(/^::ffff:/, '');
      if (!isPrivateIp(clientIp)) {
        return res.status(403).json({ error: 'Forbidden: Private network only' });
      }
      res.json({
        status: 'ok',
        server: 'PhonePC-Pair',
        time: Date.now(),
        hasPendingPairing: this.pairingManager.pendingPairings.size > 0
      });
    });

    // Phone webcam MJPEG stream (private network + session token required)
    if (this.webcamHub) {
      this.app.get('/webcam.mjpeg', this.webcamHub.createMjpegHandler());
    }
  }

  /**
   * Starts the HTTPS and WSS server on port 3000.
   */
  start() {
    return new Promise((resolve, reject) => {
      this.server = https.createServer({
        key: this.key,
        cert: this.cert
      }, this.app);

      this.wss = new WebSocketServer({
        server: this.server,
        maxPayload: 15 * 1024 * 1024, // 15MB max message payload
        // JPEG is already compressed: running permessage-deflate over it burns
        // CPU on both ends, adds a round-trip of latency, and shrinks nothing.
        // Video frames need throughput, so compression is off.
        perMessageDeflate: false
      });

      this.wss.on('connection', (ws, req) => {
        const rawIp = req.socket.remoteAddress || '';
        const clientIp = rawIp.replace(/^::ffff:/, '');

        // Security check: Private network only
        if (!isPrivateIp(clientIp)) {
          console.warn(`[WSS] Rejected connection from non-private IP: ${clientIp}`);
          ws.close(1008, 'Private network only');
          return;
        }

        console.log(`[WSS] Inbound connection from ${clientIp}`);
        this._setupSocket(ws, clientIp);
      });

      this.server.listen(this.port, '0.0.0.0', () => {
        console.log(`[WSS] HTTPS/WSS Server running on 0.0.0.0:${this.port}`);
        resolve();
      });

      this.server.on('error', (err) => {
        console.error('[WSS] Server error:', err);
        reject(err);
      });
    });
  }

  _setupSocket(ws, clientIp) {
    ws.isAlive = true;
    ws.isPendingApproval = false;
    ws.associatedSessionId = null;

    // Heartbeat check interval (30 seconds)
    const heartbeatInterval = setInterval(() => {
      // Only reclaim a socket that NEVER authenticated (a stray/incomplete
      // socket). An authenticated phone that goes quiet must not be yanked:
      // some devices pause network I/O while rendering the live view, and
      // terminating a live session here forced a reconnect/re-stream loop
      // (code 1006). Authenticated phones manage their own reconnect.
      if (!ws.isAlive && !ws.isPendingApproval && !ws.associatedSessionId) {
        clearInterval(heartbeatInterval);
        return ws.terminate();
      }
      ws.isAlive = false;
      try {
        // Send JSON heartbeat ping
        ws.send(JSON.stringify(createMessage(MessageType.HEARTBEAT_PING)));
        // Also send low-level RFC 6455 ping (timestamps the round trip)
        ws._pingSentAt = Date.now();
        ws.ping();
      } catch (_) {}
    }, 25000);

    // Standard WebSocket ping/pong — drives the Link bar latency readout.
    ws.on('pong', () => {
      ws.isAlive = true;
      if (ws._pingSentAt) {
        const rttMs = Date.now() - ws._pingSentAt;
        ws._pingSentAt = 0;
        if (ws.associatedSessionId) this.onRendererEvent('link-latency', { rttMs });
      }
    });

    ws.on('ping', () => {
      ws.isAlive = true;
      try { ws.pong(); } catch (_) {}
    });

    ws.on('message', (raw, isBinary) => {
      // Any incoming data confirms the socket is active
      ws.isAlive = true;

      // Binary frames are the video channel (see protocol.js BinaryFrame).
      // They are routed before the JSON validator so a frame never pays for a
      // JSON parse, and they don't require an envelope at all.
      if (isBinary) {
        this._handleBinaryFrame(ws, raw);
        return;
      }

      const { valid, error, message } = parseAndValidateMessage(raw);
      if (!valid) {
        console.warn(`[WSS] Invalid message: ${error}`);
        return;
      }

      this._handleMessage(ws, message, clientIp);
    });

    ws.on('close', (code, reason) => {
      clearInterval(heartbeatInterval);
      console.log(`[WSS] Client disconnected: ${code} - ${reason || 'No reason'}`);

      // Stop per-session streams owned by this socket so capture stops when
      // the phone goes away (no orphaned desktopCapturer loop / webcam).
      if (this.remotePc && ws.ownsPcScreen) {
        this.remotePc.stop();
        ws.ownsPcScreen = false;
        if (this.featureHub) this.featureHub.pcScreenActive = false;
      }

      // Only report "disconnected" if this was the session's CURRENT socket.
      // (The pairing socket closes right after pairing, while the reconnect
      // socket is already live - that must not flip the UI to disconnected.)
      const liveSession = ws.associatedSessionId
        ? this.pairingManager.activeSessions.get(ws.associatedSessionId)
        : null;
      if (ws.associatedSessionId && (!liveSession || liveSession.socket === ws)) {
        this.onRendererEvent('session-status-change', {
          sessionId: ws.associatedSessionId,
          status: 'disconnected'
        });
        // The phone stops its camera on the way down, but that final status
        // rides the dying socket and can never arrive - clear webcam state
        // here so the desktop never shows a live badge for a dead stream.
        // (If frames are genuinely still flowing, the first one back flips
        // the UI to Live again.)
        if (this.webcamHub) this.webcamHub.onStatus({ active: false });
        if (this.featureHub) this.featureHub.webcamActive = false;
        this.onRendererEvent('webcam-status', { active: false, camera: '', error: '' });
        // Nothing the phone sent is still true — drop every cached phone
        // payload (battery, media, SMS, calls, notifications) so a renderer
        // that boots or re-hydrates now cannot show data from a phone that
        // is no longer connected.
        if (this.featureHub && typeof this.featureHub.clearPhoneData === 'function') {
          this.featureHub.clearPhoneData();
        }
      }
    });

    ws.on('error', (err) => {
      console.error('[WSS] Socket error:', err.message);
    });
  }

  /**
   * Message types that are allowed BEFORE a socket has completed pairing or
   * reconnect authentication. Everything else requires ws.associatedSessionId.
   */
  static PRE_AUTH_TYPES = new Set([
    MessageType.HEARTBEAT_PING,
    MessageType.HEARTBEAT_PONG,
    MessageType.PAIR_REQUEST,
    MessageType.RECONNECT_CHALLENGE_REQUEST,
    MessageType.RECONNECT_CHALLENGE_RESPONSE,
    MessageType.DISCONNECT
  ]);

  /**
   * Measure a real round trip over the phone's WebSocket: send an RFC 6455
   * ping to every authenticated session socket; each pong reports its rttMs
   * through the normal 'link-latency' renderer event.
   */
  measureLatency() {
    let sent = 0;
    for (const session of this.pairingManager.activeSessions.values()) {
      const ws = session.socket;
      if (ws && ws.readyState === 1 /* OPEN */ && ws.associatedSessionId) {
        try {
          ws._pingSentAt = Date.now();
          ws.ping();
          sent++;
        } catch (_) {}
      }
    }
    return sent > 0;
  }

  /**
   * Binary video channel (phone -> PC webcam frames).
   *
   * Frames arrive as raw bytes with a 9-byte header (protocol.js BinaryFrame)
   * instead of base64-in-JSON, so there is no parse cost and no 33% inflation.
   * The socket is authenticated before any frame is accepted, exactly like the
   * JSON path - a LAN host still cannot inject frames.
   */
  _handleBinaryFrame(ws, raw) {
    if (!ws.associatedSessionId) {
      console.warn('[WSS] Rejected unauthenticated binary frame');
      return;
    }
    const buf = Buffer.isBuffer(raw) ? raw : Buffer.from(raw);
    const frame = decodeBinaryFrame(buf);
    if (!frame || !frame.jpeg.length) return;

    if (frame.kind === BinaryFrame.WEBCAM) {
      // Keep exactly one frame: the MJPEG hub and the preview both retain
      // only the newest, so a slow consumer drops frames instead of queueing
      // them and later replaying a backlog (the "stopped, then fast-forward"
      // artifact). Timestamped at receipt, not at send.
      if (this.webcamHub) this.webcamHub.onFrame({
        width: frame.width,
        height: frame.height,
        seq: frame.seq
      }, frame.jpeg);
      if (this.featureHub) this.featureHub.webcamActive = true;

      // Preview mirror: same single-slot pattern as the hub. Off by default -
      // the preview pulls the same frames from /webcam.mjpeg instead.
      const now = Date.now();
      if (this.previewFramesEnabled && (!this._webcamPrevAt || now - this._webcamPrevAt > 33)) {
        this._webcamPrevAt = now;
        this.onRendererEvent('webcam-frame', {
          jpeg: frame.jpeg,
          width: frame.width,
          height: frame.height
        });
      }
      return;
    }
    if (frame.kind === BinaryFrame.PHONE_H264_CONFIG) {
      // 4-byte big-endian SPS length, then SPS, then PPS. Sent once per stream
      // (and again after a rotation), so the renderer can build its avcC.
      const payload = frame.jpeg;
      if (payload.length < 5) return;
      const spsLen = payload.readUInt32BE(0);
      if (spsLen <= 0 || 4 + spsLen > payload.length) return;
      this.onRendererEvent('phone-h264-config', {
        sps: payload.subarray(4, 4 + spsLen),
        pps: payload.subarray(4 + spsLen),
        width: frame.width,
        height: frame.height
      });
      return;
    }
    if (frame.kind === BinaryFrame.PHONE_H264) {
      // Encoded frames must NOT be dropped (P-frames depend on each other);
      // the phone keeps the bitrate low enough for the socket to keep up.
      this.onRendererEvent('phone-h264-frame', {
        nal: frame.jpeg,
        width: frame.width,
        height: frame.height
      });
      return;
    }
    if (frame.kind === BinaryFrame.PHONE_SCREEN) {
      // Phone screen mirror frames now ride the binary channel: raw JPEG,
      // no base64, no JSON parse. Single-slot freshness is already enforced
      // at the source (the phone drops frames when this socket backs up), so
      // the renderer always gets the newest frame rather than a stale burst.
      this.onRendererEvent('phone-screen-frame', {
        jpeg: frame.jpeg,
        width: frame.width,
        height: frame.height
      });
      return;
    }
    if (frame.kind === BinaryFrame.PHONE_AUDIO) {
      // Phone's own playback (PCM16LE) — feed the PC speaker path. One
      // listener: the main renderer plays it, so the pop-out window showing
      // the same stream never doubles the sound.
      this.onRendererEvent('phone-audio-chunk', {
        pcm: frame.jpeg,          // Buffer of interleaved PCM16LE samples
        sampleRate: frame.width || 44100,
        channels: frame.height || 1
      });
      return;
    }
    // Unknown binary kind: ignore rather than warn (forward compatibility).
  }

  _handleMessage(ws, message, clientIp) {
    const { type, id, payload } = message;

    // Security boundary: pairing, control, file transfer, clipboard, chat and
    // Jarvis commands are only accepted from a socket that has completed an
    // approved pairing or a verified HMAC reconnect. Without this gate any
    // host on the LAN could open a WSS connection (the server certificate is
    // self-signed, so the client can skip verification) and inject input.
    if (!WssServer.PRE_AUTH_TYPES.has(type) && !ws.associatedSessionId) {
      console.warn(`[WSS] Rejected unauthenticated ${type} from ${clientIp} (ws.associatedSessionId=null)`);
      try {
        ws.send(JSON.stringify(createMessage(MessageType.PAIR_RESULT, {
          success: false,
          error: 'Not authenticated. Complete pairing first.'
        })));
      } catch (_) {}
      return;
    }

    switch (type) {
      case MessageType.HEARTBEAT_PING:
        ws.isAlive = true;
        try {
          ws.send(JSON.stringify(createMessage(MessageType.HEARTBEAT_PONG)));
        } catch (_) {}
        break;

      case MessageType.HEARTBEAT_PONG:
        ws.isAlive = true;
        break;

      // --- PHASE 1: PAIRING FLOW ---
      case MessageType.PAIR_REQUEST: {
        const { token, deviceName, deviceId, modelCode } = payload;

        const isKnown = this.pairingManager.isKnownOrActiveToken(token, deviceId);
        if (!isKnown) {
          if (!this.pairingManager.checkRateLimit(clientIp, true)) {
            ws.send(JSON.stringify(createMessage(MessageType.PAIR_RESULT, {
              success: false,
              error: 'Rate limit exceeded. Please wait 1 minute or refresh the QR code on your PC.'
            })));
            return;
          }
        }

        const claimResult = this.pairingManager.claimToken(token, deviceName, deviceId, ws, modelCode);
        if (!claimResult.success) {
          ws.send(JSON.stringify(createMessage(MessageType.PAIR_RESULT, {
            success: false,
            error: claimResult.error
          })));
          return;
        }

        ws.isPendingApproval = true;

        // Send confirmation code to phone
        ws.send(JSON.stringify(createMessage(MessageType.PAIR_CONFIRM_CODE, {
          confirmationCode: claimResult.confirmationCode,
          pendingId: claimResult.pendingId
        })));

        // Notify Desktop UI
        this.onRendererEvent('pending-pairing-request', {
          pendingId: claimResult.pendingId,
          deviceName: deviceName || 'Android Phone',
          deviceId,
          confirmationCode: claimResult.confirmationCode,
          clientIp
        });
        break;
      }

      // --- RECONNECT FLOW (CHALLENGE / RESPONSE) ---
      case MessageType.RECONNECT_CHALLENGE_REQUEST: {
        const { deviceId, sessionId } = payload;
        console.log(`[WSS] <- RECONNECT_CHALLENGE_REQUEST from ${clientIp} deviceId=${deviceId}`);
        const nonce = this.pairingManager.createReconnectChallenge(deviceId, sessionId);

        if (!nonce) {
          console.warn(`[WSS] -> sending RECONNECT_RESULT success=false (no nonce)`);
          ws.send(JSON.stringify(createMessage(MessageType.RECONNECT_RESULT, {
            success: false,
            error: 'Unknown device or revoked session'
          })));
          return;
        }

        console.log(`[WSS] -> sending challenge nonce to phone`);
        ws.send(JSON.stringify(createMessage(MessageType.RECONNECT_CHALLENGE_RESPONSE, {
          challengeNonce: nonce
        })));
        break;
      }

      case MessageType.RECONNECT_CHALLENGE_RESPONSE: {
        const { deviceId, proof } = payload;
        console.log(`[WSS] <- RECONNECT_CHALLENGE_RESPONSE from ${clientIp} deviceId=${deviceId}`);
        const result = this.pairingManager.verifyReconnectProof(deviceId, proof, ws);

        if (!result.success) {
          console.warn(`[WSS] -> RECONNECT_RESULT success=false error=${result.error}`);
          ws.send(JSON.stringify(createMessage(MessageType.RECONNECT_RESULT, {
            success: false,
            error: result.error
          })));
          return;
        }

        ws.associatedSessionId = result.session.sessionId;
        ws.isPendingApproval = false;
        console.log(`[WSS] -> AUTH OK: ws.associatedSessionId=${ws.associatedSessionId}`);

        // Auto-diagnose the phone the instant it authenticates: ask which
        // runtime permissions / services are granted, so a "nothing works"
        // report can be traced to the exact missing permission instead of
        // being guessed at. Response is logged in the DIAG_RESP case below.
        //
        // MUST follow RECONNECT_RESULT: the phone only flips to CONNECTED
        // when that result lands, so a DIAG_REQ sent first came back with
        // linkState=CONNECTING and made a healthy link look half-connected.
        ws.send(JSON.stringify(createMessage(MessageType.RECONNECT_RESULT, {
          success: true,
          sessionId: result.session.sessionId,
          deviceName: result.session.deviceName,
          permissions: result.session.permissions
        })));

        try {
          ws.send(JSON.stringify(createMessage(MessageType.DIAG_REQ, {})));
          console.log(`[Diag] sent DIAG_REQ to ${result.session.deviceName || 'phone'}`);
        } catch (_) {}

        this.onRendererEvent('session-status-change', {
          sessionId: result.session.sessionId,
          status: 'connected',
          deviceName: result.session.deviceName,
          modelCode: result.session.modelCode || ''
        });
        break;
      }

      // --- PHASE 2: CHAT, FILES & CLIPBOARD ---
      case MessageType.CHAT_MESSAGE: {
        this.onRendererEvent('chat-message-received', {
          text: payload.text,
          from: payload.senderName || 'Phone',
          ts: message.ts
        });
        break;
      }

      case MessageType.CLIPBOARD_PUSH: {
        this.onRendererEvent('clipboard-push-received', {
          text: payload.text,
          ts: message.ts
        });
        break;
      }

      case MessageType.FILE_META: {
        const { transferId, filename, totalSize, sha256, destDir } = payload;

        const failMeta = (error) => {
          try {
            ws.send(JSON.stringify(createMessage(MessageType.FILE_ACK, {
              transferId,
              success: false,
              error
            })));
          } catch (_) {}
          this.onRendererEvent('file-transfer-error', { transferId, error });
        };

        // The phone reports unrecoverable media problems (size limit exceeded,
        // unreadable file) as an error meta with no bytes following it.
        // Ack the failure and surface it instead of creating an orphan .part file.
        if (payload.error) {
          failMeta(String(payload.error));
          break;
        }

        const begin = (meta) => {
          const startResult = this.fileManager.startIncomingTransfer(transferId, filename, totalSize, meta);
          try {
            ws.send(JSON.stringify(createMessage(MessageType.FILE_ACK, {
              transferId,
              success: startResult.success,
              error: startResult.error
            })));
          } catch (_) {}
          if (startResult.success) {
            this.onRendererEvent('file-transfer-start', {
              transferId,
              filename,
              totalSize,
              preview: !!startResult.preview,
              destination: startResult.destinationPath
            });
          }
        };

        // Uploading into a PC folder: the destination must sit inside one of
        // the shared roots. Validation is async (it may prompt on the PC), so
        // the ack is sent when it resolves.
        if (destDir) {
          if (!this.pcfs) { failMeta('PC file sharing is unavailable'); break; }
          this.pcfs.resolveUploadDir(destDir)
            .then((dir) => {
              if (!dir.ok) { failMeta(dir.error || 'That folder is not shared'); return; }
              begin({ sha256, destinationDir: dir.path });
            })
            .catch((err) => failMeta(`Upload rejected: ${err.message}`));
          break;
        }
        begin({ sha256 });
        break;
      }

      case MessageType.FILE_CHUNK: {
        const { transferId, chunkBase64 } = payload;
        const writeResult = this.fileManager.writeChunk(transferId, chunkBase64);

        if (writeResult.finished) {
          this.onRendererEvent('file-transfer-complete', {
            transferId,
            path: writeResult.path,
            preview: !!writeResult.preview
          });
        } else if (!writeResult.success) {
          this.onRendererEvent('file-transfer-error', {
            transferId,
            error: writeResult.error
          });
        } else {
          this.onRendererEvent('file-transfer-progress', {
            transferId,
            progress: writeResult.progress
          });
        }
        break;
      }

      case MessageType.FILE_CANCEL: {
        this.fileManager.cancelTransfer(payload.transferId);
        this.onRendererEvent('file-transfer-cancelled', { transferId: payload.transferId });
        break;
      }

      // --- PC FILE BROWSER (phone asks, PC answers with PC_FS_RESULT) ---
      case MessageType.PC_FS_LIST:
      case MessageType.PC_FS_MKDIR:
      case MessageType.PC_FS_RENAME:
      case MessageType.PC_FS_DELETE:
      case MessageType.PC_FS_DOWNLOAD:
      case MessageType.PC_FS_COPY:
      case MessageType.PC_FS_MOVE:
      case MessageType.PC_FS_SEARCH: {
        const requestId = payload.requestId;
        const reply = (extra) => {
          try {
            ws.send(JSON.stringify(createMessage(MessageType.PC_FS_RESULT, {
              requestId,
              op: message.type,
              ...extra
            })));
          } catch (_) { /* socket closed mid-request */ }
        };
        if (!this.pcfs) {
          reply({ ok: false, error: 'PC file sharing is unavailable' });
          break;
        }
        (async () => {
          try {
            if (message.type === MessageType.PC_FS_LIST) {
              reply(await this.pcfs.list(payload.path || null));
            } else if (message.type === MessageType.PC_FS_MKDIR) {
              const r = await this.pcfs.mkdir(payload.path, payload.name);
              reply(r.ok ? { ok: true, path: r.path, name: payload.name } : r);
            } else if (message.type === MessageType.PC_FS_RENAME) {
              const r = await this.pcfs.rename(payload.path, payload.name);
              reply(r.ok ? { ok: true, path: r.path, name: payload.name } : r);
            } else if (message.type === MessageType.PC_FS_DELETE) {
              reply(await this.pcfs.remove(payload.path, { permanent: !!payload.permanent }));
            } else if (message.type === MessageType.PC_FS_COPY) {
              reply(await this.pcfs.copy(payload.paths, payload.dest));
            } else if (message.type === MessageType.PC_FS_MOVE) {
              reply(await this.pcfs.move(payload.paths, payload.dest));
            } else if (message.type === MessageType.PC_FS_SEARCH) {
              reply(await this.pcfs.search(payload.query, payload.path || null));
            } else {
              const file = await this.pcfs.openForDownload(payload.path);
              if (!file.ok) { reply(file); return; }
              reply({ ok: true, path: file.path, name: file.name, size: file.size });
              if (this.sendFileToPhone) {
                this.sendFileToPhone(file.path).then((res) => {
                  this.onRendererEvent('pcfs-download', {
                    name: file.name,
                    ok: !!(res && res.success),
                    error: (res && res.error) || ''
                  });
                }).catch((err) => {
                  this.onRendererEvent('pcfs-download', { name: file.name, ok: false, error: err.message });
                });
              }
            }
          } catch (err) {
            reply({ ok: false, error: err.message || 'PC file request failed' });
          }
        })();
        break;
      }

      // --- PHASE 3: REMOTE PC INPUT ---
      case MessageType.PC_MOUSE_MOVE: {
        this.controlManager.handleMouseMove(payload.dx, payload.dy, payload.accel !== false);
        break;
      }

      case MessageType.PC_MOUSE_CLICK: {
        this.controlManager.handleMouseClick(payload.button, payload.double);
        break;
      }

      case MessageType.PC_MOUSE_SCROLL: {
        this.controlManager.handleMouseScroll(payload.deltaY);
        break;
      }

      case MessageType.PC_KEY_INPUT: {
        this.controlManager.handleKeyInput(payload.text, payload.specialKey);
        break;
      }

      case MessageType.PC_MEDIA_ACTION: {
        this.controlManager.handleMediaAction(payload.action);
        break;
      }

      case MessageType.PC_SYSTEM_ACTION: {
        this.controlManager.handleSystemAction(payload.action);
        break;
      }

      // --- Phone tells us it could not perform a touch / navigation request ---
      case MessageType.PHONE_CONTROL_STATUS: {
        this.onRendererEvent('phone-control-status', { ok: !!payload.ok, reason: payload.reason || '' });
        break;
      }

      // --- Phone reports the result of a Lock / Restart / Shut down request ---
      case MessageType.PHONE_POWER_RESULT: {
        this.onRendererEvent('phone-power-result', { ok: !!payload.ok, message: payload.message || '' });
        break;
      }

      // --- PHASE 5: JARVIS COMMANDS FROM PHONE ---
      case MessageType.PC_COMMAND: {
        if (this.jarvis) this.jarvis.handleCommand(ws, payload);
        break;
      }

      // --- PHASE 4: SCREEN FRAME FROM PHONE ---
      case MessageType.PHONE_SCREEN_FRAME: {
        // Decode once here and ship raw JPEG bytes. Forwarding the base64
        // string forced the renderer to build a giant data: URL per frame
        // (up to 30/s), which is what made the mirror feel like a slideshow.
        let jpeg = null;
        try {
          jpeg = Buffer.from(payload.jpegBase64, 'base64');
        } catch (_) { break; }
        this.onRendererEvent('phone-screen-frame', {
          jpeg,
          width: payload.width,
          height: payload.height,
          orientation: payload.orientation
        });
        break;
      }

      // ================= V2: REMOTE PC SCREEN (phone views PC) =================
      case MessageType.PC_SCREEN_START: {
        if (!this.remotePc) break;
        const res = this.remotePc.start({
          fps: payload.fps,
          quality: payload.quality,
          maxDimension: payload.maxDimension
        });
        ws.ownsPcScreen = true;
        if (this.featureHub) this.featureHub.pcScreenActive = res.ok;
        ws.send(JSON.stringify(createMessage(MessageType.PC_SCREEN_START, {
          ok: res.ok, error: res.error || null,
          fps: this.remotePc.fps, quality: this.remotePc.quality, maxDimension: this.remotePc.maxDimension
        })));
        break;
      }

      case MessageType.PC_SCREEN_STOP: {
        if (this.remotePc) this.remotePc.stop();
        ws.ownsPcScreen = false;
        if (this.featureHub) this.featureHub.pcScreenActive = false;
        break;
      }

      case MessageType.PC_MOUSE_MOVE_ABS: {
        // Absolute normalized pointing (0..1) -> pixel coordinates
        if (this.controlManager && typeof this.controlManager.handleMouseMoveAbs === 'function') {
          this.controlManager.handleMouseMoveAbs(payload.x, payload.y);
        }
        break;
      }

      case MessageType.PC_MOUSE_DOWN: {
        if (this.controlManager && typeof this.controlManager.handleMouseButton === 'function') {
          this.controlManager.handleMouseButton('down', payload.button || 'left');
        }
        break;
      }

      case MessageType.PC_MOUSE_UP: {
        if (this.controlManager && typeof this.controlManager.handleMouseButton === 'function') {
          this.controlManager.handleMouseButton('up', payload.button || 'left');
        }
        break;
      }

      case MessageType.PC_KEY_COMBOS: {
        // Modifier combos like ['LeftControl','C'] - validated against nut.Key whitelist
        if (this.controlManager && Array.isArray(payload.keys)) {
          this.controlManager.pressKeys(payload.keys.slice(0, 6));
        }
        break;
      }

      // ================= V2: PHONE WEBCAM =================
      case MessageType.WEBCAM_FRAME: {
        // Decode the base64 payload ONCE and share the buffer with the MJPEG
        // hub and the UI preview — it used to be decoded twice per frame,
        // which is real main-thread work at 15 fps × ~150 KB.
        let jpeg = null;
        try {
          jpeg = Buffer.from(payload.jpegBase64, 'base64');
        } catch (_) { break; }
        if (this.webcamHub) this.webcamHub.onFrame(payload, jpeg);
        // Throttled preview mirror for the desktop UI. Raw JPEG bytes (Buffer),
        // not base64: the renderer wraps them in a Blob URL, which skips the
        // base64-decode + giant-string churn on the UI thread that used to hang
        // the whole app at higher phone fps.
        const now = Date.now();
        if (this.previewFramesEnabled && (!this._webcamPrevAt || now - this._webcamPrevAt > 33)) {
          this._webcamPrevAt = now;
          this.onRendererEvent('webcam-frame', {
            jpeg,
            width: payload.width,
            height: payload.height
          });
        }
        break;
      }

      case MessageType.WEBCAM_STATUS: {
        if (this.webcamHub) this.webcamHub.onStatus(payload);
        if (this.featureHub) this.featureHub.webcamActive = !!(payload && payload.active);
        this.onRendererEvent('webcam-status', {
          active: !!(payload && payload.active),
          camera: (payload && payload.camera) || '',
          error: (payload && payload.error) || ''
        });
        break;
      }

      // ================= V2: CALLS =================
      case MessageType.CALL_STATE: {
        const state = this.featureHub && this.featureHub.callStateUpdate(payload);
        if (state) this.onRendererEvent('call-state', state);
        break;
      }

      case MessageType.CALL_ACTION_RESULT: {
        this.onRendererEvent('call-action-result', {
          action: payload.action || '', ok: !!payload.ok, error: payload.error || ''
        });
        break;
      }

      // ================= Apps: catalog + launch =================
      case MessageType.APP_CATALOG: {
        this.onRendererEvent('app-catalog', payload || {});
        break;
      }

      case MessageType.APP_LAUNCH_RESULT: {
        this.onRendererEvent('app-launch-result', payload || {});
        break;
      }

      case MessageType.APP_WINDOW_FRAME: {
        this.onRendererEvent('app-window-frame', payload || {});
        break;
      }

      case MessageType.APP_WINDOW_CLOSED: {
        this.onRendererEvent('app-window-closed', payload || {});
        break;
      }

      case MessageType.ADB_PAIR_RESULT: {
        this.onRendererEvent('adb-pair-result', payload || {});
        break;
      }

      case MessageType.CALL_RECENTS: {
        const recents = this.featureHub && this.featureHub.callRecentsUpdate(payload);
        if (recents) this.onRendererEvent('call-recents', recents);
        break;
      }

      // ================= V2: NOTIFICATIONS =================
      case MessageType.NOTIF_POSTED: {
        const entry = this.featureHub && this.featureHub.notifPosted(payload);
        if (entry) this.onRendererEvent('notif-posted', entry);
        break;
      }

      case MessageType.NOTIF_REMOVED: {
        // The phone replies with {key, error} when a dismiss/reply failed
        // (e.g. notification access lost) - surface it without deleting the
        // entry so the UI can show what went wrong.
        if (payload && payload.error) {
          this.onRendererEvent('notif-removed', { key: payload.key, error: payload.error });
          break;
        }
        if (this.featureHub && this.featureHub.notifRemoved(payload)) {
          this.onRendererEvent('notif-removed', { key: payload.key });
        }
        break;
      }

      case MessageType.NOTIF_LIST: {
        if (this.featureHub && Array.isArray(payload.items)) {
          for (const item of payload.items) this.featureHub.notifPosted(item);
          if (typeof payload.hiddenCount === 'number') {
            this.featureHub.setNotifHiddenCount(payload.hiddenCount);
          }
        }
        this.onRendererEvent('notif-list', this.featureHub ? this.featureHub.notificationsSnapshot() : null);
        break;
      }

      // ================= V2: SMS =================
      case MessageType.SMS_RESP: {
        if (this.featureHub) this.featureHub.smsResponse(payload);
        this.onRendererEvent('sms-response', payload);
        break;
      }

      case MessageType.SMS_SENT: {
        this.onRendererEvent('sms-sent', {
          ok: !!payload.ok, address: payload.address || '', error: payload.error || ''
        });
        break;
      }

      // ================= V2: PHONE MEDIA =================
      case MessageType.MEDIA_LIST: {
        const result = this.featureHub && this.featureHub.mediaList(payload);
        if (result) this.onRendererEvent('media-list', result);
        else this.onRendererEvent('media-list', { kind: null, total: 0, items: [], error: 'bad payload' });
        break;
      }

      case MessageType.MEDIA_THUMB: {
        if (this.featureHub) this.featureHub.mediaThumb(payload);
        this.onRendererEvent('media-thumb', { id: payload.id, jpegBase64: payload.jpegBase64 });
        break;
      }

      // Outcome of a PC-issued delete / share (gallery action buttons).
      case MessageType.MEDIA_RESULT: {
        this.onRendererEvent('media-result', payload || {});
        break;
      }

      // Apps that can receive the selection — the picker runs ON THE PC.
      case MessageType.MEDIA_SHARE_TARGETS: {
        this.onRendererEvent('media-share-targets', payload || {});
        break;
      }

      case MessageType.CALL_LOG_DELETE_RESULT: {
        this.onRendererEvent('call-log-delete-result', payload || {});
        break;
      }

      // ================= V2: PHONE VOLUME / BATTERY / DIAG =================
      case MessageType.PHONE_VOLUME_STATE: {
        this.onRendererEvent('phone-volume-state', {
          level: payload.level | 0, max: payload.max | 0, muted: !!payload.muted
        });
        break;
      }

      case MessageType.PHONE_BATTERY: {
        const b = this.featureHub && this.featureHub.batteryUpdate(payload);
        if (b) this.onRendererEvent('phone-battery', b);
        break;
      }

      case MessageType.DIAG_RESP: {
        // Log the phone's permission/service snapshot so "nothing works" can be
        // diagnosed from duolink.log without guessing.
        try {
          const perms = (payload && payload.permissions) || {};
          const svcs = (payload && payload.services) || {};
          const dev = (payload && payload.device) || {};
          const link = (payload && payload.link) || {};
          console.log(`[Diag] PHONE permissions: ` + Object.entries(perms).map(([k, v]) => `${k}=${v}`).join(' '));
          console.log(`[Diag] PHONE services:   ` + Object.entries(svcs).map(([k, v]) => `${k}=${v}`).join(' '));
          console.log(`[Diag] PHONE device:     model=${dev.model || '?'} sdk=${dev.sdk || '?'} battery=${dev.batteryPct ?? '?'}% linkState=${link.state || '?'}`);
        } catch (e) {
          console.log(`[Diag] could not log DIAG_RESP: ${e.message}`);
        }
        this.onRendererEvent('diag-response', payload);
        break;
      }

      default:
        console.log(`[WSS] Unhandled message type: ${type}`);
    }
  }

  /**
   * Sends a message to the currently active phone session.
   * @param {string} sessionId 
   * @param {object} message 
   */
  sendMessageToSession(sessionId, message) {
    const session = this.pairingManager.activeSessions.get(sessionId);
    if (session && session.socket && session.socket.readyState === WebSocket.OPEN) {
      session.socket.send(JSON.stringify(message));
      return true;
    }
    return false;
  }

  /**
   * Broadcasts to all active sessions.
   * @returns {number} count of sockets the message was written to
   */
  broadcast(message) {
    const json = JSON.stringify(message);
    let delivered = 0;
    for (const session of this.pairingManager.activeSessions.values()) {
      if (session.socket && session.socket.readyState === WebSocket.OPEN) {
        try {
          session.socket.send(json);
          delivered++;
        } catch (_) {}
      }
    }
    return delivered;
  }

  /** Largest queued-bytes value across live sockets (0 when none are open). */
  maxBufferedAmount() {
    let max = 0;
    for (const session of this.pairingManager.activeSessions.values()) {
      const socket = session.socket;
      if (socket && socket.readyState === WebSocket.OPEN) {
        max = Math.max(max, socket.bufferedAmount || 0);
      }
    }
    return max;
  }

  /**
   * Waits until every live socket has flushed its send queue below `limit`.
   * File chunks used to be written with no flow control at all, so a whole
   * file was queued inside the socket at once: memory ballooned, the UI froze
   * and the transfer looked stuck on the phone.
   * @returns {Promise<boolean>} false when the socket never drained in time
   */
  async waitForDrain(limit = 512 * 1024, timeoutMs = 20000) {
    const started = Date.now();
    while (this.maxBufferedAmount() > limit) {
      if (Date.now() - started > timeoutMs) return false;
      await new Promise((resolve) => setTimeout(resolve, 8));
    }
    return true;
  }

  stop() {
    if (this.wss) {
      this.wss.close();
    }
    if (this.server) {
      this.server.close();
    }
  }
}

module.exports = { WssServer };
