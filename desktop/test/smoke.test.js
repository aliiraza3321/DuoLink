/**
 * PhonePC Pair - Desktop Automated Smoke Test Suite
 * Tests: Network, Protocol Envelope, Certificate SHA-256 Fingerprint,
 * Pairing Token Lifecycle (single use, burn, confirmation code),
 * Reconnect Challenge/Response HMAC verification, and Path Traversal Protection.
 */

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const { isPrivateIp, getLocalLanIp } = require('../src/network');
const { computeSha256Fingerprint, getOrCreateTlsCert } = require('../src/cert');
const { MessageType, createMessage, parseAndValidateMessage, PROTOCOL_VERSION } = require('../src/protocol');
const { PairingManager } = require('../src/pairing');
const { FileManager } = require('../src/files');
const { WssServer } = require('../src/sockets');

async function runSmokeTests() {
  console.log('====================================================');
  console.log('🚀 Running PhonePC Pair Desktop Smoke Tests...');
  console.log('====================================================');

  const testTempDir = path.join(__dirname, 'temp_test_data');
  if (fs.existsSync(testTempDir)) {
    fs.rmSync(testTempDir, { recursive: true, force: true });
  }
  fs.mkdirSync(testTempDir, { recursive: true });

  try {
    // 1. Network Tests
    console.log('\n[1/6] Testing Network & Private IP Verification...');
    assert.strictEqual(isPrivateIp('192.168.1.100'), true, '192.168.x.x should be private');
    assert.strictEqual(isPrivateIp('10.0.0.5'), true, '10.x.x.x should be private');
    assert.strictEqual(isPrivateIp('172.20.0.1'), true, '172.16-31.x.x should be private');
    assert.strictEqual(isPrivateIp('127.0.0.1'), true, '127.0.0.1 should be allowed');
    assert.strictEqual(isPrivateIp('::ffff:192.168.1.5'), true, 'IPv6-mapped IPv4 private should be true');
    assert.strictEqual(isPrivateIp('8.8.8.8'), false, 'Public IP 8.8.8.8 must be rejected');
    assert.strictEqual(isPrivateIp('1.1.1.1'), false, 'Public IP 1.1.1.1 must be rejected');
    const localIp = getLocalLanIp();
    assert.ok(localIp && typeof localIp === 'string', 'Should return a valid local IP');
    console.log(`  ✓ Private IP filters passed. Detected LAN IP: ${localIp}`);

    // 2. Protocol Validation Tests
    console.log('\n[2/6] Testing Protocol Envelope & Versioning...');
    const sampleMsg = createMessage(MessageType.PAIR_REQUEST, { token: 'abc123' });
    assert.strictEqual(sampleMsg.v, PROTOCOL_VERSION, 'Version must match PROTOCOL_VERSION');
    assert.strictEqual(sampleMsg.type, 'PAIR_REQUEST', 'Type must match');
    assert.ok(sampleMsg.id && sampleMsg.ts, 'Envelope must have id and timestamp');

    const validResult = parseAndValidateMessage(JSON.stringify(sampleMsg));
    assert.strictEqual(validResult.valid, true, 'Valid message must parse successfully');

    const invalidVersion = parseAndValidateMessage(JSON.stringify({ v: 999, type: 'PAIR_REQUEST', id: '1', ts: Date.now(), payload: {} }));
    assert.strictEqual(invalidVersion.valid, false, 'Unsupported version must fail');

    const invalidType = parseAndValidateMessage(JSON.stringify({ v: 1, type: 'UNKNOWN_ATTACK', id: '1', ts: Date.now(), payload: {} }));
    assert.strictEqual(invalidType.valid, false, 'Unknown message types must be rejected');
    console.log('  ✓ Protocol validation and envelope guards passed.');

    // 3. Certificate & SHA-256 Fingerprint Tests
    console.log('\n[3/6] Testing TLS Cert Generation & Fingerprint...');
    const certData = await getOrCreateTlsCert(testTempDir);
    assert.ok(certData.cert.includes('BEGIN CERTIFICATE'), 'Must generate PEM cert');
    assert.ok(certData.key.includes('BEGIN PRIVATE KEY') || certData.key.includes('BEGIN RSA PRIVATE KEY'), 'Must generate PEM key');
    assert.strictEqual(certData.fingerprint.length, 64, 'SHA-256 fingerprint must be 64 hex characters');
    const recomputedFp = computeSha256Fingerprint(certData.cert);
    assert.strictEqual(certData.fingerprint, recomputedFp, 'Fingerprint must match exact SHA-256 digest');
    console.log(`  ✓ Certificate generated. Fingerprint: ${certData.fingerprint.substring(0, 16)}...`);

    // 4. Pairing Lifecycle & Token Burning Tests
    console.log('\n[4/6] Testing Pairing Token Lifecycle (Single-use, Burning, 6-digit code, Approval)...');
    const pairingManager = new PairingManager(testTempDir);
    const tokenInfo = await pairingManager.generatePairingToken(localIp, 3000, certData.fingerprint);

    assert.ok(tokenInfo.token.length >= 32, 'Token must be at least 32 characters');
    assert.ok(tokenInfo.uri.startsWith('phonepair://pair?'), 'URI must follow phonepair scheme');
    assert.ok(tokenInfo.qrDataUrl.startsWith('data:image/png;base64,'), 'QR data URL must be valid PNG base64');

    // Claim token
    const mockSocket = { send: () => {}, close: () => {} };
    const claimRes = pairingManager.claimToken(tokenInfo.token, 'Google Pixel 8', 'device-pixel-123', mockSocket);
    assert.strictEqual(claimRes.success, true, 'Token claim must succeed on first try');
    assert.strictEqual(claimRes.confirmationCode.length, 6, 'Confirmation code must be 6 digits');
    assert.ok(!isNaN(Number(claimRes.confirmationCode)), 'Confirmation code must be numeric');

    // Try claiming same token again - must fail (single-use burned!)
    const replayClaim = pairingManager.claimToken(tokenInfo.token, 'Attacker Phone', 'device-bad', mockSocket);
    assert.strictEqual(replayClaim.success, false, 'Replaying burned token must fail');
    console.log(`  ✓ Single-use token claimed & burned. Generated 6-digit confirmation code: ${claimRes.confirmationCode}`);

    // Approve pairing
    const approveRes = pairingManager.approvePairing(claimRes.pendingId);
    assert.strictEqual(approveRes.success, true, 'Approval must succeed');
    assert.ok(approveRes.session.sessionId, 'Must issue sessionId');
    assert.strictEqual(approveRes.session.sessionSecret.length, 64, 'Must issue 256-bit (64 hex chars) sessionSecret');
    console.log(`  ✓ Pairing approved. SessionId: ${approveRes.session.sessionId}`);

    // 5. Reconnect Challenge / Response Tests
    console.log('\n[5/6] Testing Reconnect Challenge-Response Authentication...');
    const nonce = pairingManager.createReconnectChallenge('device-pixel-123', approveRes.session.sessionId);
    assert.ok(nonce && nonce.length === 64, 'Must generate 32-byte (64 hex chars) challenge nonce');

    // Compute legitimate HMAC
    const validProof = crypto.createHmac('sha256', Buffer.from(approveRes.session.sessionSecret, 'hex'))
      .update(nonce)
      .digest('hex');

    const reconnectRes = pairingManager.verifyReconnectProof('device-pixel-123', validProof, mockSocket);
    assert.strictEqual(reconnectRes.success, true, 'Legitimate challenge proof must succeed');

    // Invalid proof must be rejected
    const badProof = crypto.randomBytes(32).toString('hex');
    const badReconnectRes = pairingManager.verifyReconnectProof('device-pixel-123', badProof, mockSocket);
    assert.strictEqual(badReconnectRes.success, false, 'Invalid challenge proof must fail');
    console.log('  ✓ Reconnect HMAC challenge-response verified and hardened.');

    // 6. File Manager & Directory Traversal Protection Tests
    console.log('\n[6/6] Testing File Manager & Traversal Defense...');
    const fileManager = new FileManager(path.join(testTempDir, 'received'));
    const maliciousName = '../../../../Windows/System32/calc.exe';
    const safeName = fileManager.sanitizeFilename(maliciousName);
    assert.strictEqual(safeName, 'calc.exe', 'Directory traversal must be stripped to clean basename');

    const startTransfer = fileManager.startIncomingTransfer('tx-1', 'photo.jpg', 1024);
    assert.strictEqual(startTransfer.success, true, 'Transfer start must succeed');

    const chunk1 = Buffer.alloc(512, 'A');
    const p1 = fileManager.writeChunk('tx-1', chunk1);
    assert.strictEqual(p1.progress, 50, 'Progress should be 50%');
    assert.strictEqual(p1.finished, false, 'Should not be finished yet');

    const chunk2 = Buffer.alloc(512, 'B');
    const p2 = fileManager.writeChunk('tx-1', chunk2);
    assert.strictEqual(p2.progress, 100, 'Progress should be 100%');
    assert.strictEqual(p2.finished, true, 'Should be finished');
    assert.ok(fs.existsSync(p2.path), 'File should be safely written to received folder');
    console.log('  ✓ File transfer and path traversal protections passed.');

    // 7. Unauthenticated Command Rejection (security boundary regression test)
    console.log('\n[7/7] Testing Unauthenticated Command Rejection...');
    assert.ok(WssServer.PRE_AUTH_TYPES, 'WssServer must expose PRE_AUTH_TYPES');
    assert.ok(WssServer.PRE_AUTH_TYPES.has(MessageType.PAIR_REQUEST), 'PAIR_REQUEST must be pre-auth');
    assert.ok(!WssServer.PRE_AUTH_TYPES.has(MessageType.PC_MOUSE_MOVE), 'PC_MOUSE_MOVE must require auth');
    assert.ok(!WssServer.PRE_AUTH_TYPES.has(MessageType.PC_KEY_INPUT), 'PC_KEY_INPUT must require auth');
    assert.ok(!WssServer.PRE_AUTH_TYPES.has(MessageType.FILE_META), 'FILE_META must require auth');
    assert.ok(!WssServer.PRE_AUTH_TYPES.has(MessageType.CHAT_MESSAGE), 'CHAT_MESSAGE must require auth');
    assert.ok(!WssServer.PRE_AUTH_TYPES.has(MessageType.PC_COMMAND), 'PC_COMMAND must require auth');
    assert.ok(!WssServer.PRE_AUTH_TYPES.has(MessageType.CLIPBOARD_PUSH), 'CLIPBOARD_PUSH must require auth');
    console.log('  ✓ Unauthenticated sockets cannot reach control/file/chat/Jarvis handlers.');

    console.log('\n====================================================');
    console.log('🎉 ALL 7 DESKTOP SMOKE TESTS PASSED CLEANLY!');
    console.log('====================================================\n');
  } finally {
    // Cleanup test temp directory
    try {
      fs.rmSync(testTempDir, { recursive: true, force: true });
    } catch (_) {}
  }
}

runSmokeTests().catch(err => {
  console.error('❌ Smoke test failed:', err);
  process.exit(1);
});
