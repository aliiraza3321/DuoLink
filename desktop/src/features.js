/**
 * VORTEX - Feature state hub (desktop side).
 *
 * Holds the desktop-side state for the V2 features:
 *   - Notifications mirroring (active list, per-app filter, pause, unread counts)
 *   - SMS threads/messages cache
 *   - Phone media browser (list cache, thumbnail LRU)
 *   - Call state + recents
 *   - Phone battery
 *
 * All state is in-memory only (never written to disk): notification and SMS
 * content are personal data and must not persist on the PC beyond the session.
 * The only disk writes are the user's *preferences* (which apps are muted,
 * sync paused on/off) - stored without any message content.
 *
 * The hub is the single source of truth that sockets.js writes into and the
 * renderer reads from via IPC snapshots. Every mutation goes through a method
 * so duplicates are prevented centrally (phone may re-send after reconnect).
 */

class FeatureHub {
  constructor({ savePreferences, loadPreferences } = {}) {
    // Preferences survive restarts (no content, only app ids + booleans)
    this._savePrefs = savePreferences || (() => {});
    const loaded = (loadPreferences && loadPreferences()) || {};
    this.paused = !!loaded.paused;                       // pause notification sync
    this.mutedApps = new Set(loaded.mutedApps || []);    // package names with mirroring off
    this.hiddenCount = 0;                                // DuoLink's own alerts hidden on the phone

    // --- Notifications (keyed by Android NotificationListenerService key) ---
    this.notifications = new Map(); // key -> { key, appName, packageName, title, text, postTime, canReply, isOngoing, count }
    this.notifSeq = 0;

    // --- SMS ---
    this.smsThreads = [];           // [{ address, snippet, unread, count, lastDate }]
    this.smsMessages = new Map();   // address -> [ { id, address, body, date, type } ]
    this.smsSupported = null;       // null=unknown, true/false once phone answers

    // --- Media browser ---
    this.mediaItems = [];           // last list result
    this.mediaKind = 'images';
    this.thumbCache = new Map();    // id -> dataUrl  (LRU, ~120 entries)
    this.mediaSupported = null;

    // --- Calls ---
    this.callState = { state: 'IDLE', number: '', name: '', direction: '' };
    this.callRecents = [];
    this.callsError = null;
    this.callsSupported = null;

    // --- Battery / device status ---
    this.phoneBattery = null;       // { level, charging, temperatureC }

    // --- Remote PC screen ---
    this.pcScreenActive = false;

    // --- Webcam ---
    this.webcamActive = false;
  }

  // ============ Preferences ============

  _persistPrefs() {
    try {
      this._savePrefs({
        paused: this.paused,
        mutedApps: Array.from(this.mutedApps)
      });
    } catch (_) { /* preferences are best-effort */ }
  }

  setPaused(paused) {
    this.paused = !!paused;
    if (this.paused) {
      // Pausing hides new arrivals but keeps existing list; clearing avoids
      // stale personal data sitting on screen while paused.
      this.notifications.clear();
    }
    this._persistPrefs();
    return { paused: this.paused };
  }

  setAppMuted(packageName, muted) {
    if (!packageName) return { mutedApps: Array.from(this.mutedApps) };
    if (muted) {
      this.mutedApps.add(packageName);
      // Remove any already-mirrored notifications from this app
      for (const [key, n] of this.notifications) {
        if (n.packageName === packageName) this.notifications.delete(key);
      }
    } else {
      this.mutedApps.delete(packageName);
    }
    this._persistPrefs();
    return { mutedApps: Array.from(this.mutedApps) };
  }

  // ============ Notifications ============

  /** Returns true if the notification was accepted (should be forwarded to renderer). */
  notifPosted(payload) {
    if (this.paused) return false;
    if (!payload || typeof payload.key !== 'string') return false;
    if (payload.packageName && this.mutedApps.has(payload.packageName)) return false;

    const existing = this.notifications.get(payload.key);
    const entry = {
      key: payload.key,
      appName: String(payload.appName || payload.packageName || 'App').slice(0, 80),
      packageName: String(payload.packageName || '').slice(0, 160),
      title: String(payload.title || '').slice(0, 300),
      text: String(payload.text || '').slice(0, 2000),
      postTime: typeof payload.postTime === 'number' ? payload.postTime : Date.now(),
      canReply: !!payload.canReply,
      isOngoing: !!payload.isOngoing,
      // Group duplicates: same key re-posted increments a count badge
      count: existing ? existing.count + 1 : 1,
      seq: ++this.notifSeq
    };
    this.notifications.set(entry.key, entry);
    this._trimNotifications(200);
    return entry;
  }

  notifRemoved(payload) {
    if (!payload || typeof payload.key !== 'string') return false;
    return this.notifications.delete(payload.key);
  }

  clearAllNotifications() {
    this.notifications.clear();
  }

  /** DuoLink's own + ongoing alerts suppressed on the phone (spec 6.2.5). */
  setNotifHiddenCount(n) {
    this.hiddenCount = n | 0;
  }

  _trimNotifications(max) {
    if (this.notifications.size <= max) return;
    const sorted = Array.from(this.notifications.values()).sort((a, b) => a.postTime - b.postTime);
    const drop = this.notifications.size - max;
    for (let i = 0; i < drop; i++) this.notifications.delete(sorted[i].key);
  }

  /** Snapshot for the renderer. */
  notificationsSnapshot() {
    const items = Array.from(this.notifications.values()).sort((a, b) => b.postTime - a.postTime);
    const unreadByApp = {};
    for (const n of items) {
      const app = n.appName || n.packageName || 'App';
      unreadByApp[app] = (unreadByApp[app] || 0) + 1;
    }
    return {
      paused: this.paused,
      mutedApps: Array.from(this.mutedApps),
      total: items.length,
      unreadByApp,
      hiddenCount: this.hiddenCount | 0,
      items
    };
  }

