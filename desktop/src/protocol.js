/**
 * PhonePC Pair - Wire Protocol & Envelope Validation
 * Strict versioned message envelope: { v, type, id, ts, payload }
 */

const crypto = require('crypto');

const PROTOCOL_VERSION = 1;

const MessageType = {
  // Phase 1: Pairing & Session Management
  PAIR_REQUEST: 'PAIR_REQUEST',
  PAIR_CONFIRM_CODE: 'PAIR_CONFIRM_CODE',
  PAIR_RESULT: 'PAIR_RESULT',
  RECONNECT_CHALLENGE_REQUEST: 'RECONNECT_CHALLENGE_REQUEST',
  RECONNECT_CHALLENGE_RESPONSE: 'RECONNECT_CHALLENGE_RESPONSE',
  RECONNECT_RESULT: 'RECONNECT_RESULT',
  DISCONNECT: 'DISCONNECT',
  HEARTBEAT_PING: 'HEARTBEAT_PING',
  HEARTBEAT_PONG: 'HEARTBEAT_PONG',

  // Phase 2: Chat, Files, Clipboard
  CHAT_MESSAGE: 'CHAT_MESSAGE',
  CLIPBOARD_PUSH: 'CLIPBOARD_PUSH',
  FILE_META: 'FILE_META',
  FILE_CHUNK: 'FILE_CHUNK',
  FILE_ACK: 'FILE_ACK',
  FILE_CANCEL: 'FILE_CANCEL',

  // Phase 2b: PC file browser (phone browses folders this PC has shared).
  // The PC answer is always PC_FS_RESULT, tagged with the request id + op, so
  // the phone can match replies even when several requests are in flight.
  PC_FS_LIST: 'PC_FS_LIST',           // phone -> PC: { requestId, path | null }
  PC_FS_MKDIR: 'PC_FS_MKDIR',         // phone -> PC: { requestId, path, name }
  PC_FS_RENAME: 'PC_FS_RENAME',       // phone -> PC: { requestId, path, name }
  PC_FS_DELETE: 'PC_FS_DELETE',       // phone -> PC: { requestId, path, permanent? }
  PC_FS_DOWNLOAD: 'PC_FS_DOWNLOAD',   // phone -> PC: { requestId, path }
  PC_FS_COPY: 'PC_FS_COPY',           // phone -> PC: { requestId, paths, dest }
  PC_FS_MOVE: 'PC_FS_MOVE',           // phone -> PC: { requestId, paths, dest }
  PC_FS_SEARCH: 'PC_FS_SEARCH',       // phone -> PC: { requestId, query, path | null }
  PC_FS_RESULT: 'PC_FS_RESULT',       // PC -> phone: { requestId, op, ok, error?, path?, parent?, entries? }

  // Phase 3: Phone -> PC Input & Remote Media
  PC_MOUSE_MOVE: 'PC_MOUSE_MOVE',
  PC_MOUSE_CLICK: 'PC_MOUSE_CLICK',
  PC_MOUSE_SCROLL: 'PC_MOUSE_SCROLL',
  PC_KEY_INPUT: 'PC_KEY_INPUT',
  PC_MEDIA_ACTION: 'PC_MEDIA_ACTION',
  PC_SYSTEM_ACTION: 'PC_SYSTEM_ACTION',

  // Phase 4: PC -> Phone Control & Screen Mirror
  PHONE_SCREEN_START: 'PHONE_SCREEN_START',
  PHONE_SCREEN_STOP: 'PHONE_SCREEN_STOP',  PHONE_SCREEN_FRAME: 'PHONE_SCREEN_FRAME',
  PHONE_INPUT_GESTURE: 'PHONE_INPUT_GESTURE',
  PHONE_KEY_ACTION: 'PHONE_KEY_ACTION',
  PHONE_SCREEN_KEYFRAME: 'PHONE_SCREEN_KEYFRAME',
  // PC -> phone: { op: show|hide|append|delete|replace, text?, count? } —
  // "Type on Phone": pop the phone keyboard open and fill its focused field.
  PHONE_TYPE_TEXT: 'PHONE_TYPE_TEXT',
  PHONE_POWER: 'PHONE_POWER',                // PC -> phone: { action: LOCK|RESTART|SHUTDOWN }
  PHONE_POWER_RESULT: 'PHONE_POWER_RESULT',  // phone -> PC: { ok, message }

  // Phase 5: Jarvis assistant
  PC_COMMAND: 'PC_COMMAND',
  PC_COMMAND_RESULT: 'PC_COMMAND_RESULT',
  SYSTEM_STATS: 'SYSTEM_STATS',
  FIND_PHONE: 'FIND_PHONE',
  PHONE_CONTROL_STATUS: 'PHONE_CONTROL_STATUS',

  // --- V2 FEATURES (additive on protocol v1) ---

  // Remote PC screen (phone views/controls the Windows desktop)
  PC_SCREEN_START: 'PC_SCREEN_START',     // phone -> PC: { fps, quality, maxDimension }
  PC_SCREEN_STOP: 'PC_SCREEN_STOP',       // phone -> PC
  PC_SCREEN_FRAME: 'PC_SCREEN_FRAME',     // PC -> phone: { jpegBase64, width, height, seq }
  PC_MOUSE_MOVE_ABS: 'PC_MOUSE_MOVE_ABS', // phone -> PC: { x, y } normalized 0..1 (absolute pointing)
  PC_KEY_COMBOS: 'PC_KEY_COMBOS',         // phone -> PC: { keys: ['LeftControl','C'] } modifier combos
  PC_MOUSE_DOWN: 'PC_MOUSE_DOWN',         // phone -> PC: { button } press & hold (drag start)
  PC_MOUSE_UP: 'PC_MOUSE_UP',             // phone -> PC: { button } release (drag end)

  // Phone webcam (phone camera -> PC)
  WEBCAM_START: 'WEBCAM_START',           // PC -> phone: { camera, width, height, fps, quality }
  WEBCAM_STOP: 'WEBCAM_STOP',             // PC -> phone
  WEBCAM_TORCH: 'WEBCAM_TORCH',           // PC -> phone: { on } flip the torch live
  WEBCAM_FRAME: 'WEBCAM_FRAME',           // phone -> PC: { jpegBase64, width, height, seq }
  WEBCAM_STATUS: 'WEBCAM_STATUS',         // phone -> PC: { active, camera, error }

  // Calls
  CALL_STATE: 'CALL_STATE',               // phone -> PC: { state, number, name, direction }
  CALL_ACTION: 'CALL_ACTION',             // PC -> phone: { action: ANSWER|REJECT|END }
  CALL_ACTION_RESULT: 'CALL_ACTION_RESULT',// phone -> PC: { action, ok, error }
  CALL_RECENTS_REQ: 'CALL_RECENTS_REQ',   // PC -> phone: { limit }
  CALL_RECENTS: 'CALL_RECENTS',           // phone -> PC: { calls: [...] }

  // App catalog: label + icon for every launchable app on the phone
  APP_CATALOG_REQ: 'APP_CATALOG_REQ',     // PC -> phone
  APP_CATALOG: 'APP_CATALOG',             // phone -> PC: { apps: [{ pkg, label, icon }] }
  APP_LAUNCH: 'APP_LAUNCH',               // PC -> phone: { pkg }
  APP_LAUNCH_RESULT: 'APP_LAUNCH_RESULT', // phone -> PC: { ok, pkg, error? }

  // Notifications mirroring
  NOTIF_POSTED: 'NOTIF_POSTED',           // phone -> PC: { key, appName, title, text, postTime, canReply, isOngoing }
  NOTIF_REMOVED: 'NOTIF_REMOVED',         // phone -> PC: { key }
  NOTIF_DISMISS: 'NOTIF_DISMISS',         // PC -> phone: { key }
  NOTIF_REPLY: 'NOTIF_REPLY',             // PC -> phone: { key, text }
  NOTIF_LIST: 'NOTIF_LIST',               // phone -> PC: full active snapshot

  // SMS / Messages
  SMS_REQ: 'SMS_REQ',                     // PC -> phone: { op: threads|messages, address?, limit?, offset? }
  SMS_RESP: 'SMS_RESP',                   // phone -> PC: { op, threads?|messages?, error? }
  SMS_SEND: 'SMS_SEND',                   // PC -> phone: { address, text }
  SMS_SENT: 'SMS_SENT',                   // phone -> PC: { ok, address, error? }

  // Phone media browser
  MEDIA_REQ: 'MEDIA_REQ',                 // PC -> phone: { op: list|get, kind, search?, sort?, limit?, offset?, id? }
  MEDIA_LIST: 'MEDIA_LIST',                 // phone -> PC: { kind, total, items: [...] }
  MEDIA_THUMB: 'MEDIA_THUMB',               // phone -> PC: { id, jpegBase64 }
  MEDIA_RESULT: 'MEDIA_RESULT',             // phone -> PC: { op: delete|share, ok, ids?|id?, error? }
  // phone -> PC: share-target list for PC-side sharing (the phone never shows
  // its share sheet for an action started on the PC).
  MEDIA_SHARE_TARGETS: 'MEDIA_SHARE_TARGETS', // { ids, targets:[{pkg,label,icon?}], error? }
  CALL_LOG_DELETE: 'CALL_LOG_DELETE',        // PC -> phone: { id }
  CALL_LOG_DELETE_RESULT: 'CALL_LOG_DELETE_RESULT', // phone -> PC: { id, ok, error? }

  // Phone audio/volume control
  PHONE_VOLUME: 'PHONE_VOLUME',           // PC -> phone: { action: UP|DOWN|MUTE|UNMUTE|SET, value? }
  PHONE_VOLUME_STATE: 'PHONE_VOLUME_STATE',// phone -> PC: { level, max, muted }

  // Phone battery status (periodic push)
  PHONE_BATTERY: 'PHONE_BATTERY',         // phone -> PC: { level, charging, temperatureC }

  // Diagnostics
  DIAG_REQ: 'DIAG_REQ',                   // PC -> phone: {}
  DIAG_RESP: 'DIAG_RESP'                  // phone -> PC: { permissions, serviceStates, ... }
};// ===================== Binary video frames =====================
// Video frames ride a separate binary channel so they never touch JSON.
// The old path (base64 -> JSON.stringify -> text frame -> Gson parse -> base64
// decode) inflated every frame by ~33% and burned CPU on escaping on BOTH ends.
//
// Layout (little-endian):
//   [0]      u8   kind   - BinaryFrame.PC_SCREEN | WEBCAM | PHONE_AUDIO | PHONE_SCREEN
//   [1..4]   u32  seq    - monotonically increasing frame counter
//   [5..6]   u16  width  (audio kind: sample rate)
//   [7..8]   u16  height (audio kind: channel count)
//   [9..]    raw JPEG bytes, or raw PCM16LE for PHONE_AUDIO
const BinaryFrame = {
  PC_SCREEN: 1,
  WEBCAM: 2,
  PHONE_AUDIO: 3,
  // Phone screen mirror (phone -> PC). Moved off base64-in-JSON: 33% less
  // bytes, no JSON/Gson parse, and the phone drops frames under backpressure
  // instead of queueing a stale backlog.
  PHONE_SCREEN: 4,
  // H.264 mirror: a codec-config frame (SPS/PPS) and encoded access units.
  // Native resolution at 60 fps needs ~9 Mbit/s, which JPEG frames could never
  // fit (150-250 KB each), so the phone hardware-encodes instead.
  PHONE_H264_CONFIG: 5,
  PHONE_H264: 6
};
const BINARY_HEADER_BYTES = 9;

