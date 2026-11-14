/**
 * PhonePC Pair - Jarvis command layer
 *  - Live PC stats (CPU / RAM / uptime) pushed to the phone
 *  - Safe command handling for the phone's voice assistant
 *
 * Safety rules:
 *  - Every command requires "Allow Phone to Control this PC" to be ON and the kill switch OFF.
 *  - The phone can only name an app from the whitelist below. It can never send a command line.
 *  - URLs must be http(s). Sleep / restart / shutdown need a click on the PC itself.
 */
const os = require('os');
const fs = require('fs');
const path = require('path');
const { execFile } = require('child_process');
const { MessageType, createMessage } = require('./protocol');

const WIN = process.platform === 'win32';

/** Short, readable form of a URL for a spoken reply: fiverr.com/search. */
function shortUrl(u) {
  try {
    const x = new URL(u);
    return (x.hostname.replace(/^www\./, '') + (x.pathname === '/' ? '' : x.pathname)).replace(/\/$/, '');
  } catch (_) {
    return String(u).slice(0, 60);
  }
}

// canonical key -> launch target (Windows). Phone only ever sends the key.
const APPS = {
  chrome: 'chrome',
  edge: 'msedge',
  firefox: 'firefox',
  notepad: 'notepad',
  calculator: 'calc',
  explorer: 'explorer',
  taskmanager: 'taskmgr',
  settings: 'ms-settings:',
  spotify: 'spotify',
  vscode: 'code',
  terminal: 'wt',
  paint: 'mspaint',
  word: 'winword',
  excel: 'excel',
  powerpoint: 'powerpnt',
  whatsapp: 'whatsapp:',
  camera: 'microsoft.windows.camera:',
  store: 'ms-windows-store:',
  snipping: 'snippingtool',
  controlpanel: 'control',
  devicemanager: 'devmgmt.msc',
  downloads: 'shell:Downloads',
  documents: 'shell:Personal',
  desktopfolder: 'shell:Desktop'
};

// app key -> process image name, for a polite (non-forced) close request
const PROCESSES = {
  chrome: 'chrome.exe', edge: 'msedge.exe', firefox: 'firefox.exe', notepad: 'notepad.exe',
  calculator: 'CalculatorApp.exe', spotify: 'Spotify.exe', vscode: 'Code.exe',
  word: 'WINWORD.EXE', excel: 'EXCEL.EXE', powerpoint: 'POWERPNT.EXE', paint: 'mspaint.exe'
};

// shortcut name -> [spoken reply, nut-js key names]
const HOTKEYS = {
  SHOW_DESKTOP: ['Showing the desktop.', ['LeftSuper', 'D']],
  ALT_TAB: ['Switching window.', ['LeftAlt', 'Tab']],
  TASK_VIEW: ['Opening task view.', ['LeftSuper', 'Tab']],
  CLOSE_WINDOW: ['Closing the window.', ['LeftAlt', 'F4']],
  NEW_TAB: ['New tab.', ['LeftControl', 'T']],
  CLOSE_TAB: ['Closing the tab.', ['LeftControl', 'W']],
  NEXT_TAB: ['Next tab.', ['LeftControl', 'Tab']],
  REFRESH: ['Refreshing.', ['F5']],
  FULLSCREEN: ['Toggling full screen.', ['F11']],
  COPY: ['Copied.', ['LeftControl', 'C']],
  PASTE: ['Pasted.', ['LeftControl', 'V']],
  CUT: ['Cut.', ['LeftControl', 'X']],
  SELECT_ALL: ['Selected everything.', ['LeftControl', 'A']],
  UNDO: ['Undone.', ['LeftControl', 'Z']],
  REDO: ['Redone.', ['LeftControl', 'Y']],
  SAVE: ['Saved.', ['LeftControl', 'S']],
  SNIP: ['Opening the snipping tool.', ['LeftSuper', 'LeftShift', 'S']],
  BACK: ['Going back.', ['LeftAlt', 'Left']],
  PREV_TAB: ['Previous tab.', ['LeftControl', 'LeftShift', 'Tab']],
  MAXIMIZE: ['Maximizing the window.', ['LeftSuper', 'Up']],
  MINIMIZE: ['Minimizing the window.', ['LeftSuper', 'Down']],
  NEW_DESKTOP: ['New desktop.', ['LeftControl', 'LeftSuper', 'D']],
  NEXT_DESKTOP: ['Next desktop.', ['LeftControl', 'LeftSuper', 'Right']],
  PREV_DESKTOP: ['Previous desktop.', ['LeftControl', 'LeftSuper', 'Left']]
};

