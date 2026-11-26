/**
 * PhonePC Pair - Main Electron Process
 * Hardened security, TLS WSS server initialization, tray management, global hotkeys, IPC handlers.
 */

const { app, BrowserWindow, ipcMain, Tray, Menu, globalShortcut, dialog, shell, clipboard, nativeImage, desktopCapturer, screen, nativeTheme } = require('electron');
const path = require('path');
const fs = require('fs');

const { getLocalLanIp } = require('./src/network');
const { getOrCreateTlsCert, computeSha256Fingerprint } = require('./src/cert');
const { PairingManager } = require('./src/pairing');
const { FileManager } = require('./src/files');
const { PcFileSystem } = require('./src/pcfs');
const { PcControlManager } = require('./src/control');
const { WssServer } = require('./src/sockets');
const { JarvisManager } = require('./src/jarvis');
const { MessageType, createMessage } = require('./src/protocol');
const { FeatureHub } = require('./src/features');
const { RemotePcStreamer } = require('./src/remotepc');
const { WebcamHub } = require('./src/webcam');
const { WirelessAdb } = require('./src/wirelessadb');

// Enforce single instance so clicking shortcut brings existing window to front
const gotTheLock = app.requestSingleInstanceLock();
if (!gotTheLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (!mainWindow || mainWindow.isDestroyed()) {
      if (app.isReady()) createWindow();
      return;
    }
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.show();
    mainWindow.focus();
  });
}

const APP_NAME = 'DuoLink';

// duo-media:// — lets the Gallery open images/videos/audio INSIDE DuoLink
// (a lightbox in the app) instead of shelling out to an external player.
// Registered before app ready; the handler below only ever serves files from
// the configured "DuoLink Received" folder.
const { protocol } = require('electron');
protocol.registerSchemesAsPrivileged([
  { scheme: 'duo-media', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true } }
]);

// Log to a file so startup problems are never silent.
// Bounded on purpose: the file is capped at 2 MB with a single .old rotation,
// so an app left running for weeks can never turn the log into unbounded disk
// churn (the size is re-checked at most once a minute; every write is one
// append). State-change events only — no per-frame or per-tick lines.
let logSizeAt = 0;
let logStatAt = 0;
function logLine(...args) {
  try {
    const f = path.join(app.getPath('userData'), 'duolink.log');
    const now = Date.now();
    if (now - logStatAt > 60000) {
      logStatAt = now;
      try { logSizeAt = fs.statSync(f).size; } catch (_) { logSizeAt = 0; }
    }
    const line = `[${new Date().toISOString()}] ${args.join(' ')}\n`;
    if (logSizeAt > 2 * 1024 * 1024) {
      try { fs.renameSync(f, `${f}.old`); } catch (_) {}
      logSizeAt = 0;
    }
    fs.appendFileSync(f, line);
    logSizeAt += line.length;
  } catch (_) {}
}
process.on('uncaughtException', (e) => { logLine('uncaughtException', e && e.stack || e); });
process.on('unhandledRejection', (e) => { logLine('unhandledRejection', e && e.stack || e); });

// Tee console output to duolink.log so the packaged build (which has no visible
// console) is still diagnosable. Only connection/auth/pairing events are logged,
// so volume stays low (no per-frame or per-heartbeat noise).
for (const method of ['log', 'warn', 'error']) {
  const original = console[method].bind(console);
  console[method] = (...args) => {
    original(...args);
    try {
      const line = args
        .map(a => (typeof a === 'string' ? a : (a && a.stack) || JSON.stringify(a)))
        .join(' ');
      logLine(`[${method}]`, line);
    } catch (_) {}
  };
}

let mainWindow = null;
let tray = null;
let wssServer = null;
let jarvis = null;
let pairingManager = null;
let fileManager = null;
let pcfs = null;
let controlManager = null;
let tlsCredentials = null;
let lanIp = null;
let featureHub = null;
let remotePc = null;
let webcamHub = null;
let PORT = 3000;
const APP_ICON = path.join(__dirname, 'renderer', 'assets', 'icon.png');

// ---------------- Themes ----------------
// Palettes live in renderer/tokens.css as [data-theme="..."]. Main only needs
// the window-chrome colours (window background + native title-bar symbols) per
// palette so the frame matches the page on the very first paint. Every palette
// shares one design system, so switching themes recolours the app without
// changing its layout or identity.
const THEME_COLORS = {
  dark:      { bg: '#0a0b0e', text: '#e7e9ef', light: false },
  light:     { bg: '#f6f7f9', text: '#12151f', light: true },
  midnight:  { bg: '#050a16', text: '#e6eefc', light: false },
  ocean:     { bg: '#e9f4fa', text: '#082b3a', light: true },
  cyberpunk: { bg: '#0a0410', text: '#ffe8f7', light: false },
  purple:    { bg: '#f5f0ff', text: '#241338', light: true },
  emerald:   { bg: '#04140f', text: '#e6fbef', light: false },
  graphite:  { bg: '#f0f0ec', text: '#1c1e20', light: true }
};
const THEME_IDS = Object.keys(THEME_COLORS);
const defaultSettings = {
  autoClipboardSync: false,
  startMinimized: false,
  startWithWindows: false,
  minimiseToTray: true,
  theme: 'dark',         // palette id — see THEME_IDS
  followSystem: false,   // follow Windows light/dark instead of a fixed palette
  sharedFolders: null,   // null = first-run defaults; [] = nothing shared
  accent: 'violet',      // 'violet' | 'cyan' | 'green' | 'orange'
  reduceMotion: false
};
const settingsPath = () => path.join(app.getPath('userData'), 'settings.json');
function loadSettings() {
  try {
    if (fs.existsSync(settingsPath())) {
      return JSON.parse(fs.readFileSync(settingsPath(), 'utf8'));
    }
  } catch (_) {}
  return {};
}
function saveSettings(patch) {
  const merged = { ...defaultSettings, ...loadSettings(), ...(patch && typeof patch === 'object' ? patch : {}) };
  fs.writeFileSync(settingsPath(), JSON.stringify(merged, null, 2), 'utf8');
  return merged;
}
/**
 * Palette actually painted right now. Saved palettes win; "Follow Windows"
 * resolves to the light/dark pair; settings written by older builds only have
 * themeMode, which still maps cleanly.
 */
function savedPalette() {
  const s = loadSettings();
  if (s.followSystem) return nativeTheme.shouldUseDarkColors ? 'dark' : 'light';
  if (s.theme && THEME_COLORS[s.theme]) return s.theme;
  if (s.themeMode === 'light') return 'light';
  if (s.themeMode === 'dark') return 'dark';
  return nativeTheme.shouldUseDarkColors ? 'dark' : 'light';
}
/** Resolved palette actually painted right now. */
function resolvedTheme() {
  return savedPalette();
}
function applyThemeToWindow() {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  const resolved = resolvedTheme();
  const c = THEME_COLORS[resolved] || THEME_COLORS.dark;
  try {
    mainWindow.setBackgroundColor(c.bg);
    mainWindow.setTitleBarOverlay({ color: c.bg, symbolColor: c.text, height: 40 });
  } catch (err) { logLine('titlebar overlay error', err && err.message); }
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('theme-changed', themeSnapshot());
  }
}
function themeSnapshot() {
  const s = loadSettings();
  const palette = resolvedTheme();
  const isLight = !!(THEME_COLORS[palette] && THEME_COLORS[palette].light);
  return {
    palette,
    theme: palette,
    // Light/dark chrome family; several palettes share it, palette stays unique.
    base: isLight ? 'light' : 'dark',
    light: isLight,
    mode: s.followSystem ? 'system' : (isLight ? 'light' : 'dark'),
    resolved: isLight ? 'light' : 'dark',
    followSystem: !!s.followSystem,
    accent: s.accent || defaultSettings.accent,
    reduceMotion: !!s.reduceMotion
  };
}
// System theme flips live: update the title bar + tell the renderer.
nativeTheme.on('updated', () => {
  if (loadSettings().followSystem) applyThemeToWindow();
});

function createWindow() {
  // Resolve theme BEFORE the window exists so backgroundColor and the
  // title bar overlay match the theme on the very first frame.
  nativeTheme.themeSource = loadSettings().followSystem
    ? 'system'
    : ((THEME_COLORS[resolvedTheme()] || THEME_COLORS.dark).light ? 'light' : 'dark');
  const firstTheme = THEME_COLORS[resolvedTheme()] || THEME_COLORS.dark;

  // Open large enough for the handset previews to read as a real 6.7" phone
  // (they scale with the window, up to 360 x 800 logical px), but never larger
  // than the display's work area.
  const workArea = screen.getPrimaryDisplay().workAreaSize;
  // Use the whole work area: on a 1366x768 laptop the old 1440x940 target was
  // clamped to 668 px tall, which is exactly what kept the phone preview small.
  const winW = Math.max(920, Math.min(1600, workArea.width));
  const winH = Math.max(620, Math.min(1040, workArea.height));

  mainWindow = new BrowserWindow({
    width: winW,
    height: winH,
    minWidth: 920,
    minHeight: 620,
    show: true,
    backgroundColor: firstTheme.bg,
    title: APP_NAME,
    icon: APP_ICON,
    autoHideMenuBar: true,
    // Hidden native title bar + colour-matched window controls overlay.
    // The renderer draws a drag strip (.titlebar) for moving the window.
    titleBarStyle: 'hidden',
    titleBarOverlay: { color: firstTheme.bg, symbolColor: firstTheme.text, height: 40 },
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      // The webcam recorder paints the preview into a canvas every animation
      // tick; a throttled (occluded or minimised) window would record a
      // slideshow of one frame per second instead of the camera.
      backgroundThrottling: false
    }
  });

  mainWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'));

  mainWindow.once('ready-to-show', () => {
    mainWindow.show();
    mainWindow.focus();
  });

  // Security hardening: Block external window opening and unwanted navigation
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('https:') || url.startsWith('http:')) {
      shell.openExternal(url);
    }
    return { action: 'deny' };
  });

  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (!url.startsWith('file://')) {
      event.preventDefault();
    }
  });

  // Least privilege: the main renderer needs no device permission at all - the
  // camera and microphone belong to the phone, files are chosen through main
  // process dialogs. Only clipboard writes (copy pairing code / URLs) are
  // granted to it; every other request is refused outright.
  //
  // Desktop capture is the one exception, and it is scoped to the offscreen
  // capture page (capture/capture.html): that page is created by this process,
  // never navigates and holds no remote content, which is why it can be
  // identified by URL. Granting 'media' / 'display-capture' to the whole
  // session instead would let any future renderer page read the screen.
  const ALLOWED_PERMISSIONS = new Set(['clipboard-sanitized-write']);
  const mainSession = mainWindow.webContents.session;
  const isCapturePage = (wc) => {
    try {
      const url = wc && wc.getURL ? wc.getURL() : '';
      return !!url && decodeURIComponent(url).endsWith('/capture/capture.html');
    } catch (_) {
      return false;
    }
  };
  mainSession.setPermissionRequestHandler((wc, permission, callback) => {
    if ((permission === 'display-capture' || permission === 'media') && isCapturePage(wc)) return callback(true);
    callback(ALLOWED_PERMISSIONS.has(permission));
  });
  mainSession.setPermissionCheckHandler((wc, permission) => {
    if ((permission === 'display-capture' || permission === 'media') && isCapturePage(wc)) return true;
    return ALLOWED_PERMISSIONS.has(permission);
  });
  // Chromium >= 111 routes desktop video through getDisplayMedia instead of the
  // legacy chromeMediaSource constraint. Answer it with the primary screen, but
  // only for our capture page - every other caller is refused.
  mainSession.setDisplayMediaRequestHandler(async (request, callback) => {
    try {
      const frameUrl = request && request.frame && request.frame.url ? request.frame.url : '';
      if (!decodeURIComponent(frameUrl).endsWith('/capture/capture.html')) return callback({});
      const sources = await desktopCapturer.getSources({
        types: ['screen'],
        thumbnailSize: { width: 0, height: 0 },
        fetchWindowIcons: false
      });
      const primary = screen.getPrimaryDisplay();
      const source =
        sources.find((s) => s.display_id === String(primary.id)) || sources[0];
      if (!source) return callback({});
      callback({ video: source });
    } catch (_) {
      callback({});
    }
  });
  mainWindow.webContents.on('will-attach-webview', (event) => event.preventDefault());

  mainWindow.on('close', (event) => {
    const minimiseToTray = loadSettings().minimiseToTray !== false;
    if (!app.isQuitting && tray && minimiseToTray) {
      event.preventDefault();
      mainWindow.hide();
    }
  });
}

