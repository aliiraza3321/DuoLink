/**
 * PhonePC Pair - Pairing Manager, Token Lifecycle & Trusted Device Registry
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const QRCode = require('qrcode');

const TOKEN_TTL_MS = 3 * 60 * 1000; // 3 minutes

class PairingManager {
  /**
   * @param {string} storageDir
   */
  constructor(storageDir) {
    this.storageDir = storageDir;
    this.devicesFilePath = path.join(storageDir, 'trusted_devices.json');
    this.trustedDevices = this._loadTrustedDevices();

    // Active one-time pairing token
    this.activeToken = null;
    this.tokenExpiresAt = 0;
    this.activeQrDataUrl = null;
    this.activeUri = null;

    // Pending pairing requests awaiting PC user confirmation
    // pendingPairings: Map<pendingId, { pendingId, token, deviceName, deviceId, confirmationCode, socket, createdAt }>
    this.pendingPairings = new Map();

    // Active sessions: Map<sessionId, { sessionId, deviceId, deviceName, sessionSecret, permissions, socket, connectedAt }>
    this.activeSessions = new Map();

    // Challenge nonces for reconnect: Map<deviceId, { nonce, expiresAt, sessionId }>
    this.pendingChallenges = new Map();

    // Rate limiting for pairing attempts: Map<ip, { attempts: number, resetAt: number }>
    this.pairingAttempts = new Map();
  }

  _loadTrustedDevices() {
    try {
      if (fs.existsSync(this.devicesFilePath)) {
        const raw = fs.readFileSync(this.devicesFilePath, 'utf8');
        return JSON.parse(raw);
      }
    } catch (err) {
      console.warn('[Pairing] Could not load trusted_devices.json, starting fresh', err);
    }
    return {};
  }

  _saveTrustedDevices() {
    try {
      if (!fs.existsSync(this.storageDir)) {
        fs.mkdirSync(this.storageDir, { recursive: true });
      }
      fs.writeFileSync(this.devicesFilePath, JSON.stringify(this.trustedDevices, null, 2), 'utf8');
    } catch (err) {
      console.error('[Pairing] Error saving trusted devices', err);
    }
  }

  /**
   * Generates or refreshes the pairing token and QR code.
   * @param {string} host 
   * @param {number} port 
   * @param {string} fingerprintSha256 
   * @returns {Promise<{ token: string, uri: string, qrDataUrl: string, expiresInMs: number }>}
   */
  async generatePairingToken(host, port, fingerprintSha256) {
    const token = crypto.randomBytes(24).toString('hex'); // 48 hex chars
    const expiresAt = Date.now() + TOKEN_TTL_MS;
    const uri = `phonepair://pair?host=${encodeURIComponent(host)}&port=${port}&token=${token}&fp=${fingerprintSha256}`;

    const qrDataUrl = await QRCode.toDataURL(uri, {
      errorCorrectionLevel: 'M',
      margin: 2,
      width: 320,
      color: {
        dark: '#0f172a',
        light: '#ffffff'
      }
    });

    this.activeToken = token;
    this.tokenExpiresAt = expiresAt;
    this.activeQrDataUrl = qrDataUrl;
    this.activeUri = uri;

    return {
      token,
      uri,
      qrDataUrl,
      expiresInMs: TOKEN_TTL_MS
    };
  }

  /**
   * Checks whether the token is currently active or known in pending requests
   */
  isKnownOrActiveToken(token, deviceId) {
    if (!token) return false;
    if (token === this.activeToken && Date.now() <= this.tokenExpiresAt) return true;
    for (const pending of this.pendingPairings.values()) {
      if (pending.token === token || (deviceId && pending.deviceId === deviceId)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Check rate limiting for IP (only count failed/brute-force attempts)
   */
  checkRateLimit(ip, isFailedAttempt = false) {
    const cleanIp = (ip || '').replace(/^::ffff:/, '');
    const now = Date.now();
    const record = this.pairingAttempts.get(cleanIp);
    if (record) {
      if (now > record.resetAt) {
        this.pairingAttempts.delete(cleanIp);
        if (isFailedAttempt) {
          this.pairingAttempts.set(cleanIp, { attempts: 1, resetAt: now + 60 * 1000 });
        }
        return true;
      }
      if (record.attempts >= 60) {
        return false;
      }
      if (isFailedAttempt) {
        record.attempts += 1;
      }
      return true;
    }
    if (isFailedAttempt) {
      this.pairingAttempts.set(cleanIp, { attempts: 1, resetAt: now + 60 * 1000 });
    }
    return true;
  }

  /**
   * Resets the rate limiter and any stale pending requests
   */
  resetRateLimit() {
    this.pairingAttempts.clear();
    console.log('[Pairing] Rate limiter cleared.');
    return true;
  }

  /**
   * Validates pairing token from phone.
   * If a pending pairing for this device or token already exists, update its socket!
   * @param {string} token 
   * @param {string} deviceName Display name (spec §9: clean marketing name)
   * @param {string} deviceId 
   * @param {object} socket 
   * @param {string} [modelCode] Raw Build.MODEL, shown only as small text
   * @returns {{ success: boolean, confirmationCode?: string, pendingId?: string, error?: string }}
   */
  claimToken(token, deviceName, deviceId, socket, modelCode) {
    // 1. Check if the SAME device is resuming its own pending pairing request
    if (deviceId) {
      for (const [pId, pending] of this.pendingPairings.entries()) {
        if (pending.deviceId === deviceId && pending.token === token) {
          // Update the live socket connection
          pending.socket = socket;
          if (deviceName) pending.deviceName = deviceName;
          console.log(`[Pairing] Existing pending pairing request refreshed for device: ${pending.deviceName}`);
          return {
            success: true,
            confirmationCode: pending.confirmationCode,
            pendingId: pId
          };
        }
      }
    }

    // 2. Validate token against active one-time token
    if (!token || token !== this.activeToken) {
      return { success: false, error: 'Invalid or already used token' };
    }

    if (Date.now() > this.tokenExpiresAt) {
      this.activeToken = null;
      return { success: false, error: 'Token has expired' };
    }

    // Burn token immediately - single use!
    this.activeToken = null;
    this.tokenExpiresAt = 0;

    // Generate 6-digit confirmation code
    const confirmationCode = Math.floor(100000 + Math.random() * 900000).toString();
    const pendingId = crypto.randomUUID();

    this.pendingPairings.set(pendingId, {
      pendingId,
      token,
      deviceName: deviceName || 'Android Phone',
      modelCode: modelCode || '',
      deviceId: deviceId || crypto.randomUUID(),
      confirmationCode,
      socket,
      createdAt: Date.now()
    });

    console.log(`[Pairing] New pending pairing created: ${pendingId} code: ${confirmationCode}`);

    return {
      success: true,
      confirmationCode,
      pendingId
    };
  }

  /**
   * Called when PC user clicks "Approve" on the confirmation code.
   * @param {string} pendingId 
   * @returns {{ success: boolean, session?: object, confirmationCode?: string, error?: string }}
   */
  approvePairing(pendingId) {
    const pending = this.pendingPairings.get(pendingId);
    if (!pending) {
      // Check if already approved into active sessions
      for (const session of this.activeSessions.values()) {
        if (session.pendingId === pendingId) {
          return { success: true, session, confirmationCode: session.confirmationCode };
        }
      }
      return { success: false, error: 'Pending pairing request not found or expired.' };
    }

    // Token has fulfilled its lifecycle upon approval
    this.pendingPairings.delete(pendingId);
    this.activeToken = null;
    this.tokenExpiresAt = 0;

    const sessionId = crypto.randomUUID();
    const sessionSecret = crypto.randomBytes(32).toString('hex'); // 256-bit secret

    const session = {
      pendingId,
      sessionId,
      deviceId: pending.deviceId,
      deviceName: pending.deviceName,
      modelCode: pending.modelCode || '',
      sessionSecret,
      confirmationCode: pending.confirmationCode,
      permissions: {
        allowPhoneToControlPc: false,
        allowPcToControlPhone: false,
        allowFileTransfer: true,
        allowClipboardSync: true,
        allowChat: true
      },
      socket: pending.socket,
      connectedAt: Date.now()
    };

    // Store in active sessions
    this.activeSessions.set(sessionId, session);

    // Save to trusted devices registry
    this.trustedDevices[pending.deviceId] = {
      deviceId: pending.deviceId,
      deviceName: pending.deviceName,
      modelCode: pending.modelCode || '',
      sessionId,
      sessionSecret,
      pairedAt: Date.now(),
      lastSeenAt: Date.now()
    };
    this._saveTrustedDevices();

    console.log(`[Pairing] Approved pairing for ${pending.deviceName} (Session: ${sessionId})`);

    return { success: true, session, confirmationCode: pending.confirmationCode };
  }

  /**
   * Rejects a pending pairing and notifies phone
   */
  rejectPairing(pendingId) {
    const pending = this.pendingPairings.get(pendingId);
    if (pending) {
      if (pending.socket) {
        try {
          const { MessageType, createMessage } = require('./protocol');
          pending.socket.send(JSON.stringify(createMessage(MessageType.PAIR_RESULT, {
            success: false,
            error: 'Pairing request was declined on your PC.'
          })));
          pending.socket.close(1008, 'Pairing rejected by PC user');
        } catch (_) {}
      }
      this.pendingPairings.delete(pendingId);
      console.log(`[Pairing] Rejected pending pairing ${pendingId}`);
      return true;
    }
    return false;
  }

  /**
   * Creates a challenge nonce for reconnect authentication
   * @param {string} deviceId 
   * @param {string} sessionId 
   * @returns {string|null}
   */
  createReconnectChallenge(deviceId, sessionId) {
    const trusted = this.trustedDevices[deviceId];
    console.log(`[Auth] RECONNECT_CHALLENGE_REQUEST deviceId=${deviceId} sessionId=${sessionId}`);
    console.log(`[Auth]   trusted device present: ${!!trusted}`);
    if (trusted) console.log(`[Auth]   trusted.sessionId=${trusted.sessionId} match=${trusted.sessionId === sessionId}`);
    if (!trusted || trusted.sessionId !== sessionId) {
      console.log(`[Auth]   -> challenge REJECTED (unknown device or sessionId mismatch)`);
      return null;
    }
    console.log(`[Auth]   -> challenge nonce issued`);
    const nonce = crypto.randomBytes(32).toString('hex');
    this.pendingChallenges.set(deviceId, {
      nonce,
      sessionId,
      expiresAt: Date.now() + 60 * 1000 // 1 minute
    });
    return nonce;
  }

  /**
   * Validates reconnect proof HMAC-SHA256(nonce, sessionSecret)
   * @param {string} deviceId 
   * @param {string} proof 
   * @param {object} socket 
   * @returns {{ success: boolean, session?: object, error?: string }}
   */
  verifyReconnectProof(deviceId, proof, socket) {
    const challenge = this.pendingChallenges.get(deviceId);
    console.log(`[Auth] RECONNECT_PROOF deviceId=${deviceId} proofLen=${String(proof||'').length} challengePresent=${!!challenge}`);
    if (!challenge) {
      console.log(`[Auth]   -> FAILED: No active challenge found`);
      return { success: false, error: 'No active challenge found' };
    }
    this.pendingChallenges.delete(deviceId);

    if (Date.now() > challenge.expiresAt) {
      console.log(`[Auth]   -> FAILED: Challenge expired`);
      return { success: false, error: 'Challenge expired' };
    }

    const trusted = this.trustedDevices[deviceId];
    if (!trusted) {
      console.log(`[Auth]   -> FAILED: Unknown device at proof stage`);
      return { success: false, error: 'Unknown device' };
    }

    // The phone keys the HMAC with the hex-DECODED secret bytes, so we must too.
    const expectedHmac = crypto.createHmac('sha256', Buffer.from(trusted.sessionSecret, 'hex'))
      .update(challenge.nonce)
      .digest('hex');

    // Constant-time compare
    const expectedBuf = Buffer.from(expectedHmac, 'hex');
    const proofBuf = Buffer.from(String(proof || ''), 'hex');

    if (expectedBuf.length !== proofBuf.length || !crypto.timingSafeEqual(expectedBuf, proofBuf)) {
      console.log(`[Auth]   -> FAILED: HMAC mismatch expected=${expectedHmac.slice(0,12)}… got=${String(proof||'').slice(0,12)}…`);
      return { success: false, error: 'Challenge proof verification failed' };
    }
    console.log(`[Auth]   -> SUCCESS: proof verified, session ${trusted.sessionId} authenticated`);

    // Restore or create active session
    let session = this.activeSessions.get(trusted.sessionId);
    if (!session) {
      session = {
        sessionId: trusted.sessionId,
        deviceId: trusted.deviceId,
        deviceName: trusted.deviceName,
        sessionSecret: trusted.sessionSecret,
        permissions: {
          allowPhoneToControlPc: false,
          allowPcToControlPhone: false,
          allowFileTransfer: true,
          allowClipboardSync: true,
          allowChat: true
        },
        socket,
        connectedAt: Date.now()
      };
      this.activeSessions.set(trusted.sessionId, session);
    } else {
      session.socket = socket;
    }

    trusted.lastSeenAt = Date.now();
    this._saveTrustedDevices();

    return { success: true, session };
  }

  /**
   * Revoke a trusted device
   * @param {string} deviceId 
   */
  revokeDevice(deviceId) {
    const trusted = this.trustedDevices[deviceId];
    if (trusted) {
      const session = this.activeSessions.get(trusted.sessionId);
      if (session && session.socket) {
        try {
          session.socket.close(1000, 'Device pairing revoked by PC');
        } catch (_) {}
      }
      this.activeSessions.delete(trusted.sessionId);
      delete this.trustedDevices[deviceId];
      this._saveTrustedDevices();
      return true;
    }
    return false;
  }

  /**
   * Disconnect an active session
   */
  disconnectSession(sessionId) {
    const session = this.activeSessions.get(sessionId);
    if (session) {
      if (session.socket) {
        try {
          session.socket.close(1000, 'Disconnected by user');
        } catch (_) {}
      }
      this.activeSessions.delete(sessionId);
      return true;
    }
    return false;
  }

  getTrustedDevicesList() {
    return Object.values(this.trustedDevices);
  }
}

module.exports = { PairingManager, TOKEN_TTL_MS };
