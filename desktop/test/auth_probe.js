// Simulates the Android phone's reconnect handshake end-to-end against the
// local WSS server. Uses the REAL deviceId/sessionId/sessionSecret from
// trusted_devices.json and mirrors the phone's HMAC computation byte-for-byte.
// Prints exactly where the handshake succeeds or fails.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const WebSocket = require('ws');

const devFile = path.join(process.env.APPDATA, 'duolink-desktop', 'trusted_devices.json');
const devices = JSON.parse(fs.readFileSync(devFile, 'utf8'));
const key = Object.keys(devices)[0];
const dev = devices[key];
console.log(`[probe] Using device ${dev.deviceId} session ${dev.sessionId}`);
console.log(`[probe] sessionSecret len=${dev.sessionSecret.length}`);

const ws = new WebSocket('wss://127.0.0.1:3000', { rejectUnauthorized: false });

function send(type, payload) {
  ws.send(JSON.stringify({ v: 1, type, id: crypto.randomUUID(), ts: Date.now(), payload }));
}

ws.on('open', () => {
  console.log('[probe] socket open -> sending RECONNECT_CHALLENGE_REQUEST');
  send('RECONNECT_CHALLENGE_REQUEST', { deviceId: dev.deviceId, sessionId: dev.sessionId });
});

ws.on('message', (raw) => {
  let msg;
  try { msg = JSON.parse(raw.toString('utf8')); } catch { return; }
  console.log(`[probe] <- ${msg.type}`);

  if (msg.type === 'HEARTBEAT_PING') { send('HEARTBEAT_PONG', {}); return; }

  if (msg.type === 'RECONNECT_CHALLENGE_RESPONSE') {
    const nonce = msg.payload.challengeNonce;
    console.log(`[probe] got nonce ${String(nonce).slice(0, 12)}… -> computing HMAC`);
    // Mirror TlsPinningSocket.computeHmacSha256 exactly:
    //   key = hex-decoded sessionSecret bytes; message = nonce hex string as UTF-8
    const hmac = crypto.createHmac('sha256', Buffer.from(dev.sessionSecret, 'hex'))
      .update(nonce)
      .digest('hex');
    console.log(`[probe] proof=${hmac.slice(0, 12)}… -> sending RECONNECT_CHALLENGE_RESPONSE`);
    send('RECONNECT_CHALLENGE_RESPONSE', { deviceId: dev.deviceId, proof: hmac });
    return;
  }

  if (msg.type === 'RECONNECT_RESULT') {
    console.log(`[probe] ==> RECONNECT_RESULT success=${msg.payload.success} error=${msg.payload.error || '(none)'}`);
    console.log(msg.payload.success
      ? '[probe] PASS: server accepts a correctly-computed proof. Auth logic is healthy.'
      : '[probe] FAIL: server rejected a correctly-computed proof. BUG on server or credential mismatch.');
    ws.close();
    process.exit(msg.payload.success ? 0 : 1);
  }
});

ws.on('error', (e) => { console.log(`[probe] socket error: ${e.message}`); process.exit(2); });
setTimeout(() => { console.log('[probe] TIMEOUT (8s) - no RECONNECT_RESULT'); process.exit(3); }, 8000);