class JarvisManager {
  constructor({ controlManager, getMainWindow, dialog, shell, desktopCapturer, screen, sendFileBuffer, broadcast, hasSessions }) {
    this.control = controlManager;
    this.getMainWindow = getMainWindow;
    this.dialog = dialog;
    this.shell = shell;
    this.desktopCapturer = desktopCapturer;
    this.screen = screen;
    this.sendFileBuffer = sendFileBuffer;
    this.broadcast = broadcast;
    this.hasSessions = hasSessions;
    this.lastCmdAt = 0;
    this.statsTimer = null;
    this.prevCpu = this._cpuTimes();
    this.confirming = false;
    // PC disk + GPU usage for the phone's telemetry card. Sampled in the
    // background (PowerShell takes ~1s) and cached; -1 = not reported yet.
    this.diskSample = { pct: -1, usedGb: 0, totalGb: 0 };
    this.gpuPct = -1;
    this.lastDiskAt = 0;
    this.lastGpuAt = 0;
  }

  // ---------------- Stats ----------------
  _cpuTimes() {
    let idle = 0, total = 0;
    for (const c of os.cpus()) {
      for (const k of Object.keys(c.times)) total += c.times[k];
      idle += c.times.idle;
    }
    return { idle, total };
  }

  sampleStats() {
    const now = this._cpuTimes();
    const dIdle = now.idle - this.prevCpu.idle;
    const dTotal = now.total - this.prevCpu.total;
    this.prevCpu = now;
    const cpu = dTotal > 0 ? Math.round((1 - dIdle / dTotal) * 100) : 0;
    const totalMem = os.totalmem();
    const usedMem = totalMem - os.freemem();
    return {
      cpu: Math.max(0, Math.min(100, cpu)),
      memPct: Math.round((usedMem / totalMem) * 100),
      memUsedGb: +(usedMem / 1073741824).toFixed(1),
      memTotalGb: +(totalMem / 1073741824).toFixed(1),
      uptimeSec: Math.round(os.uptime()),
      host: os.hostname(),
      platform: process.platform,
      // PC disk + GPU (cached; -1 until the first sample lands)
      diskPct: this.diskSample.pct,
      diskUsedGb: this.diskSample.usedGb,
      diskTotalGb: this.diskSample.totalGb,
      gpuPct: this.gpuPct
    };
  }