/** Build a binary frame message ready for ws.send(). */
function encodeBinaryFrame(kind, seq, width, height, jpeg) {
  const body = Buffer.isBuffer(jpeg) ? jpeg : Buffer.from(jpeg);
  const buf = Buffer.allocUnsafe(BINARY_HEADER_BYTES + body.length);
  buf.writeUInt8(kind, 0);
  buf.writeUInt32LE(seq >>> 0, 1);
  buf.writeUInt16LE(width & 0xffff, 5);
  buf.writeUInt16LE(height & 0xffff, 7);
  body.copy(buf, BINARY_HEADER_BYTES);
  return buf;
}

/** Parse a binary frame; returns null if the buffer is too short. */
function decodeBinaryFrame(buf) {
  if (!buf || buf.length < BINARY_HEADER_BYTES) return null;
  return {
    kind: buf.readUInt8(0),
    seq: buf.readUInt32LE(1),
    width: buf.readUInt16LE(5),
    height: buf.readUInt16LE(7),
    jpeg: buf.subarray(BINARY_HEADER_BYTES)
  };
}

/**
 * Creates a formatted envelope message.
 * @param {string} type 
 * @param {object} payload 
 * @returns {object} 
 */
function createMessage(type, payload = {}) {
  return {
    v: PROTOCOL_VERSION,
    type,
    id: crypto.randomUUID(),
    ts: Date.now(),
    payload
  };
}

