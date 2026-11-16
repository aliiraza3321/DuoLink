/**
 * PhonePC Pair - Hardened Electron Preload Script
 * Strict context isolation, no node globals exposed, sanitized IPC channels.
 */

const { contextBridge, ipcRenderer } = require('electron');

// ---- Apply the saved theme BEFORE first paint ----
// Runs before any page script or style is used: the window never flashes the
// wrong theme. System mode is pre-resolved by the main process (nativeTheme).
try {
  const theme = ipcRenderer.sendSync('get-theme-sync');
  if (theme && typeof document !== 'undefined' && document.documentElement) {
    document.documentElement.dataset.theme = theme.palette || (theme.resolved === 'light' ? 'light' : 'dark');
    document.documentElement.dataset.base = theme.base || (theme.resolved === 'light' ? 'light' : 'dark');
    document.documentElement.dataset.accent = theme.accent || 'violet';
    document.documentElement.dataset.reduceMotion = theme.reduceMotion ? '1' : '0';
  }
} catch (_) { /* no main process (preview) — keep dark default */ }

const ALLOWED_RECEIVE_CHANNELS = [
  'pending-pairing-request',
  'session-status-change',
  'chat-message-received',
  'clipboard-push-received',
  'file-transfer-start',
  'file-transfer-progress',
  'file-transfer-complete',
  'file-transfer-error',
  'file-transfer-cancelled',
  'kill-switch-triggered',
  'phone-screen-frame',
  // H.264 mirror channel: config (SPS/PPS) + encoded access units. Without
  // these entries ipcRenderer.on() drops the events and the pane stays blank.
  'phone-h264-config',
  'phone-h264-frame',
  'phone-audio-chunk',
  'phone-control-status',
  'phone-power-result',
  // V2 features
  'notif-posted',
  'notif-removed',
  'notif-list',
  'sms-response',
  'sms-sent',
  'media-list',
  'media-thumb',
  'media-result',
  'media-share-targets',
  'call-state',
  'call-recents',
  'app-catalog',
  'app-launch-result',
  'app-window-frame',
  'app-window-closed',
  'adb-pair-result',
  'call-action-result',
  'call-log-delete-result',
  'webcam-status',
  'webcam-frame',
  'phone-volume-state',
  'phone-battery',
  'diag-response',
  'theme-changed',
  'link-latency',
  // Offscreen screen-capture window (mirror) reporting an unrecoverable failure
  'pc-screen-error',
  // In-app mirror switching to/from its pop-out window
  'screen-window-state'
];