  // ============ SMS ============

  setSmsSupported(ok) { this.smsSupported = !!ok; }

  smsResponse(payload) {
    if (!payload) return;
    if (payload.error) { this.smsSupported = false; return; }
    if (payload.op === 'threads' && Array.isArray(payload.threads)) {
      this.smsSupported = true;
      this.smsThreads = payload.threads.slice(0, 500);
    } else if (payload.op === 'messages' && typeof payload.address === 'string' && Array.isArray(payload.messages)) {
      this.smsSupported = true;
      // Merge by id to survive pagination without duplicating
      const existing = this.smsMessages.get(payload.address) || [];
      const byId = new Map(existing.map((m) => [m.id, m]));
      for (const m of payload.messages) byId.set(m.id, m);
      const merged = Array.from(byId.values()).sort((a, b) => a.date - b.date);
      this.smsMessages.set(payload.address, merged.slice(-2000));
    }
  }

  markThreadRead(address) {
    const t = this.smsThreads.find((x) => x.address === address);
    if (t) t.unread = 0;
  }

  smsSnapshot(address) {
    return {
      supported: this.smsSupported,
      threads: this.smsThreads,
      messages: address ? (this.smsMessages.get(address) || []) : null,
      totalUnread: this.smsThreads.reduce((n, t) => n + (t.unread || 0), 0)
    };
  }

  // ============ Media ============

  setMediaSupported(ok) { this.mediaSupported = !!ok; }

  mediaList(payload) {
    if (!payload || !Array.isArray(payload.items)) return null;
    this.mediaSupported = true;
    this.mediaKind = payload.kind || this.mediaKind;
    this.mediaItems = payload.items;
    // Surface permission errors from the phone (e.g. READ_MEDIA_* not granted)
    return {
      kind: this.mediaKind,
      total: payload.total | 0,
      items: this.mediaItems,
      error: payload.error ? String(payload.error) : null
    };
  }

  mediaThumb(payload) {
    if (!payload || typeof payload.id !== 'string' || typeof payload.jpegBase64 !== 'string') return;
    this.thumbCache.set(payload.id, `data:image/jpeg;base64,${payload.jpegBase64}`);
    // LRU trim
    while (this.thumbCache.size > 160) {
      const first = this.thumbCache.keys().next().value;
      this.thumbCache.delete(first);
    }
  }

  mediaSnapshot() {
    return { supported: this.mediaSupported, kind: this.mediaKind, items: this.mediaItems };
  }

  // ============ Calls ============

  setCallsSupported(ok) { this.callsSupported = !!ok; }

  callStateUpdate(payload) {
    if (!payload || typeof payload.state !== 'string') return null;
    this.callsSupported = true;
    this.callState = {
      state: ['RINGING', 'OFFHOOK', 'IDLE'].includes(payload.state) ? payload.state : 'IDLE',
      number: String(payload.number || ''),
      name: String(payload.name || ''),
      direction: String(payload.direction || '')
    };
    return this.callState;
  }

  callRecentsUpdate(payload) {
    if (!payload || !Array.isArray(payload.calls)) return null;
    this.callsSupported = true;
    this.callsError = payload.error ? String(payload.error) : null;
    this.callRecents = payload.calls.slice(0, 200);
    return { recents: this.callRecents, error: this.callsError };
  }

  callsSnapshot() {
    return {
      supported: this.callsSupported,
      state: this.callState,
      recents: this.callRecents,
      recentsError: this.callsError
    };
  }

  // ============ Battery ============

  batteryUpdate(payload) {
    if (!payload || typeof payload.level !== 'number') return null;
    this.phoneBattery = {
      level: Math.max(0, Math.min(100, Math.round(payload.level))),
      charging: !!payload.charging,
      // Phone already converts EXTRA_TEMPERATURE (tenths of °C) to °C once —
      // do NOT divide again here (that produced "3.6 °C" readings).
      temperatureC: typeof payload.temperatureC === 'number' ? Math.round(payload.temperatureC * 10) / 10 : null
    };
    return this.phoneBattery;
  }

  // ============ Phone gone: drop every phone-sourced cache ============
  //
  // None of this data is true once the phone disconnects. Leaving it in the
  // hub means a renderer that boots (or re-hydrates) afterwards would show a
  // battery reading, a media list and SMS threads from a phone that is no
  // longer there — which is exactly the "stale data" bug.
  clearPhoneData() {
    this.smsThreads = [];
    this.smsMessages.clear();   // Map: address -> messages
    this.smsSupported = null;

    this.mediaItems = [];
    this.mediaSupported = null;
    this.thumbCache.clear();

    this.callState = { state: 'IDLE', number: '', name: '', direction: '' };
    this.callRecents = [];
    this.callsError = null;
    this.callsSupported = null;

    this.phoneBattery = null;
    this.pcScreenActive = false;
    this.webcamActive = false;
    this.notifications.clear();
  }

  // ============ One-shot snapshot for renderer boot ============

  fullSnapshot() {
    return {
      notifications: this.notificationsSnapshot(),
      sms: this.smsSnapshot(null),
      media: this.mediaSnapshot(),
      calls: this.callsSnapshot(),
      phoneBattery: this.phoneBattery,
      pcScreenActive: this.pcScreenActive,
      webcamActive: this.webcamActive
    };
  }
}

module.exports = { FeatureHub };