function createTray() {
  let trayIcon = nativeImage.createFromPath(path.join(__dirname, 'renderer', 'assets', 'tray.png'));
  if (trayIcon.isEmpty()) trayIcon = nativeImage.createFromPath(APP_ICON);
  tray = new Tray(trayIcon.resize({ width: 16, height: 16 }));

  const contextMenu = Menu.buildFromTemplate([
    { label: APP_NAME, enabled: false },
    { type: 'separator' },
    {
      label: 'Open Window',
      click: () => {
        if (mainWindow) {
          mainWindow.show();
          mainWindow.focus();
        }
      }
    },
    {
      label: 'Kill Switch (Disable Control)',
      accelerator: 'CmdOrCtrl+Alt+Shift+Q',
      click: () => {
        triggerGlobalKillSwitch();
      }
    },
    { type: 'separator' },
    {
      label: 'Exit',
      click: () => {
        app.isQuitting = true;
        app.quit();
      }
    }
  ]);

  tray.setToolTip(`${APP_NAME} - running locally`);
  tray.setContextMenu(contextMenu);
  tray.on('double-click', () => {
    if (mainWindow) {
      mainWindow.show();
      mainWindow.focus();
    }
  });
}

function triggerGlobalKillSwitch() {
  if (controlManager) {
    controlManager.triggerKillSwitch();
  }
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('kill-switch-triggered');
  }
  if (tray) {
    try {
      tray.displayBalloon({
        title: 'Kill Switch Activated',
        content: 'Remote phone-to-PC control has been immediately killed.'
      });
    } catch (_) {}
  }
}

async function streamFileToPhone(filePath) {
  if (!fs.existsSync(filePath)) return { success: false, error: 'File does not exist' };
  const stats = fs.statSync(filePath);
  if (stats.isDirectory()) {
    return { success: false, error: 'Folders are sent as a batch of files - select files inside the folder' };
  }
  const MAX_SEND_BYTES = 5 * 1024 * 1024 * 1024; // 5 GB (spec)
  if (stats.size > MAX_SEND_BYTES) {
    return { success: false, error: 'File exceeds 5 GB maximum size limit' };
  }
  const filename = path.basename(filePath);
  // Small files keep the original buffered path (carries a sha256 digest).
  // Large ones stream straight from disk — a 5GB readFileSync would need 5GB
  // of RAM. The phone validates by size, so the streamed path is size-only.
  if (stats.size <= 100 * 1024 * 1024) {
    const buffer = fs.readFileSync(filePath);
    return streamBufferToPhone(buffer, filename);
  }
  return enqueueOutgoing(() => _streamFileFromDiskNow(filePath, stats.size, filename));
}

async function _streamFileFromDiskNow(filePath, totalSize, filename) {
  const crypto = require('crypto');
  const transferId = crypto.randomUUID();
  const metaMsg = createMessage(MessageType.FILE_META, {
    transferId,
    filename,
    totalSize
  });
  const delivered = wssServer.broadcast(metaMsg);
  if (!delivered) {
    if (fileManager) fileManager.recordOutgoing(transferId, filename, totalSize, 'failed', 'Phone not connected');
    return { success: false, error: 'Phone not connected' };
  }

  const CHUNK_SIZE = 64 * 1024;
  let sent = 0;
  const stream = fs.createReadStream(filePath, { highWaterMark: CHUNK_SIZE });
  try {
    for await (const chunk of stream) {
      const ok = wssServer.broadcast(createMessage(MessageType.FILE_CHUNK, {
        transferId,
        chunkBase64: chunk.toString('base64')
      }));
      if (!ok) {
        if (fileManager) fileManager.recordOutgoing(transferId, filename, totalSize, 'failed', 'Phone disconnected mid-transfer');
        stream.destroy();
        return { success: false, error: 'Phone disconnected mid-transfer' };
      }
      sent += chunk.length;
      // Let the socket flush before reading the next chunk: without this the
      // whole file piles up in the socket buffer and the phone stops reporting.
      await wssServer.waitForDrain();
    }
  } catch (err) {
    if (fileManager) fileManager.recordOutgoing(transferId, filename, totalSize, 'failed', err.message);
    return { success: false, error: err.message };
  }
  if (fileManager) fileManager.recordOutgoing(transferId, filename, sent, 'sent');
  return { success: true, filename, size: sent };
}

// Simple FIFO queue so batch/drag-drop selections send one at a time
// (the wire protocol is one FILE_META at a time per session).
let outgoingChain = Promise.resolve();
function enqueueOutgoing(job) {
  const result = outgoingChain.then(job, job);
  outgoingChain = result.then(() => undefined, () => undefined);
  return result;
}

async function streamBufferToPhone(buffer, filename) {
  return enqueueOutgoing(() => _streamBufferNow(buffer, filename));
}

async function _streamBufferNow(buffer, filename) {
  const crypto = require('crypto');
  const transferId = crypto.randomUUID();
  const sha256 = crypto.createHash('sha256').update(buffer).digest('hex');

  // 1. Send FILE_META (with integrity digest)
  const metaMsg = createMessage(MessageType.FILE_META, {
    transferId,
    filename,
    totalSize: buffer.length,
    sha256
  });
  const delivered = wssServer.broadcast(metaMsg);
  if (!delivered) {
    if (fileManager) fileManager.recordOutgoing(transferId, filename, buffer.length, 'failed', 'Phone not connected');
    return { success: false, error: 'Phone not connected' };
  }

  // 2. Stream chunked (64KB chunks). The socket queue is drained between
  //    chunks so a large file cannot be dumped into memory in one burst.
  const CHUNK_SIZE = 64 * 1024;
  let offset = 0;

  while (offset < buffer.length) {
    const slice = buffer.subarray(offset, offset + CHUNK_SIZE);
    const chunkMsg = createMessage(MessageType.FILE_CHUNK, {
      transferId,
      chunkBase64: slice.toString('base64')
    });
    if (!wssServer.broadcast(chunkMsg)) {
      if (fileManager) fileManager.recordOutgoing(transferId, filename, offset, 'failed', 'Phone disconnected mid-transfer');
      return { success: false, error: 'Phone disconnected mid-transfer' };
    }
    offset += CHUNK_SIZE;
    await wssServer.waitForDrain();
  }

  if (fileManager) fileManager.recordOutgoing(transferId, filename, buffer.length, 'sent');
  return { success: true, filename, size: buffer.length };
}

// ================= Offscreen mirror capture window =================
// The mirror used to snapshot the desktop through desktopCapturer.getSources()
// once per frame; that API re-enumerates every screen and window and re-reads
// its thumbnails on every call, which capped the stream in the low teens and
// is why a healthy local link still looked like a slideshow. Capture now lives
// in this hidden window: the desktop is acquired once as a MediaStream and
// re-encoded from a canvas at the requested rate - the same shape Chrome
// Remote Desktop uses. See capture/renderer.js for the loop itself.
let captureWin = null;
// Destroy timer for the offscreen capture window (see captureAdapter.stop):
// the hidden window is torn down a few seconds after a stream ends instead of
// outliving the stream for the rest of the app's session.
let captureWinDestroyTimer = null;

// Pop-out window holding ONLY the phone screen (Control Phone → Open in Window).
// It gets its frames forwarded straight from the socket layer so the two views
// never fight over the same canvas.
let screenWin = null;
const SCREEN_WIN_EVENTS = new Set(['phone-screen-frame', 'phone-control-status', 'session-status-change', 'phone-h264-config', 'phone-h264-frame']);

// Phone apps opened in windows of their own: package -> BrowserWindow. Each
// window draws the live phone picture and is titled with the app's own name,
// so the app the user picked is the window they get (and re-picking focuses
// the window that is already open instead of stacking another copy).
const appWindows = new Map();

// Last frame the phone sent, kept so a pop-out window can paint its very first
// picture the instant it opens instead of waiting (blank) for the next frame
// and flashing a false "Reconnecting…" in the meantime.
let latestScreenFrame = null;
let latestH264Config = null;   // SPS/PPS for a pop-out opened mid-stream
// Last-seen screen orientation: 'portrait' | 'landscape' | null. A phone that
// rotates into landscape pops its picture into the dedicated window (the
// in-app pane is too cramped for a wide picture).
let lastScreenOrientation = null;
// True while the pop-out is being opened by the rotation watcher, so frames
// arriving mid-open don't schedule a second window.
let screenWinOpening = false;
// Counts frames while the pop-out owns the picture, so the in-app pane gets a
// ~1fps liveness trickle instead of the full-rate stream it cannot paint.
let popoutLivenessTick = 0;