contextBridge.exposeInMainWorld('phonePcApi', {
  // Network & Pairing state
  getNetworkInfo: () => ipcRenderer.invoke('get-network-info'),
  getSessionStatus: () => ipcRenderer.invoke('get-session-status'),
  onScreenWindowState: (cb) => ipcRenderer.on('screen-window-state', (_e, v) => cb(v)),
  healthCheck: () => ipcRenderer.invoke('health-check'),
  getSettings: () => ipcRenderer.invoke('get-settings'),
  saveSettings: (patch) => ipcRenderer.invoke('save-settings', patch),

  // Theme (Light / Dark / System + accent + reduce motion)
  getTheme: () => ipcRenderer.invoke('get-theme'),
  setTheme: (patch) => ipcRenderer.invoke('set-theme', patch),
  getAppInfo: () => ipcRenderer.invoke('get-app-info'),
  generatePairingToken: () => ipcRenderer.invoke('generate-pairing-token'),
  resetRateLimit: () => ipcRenderer.invoke('reset-rate-limit'),
  measureLatency: () => ipcRenderer.invoke('measure-latency'),
  openLog: () => ipcRenderer.invoke('open-log'),
  checkUpdates: () => ipcRenderer.invoke('check-updates'),
  approvePairing: (pendingId) => ipcRenderer.invoke('approve-pairing', pendingId),
  rejectPairing: (pendingId) => ipcRenderer.invoke('reject-pairing', pendingId),
  disconnectSession: (sessionId) => ipcRenderer.invoke('disconnect-session', sessionId),
  getTrustedDevices: () => ipcRenderer.invoke('get-trusted-devices'),
  revokeDevice: (deviceId) => ipcRenderer.invoke('revoke-device', deviceId),

  // Phase 2: Chat, Clipboard & Files
  sendChatMessage: (text) => ipcRenderer.invoke('send-chat-message', text),
  getClipboard: () => ipcRenderer.invoke('get-clipboard'),
  setClipboard: (text) => ipcRenderer.invoke('set-clipboard', text),
  sendClipboardToPhone: (text) => ipcRenderer.invoke('send-clipboard-to-phone', text),
  openReceivedFolder: () => ipcRenderer.invoke('open-received-folder'),
  selectAndSendFile: () => ipcRenderer.invoke('select-and-send-file'),
  sendDroppedFile: (filePath) => ipcRenderer.invoke('send-dropped-file', filePath),
  transferHistory: () => ipcRenderer.invoke('transfer-history'),
  setDownloadDir: () => ipcRenderer.invoke('set-download-dir'),
  getDownloadDir: () => ipcRenderer.invoke('get-download-dir'),

  // Phone file access: the folders the phone's browser may reach
  getSharedFolders: () => ipcRenderer.invoke('get-shared-folders'),
  addSharedFolder: () => ipcRenderer.invoke('add-shared-folder'),
  removeSharedFolder: (folder) => ipcRenderer.invoke('remove-shared-folder', folder),
  setFileAccess: (on) => ipcRenderer.invoke('set-file-access', on),

  // Phase 3: Control permissions, Media & Kill switch
  setAllowControl: (allowed) => ipcRenderer.invoke('set-allow-control', allowed),
  getControlStatus: () => ipcRenderer.invoke('get-control-status'),
  triggerMediaAction: (action) => ipcRenderer.invoke('trigger-media-action', action),
  triggerSystemAction: (action) => ipcRenderer.invoke('trigger-system-action', action),
  findPhone: (on) => ipcRenderer.invoke('find-phone', on),

  // Phase 4: Phone Control & Screen Stream
  sendPhoneGesture: (gestureData) => ipcRenderer.invoke('send-phone-gesture', gestureData),
  sendPhoneKeyAction: (action) => ipcRenderer.invoke('send-phone-key-action', action),
  // Type on Phone: show/hide the phone keyboard and inject text into its
  // focused field ({ op: show|hide|append|delete|replace, text? }).
  sendPhoneTypeText: (payload) => ipcRenderer.invoke('send-phone-type-text', payload),
  phonePower: (action) => ipcRenderer.invoke('phone-power', action),
  toggleScreenStream: (start) => ipcRenderer.invoke('toggle-screen-stream', start),
  openScreenWindow: () => ipcRenderer.invoke('open-screen-window'),
  openAppWindow: (pkg, label) => ipcRenderer.invoke('open-app-window', { pkg, label }),
  saveShotImage: (dataUrl) => ipcRenderer.invoke('mirror-save-image', dataUrl),
  copyShotImage: (dataUrl) => ipcRenderer.invoke('mirror-copy-image', dataUrl),
  saveShotVideo: (bytes) => ipcRenderer.invoke('mirror-save-video', bytes),
  launchAppInWindow: (pkg, label) => ipcRenderer.invoke('launch-app-in-window', { pkg, label }),
  appWindowInput: (session, action, x, y) => ipcRenderer.invoke('app-window-input', { session, action, x, y }),
  closeAppWindow: (session) => ipcRenderer.invoke('app-window-close', session),
  pairPhoneAdb: (code) => ipcRenderer.invoke('adb-pair-phone', code),
  // Pop-out viewer: ask main for the cached last frame so the window paints
  // its picture immediately instead of opening blank.
  screenWindowReady: () => ipcRenderer.invoke('screen-window-ready'),
  minimizeScreenWindow: () => ipcRenderer.invoke('screen-window-minimize'),
  maximizeScreenWindow: () => ipcRenderer.invoke('screen-window-maximize'),

  // V2: Feature hub
  featureSnapshot: () => ipcRenderer.invoke('feature-snapshot'),

  // V2: Notifications
  notifSetPaused: (paused) => ipcRenderer.invoke('notif-set-paused', paused),
  notifSetAppMuted: (packageName, muted) => ipcRenderer.invoke('notif-set-app-muted', packageName, muted),
  notifClearAll: () => ipcRenderer.invoke('notif-clear-all'),
  notifDismiss: (key) => ipcRenderer.invoke('notif-dismiss', key),
  notifReply: (key, text) => ipcRenderer.invoke('notif-reply', { key, text }),

  // V2: SMS / Messages
  smsRequest: (req) => ipcRenderer.invoke('sms-request', req),
  smsSend: (address, text) => ipcRenderer.invoke('sms-send', { address, text }),
  smsMarkRead: (address) => ipcRenderer.invoke('sms-mark-read', address),

  // V2: Phone media browser
  mediaRequest: (req) => ipcRenderer.invoke('media-request', req),
  mediaThumb: (id) => ipcRenderer.invoke('media-thumb', id),
  // opts: { preview?: boolean, name?: string } — preview transfers land in a
  // temp cache instead of the Received folder (nothing is saved until Download).
  mediaGet: (id, opts) => ipcRenderer.invoke('media-get', id, opts || {}),
  savePreviewFile: (filePath) => ipcRenderer.invoke('save-preview-file', filePath),
  openExternal: (url) => ipcRenderer.invoke('open-external-url', url),
  mediaDelete: (ids) => ipcRenderer.invoke('media-delete', ids),
  openReceivedFile: (filePath) => ipcRenderer.invoke('open-received-file', filePath),
  mediaFileUrl: (filePath) => ipcRenderer.invoke('media-file-url', filePath),
  // PC-side share picker: ask the phone who can receive, then share to one app.
  mediaShareTargets: (req) => ipcRenderer.invoke('media-share-targets', req),
  mediaShare: (req) => ipcRenderer.invoke('media-share', req),

  // V2: Calls
  callAction: (action, number) => ipcRenderer.invoke('call-action', number ? { action, number } : action),
  callRecents: () => ipcRenderer.invoke('call-recents'),
  appCatalog: () => ipcRenderer.invoke('app-catalog'),
  launchPhoneApp: (pkg) => ipcRenderer.invoke('app-launch', pkg),
  callLogDelete: (id) => ipcRenderer.invoke('call-log-delete', id),

  // Phone apps over Wireless debugging (adb)
  adbStatus: () => ipcRenderer.invoke('adb-status'),
  adbPair: (addr, code) => ipcRenderer.invoke('adb-pair', { addr, code }),
  adbConnect: (addr) => ipcRenderer.invoke('adb-connect', { addr }),
  adbDisconnect: (addr) => ipcRenderer.invoke('adb-disconnect', { addr }),
  adbApps: (serial) => ipcRenderer.invoke('adb-apps', { serial }),
  adbLaunch: (serial, pkg) => ipcRenderer.invoke('adb-launch', { serial, pkg }),

  // V2: Phone volume
  phoneVolume: (action, value) => ipcRenderer.invoke('phone-volume', { action, value }),

  // V2: Diagnostics & webcam & remote screen
  diagRequest: () => ipcRenderer.invoke('diag-request'),
  webcamInfo: () => ipcRenderer.invoke('webcam-info'),
  webcamStart: (opts) => ipcRenderer.invoke('webcam-start', opts),
  webcamTorch: (on) => ipcRenderer.invoke('webcam-torch', on),
  webcamStop: () => ipcRenderer.invoke('webcam-stop'),
  webcamPreviewFallback: (enabled) => ipcRenderer.invoke('webcam-preview-fallback', !!enabled),
  pcScreenStatus: () => ipcRenderer.invoke('pc-screen-status'),
  pcScreenStop: () => ipcRenderer.invoke('pc-screen-stop'),

  // Event Subscription
  on: (channel, callback) => {
    if (ALLOWED_RECEIVE_CHANNELS.includes(channel)) {
      const subscription = (_event, ...args) => callback(...args);
      ipcRenderer.on(channel, subscription);
      return () => ipcRenderer.removeListener(channel, subscription);
    }
  }
});