  // Background sampler: DISK ACTIVE TIME (the number Task Manager shows in
  // its Disk column — % of time the disks were busy), NOT capacity used.
  // The old sampler reported "how full the drive is", which never matches
  // Task Manager and read as a fake number on the phone's telemetry card.
  // Falls back to capacity only if the performance counter is unavailable.
  _sampleDisk() {
    if (!WIN) return;
    if (this._sampleBusy.disk) return;      // never overlap probes
    this._sampleBusy.disk = true;
    const ps = "$c = Get-Counter '\\PhysicalDisk(_Total)\\% Disk Time' -ErrorAction SilentlyContinue; " +
      'if ($c) { [int][Math]::Min(100, [Math]::Round($c.CounterSamples[0].CookedValue)) } else { -1 }';
    execFile('powershell.exe', ['-NoProfile', '-Command', ps],
      { timeout: 15000, windowsHide: true }, (err, out) => {
        if (err || !out) { this._sampleFail('disk'); return; }
        const n = parseInt(String(out).trim(), 10);
        if (Number.isFinite(n) && n >= 0) {
          this.diskSample = { pct: Math.min(100, n), usedGb: 0, totalGb: 0 };
          this._sampleOk('disk');
          return;
        }
        // Counter missing (non-English locale, exotic storage): fall back to
        // capacity so the gauge still shows a truthful "how full" number.
        execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
          "$d = Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=3'; " +
          '$t = ($d | Measure-Object Size -Sum).Sum; $f = ($d | Measure-Object FreeSpace -Sum).Sum; ' +
          'Write-Output "$t $f"'],
          { timeout: 15000, windowsHide: true }, (e2, out2) => {
            if (e2 || !out2) { this._sampleFail('disk'); return; }
            const m = String(out2).trim().split(/\s+/);
            const total = Number(m[0]), free = Number(m[1]);
            if (Number.isFinite(total) && total > 0 && Number.isFinite(free)) {
              this.diskSample = {
                pct: Math.max(0, Math.min(100, Math.round(((total - free) / total) * 100))),
                usedGb: +((total - free) / 1073741824).toFixed(1),
                totalGb: +(total / 1073741824).toFixed(0)
              };
              this._sampleOk('disk');
            } else {
              this._sampleFail('disk');
            }
          });
      });
  }

  // Background sampler: GPU busy % from performance counters (10s cadence).
  // Sums every engine context and caps at 100 — engines share the GPU, so the
  // capped sum approximates overall utilisation honestly.
  _sampleGpu() {
    if (!WIN) return;
    if (this._sampleBusy.gpu) return;       // never overlap probes
    this._sampleBusy.gpu = true;
    const ps = "$c = Get-Counter '\\GPU Engine(*)\\Utilization Percentage' -ErrorAction SilentlyContinue; " +
      'if ($c) { $s = ($c.CounterSamples | Measure-Object CookedValue -Sum).Sum; ' +
      '[int][Math]::Min(100, [Math]::Round($s)) } else { -1 }';
    execFile('powershell.exe', ['-NoProfile', '-Command', ps],
      { timeout: 15000, windowsHide: true }, (err, out) => {
        if (err || !out) { this._sampleFail('gpu'); return; }
        const n = parseInt(String(out).trim(), 10);
        if (Number.isFinite(n) && n >= 0) { this.gpuPct = Math.min(100, n); this._sampleOk('gpu'); }
      });
  }

  startStats(intervalMs = 2000) {
    this.stopStats();
    // Sampler process budget. Each disk/GPU sample spawns ONE PowerShell run-
    // ning Get-Counter — a WMI/perf-counter query. That is cheap on a healthy
    // machine, but when the counter infrastructure is broken the probe FAILS
    // every time, and retrying a failed probe forever keeps the diagnostics
    // stack (WmiPrvSE / perflib / Diagnostic Policy Service) churning CPU and
    // disk while DuoLink sits idle. So the samplers obey three rules:
    //  - at most one probe of each kind in flight (no overlapping spawns),
    //  - each failure doubles the retry delay (5s -> 10s -> … -> 60s cap),
    //  - after 3 consecutive failures the sampler STOPS until a phone
    //    reconnects, so a broken counter costs three probes, never a spawn
    //    loop. The in-app gauges keep the last good value meanwhile.
    this._sampleBusy = { disk: false, gpu: false };
    this._sampleFails = { disk: 0, gpu: 0 };
    this._sampleBackoff = { disk: 15000, gpu: 30000 };
    this.statsTimer = setInterval(() => {
      if (!this.hasSessions()) { this._statsWarm = false; return; }
      // A phone that just (re)connected gets a FRESH sample immediately instead
      // of waiting out a full cadence window with a stale or empty number.
      const now = Date.now();
      if (!this._statsWarm) {
        this._statsWarm = true;
        this.lastDiskAt = 0;
        this.lastGpuAt = 0;
        // New session: the probes get their full failure budget back.
        this._sampleFails = { disk: 0, gpu: 0 };
        this._sampleBackoff = { disk: 15000, gpu: 30000 };
        this._sampleBusy = { disk: false, gpu: false };
      }
      // Refresh the cached disk/GPU samples only while a phone is listening.
      // Steady-state cadence (15s / 30s) keeps both gauges moving with a
      // fraction of the process spawns the old 6s/10s loop produced.
      if (now - this.lastDiskAt >= this._sampleBackoff.disk) { this.lastDiskAt = now; this._sampleDisk(); }
      if (now - this.lastGpuAt >= this._sampleBackoff.gpu) { this.lastGpuAt = now; this._sampleGpu(); }
      this.broadcast(createMessage(MessageType.SYSTEM_STATS, this.sampleStats()));
    }, intervalMs);
    if (this.statsTimer.unref) this.statsTimer.unref();
  }

  stopStats() {
    if (this.statsTimer) clearInterval(this.statsTimer);
    this.statsTimer = null;
  }

  /** A sampler probe answered: back to the healthy cadence, budget reset. */
  _sampleOk(kind) {
    this._sampleBusy[kind] = false;
    this._sampleFails[kind] = 0;
    this._sampleBackoff[kind] = kind === 'disk' ? 15000 : 30000;
  }

  /**
   * A sampler probe failed (spawn error, timeout, or unusable output).
   * Doubles that sampler's delay; after the third strike it stops for the
   * whole session instead of spawning PowerShell forever.
   */
  _sampleFail(kind) {
    this._sampleBusy[kind] = false;
    const n = ++this._sampleFails[kind];
    if (n >= 3) {
      this._sampleBackoff[kind] = Infinity;   // parked; reset on reconnect
      console.log(`[Jarvis] ${kind} sampler parked after ${n} failed probes (resumes when a phone reconnects)`);
      return;
    }
    this._sampleBackoff[kind] = Math.min(60000, 5000 * Math.pow(2, n - 1));
  }

  // ---------------- Commands ----------------
  /**
   * Resolve a spoken Chrome/Edge profile ("ali 2", or just "2") to the actual
   * --profile-directory value. The browser's Local State keeps the DISPLAY
   * name next to each directory, so a profile the user renamed to "Ali 2"
   * still opens even though its directory is e.g. "Profile 3".
   * @returns {{dir: string, label: string}}
   */
  _resolveProfileDir(browser, profileName, digits) {
    const userData = process.env.LOCALAPPDATA || '';
    const base = browser === 'chrome'
      ? path.join(userData, 'Google', 'Chrome', 'User Data')
      : path.join(userData, 'Microsoft', 'Edge', 'User Data');
    const fallback = digits ? `Profile ${digits}` : 'Default';
    const fallbackLabel = digits ? `profile ${digits}` : 'Default';
    try {
      const raw = fs.readFileSync(path.join(base, 'Local State'), 'utf8');
      const info = (JSON.parse(raw).profile || {}).info_cache || {};
      const dirs = Object.keys(info);
      if (!dirs.length) return { dir: fallback, label: fallbackLabel };

      const norm = (s) => String(s || '').toLowerCase().replace(/\s+/g, ' ').trim();
      const want = norm(profileName);
      if (want) {
        // Exact displayed name, then a forgiving "contains" match
        // ("ali 2" -> "Ali 2 (Work)"), then the number inside the spoken name.
        const hit = dirs.find((d) => norm(info[d] && info[d].name) === want)
          || dirs.find((d) => {
            const n = norm(info[d] && info[d].name);
            return n && (n.includes(want) || want.includes(n));
          })
          || (want.match(/\d+/) ? dirs.find((d) => /^profile \d+$/.test(d) &&
              new RegExp(`(^|\\D)${want.match(/\d+/)[0]}(\\D|$)`).test(d)) : null);
        if (hit) return { dir: hit, label: (info[hit] && info[hit].name) || hit };
      }
      if (digits) {
        const byNumber = `Profile ${digits}`;
        if (dirs.includes(byNumber)) return { dir: byNumber, label: (info[byNumber] && info[byNumber].name) || byNumber };
      }
      return { dir: fallback, label: fallbackLabel };
    } catch (_) {
      // Local State unreadable (no browser installed yet): old numeric behavior.
      return { dir: fallback, label: fallbackLabel };
    }
  }

  async handleCommand(ws, payload) {
    const { requestId, action, args } = payload || {};
    const a = args || {};
    const reply = (ok, message) => {
      try {
        ws.send(JSON.stringify(createMessage(MessageType.PC_COMMAND_RESULT, { requestId, action, ok, message })));
      } catch (_) {}
    };

    if (!this.control.allowed || this.control.isKillSwitched) {
      return reply(false, 'Remote control is switched off on the PC.');
    }
    const now = Date.now();
    if (now - this.lastCmdAt < 250) return reply(false, 'Too many commands, slow down.');
    this.lastCmdAt = now;

    try {
      switch (String(action || '').toUpperCase()) {
        case 'LAUNCH_APP': {
          const key = String(a.app || '').toLowerCase();
          const target = APPS[key];
          if (!target) return reply(false, `I don't know the app "${key}".`);
          if (!WIN) return reply(false, 'Launching apps is only supported on Windows.');
          // "open fiverr in ali 2 chrome profile" -> launch Chrome as THAT
          // profile (matched by its displayed name first, then by number) with
          // the page already open in it. Profile args are sanitised to safe
          // characters and quoted, so neither a name nor a URL can break the
          // command line.
          const digits = String(a.profile || '').replace(/\D/g, '');
          const profileName = String(a.profileName || '')
            .replace(/[^\p{L}\p{N} ._-]/gu, '').trim().slice(0, 64);
          const urlArg = /^https?:\/\//i.test(String(a.url || '')) ? String(a.url) : '';
          if ((digits || profileName) && (key === 'chrome' || key === 'edge')) {
            const { dir, label } = this._resolveProfileDir(key, profileName, digits);
            const cmdArgs = ['start', '', target, `"--profile-directory=${dir}"`];
            if (urlArg) cmdArgs.push(`"${urlArg.replace(/["^%]/g, '')}"`);
            execFile('cmd.exe', ['/c', ...cmdArgs], { windowsHide: true }, (err) => {
              if (err) console.error('[Jarvis] profile launch failed:', err.message);
            });
            return reply(
              true,
              urlArg
                ? `Opening ${shortUrl(urlArg)} in Chrome (${label}).`
                : `Opening Chrome profile ${label}.`
            );
          }
          const plainArgs = ['start', '', target];
          if (urlArg) plainArgs.push(`"${urlArg.replace(/["^%]/g, '')}"`);
          execFile('cmd.exe', ['/c', ...plainArgs], { windowsHide: true }, (err) => {
            if (err) console.error('[Jarvis] launch failed:', err.message);
          });
          return reply(true, `Opening ${key}.`);
        }

        case 'CLOSE_APP': {
          const key = String(a.app || '').toLowerCase();
          const exe = PROCESSES[key];
          if (!exe) return reply(false, `I can't close "${key}".`);
          if (!WIN) return reply(false, 'Closing apps is only supported on Windows.');
          // no /F: asks the app to close politely so it can offer to save your work
          execFile('taskkill', ['/IM', exe], { windowsHide: true }, () => {});
          return reply(true, `Closing ${key}.`);
        }

        case 'HOTKEY': {
          const entry = HOTKEYS[String(a.name || '').toUpperCase()];
          if (!entry) return reply(false, 'Unknown shortcut.');
          const ok = await this.control.pressKeys(entry[1]);
          return reply(ok, ok ? entry[0] : 'Keyboard control is not available on the PC.');
        }

        case 'OPEN_URL': {
          let u;
          try { u = new URL(String(a.url || '')); } catch (_) { return reply(false, 'That link is not valid.'); }
          if (u.protocol !== 'https:' && u.protocol !== 'http:') return reply(false, 'Only web links can be opened.');
          await this.shell.openExternal(u.toString());
          return reply(true, 'Opening it in your browser.');
        }

        case 'SCREENSHOT': {
          const name = await this._screenshot();
          return reply(true, `Screenshot sent to your phone as ${name}.`);
        }

        case 'POWER':
          return await this._power(String(a.kind || '').toUpperCase(), reply);

        case 'GET_STATS':
          ws.send(JSON.stringify(createMessage(MessageType.SYSTEM_STATS, this.sampleStats())));
          return reply(true, 'Stats updated.');

        case 'VOLUME_SET': {
          // Windows has no public CLI for the master volume, so drive the media
          // keys: bottom out with VOLUME_DOWN, then walk up to the target.
          // One PowerShell call with a small sleep keeps the cadence reliable.
          if (!WIN) return reply(false, 'Volume control is only supported on Windows.');
          const level = Math.max(0, Math.min(100, parseInt(a.level, 10) || 0));
          const ups = Math.round(level / 2); // media keys move in 2% steps
          const cmd = "$s=New-Object -ComObject WScript.Shell;" +
            "1..50|%{$s.SendKeys([char]174);Start-Sleep -m 10};" +
            (ups > 0 ? `1..${ups}|%{$s.SendKeys([char]175);Start-Sleep -m 10}` : '');
          execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', cmd], { windowsHide: true }, (err) => {
            if (err) return reply(false, 'Could not change the volume.');
            reply(true, `Volume set to ${level} percent.`);
          });
          return;
        }

        case 'BRIGHTNESS': {
          if (!WIN) return reply(false, 'Brightness control is only supported on Windows.');
          const level = Math.max(0, Math.min(100, parseInt(a.level, 10) || 0));
          const ps = `(Get-WmiObject -Namespace root/WMI -Class WmiMonitorBrightnessMethods).WmiSetBrightness(1,${level})`;
          execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', ps], { windowsHide: true, timeout: 8000 }, (err) => {
            if (err) return reply(false, 'This PC does not support brightness control (desktop monitors have none).');
            reply(true, `Brightness set to ${level} percent.`);
          });
          return;
        }

        default:
          return reply(false, `Unknown command ${action}.`);
      }
    } catch (err) {
      console.error('[Jarvis] command error:', err);
      return reply(false, 'That did not work: ' + err.message);
    }
  }

  async _screenshot() {
    const d = this.screen.getPrimaryDisplay();
    const w = Math.min(2560, Math.round(d.size.width * d.scaleFactor));
    const h = Math.min(1600, Math.round(d.size.height * d.scaleFactor));
    const sources = await this.desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: w, height: h } });
    if (!sources.length) throw new Error('No screen available to capture');
    const png = sources[0].thumbnail.toPNG();
    const stamp = new Date().toISOString().replace(/[:T]/g, '-').slice(0, 19);
    const name = `pc-screenshot-${stamp}.png`;
    await this.sendFileBuffer(name, png);
    return name;
  }

  async _power(kind, reply) {
    if (kind === 'LOCK') {
      this.control.handleSystemAction('LOCK_PC');
      return reply(true, 'Locking your PC.');
    }
    if (!['SLEEP', 'RESTART', 'SHUTDOWN'].includes(kind)) return reply(false, 'Unknown power action.');
    if (!WIN) return reply(false, 'Power commands are only supported on Windows.');

    if (this.confirming) return reply(false, 'Another confirmation is already open on the PC.');
    this.confirming = true;
    let timedOut = false;
    let confirmed = false;
    try {
      const ac = new AbortController();
      const timer = setTimeout(() => { timedOut = true; ac.abort(); }, 20000);
      const win = this.getMainWindow();
      const opts = {
        type: 'warning',
        title: 'Phone request',
        message: `Your phone wants to ${kind.toLowerCase()} this PC.`,
        detail: 'Unsaved work may be lost. This request expires in 20 seconds.',
        buttons: ['Cancel', kind.charAt(0) + kind.slice(1).toLowerCase()],
        defaultId: 0,
        cancelId: 0,
        noLink: true,
        signal: ac.signal
      };
      const res = await (win && !win.isDestroyed() ? this.dialog.showMessageBox(win, opts) : this.dialog.showMessageBox(opts));
      clearTimeout(timer);
      confirmed = res.response === 1 && !timedOut;
    } catch (_) {
      confirmed = false;
    } finally {
      this.confirming = false;
    }
    if (!confirmed) return reply(false, `The ${kind.toLowerCase()} was not confirmed on the PC.`);

    if (kind === 'SLEEP') execFile('rundll32.exe', ['powrprof.dll,SetSuspendState', '0,1,0']);
    if (kind === 'RESTART') execFile('shutdown', ['/r', '/t', '20']);
    if (kind === 'SHUTDOWN') execFile('shutdown', ['/s', '/t', '20']);
    return reply(true, kind === 'SLEEP' ? 'Going to sleep.' : `Your PC will ${kind.toLowerCase()} in 20 seconds.`);
  }
}

module.exports = { JarvisManager, APPS, PROCESSES, HOTKEYS };