/**
 * Keep the pop-out window at the phone's own aspect ratio so it shows the
 * screen at the phone's shape — no letterboxing chrome. Only acts when the
 * aspect actually changes (first frame, orientation flip), so a manual
 * resize by the user is never fought frame-by-frame.
 *
 * MODULE SCOPE: the frame-forwarding hook in wssOptions (also module scope)
 * calls this. When it lived inside setupIpc() every arriving frame threw
 * ReferenceError *before* the frame was forwarded, so the pop-out window
 * stayed black forever.
 */
let screenWinFitAspect = 0;
function fitScreenWindow(fw, fh) {
  if (!screenWin || screenWin.isDestroyed() || !(fw > 0) || !(fh > 0)) return;
  if (screenWin.isFullScreen()) return;
  const aspect = fw / fh;
  const prev = screenWinFitAspect;
  // Portrait <-> landscape flip (the phone rotated, e.g. a game started):
  // re-fit edge to edge in the new orientation — game-style, no letterbox.
  const flipped = prev > 0 && ((prev < 1) !== (aspect < 1));
  if (screenWin.isMaximized()) {
    if (!flipped) return; // stay maximized while the orientation is stable
    screenWin.unmaximize();
  } else if (Math.abs(aspect - prev) < 0.01) {
    return;
  }
  const wa = screen.getPrimaryDisplay().workArea;
  // Fill as much of the work area as the phone's aspect allows: keep the
  // window exactly the frame's aspect (so the picture has no bars inside the
  // window) while clamping both dimensions to the display.
  let h = Math.min((screenWin.getContentSize()[1] || wa.height), wa.height);
  if (flipped) h = wa.height - 24; // new orientation: grow to the screen
  let w = Math.round(h * aspect);
  if (w > wa.width) {
    w = wa.width;
    h = Math.round(w / aspect);
  }
  if (w > 40 && h > 40) {
    screenWin.setContentSize(w, h, false);
  }
  // After unmaximizing, the restore animation can win the race against the
  // resize above; leaving the stored aspect at 0 makes the next frame re-apply
  // the fit, so the window always converges to the phone's exact aspect.
  screenWinFitAspect = screenWin.isMaximized() ? 0 : aspect;
}

function ensureCaptureWindow() {
  if (captureWin && !captureWin.isDestroyed()) return captureWin;
  captureWin = new BrowserWindow({
    show: false,
    skipTaskbar: true,
    width: 320,
    height: 180,
    webPreferences: {
      preload: path.join(__dirname, 'capture', 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      // Without this, Chromium throttles timers in a hidden window to about
      // one per second and the mirror crawls the moment the app loses focus.
      backgroundThrottling: false
      // No offscreen rendering: frames are read straight off the capture
      // track (see capture/renderer.js), so a software compositor would only
      // burn CPU for a window nobody ever sees.
    }
  });
  captureWin.setMenuBarVisibility(false);
  captureWin.loadFile(path.join(__dirname, 'capture', 'capture.html'));
  captureWin.webContents.on('render-process-gone', (_e, details) => {
    logLine('[RemotePC] capture window gone:', details && details.reason);
    captureWin = null;
  });
  return captureWin;
}

/** Adapter handed to RemotePcStreamer so it never touches a BrowserWindow. */
const captureAdapter = {
  configure(opts) {
    try {
      // A new stream cancels any pending teardown from the previous stop.
      if (captureWinDestroyTimer) {
        clearTimeout(captureWinDestroyTimer);
        captureWinDestroyTimer = null;
      }
      ensureCaptureWindow().webContents.send('capture-configure', opts);
    } catch (e) {
      logLine('[RemotePC] capture configure failed:', e.message);
    }
  },
  stop() {
    if (captureWin && !captureWin.isDestroyed()) {
      try { captureWin.webContents.send('capture-stop'); } catch (_) {}
    }
    // Full teardown, not just a paused loop: the offscreen window carries its
    // own compositor/raster threads, and leaving it hidden after the stream
    // ends was a permanent (if small) CPU/ram cost plus one more window the
    // diagnostics stack tracks. The 4s grace absorbs a quick stop->start
    // toggle; the next configure() simply re-creates the window.
    if (captureWinDestroyTimer) clearTimeout(captureWinDestroyTimer);
    captureWinDestroyTimer = setTimeout(() => {
      captureWinDestroyTimer = null;
      if (captureWin && !captureWin.isDestroyed()) {
        try { captureWin.destroy(); } catch (_) {}
      }
      captureWin = null;
    }, 4000);
    if (captureWinDestroyTimer.unref) captureWinDestroyTimer.unref();
  }
};

// Encoded frames come back from the capture window and are sunk straight into
// the streamer, which owns socket selection, backpressure and the binary header.
ipcMain.on('capture-frame', (_event, frame) => {
  if (!remotePc || !frame || !frame.jpeg) return;
  remotePc.pushFrame(frame.jpeg, frame.width, frame.height);
});

ipcMain.on('capture-error', (_event, message) => {
  const text = String(message || 'Screen capture failed');
  logLine('[RemotePC]', text);
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('pc-screen-error', text);
  }
});