/**
 * Validates an incoming wire message.
 * Returns { valid: boolean, error?: string, message?: object }
 */
function parseAndValidateMessage(raw) {
  let parsed;
  try {
    parsed = typeof raw === 'string' ? JSON.parse(raw) : JSON.parse(raw.toString('utf8'));
  } catch (err) {
    return { valid: false, error: 'JSON parse error' };
  }

  if (typeof parsed !== 'object' || parsed === null) {
    return { valid: false, error: 'Message must be a JSON object' };
  }

  if (parsed.v !== PROTOCOL_VERSION) {
    return { valid: false, error: `Unsupported protocol version ${parsed.v}` };
  }

  if (typeof parsed.type !== 'string' || !MessageType[parsed.type]) {
    return { valid: false, error: `Invalid message type: ${parsed.type}` };
  }

  if (typeof parsed.id !== 'string' || parsed.id.length === 0) {
    return { valid: false, error: 'Missing or invalid message id' };
  }

  if (typeof parsed.ts !== 'number' || isNaN(parsed.ts)) {
    return { valid: false, error: 'Missing or invalid message timestamp' };
  }

  if (typeof parsed.payload !== 'object' || parsed.payload === null) {
    return { valid: false, error: 'Payload must be an object' };
  }

  return { valid: true, message: parsed };
}

module.exports = {
  PROTOCOL_VERSION,
  MessageType,
  BinaryFrame,
  BINARY_HEADER_BYTES,
  encodeBinaryFrame,
  decodeBinaryFrame,
  createMessage,
  parseAndValidateMessage
};