let ipcReady = false;
function setupIpc() {
  if (ipcReady) return;
  ipcReady = true;

  // --- Persisted user preferences (stored as JSON in userData, never secrets) ---
  // Settings helpers (settingsPath / loadSettings / saveSettings / defaultSettings)
  // live at module scope in main.js — see the Theme block above.

  ipcMain.handle('get-settings', () => ({ ...defaultSettings, ...loadSettings() }));
  ipcMain.handle('save-settings', (_event, patch) => {
    try {
      const merged = saveSettings(patch);
      // Start with Windows follows the saved preference.
      if (patch && typeof patch.startWithWindows === 'boolean') {
        try { app.setLoginItemSettings({ openAtLogin: patch.startWithWindows }); } catch (_) {}
      }
      return { success: true, settings: merged };
    } catch (err) {
      return { success: false, error: err.message, settings: { ...defaultSettings, ...loadSettings() } };
    }
  });

  // --- Theme: sync fetch for pre-paint application in preload.js ---
  ipcMain.on('get-theme-sync', (event) => {
    event.returnValue = themeSnapshot();
  });
  ipcMain.handle('get-theme', () => themeSnapshot());
  ipcMain.handle('set-theme', (_event, patch) => {
    const p = patch && typeof patch === 'object' ? patch : {};
    const clean = {};
    const wanted = p.theme || p.palette;
    if (typeof wanted === 'string' && THEME_COLORS[wanted]) {
      clean.theme = wanted;
      clean.followSystem = false;
    }
    // Legacy callers still send { mode } — map it onto a palette.
    if (p.mode === 'system') clean.followSystem = true;
    else if (p.mode === 'light' || p.mode === 'dark') {
      clean.theme = p.mode;
      clean.followSystem = false;
    }
    if (typeof p.followSystem === 'boolean') clean.followSystem = p.followSystem;
    if (['violet', 'cyan', 'green', 'orange'].includes(p.accent)) clean.accent = p.accent;
    if (typeof p.reduceMotion === 'boolean') clean.reduceMotion = p.reduceMotion;
    if (Object.keys(clean).length) {
      try { saveSettings(clean); } catch (err) { logLine('theme save failed', err && err.message); }
    }
    // nativeTheme.themeSource drives shouldUseDarkColors for the follow mode.
    nativeTheme.themeSource = loadSettings().followSystem
      ? 'system'
      : (resolvedTheme() === 'light' ? 'light' : 'dark');
    applyThemeToWindow();
    return themeSnapshot();
  });

  ipcMain.handle('get-app-info', () => ({
    name: APP_NAME,
    version: app.getVersion(),
    electron: process.versions.electron,
    node: process.versions.node,
    platform: process.platform,
    hostname: require('os').hostname(),
    userDataPath: app.getPath('userData'),
    logPath: path.join(app.getPath('userData'), 'duolink.log')
  }));

  // Real WebSocket round trip to the paired phone (bug #10: no fake "<1ms").
  ipcMain.handle('measure-latency', () => {
    if (!wssServer) return false;
    try { return wssServer.measureLatency(); } catch (_) { return false; }
  });

  ipcMain.handle('open-log', () => {
    const logFile = path.join(app.getPath('userData'), 'duolink.log');
    try { fs.closeSync(fs.openSync(logFile, 'a')); } catch (_) {}
    return shell.openPath(logFile);
  });

  // Offline app: report the installed version instead of phoning home.
  ipcMain.handle('check-updates', () => ({ version: app.getVersion() }));

  ipcMain.handle('get-network-info', () => {
    return {
      serverUp: !!wssServer,
      lanIp,
      port: PORT,
      fingerprint: tlsCredentials ? tlsCredentials.fingerprint : ''
    };
  });

  // Renderer startup state: the phone may already be connected when the page
  // loads (the transition event fired before the listener existed). Pull the
  // current status once so the hero / session containers repaint immediately.
  ipcMain.handle('get-session-status', () =>
    wssServer && typeof wssServer.currentSessionStatus === 'function'
      ? wssServer.currentSessionStatus()
      : { status: 'disconnected' }
  );

  // ---- V2 feature IPC: notifications, SMS, media, calls, webcam, diag ----

  const sendToPhone = (type, payload = {}) => {
    if (!wssServer) return { ok: false, error: 'Server not running' };
    const delivered = wssServer.broadcast(createMessage(type, payload));
    return { ok: delivered > 0, error: delivered > 0 ? '' : 'Phone not connected' };
  };

  ipcMain.handle('feature-snapshot', () => (featureHub ? featureHub.fullSnapshot() : null));

  // Notifications
  ipcMain.handle('notif-set-paused', (_e, paused) => {
    if (!featureHub) return { paused: false };
    const res = featureHub.setPaused(paused);
    mainWindow?.webContents.send('notif-list', featureHub.notificationsSnapshot());
    return res;
  });
  ipcMain.handle('notif-set-app-muted', (_e, packageName, muted) => {
    if (!featureHub) return { mutedApps: [] };
    const res = featureHub.setAppMuted(packageName, !!muted);
    mainWindow?.webContents.send('notif-list', featureHub.notificationsSnapshot());
    return res;
  });
  ipcMain.handle('notif-clear-all', () => {
    if (featureHub) featureHub.clearAllNotifications();
    mainWindow?.webContents.send('notif-list', featureHub ? featureHub.notificationsSnapshot() : null);
    return true;
  });
  ipcMain.handle('notif-dismiss', (_e, key) => {
    if (featureHub) featureHub.notifRemoved({ key });
    mainWindow?.webContents.send('notif-removed', { key });
    // Ask the phone to cancel the actual Android notification
    return sendToPhone(MessageType.NOTIF_DISMISS, { key });
  });
  ipcMain.handle('notif-reply', (_e, { key, text }) => {
    if (!key || typeof text !== 'string' || !text.trim()) return false;
    return sendToPhone(MessageType.NOTIF_REPLY, { key, text: text.slice(0, 4000) });
  });

  // SMS
  ipcMain.handle('sms-request', (_e, req) => {
    const op = ['messages', 'delete'].includes(req && req.op) ? req.op : 'threads';
    return sendToPhone(MessageType.SMS_REQ, {
      op,
      address: req && req.address ? String(req.address).slice(0, 64) : undefined,
      id: req && req.id ? String(req.id).slice(0, 40) : undefined,
      limit: Math.min(500, Math.max(1, (req && req.limit) || 100)),
      offset: Math.max(0, (req && req.offset) || 0)
    });
  });
  ipcMain.handle('sms-send', (_e, { address, text }) => {
    if (!address || typeof text !== 'string' || !text.trim()) return { ok: false, error: 'Empty message' };
    const cleanAddr = String(address).replace(/[^0-9+*#]/g, '').slice(0, 32);
    if (!cleanAddr) return { ok: false, error: 'Invalid address' };
    return sendToPhone(MessageType.SMS_SEND, { address: cleanAddr, text: text.slice(0, 1600) });
  });
  ipcMain.handle('sms-mark-read', (_e, address) => {
    if (featureHub) featureHub.markThreadRead(String(address || ''));
    return true;
  });

  // Media
  ipcMain.handle('media-request', (_e, req) => {
    const kind = ['images', 'videos', 'audio', 'documents', 'downloads', 'all'].includes(req && req.kind)
      ? req.kind : 'images';
    return sendToPhone(MessageType.MEDIA_REQ, {
      op: 'list',
      kind,
      search: typeof (req && req.search) === 'string' ? req.search.slice(0, 120) : '',
      sort: ['date_desc', 'date_asc', 'name', 'size_desc'].includes(req && req.sort) ? req.sort : 'date_desc',
      limit: Math.min(500, Math.max(1, (req && req.limit) || 200)),
      offset: Math.max(0, (req && req.offset) || 0)
    });
  });
  ipcMain.handle('media-thumb', (_e, id) => {
    if (featureHub && featureHub.thumbCache.has(String(id))) {
      return { id, dataUrl: featureHub.thumbCache.get(String(id)) };
    }
    sendToPhone(MessageType.MEDIA_REQ, { op: 'thumb', id: String(id) });
    return { id, pending: true };
  });
  ipcMain.handle('media-get', (_e, id, opts) => {
    // Phone answers by streaming the file via existing FILE_META/FILE_CHUNK.
    // A preview request is tagged by FILENAME so the FILE_META handler below
    // (in sockets.js, via fileManager.markPreview) diverts it to the temp
    // cache: the Received folder only ever sees explicit downloads.
    const o = opts || {};
    if (o.preview && fileManager && typeof o.name === 'string' && o.name) {
      fileManager.markPreview(o.name);
    }
    return sendToPhone(MessageType.MEDIA_REQ, { op: 'get', id: String(id) });
  });
  // Promote a previewed file (temp cache) into the Received folder. The bytes
  // already crossed the LAN once — no second transfer, no history until now.
  ipcMain.handle('save-preview-file', (_e, filePath) => {
    try {
      if (!fileManager) return { ok: false, error: 'Not ready' };
      const res = fileManager.promotePreview(String(filePath || ''));
      return res;
    } catch (err) {
      return { ok: false, error: err.message };
    }
  });
  // Open an http(s) link (LAN chat messages, external buttons) in the browser.
  ipcMain.handle('open-external-url', async (_e, url) => {
    try {
      const u = new URL(String(url || ''));
      if (u.protocol !== 'https:' && u.protocol !== 'http:') {
        return { ok: false, error: 'Only web links can be opened' };
      }
      await shell.openExternal(u.toString());
      return { ok: true };
    } catch (_) {
      return { ok: false, error: 'That link is not valid' };
    }
  });
  // Delete / share from the PC. Results come back async as MEDIA_RESULT.
  ipcMain.handle('media-delete', (_e, ids) => {
    const list = (Array.isArray(ids) ? ids : []).map(String).filter(Boolean).slice(0, 300);
    if (!list.length) return { ok: false, error: 'Nothing selected' };
    return sendToPhone(MessageType.MEDIA_REQ, { op: 'delete', ids: list });
  });
  // media-share removed: it raised Android's share sheet on the phone. The
  // renderer now pulls the file over (media-get) and opens it on this PC.

  // PC-side share picker: the phone only lists apps that can receive the
  // selection (MEDIA_SHARE_TARGETS) — the picker dialog is drawn on the PC and
  // the chosen app is launched on the phone by package. No handset prompt.
  ipcMain.handle('media-share-targets', (_e, req) => {
    const list = (Array.isArray(req && req.ids) ? req.ids : []).map(String).filter(Boolean).slice(0, 50);
    if (!list.length) return { ok: false, error: 'Nothing selected' };
    return sendToPhone(MessageType.MEDIA_REQ, {
      op: 'share_targets',
      ids: list,
      mime: typeof (req && req.mime) === 'string' ? req.mime.slice(0, 120) : ''
    });
  });
  ipcMain.handle('media-share', (_e, req) => {
    const list = (Array.isArray(req && req.ids) ? req.ids : []).map(String).filter(Boolean).slice(0, 50);
    const target = String((req && req.target) || '').trim().slice(0, 160);
    if (!list.length) return { ok: false, error: 'Nothing selected' };
    // Package-name charset only: never pass arbitrary strings to the phone.
    if (!/^[A-Za-z0-9_.]+$/.test(target)) return { ok: false, error: 'Invalid app package' };
    return sendToPhone(MessageType.MEDIA_REQ, { op: 'share', ids: list, target });
  });

  // Calls
  ipcMain.handle('call-action', (_e, req) => {
    const action = typeof req === 'string' ? req : (req && req.action);
    if (!['ANSWER', 'REJECT', 'END', 'DIAL'].includes(action)) return false;
    const payload = { action };
    if (action === 'DIAL') {
      // Digits only — never pass raw strings to the phone's dialer.
      const number = String((req && req.number) || '').replace(/[^0-9+*#,]/g, '').slice(0, 32);
      if (!number) return { ok: false, error: 'Enter a number to call' };
      payload.number = number;
    }
    return sendToPhone(MessageType.CALL_ACTION, payload);
  });
  ipcMain.handle('call-recents', () => sendToPhone(MessageType.CALL_RECENTS_REQ, { limit: 50 }));
  // ---- Phone apps catalog (label + icon, over the paired session) ----
  ipcMain.handle('app-catalog', () => {
    const res = sendToPhone(MessageType.APP_CATALOG_REQ, {});
    // The pane is opened because an app is about to be launched: hand the
    // phone its wireless-debugging port while the link is warm.
    relayAdbPorts().catch(() => {});
    return res;
  });
  ipcMain.handle('app-launch', (_e, pkg) => {
    if (!/^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+){1,}$/.test(String(pkg || ''))) {
      return { ok: false, error: 'Unknown package' };
    }
    return sendToPhone(MessageType.APP_LAUNCH, { pkg: String(pkg) });
  });

  // ---- Phone apps over Wireless debugging (adb) ----
  const wirelessAdb = new WirelessAdb();

  // Some ROMs never resolve adbd's own mDNS advertisement from inside the
  // phone (the lookup times out while the service is plainly advertised). This
  // PC's adb sees it, so it relays the port numbers over the link already in
  // place: pairing and app windows then need no address and no guessing.
  let adbPortRelayTimer = null;
  const relayAdbPorts = async () => {
    if (!wssServer) return { ok: false, error: 'Server not running' };
    const ip = typeof wssServer.phoneIp === 'function' ? wssServer.phoneIp() : null;
    const seen = await wirelessAdb.mdnsServices();
    if (!seen.ok) return { ok: false, error: seen.error || 'adb mdns unavailable' };
    const mine = ip ? seen.services.filter((s) => s.host === ip) : seen.services;
    const connect = mine.find((s) => s.service.indexOf('_adb-tls-connect') === 0);
    const pairing = mine.find((s) => s.service.indexOf('_adb-tls-pairing') === 0);
    if (!connect && !pairing) return { ok: false, error: 'Wireless debugging is not advertising yet' };
    const payload = {};
    if (connect) payload.connectPort = connect.port;
    if (pairing) payload.pairingPort = pairing.port;
    sendToPhone(MessageType.ADB_PORT_INFO, payload);
    return { ok: true, ...payload };
  };
  const stopAdbPortRelay = () => {
    if (adbPortRelayTimer) clearInterval(adbPortRelayTimer);
    adbPortRelayTimer = null;
  };
  ipcMain.handle('adb-relay-ports', () => relayAdbPorts());

  ipcMain.handle('adb-status', () => wirelessAdb.status());
  ipcMain.handle('adb-pair', (_e, { addr, code }) => wirelessAdb.pair(addr, code));
  ipcMain.handle('adb-connect', async (_e, { addr }) => {
    const res = await wirelessAdb.connect(addr);
    return res;
  });
  ipcMain.handle('adb-disconnect', (_e, { addr }) => wirelessAdb.disconnect(addr));
  ipcMain.handle('adb-apps', (_e, { serial }) => wirelessAdb.apps(serial));
  ipcMain.handle('adb-launch', (_e, { serial, pkg }) => wirelessAdb.launch(serial, pkg));

  ipcMain.handle('call-log-delete', (_e, id) => {
    if (!id) return { ok: false, error: 'Nothing to delete' };
    return sendToPhone(MessageType.CALL_LOG_DELETE, { id: String(id).slice(0, 40) });
  });

  // Phone volume
  ipcMain.handle('phone-volume', (_e, { action, value }) => {
    if (!['UP', 'DOWN', 'MUTE', 'UNMUTE', 'SET'].includes(action)) return false;
    return sendToPhone(MessageType.PHONE_VOLUME, {
      action, value: typeof value === 'number' ? Math.max(0, Math.min(15, value)) : undefined
    });
  });

  // Screenshot / recording from the phone-screen window toolbar. The window
  // hands over its canvas as a data URL (still) or raw chunks (video); the main
  // process owns the disk, so the file lands in the user's own folders.
  const mediaFolder = (kind) => {
    const base = kind === 'video' ? 'videos' : 'pictures';
    const dir = path.join(app.getPath(base), 'DuoLink');
    try { fs.mkdirSync(dir, { recursive: true }); } catch (_) {}
    return dir;
  };

  const stampName = (kind, ext) => {
    const d = new Date();
    const p = (n) => String(n).padStart(2, '0');
    return `DuoLink-${kind}-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}.${ext}`;
  };

  ipcMain.handle('mirror-save-image', (_e, dataUrl) => {
    try {
      if (typeof dataUrl !== 'string' || !dataUrl.startsWith('data:image/png;base64,')) {
        return { ok: false, error: 'No screen frame to save' };
      }
      const file = path.join(mediaFolder('screenshot'), stampName('screenshot', 'png'));
      fs.writeFileSync(file, Buffer.from(dataUrl.split(',')[1], 'base64'));
      return { ok: true, path: file };
    } catch (e) {
      return { ok: false, error: e.message };
    }
  });

  ipcMain.handle('mirror-copy-image', (_e, dataUrl) => {
    try {
      if (typeof dataUrl !== 'string' || !dataUrl.startsWith('data:image/png;base64,')) {
        return { ok: false, error: 'No screen frame to copy' };
      }
      const img = nativeImage.createFromDataURL(dataUrl);
      if (img.isEmpty()) return { ok: false, error: 'No screen frame to copy' };
      clipboard.writeImage(img);
      return { ok: true };
    } catch (e) {
      return { ok: false, error: e.message };
    }
  });

  ipcMain.handle('mirror-save-video', (_e, bytes) => {
    try {
      if (!bytes || !bytes.length) return { ok: false, error: 'The recording was empty' };
      const file = path.join(mediaFolder('video'), stampName('recording', 'webm'));
      fs.writeFileSync(file, Buffer.from(bytes));
      return { ok: true, path: file };
    } catch (e) {
      return { ok: false, error: e.message };
    }
  });

  // Diagnostics
  ipcMain.handle('diag-request', () => sendToPhone(MessageType.DIAG_REQ, {}));

  // Webcam (stream token for OBS / browser viewers)
  ipcMain.handle('webcam-info', () => {
    if (!webcamHub) return null;
    return {
      active: featureHub ? featureHub.webcamActive : false,
      camera: webcamHub.camera,
      hasFrame: !!webcamHub.latestFrame,
      token: webcamHub.issueToken(),
      mjpegUrl: `https://127.0.0.1:${PORT}/webcam.mjpeg`
    };
  });

  ipcMain.handle('webcam-start', (_e, opts) => {
    const o = opts && typeof opts === 'object' ? opts : {};
    return sendToPhone(MessageType.WEBCAM_START, {
      camera: o.camera === 'front' ? 'front' : 'back',
      width: Math.min(1920, Math.max(320, o.width | 0 || 1280)),
      height: Math.min(1920, Math.max(240, o.height | 0 || 720)),
      // 30 fps is the default: at 1280x720 a 60 fps phone encode saturates
      // Wi-Fi and leaves the picture behind the audio/cursor.
      fps: Math.min(60, Math.max(1, o.fps | 0 || 30)),
      quality: Math.min(95, Math.max(20, o.quality | 0 || 85)),
      torch: !!o.torch
    });
  });

  ipcMain.handle('webcam-stop', () => sendToPhone(MessageType.WEBCAM_STOP, {}));
  ipcMain.handle('webcam-torch', (_e, on) => sendToPhone(MessageType.WEBCAM_TORCH, { on: !!on }));

  // Fallback only: the desktop preview pulls /webcam.mjpeg and decodes it
  // natively. If that picture cannot load, the renderer asks for the old
  // per-frame IPC mirror instead of showing nothing.
  ipcMain.handle('webcam-preview-fallback', (_e, enabled) => {
    if (wssServer && typeof wssServer.setWebcamPreviewFrames === 'function') {
      wssServer.setWebcamPreviewFrames(!!enabled);
    }
    return true;
  });

  // Remote PC screen status (phone drives the stream; desktop can also stop it)
  ipcMain.handle('pc-screen-status', () => ({
    active: remotePc ? remotePc.active : false,
    fps: remotePc ? remotePc.fps : 0,
    quality: remotePc ? remotePc.quality : 0,
    maxDimension: remotePc ? remotePc.maxDimension : 0
  }));
  ipcMain.handle('pc-screen-stop', () => {
    if (remotePc) remotePc.stop();
    if (featureHub) featureHub.pcScreenActive = false;
    return true;
  });

  // Health check from the MAIN process: the server uses a self-signed cert,
  // which the renderer's fetch() would reject. Skip verification only for
  // this loopback probe against our own server.
  ipcMain.handle('health-check', async () => {
    const start = Date.now();
    try {
      const https = require('https');
      await new Promise((resolve, reject) => {
        const req = https.request({
          host: '127.0.0.1',
          port: PORT,
          path: '/health',
          method: 'GET',
          rejectUnauthorized: false,
          timeout: 3000
        }, (res) => {
          res.resume();
          if (res.statusCode === 200) resolve();
          else reject(new Error('HTTP ' + res.statusCode));
        });
        req.on('error', reject);
        req.on('timeout', () => req.destroy(new Error('Timeout')));
        req.end();
      });
      return { ok: true, latencyMs: Date.now() - start };
    } catch (err) {
      return { ok: false, error: err.message, latencyMs: Date.now() - start };
    }
  });

  ipcMain.handle('generate-pairing-token', async () => {
    if (!pairingManager) return null;
    // Re-detect the LAN address on every QR so a DHCP/Wi-Fi change mid-run
    // can't leave a stale IP baked into the pairing URI.
    const freshIp = getLocalLanIp();
    lanIp = freshIp && freshIp !== '127.0.0.1' ? freshIp : lanIp;
    return await pairingManager.generatePairingToken(lanIp, PORT, tlsCredentials.fingerprint);
  });

  ipcMain.handle('reset-rate-limit', () => {
    if (pairingManager) {
      return pairingManager.resetRateLimit();
    }
    return true;
  });

  ipcMain.handle('approve-pairing', (_event, pendingId) => {
    const result = pairingManager.approvePairing(pendingId);
    if (result.success && result.session) {
      // Send PAIR_RESULT to phone
      if (result.session.socket && result.session.socket.readyState === 1 /* WebSocket.OPEN */) {
        result.session.socket.isPendingApproval = false;
        result.session.socket.send(JSON.stringify(createMessage(MessageType.PAIR_RESULT, {
          success: true,
          sessionId: result.session.sessionId,
          sessionSecret: result.session.sessionSecret,
          deviceName: 'Windows PC'
        })));
        result.session.socket.associatedSessionId = result.session.sessionId;
      }
      return {
        success: true,
        session: {
          sessionId: result.session.sessionId,
          deviceName: result.session.deviceName,
          deviceId: result.session.deviceId,
          pairedAt: result.session.pairedAt
        }
      };
    }
    return { success: false, error: result.error || 'Pairing request expired or not found' };
  });

  ipcMain.handle('reject-pairing', (_event, pendingId) => {
    return pairingManager.rejectPairing(pendingId);
  });

  ipcMain.handle('disconnect-session', (_event, sessionId) => {
    return pairingManager.disconnectSession(sessionId);
  });

  ipcMain.handle('get-trusted-devices', () => {
    return pairingManager.getTrustedDevicesList();
  });

  ipcMain.handle('revoke-device', (_event, deviceId) => {
    return pairingManager.revokeDevice(deviceId);
  });

  ipcMain.handle('set-allow-control', (_event, allowed) => {
    if (controlManager) {
      controlManager.setAllowed(allowed);
      return { allowed: controlManager.allowed };
    }
    return { allowed: false };
  });

  ipcMain.handle('get-control-status', () => {
    return {
      allowed: controlManager ? controlManager.allowed : false,
      isKillSwitched: controlManager ? controlManager.isKillSwitched : false
    };
  });

  ipcMain.handle('send-chat-message', (_event, text) => {
    const msg = createMessage(MessageType.CHAT_MESSAGE, {
      text,
      senderName: 'Windows PC'
    });
    wssServer.broadcast(msg);
    return true;
  });

  ipcMain.handle('get-clipboard', () => {
    return clipboard.readText();
  });

  ipcMain.handle('set-clipboard', (_event, text) => {
    clipboard.writeText(text || '');
    return true;
  });

  ipcMain.handle('send-clipboard-to-phone', (_event, text) => {
    const content = text || clipboard.readText();
    const msg = createMessage(MessageType.CLIPBOARD_PUSH, { text: content });
    wssServer.broadcast(msg);
    return true;
  });

  ipcMain.handle('open-received-folder', () => {
    const folder = path.join(app.getPath('documents'), 'DuoLink Received');
    fs.mkdirSync(folder, { recursive: true });
    shell.openPath(folder);
    return true;
  });

  ipcMain.handle('select-and-send-file', async () => {
    const res = await dialog.showOpenDialog(mainWindow, {
      properties: ['openFile', 'multiSelections'],
      title: 'Select File(s) to Send to Android Phone'
    });

    if (res.canceled || !res.filePaths.length) return false;
    // Batch: queue each selected file (single file keeps the original shape)
    for (const p of res.filePaths) {
      const r = await streamFileToPhone(p);
      if (res.filePaths.length === 1) return r;
      if (!r.success) return { success: false, error: r.error };
    }
    return { success: true, filename: `${res.filePaths.length} files`, size: 0, batch: true };
  });

  ipcMain.handle('send-dropped-file', async (_event, filePath) => {
    // Renderer input is trusted, but a path is still validated as a string so
    // a malformed drop cannot throw inside fs.existsSync. streamFileToPhone
    // then enforces existence, file-not-folder and the 5 GB cap.
    if (typeof filePath !== 'string' || !filePath.trim()) {
      return { success: false, error: 'No file to send' };
    }
    return await streamFileToPhone(path.resolve(filePath));
  });

  ipcMain.handle('transfer-history', () => (fileManager ? fileManager.getHistory() : []));

  ipcMain.handle('set-download-dir', async () => {
    const res = await dialog.showOpenDialog(mainWindow, {
      properties: ['openDirectory', 'createDirectory'],
      title: 'Choose Folder for Files Received from Phone'
    });
    if (res.canceled || !res.filePaths.length) return { ok: false };
    const ok = fileManager.setDownloadDir(res.filePaths[0]);
    return { ok, dir: fileManager.receivedDir };
  });

  ipcMain.handle('get-download-dir', () => (fileManager ? fileManager.receivedDir : ''));

  // Open a file that arrived from the phone (media browser "Open" action).
  // Path must live inside the configured received dir or the preview cache -
  // never arbitrary paths.
  ipcMain.handle('open-received-file', (_e, filePath) => {
    try {
      if (!fileManager) return { ok: false, error: 'Not ready yet' };
      if (!fileManager.isAllowedMediaPath(String(filePath || ''))) {
        return { ok: false, error: 'Outside received folder' };
      }
      const target = path.resolve(String(filePath || ''));
      if (!fs.existsSync(target)) return { ok: false, error: 'File no longer exists' };
      shell.openPath(target);
      return { ok: true };
    } catch (err) {
      return { ok: false, error: err.message };
    }
  });

  // duo-media:// URL for a received or previewed file so the renderer can show
  // it in the app's own lightbox. Same received-dir / preview-cache boundary.
  ipcMain.handle('media-file-url', (_e, filePath) => {
    try {
      if (!fileManager) return { ok: false, error: 'Not ready yet' };
      if (!fileManager.isAllowedMediaPath(String(filePath || ''))) {
        return { ok: false, error: 'Outside received folder' };
      }
      const target = path.resolve(String(filePath || ''));
      if (!fs.existsSync(target)) return { ok: false, error: 'File no longer exists' };
      return { ok: true, url: `duo-media://local/?p=${encodeURIComponent(target)}` };
    } catch (err) {
      return { ok: false, error: err.message };
    }
  });

  ipcMain.handle('trigger-media-action', (_event, action) => {
    if (controlManager) {
      controlManager.handleMediaAction(action);
      return true;
    }
    return false;
  });

  ipcMain.handle('find-phone', (_event, on) => {
    if (!wssServer) return false;
    wssServer.broadcast(createMessage(MessageType.FIND_PHONE, { on: !!on }));
    return true;
  });

  ipcMain.handle('trigger-system-action', (_event, action) => {
    if (controlManager) {
      controlManager.handleSystemAction(action);
      return true;
    }
    return false;
  });

  // Phase 4: Screen stream & remote gestures on phone
  ipcMain.handle('toggle-screen-stream', (_event, start) => {
    const msg = createMessage(start ? MessageType.PHONE_SCREEN_START : MessageType.PHONE_SCREEN_STOP);
    wssServer.broadcast(msg);
    return true;
  });

  /**
   * Pop-out viewer: a second window containing nothing but the phone screen.
   * Its frames are forwarded from the socket layer (see SCREEN_WIN_EVENTS), so
   * this window is independent of whether the Control Phone pane is on screen.
   */

  /**
   * Size of the in-app live-stream container, so the pop-out opens at exactly
   * the same size the user already sees. Falls back to null (=> default size)
   * when the mirror pane is hidden or the window is gone.
   */
  async function mirrorContainerSize() {
    let r = null;
    try {
      if (!mainWindow || mainWindow.isDestroyed()) return null;
      r = await mainWindow.webContents.executeJavaScript(`(() => {
        const pane = document.querySelector('#tab-screen-mirror.active') || document.querySelector('#tab-screen-mirror');
        const el = (pane && pane.querySelector('.mirror-viewport')) || document.querySelector('.mirror-viewport');
        if (!el) return null;
        const b = el.getBoundingClientRect();
        let w = b.width, h = b.height;
        // Hidden tab (display:none) has a zero rect, but its height is a vh
        // calc that still resolves in computed style — use it so the pop-out
        // matches the live-stream container from ANY active tab.
        if (!(h > 40)) {
          const ch = parseFloat(getComputedStyle(el).height);
          if (ch > 40) h = ch;
        }
        if (!(w > 40) && h > 40) w = h * 360 / 740; // container aspect; first
        return (w > 40 && h > 40) ? { w: Math.round(w), h: Math.round(h) } : null; // frame corrects width anyway
      })()`);
    } catch (_) { return null; }
    return r || null;
  }

  /** The pop-out window that sent an IPC call: the mirror or an app window. */
  const senderWindow = (e) => {
    const win = e && e.sender ? BrowserWindow.fromWebContents(e.sender) : null;
    return win && !win.isDestroyed() ? win : null;
  };

  ipcMain.handle('screen-window-minimize', (e) => {
    const win = senderWindow(e) || screenWin;
    if (win && !win.isDestroyed()) win.minimize();
    return true;
  });

  ipcMain.handle('screen-window-maximize', (e) => {
    const win = senderWindow(e) || screenWin;
    if (!win || win.isDestroyed()) return false;
    if (win.isMaximized()) win.unmaximize();
    else win.maximize();
    return win.isMaximized();
  });

  /**
   * Open (or focus) the pop-out phone-screen window.
   *
   * orientation: 'portrait' | 'landscape' | null. Null follows the current
   * picture: the newest frame's own aspect when frames are flowing (so a
   * landscape game opens a landscape window), else the in-app container's
   * aspect, else the classic 360:740.
   */
  async function openScreenWindowInternal(orientation) {
    if (screenWin && !screenWin.isDestroyed()) {
      screenWin.focus();
      return { ok: true, existing: true };
    }
    screenWinOpening = true;
    try {
      const cont = await mirrorContainerSize();
      const theme = THEME_COLORS[resolvedTheme()];
      // Big pop-out (request): the in-app box is limited by the window height,
      // so scale the phone picture up to ~the display height instead of just
      // copying the small container. Aspect always follows the phone (newest
      // frame first — it is the real current picture — then container, then
      // the classic 360:740).
      let winW, winH;
      const wa = screen.getPrimaryDisplay().workAreaSize;
      let aspect = cont && cont.h > 0 ? (cont.w / cont.h) : (360 / 740);
      if (latestScreenFrame && latestScreenFrame.width > 0 && latestScreenFrame.height > 0) {
        aspect = latestScreenFrame.width / latestScreenFrame.height;
      }
      if (orientation === 'landscape' && aspect < 1) aspect = 1 / aspect;
      if (orientation === 'portrait' && aspect > 1) aspect = 1 / aspect;
      winH = Math.min(Math.round(wa.height - 48), 1400);
      winW = Math.round(winH * aspect);
      if (cont && orientation !== 'landscape' && (winW < cont.w || winH < cont.h)) {
        // Never open smaller than what the user was already looking at.
        const scale = Math.max(cont.w / winW, cont.h / winH);
        winW = Math.round(winW * scale);
        winH = Math.round(winH * scale);
      }
      screenWin = new BrowserWindow({
        width: winW,
        height: winH,
        // Low enough that a small live-stream container isn't clamped away from
        // its true size (the container itself is the floor the user asked for).
        minWidth: 160,
        minHeight: 200,
        backgroundColor: '#000000',
        title: `${APP_NAME} · Phone screen`,
        icon: APP_ICON,
        autoHideMenuBar: true,
        // Pure phone screen: no title bar, no menu, no window chrome. The
        // renderer draws only the phone picture (Esc closes; an invisible top
        // strip drags the window).
        frame: false,
        webPreferences: {
          preload: path.join(__dirname, 'preload.js'),
          contextIsolation: true,
          nodeIntegration: false,
          sandbox: true
        }
      });
      // Frames may flip orientation while the window opens; fitScreenWindow
      // converges the size to the real picture, so don't seed a stale aspect.
      screenWinFitAspect = 0;
      screenWin.loadFile(path.join(__dirname, 'renderer', 'screen-window.html'));
      screenWin.on('closed', () => {
        screenWin = null;
        screenWinFitAspect = 0;
        // Pop-out is gone: the in-app pane takes the picture again.
        if (mainWindow && !mainWindow.isDestroyed()) {
          mainWindow.webContents.send('screen-window-state', false);
        }
      });
      screenWin.webContents.setWindowOpenHandler(({ url }) => {
        if (url.startsWith('https:') || url.startsWith('http:')) shell.openExternal(url);
        return { action: 'deny' };
      });
      screenWin.webContents.on('will-navigate', (event, url) => {
        if (!url.startsWith('file://')) event.preventDefault();
      });
      // Returning to the in-app pane restores the live picture.
      screenWin.once('ready-to-show', () => {
        screenWinOpening = false;
        if (mainWindow && !mainWindow.isDestroyed()) {
          mainWindow.webContents.send('screen-window-state', true);
        }
      });
      return { ok: true };
    } finally {
      screenWinOpening = false;
    }
  }

  ipcMain.handle('open-screen-window', () => openScreenWindowInternal(null));

  /**
   * A phone app in a window of its own.
   *
   * The app is started on the phone and this window shows the live phone
   * picture, titled with the app's own name so the window is the thing the
   * user picked. Tapping the same app again focuses its window instead of
   * stacking a second copy of it.
   */
  /**
   * A phone app in a window of its own.
   *
   * The phone starts the app on a display only that app occupies and streams
   * that display back on a session of its own; this window paints that stream,
   * titled with the app's own name. Ten apps open means ten sessions, ten
   * streams and ten windows, and each one shows that app and nothing else.
   */
  function appWindowFor(session) {
    const entry = appWindows.get(String(session || ''));
    return entry && entry.win && !entry.win.isDestroyed() ? entry : null;
  }

  function openAppWindowInternal(pkg, label, session) {
    const cleanPkg = String(pkg || '').trim();
    if (!/^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+){1,}$/.test(cleanPkg)) {
      return { ok: false, error: 'Unknown package' };
    }
    const sid = String(session || '').trim();
    if (!sid) return { ok: false, error: 'The phone did not open a window for that app' };
    const existing = appWindowFor(sid);
    if (existing) {
      existing.win.show();
      existing.win.focus();
      return { ok: true, existing: true };
    }

    const name = String(label || cleanPkg).trim() || cleanPkg;
    const wa = screen.getPrimaryDisplay().workAreaSize;
    let aspect = 360 / 740;
    if (latestScreenFrame && latestScreenFrame.width > 0 && latestScreenFrame.height > 0) {
      aspect = latestScreenFrame.width / latestScreenFrame.height;
    }
    let winH = Math.min(Math.round(wa.height * 0.82), 960);
    let winW = Math.round(winH * aspect);
    if (winW > wa.width - 80) {
      winW = wa.width - 80;
      winH = Math.round(winW / aspect);
    }

    const win = new BrowserWindow({
      width: Math.max(320, winW),
      height: Math.max(420, winH),
      minWidth: 220,
      minHeight: 280,
      backgroundColor: '#0B0D14',
      title: `${name} — ${APP_NAME}`,
      icon: APP_ICON,
      autoHideMenuBar: true,
      webPreferences: {
        preload: path.join(__dirname, 'preload.js'),
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true
      }
    });
    appWindows.set(sid, { win, pkg: cleanPkg, label: name });
    win.loadFile(path.join(__dirname, 'renderer', 'screen-window.html'), {
      query: { app: '1', label: name, session: sid }
    });
    win.on('closed', () => {
      const entry = appWindows.get(sid);
      if (entry && entry.win === win) appWindows.delete(sid);
      // Closing the window closes that app's display on the phone too.
      try { sendToPhone(MessageType.APP_WINDOW_CLOSE, { session: sid }); } catch (_) {}
    });
    win.webContents.setWindowOpenHandler(({ url }) => {
      if (url.startsWith('https:') || url.startsWith('http:')) shell.openExternal(url);
      return { action: 'deny' };
    });
    win.webContents.on('will-navigate', (event, url) => {
      if (!url.startsWith('file://')) event.preventDefault();
    });
    return { ok: true };
  }

  ipcMain.handle('open-app-window', (_e, req) => {
    const pkg = typeof req === 'string' ? req : (req && req.pkg);
    const label = typeof req === 'string' ? '' : (req && req.label);
    const session = typeof req === 'string' ? '' : (req && req.session);
    return openAppWindowInternal(pkg, label, session);
  });

  // A phone app opened from the Apps pane: the phone gives it a display of its
  // own and answers with a session; that session becomes a window here.
  ipcMain.handle('launch-app-in-window', (_e, req) => {
    const pkg = typeof req === 'string' ? req : (req && req.pkg);
    if (!/^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+){1,}$/.test(String(pkg || ''))) {
      return { ok: false, error: 'Unknown package' };
    }
    return sendToPhone(MessageType.APP_LAUNCH, { pkg: String(pkg), window: true });
  });

  // The window's mouse and keyboard, on that app's own display.
  ipcMain.handle('app-window-input', (_e, req) => {
    const session = String((req && req.session) || '');
    if (!session) return false;
    const action = String((req && req.action) || 'UP');
    const x = Number((req && req.x) || 0);
    const y = Number((req && req.y) || 0);
    return sendToPhone(MessageType.APP_WINDOW_INPUT, { session, action, x, y });
  });

  ipcMain.handle('app-window-close', (_e, session) => {
    const sid = String(session || '');
    const entry = appWindowFor(sid);
    if (entry) {
      appWindows.delete(sid);
      if (!entry.win.isDestroyed()) entry.win.close();
    }
    return sendToPhone(MessageType.APP_WINDOW_CLOSE, { session: sid });
  });

  // Ask the phone itself for a wireless-debugging pairing: it posts the
  // notification where the six digits are typed, so the PC never asks for an
  // address or a port.
  ipcMain.handle('adb-pair-prompt', () => {
    const res = sendToPhone(MessageType.ADB_PAIR_PROMPT, {});
    // Android only advertises the pairing service once the user opens
    // "Pair device with pairing code", so keep relaying for a couple of
    // minutes — the six digits are then all that is left to do.
    relayAdbPorts().catch(() => {});
    stopAdbPortRelay();
    const startedAt = Date.now();
    adbPortRelayTimer = setInterval(() => {
      if (Date.now() - startedAt > 120000) {
        stopAdbPortRelay();
        return;
      }
      relayAdbPorts().catch(() => {});
    }, 2000);
    if (adbPortRelayTimer.unref) adbPortRelayTimer.unref();
    return res;
  });

  // Wireless debugging on the phone itself: one pairing code, then the phone
  // can run the shell side that gives each app its own display.
  ipcMain.handle('adb-pair-phone', (_e, code) => {
    const clean = String(code || '').replace(/[^0-9]/g, '').slice(0, 6);
    if (clean.length < 6) return { ok: false, error: 'Enter the six-digit pairing code' };
    relayAdbPorts().catch(() => {});
    return sendToPhone(MessageType.ADB_PAIR_REQ, { code: clean });
  });

  // The pop-out renderer pings as soon as its script is up. Answer with the
  // last frame the phone sent so the window shows a picture IMMEDIATELY -
  // no blank canvas, no "Reconnecting…" while waiting for the next frame.
  ipcMain.handle('screen-window-ready', (e) => {
    const win = BrowserWindow.fromWebContents(e.sender);
    if (!win || win.isDestroyed()) return false;
    let sent = false;
    if (latestH264Config) {
      win.webContents.send('phone-h264-config', latestH264Config);
      // The decoder starts at the next IDR (at most one keyframe interval
      // away); the cached JPEG below covers the gap until then.
      sent = false;
    }
    if (latestScreenFrame) {
      win.webContents.send('phone-screen-frame', latestScreenFrame);
      sent = !latestH264Config;
    }
    // New viewer, fresh picture: ask the phone for an IDR so the pop-out paints
    // a real frame immediately instead of showing "Reconnecting…" until the
    // encoder's next scheduled keyframe.
    try { sendToPhone(MessageType.PHONE_SCREEN_KEYFRAME, {}); } catch (_) {}
    return sent || !!latestH264Config;
  });

  ipcMain.handle('send-phone-gesture', (_event, gestureData) => {
    const msg = createMessage(MessageType.PHONE_INPUT_GESTURE, gestureData);
    wssServer.broadcast(msg);
    return true;
  });

  ipcMain.handle('send-phone-key-action', (_event, action) => {
    const msg = createMessage(MessageType.PHONE_KEY_ACTION, { action });
    wssServer.broadcast(msg);
    return true;
  });

  // Type on Phone: { op: show|hide|append|delete|replace, text? }.
  ipcMain.handle('send-phone-type-text', (_event, payload) => {
    const p = payload && typeof payload === 'object' ? payload : {};
    const op = String(p.op || '').toLowerCase();
    if (!['show', 'hide', 'append', 'delete', 'replace'].includes(op)) {
      return { ok: false, error: 'Invalid typing action' };
    }
    const body = { op };
    if (op === 'delete') {
      body.count = Math.max(1, Math.min(500, parseInt(p.text, 10) || 1));
    } else if (op !== 'show' && op !== 'hide') {
      body.text = String(p.text || '').slice(0, 4000);
      if (op === 'append' && !body.text) return { ok: true };
    }
    const delivered = wssServer ? wssServer.broadcast(createMessage(MessageType.PHONE_TYPE_TEXT, body)) : 0;
    if (!delivered) return { ok: false, error: 'Phone not connected' };
    return { ok: true };
  });

  // --- Shared folders: the allow-list behind the phone's PC file browser ---
  ipcMain.handle('get-shared-folders', () => ({
    folders: pcfs ? pcfs.roots() : [],
    fullAccess: pcfs ? pcfs.fullAccess() : false,
    drives: pcfs ? pcfs.drives() : [],
    defaults: pcfs ? pcfs.defaultRoots() : []
  }));
  // One consent switch for the whole machine: the phone can browse every local
  // drive only while this is on. Windows ACLs still apply to every request.
  ipcMain.handle('set-file-access', (_event, on) => {
    if (!pcfs) return { ok: false, error: 'File sharing is not available' };
    const fullAccess = pcfs.setFullAccess(!!on);
    return { ok: true, fullAccess, drives: pcfs.drives() };
  });
  ipcMain.handle('add-shared-folder', async () => {
    if (!pcfs) return { ok: false, error: 'File sharing is not available' };
    const res = await dialog.showOpenDialog(mainWindow, {
      title: 'Share a folder with your phone',
      buttonLabel: 'Share folder',
      properties: ['openDirectory', 'createDirectory']
    });
    if (res.canceled || !res.filePaths.length) return { ok: false, canceled: true };
    const folder = res.filePaths[0];
    const roots = pcfs.roots();
    if (roots.some((r) => r.toLowerCase() === folder.toLowerCase())) {
      return { ok: false, error: 'That folder is already shared' };
    }
    pcfs.setRoots([...roots, folder]);
    return { ok: true, folders: pcfs.roots() };
  });
  ipcMain.handle('remove-shared-folder', (_event, folder) => {
    if (!pcfs) return { ok: false, error: 'File sharing is not available' };
    const roots = pcfs.roots();
    const next = roots.filter((r) => r.toLowerCase() !== String(folder || '').toLowerCase());
    pcfs.setRoots(next);
    return { ok: true, folders: pcfs.roots() };
  });

  // Phone power: Lock / Restart / Shut down from the PC -> Phone Control pane
  ipcMain.handle('phone-power', (_event, action) => {
    const allowed = ['LOCK', 'RESTART', 'SHUTDOWN'];
    const a = String(action || '').toUpperCase();
    if (!allowed.includes(a)) return { ok: false, error: 'Invalid power action' };
    const delivered = wssServer ? wssServer.broadcast(createMessage(MessageType.PHONE_POWER, { action: a })) : 0;
    return { ok: delivered > 0, error: delivered > 0 ? '' : 'Phone not connected' };
  });
}

// App lifecycle
async function startServerWithFallback() {
  // Try 3000, then the next few ports, in case a stale copy still holds 3000
  let lastErr = null;
  for (let p = 3000; p <= 3010; p++) {
    try {
      wssServer = new WssServer(wssOptions(p));
      await wssServer.start();
      PORT = p;
      return;
    } catch (err) {
      lastErr = err;
      logLine('server start failed on port', p, err && err.code || err);
      try { wssServer.stop(); } catch (_) {}
      wssServer = null;
      if (!err || err.code !== 'EADDRINUSE') break;
    }
  }
  throw lastErr || new Error('Server failed to start');
}

let wssOptions = () => ({});

// The desktop preview consumes the local MJPEG endpoint, which is served over
// our own self-signed certificate. Chromium refuses that certificate unless the
// app explicitly trusts it, so allow exactly one certificate: ours, on loopback,
// matched by SHA-256 fingerprint. Nothing else is ever trusted, and webSecurity
// stays on.
app.on('certificate-error', (event, webContents, url, error, certificate, callback) => {
  let allowed = false;
  try {
    const target = new URL(url);
    const loopback = target.hostname === '127.0.0.1' || target.hostname === 'localhost';
    const samePort = target.port === String(PORT);
    const own = tlsCredentials && certificate && certificate.data &&
      computeSha256Fingerprint(certificate.data) === tlsCredentials.fingerprint;
    allowed = !!(loopback && samePort && own);
  } catch (_) {
    allowed = false;
  }
  if (!allowed) logLine('cert', `Rejected certificate for ${url} (${error})`);
  event.preventDefault();
  callback(allowed);
});

if (gotTheLock) app.whenReady().then(async () => {
  try {
    const userDataDir = app.getPath('userData');
    const receivedDir = path.join(app.getPath('documents'), 'DuoLink Received');

    lanIp = getLocalLanIp();
    tlsCredentials = await getOrCreateTlsCert(userDataDir);

    pairingManager = new PairingManager(userDataDir);
    const historyPath = path.join(userDataDir, 'transfer_history.json');
    fileManager = new FileManager(receivedDir, {
      loadHistory: () => {
        try {
          if (fs.existsSync(historyPath)) return JSON.parse(fs.readFileSync(historyPath, 'utf8'));
        } catch (_) {}
        return [];
      },
      saveHistory: (history) => {
        try { fs.writeFileSync(historyPath, JSON.stringify(history.slice(0, 200)), 'utf8'); } catch (_) {}
      }
    });
    // PC file browser: only these folders are ever exposed to the phone.
    pcfs = new PcFileSystem({
      loadSettings,
      saveSettings,
      dialog,
      getWindow: () => (mainWindow && !mainWindow.isDestroyed() ? mainWindow : null),
      log: (msg) => logLine('pcfs', msg)
    });
    controlManager = new PcControlManager();

    // --- duo-media protocol: stream received files to the in-app viewer ----
    // Path is validated against the received dir on every request; Range is
    // honoured so <video> can seek without downloading the whole file first.
    const { Readable } = require('stream');
    const MEDIA_MIME = {
      '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.gif': 'image/gif',
      '.webp': 'image/webp', '.bmp': 'image/bmp', '.heic': 'image/heic', '.avif': 'image/avif',
      '.mp4': 'video/mp4', '.webm': 'video/webm', '.mkv': 'video/x-matroska', '.mov': 'video/quicktime',
      '.3gp': 'video/3gpp', '.avi': 'video/x-msvideo',
      '.mp3': 'audio/mpeg', '.m4a': 'audio/mp4', '.wav': 'audio/wav', '.ogg': 'audio/ogg',
      '.opus': 'audio/ogg', '.pdf': 'application/pdf'
    };
    protocol.handle('duo-media', (request) => {
      try {
        const u = new URL(request.url);
        const target = path.resolve(u.searchParams.get('p') || '');
        if (!fileManager.isAllowedMediaPath(target) || !fs.existsSync(target)) {
          return new Response('Not found', { status: 404 });
        }
        const type = MEDIA_MIME[path.extname(target).toLowerCase()] || 'application/octet-stream';
        const size = fs.statSync(target).size;
        const range = request.headers.get('range');
        const m = range && /bytes=(\d*)-(\d*)/.exec(range);
        if (m) {
          let start = m[1] ? parseInt(m[1], 10) : 0;
          let end = m[2] ? parseInt(m[2], 10) : size - 1;
          if (Number.isNaN(start)) start = 0;
          if (Number.isNaN(end) || end >= size) end = size - 1;
          if (start >= size || start > end) {
            return new Response(null, { status: 416, headers: { 'Content-Range': `bytes */${size}` } });
          }
          return new Response(Readable.toWeb(fs.createReadStream(target, { start, end })), {
            status: 206,
            headers: {
              'Content-Type': type,
              'Content-Range': `bytes ${start}-${end}/${size}`,
              'Content-Length': String(end - start + 1),
              'Accept-Ranges': 'bytes'
            }
          });
        }
        return new Response(Readable.toWeb(fs.createReadStream(target)), {
          status: 200,
          headers: { 'Content-Type': type, 'Content-Length': String(size), 'Accept-Ranges': 'bytes' }
        });
      } catch (err) {
        return new Response('Error', { status: 500 });
      }
    });

    // --- V2 feature hubs (state persists preferences only, never message content) ---
    const prefsPath = path.join(userDataDir, 'feature_prefs.json');
    featureHub = new FeatureHub({
      loadPreferences: () => {
        try {
          if (fs.existsSync(prefsPath)) return JSON.parse(fs.readFileSync(prefsPath, 'utf8'));
        } catch (_) {}
        return {};
      },
      savePreferences: (prefs) => {
        try { fs.writeFileSync(prefsPath, JSON.stringify(prefs, null, 2), 'utf8'); } catch (_) {}
      }
    });
    webcamHub = new WebcamHub();
    remotePc = new RemotePcStreamer({
      getSocket: () => {
        // The streamer targets the first authenticated, connected session
        if (!pairingManager) return null;
        for (const session of pairingManager.activeSessions.values()) {
          if (session.socket && session.socket.readyState === 1) return session.socket;
        }
        return null;
      },
      desktopCapturer,
      screen,
      capture: captureAdapter
    });
    // Keep Electron display metrics in sync for absolute/relative input mapping
    try {
      const applyBounds = () => {
        const d = screen.getPrimaryDisplay();
        controlManager.setScreenBounds(d.size.width * (d.scaleFactor || 1), d.size.height * (d.scaleFactor || 1));
      };
      applyBounds();
      screen.on('display-metrics-changed', applyBounds);
      screen.on('display-added', applyBounds);
      screen.on('display-removed', applyBounds);
    } catch (e) { logLine('screen bounds error', e.message); }

    jarvis = new JarvisManager({
      controlManager,
      getMainWindow: () => mainWindow,
      dialog, shell, desktopCapturer, screen,
      sendFileBuffer: (name, buf) => streamBufferToPhone(buf, name),
      broadcast: (msg) => { if (wssServer) wssServer.broadcast(msg); },
      hasSessions: () => !!(pairingManager && pairingManager.activeSessions.size > 0)
    });

    wssOptions = (port) => ({
      cert: tlsCredentials.cert,
      key: tlsCredentials.key,
      port,
      pairingManager,
      fileManager,
      controlManager,
      jarvis,
      featureHub,
      remotePc,
      webcamHub,
      pcfs,
      sendFileToPhone: (filePath) => streamFileToPhone(filePath),
      onRendererEvent: (event, data) => {
        // While the pop-out owns the picture the in-app pane needs only a
        // liveness trickle — shipping every large frame to a renderer that
        // discards it (mirrorPopoutOpen short-circuits its paint) doubled the
        // IPC deserialization cost of the stream and made the new window
        // stutter. Each frame is painted by exactly one window; the main pane
        // still gets ~1 fps so its Live badge and 5s stale timer stay fed.
        const isMirrorFrame = event === 'phone-screen-frame' || event === 'phone-h264-frame';
        const popoutOwnsFrame = isMirrorFrame && screenWin && !screenWin.isDestroyed();
        if (mainWindow && !mainWindow.isDestroyed()) {
          if (!popoutOwnsFrame || (++popoutLivenessTick % 30) === 0) {
            mainWindow.webContents.send(event, data);
          }
        }
        if (event === 'phone-screen-frame' && data && data.jpeg) {
          latestScreenFrame = data;   // cached for instant paint on window open
          if (data.width && data.height) {
            lastScreenOrientation = data.width > data.height ? 'landscape' : 'portrait';
          }
        }
        // The pop-out needs the SPS/PPS before it can decode a single frame;
        // a window opened mid-stream gets the cached config replayed to it.
        if (event === 'phone-h264-config') latestH264Config = data;
        if (event === 'phone-h264-config' && data && data.width && data.height) {
          lastScreenOrientation = data.width > data.height ? 'landscape' : 'portrait';
          fitScreenWindow(data.width, data.height);
        }
        if (screenWin && !screenWin.isDestroyed() && SCREEN_WIN_EVENTS.has(event)) {
          if (event === 'phone-screen-frame' && data && data.width && data.height) {
            fitScreenWindow(data.width, data.height);
          }
          screenWin.webContents.send(event, data);
        }
        // Each app window gets only its own app's frames, by session.
        if (event === 'app-window-frame' && data && data.session) {
          const entry = appWindows.get(String(data.session));
          if (entry && entry.win && !entry.win.isDestroyed()) {
            entry.win.webContents.send('app-window-frame', data);
          }
        }
        if (event === 'app-window-closed' && data && data.session) {
          const entry = appWindows.get(String(data.session));
          if (entry && entry.win && !entry.win.isDestroyed()) entry.win.close();
        }
        if (event === 'app-launch-result' && data && data.ok && data.session) {
          const opened = openAppWindowInternal(data.pkg, '', data.session);
          if (opened && opened.ok === false && mainWindow && !mainWindow.isDestroyed()) {
            mainWindow.webContents.send('app-launch-result', { ok: false, pkg: data.pkg, error: opened.error });
          }
        }
      }
    });

    // Window + tray as soon as managers exist, BEFORE the server binds a port
    setupIpc();
    createWindow();
    try { createTray(); } catch (e) { logLine('tray error', e.message); }

    await startServerWithFallback();
    jarvis.startStats();
  } catch (err) {
    if (!mainWindow) { try { setupIpc(); } catch (_) {} createWindow(); try { createTray(); } catch (_) {} }
    logLine('STARTUP FAILED', err && err.stack || err);      dialog.showErrorBox(`${APP_NAME} could not start its link server`,
        `${err && err.message || err}\n\nThe window will stay open, but phone pairing is unavailable.\nDetails: ${path.join(app.getPath('userData'), 'duolink.log')}`);
  }

  // Global Kill Switch Hotkey: Ctrl+Alt+Shift+Q
  try {
    globalShortcut.register('CommandOrControl+Alt+Shift+Q', () => {
      triggerGlobalKillSwitch();
    });
  } catch (err) {
    logLine('shortcut error', err.message);
  }

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on('will-quit', () => {
  globalShortcut.unregisterAll();
  if (remotePc) remotePc.stop();
  if (webcamHub) webcamHub.stop();
  if (wssServer) {
    wssServer.stop();
  }
  // Release the hidden windows explicitly so nothing outlives the quit path.
  try { if (captureWinDestroyTimer) clearTimeout(captureWinDestroyTimer); } catch (_) {}
  try { if (captureWin && !captureWin.isDestroyed()) captureWin.destroy(); } catch (_) {}
  try { if (screenWin && !screenWin.isDestroyed()) screenWin.destroy(); } catch (_) {}
  for (const entry of appWindows.values()) {
    try { if (entry.win && !entry.win.isDestroyed()) entry.win.destroy(); } catch (_) {}
  }
  appWindows.clear();
});
