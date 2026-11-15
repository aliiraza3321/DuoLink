/**
 * Wireless ADB bridge for the Apps pane.
 *
 * Talks to the Android Debug Bridge that ships with the Android SDK so a phone
 * paired over Wireless debugging can list its launchable apps and open one from
 * this PC. Everything runs through execFile with an argument array: nothing is
 * ever handed to a shell, and every address/package is validated first.
 */

const path = require('path');
const fs = require('fs');
const { execFile } = require('child_process');
const { app } = require('electron');

const ADDR_RE = /^[A-Za-z0-9.\-]+:\d{1,5}$/;
const CODE_RE = /^\d{6}$/;
const PKG_RE = /^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+){1,}$/;

// Labels the Play Store apps most people actually open. Anything unknown falls
// back to a tidied package name, so the list is never blank.
const KNOWN_LABELS = {
  'com.whatsapp': 'WhatsApp',
  'com.whatsapp.w4b': 'WhatsApp Business',
  'com.instagram.android': 'Instagram',
  'org.telegram.messenger': 'Telegram',
  'org.telegram.plus': 'Telegram Plus',
  'com.google.android.youtube': 'YouTube',
  'com.google.android.apps.youtube.music': 'YouTube Music',
  'com.android.chrome': 'Chrome',
  'com.facebook.katana': 'Facebook',
  'com.facebook.orca': 'Messenger',
  'com.google.android.gm': 'Gmail',
  'com.spotify.music': 'Spotify',
  'com.google.android.apps.photos': 'Photos',
  'com.google.android.dialer': 'Phone',
  'com.android.settings': 'Settings',
  'com.google.android.apps.maps': 'Maps',
  'com.google.android.apps.messaging': 'Messages',
  'com.google.android.apps.docs': 'Drive',
  'com.google.android.calendar': 'Calendar',
  'com.google.android.apps.wellbeing': 'Digital Wellbeing',
  'com.linkedin.android': 'LinkedIn',
  'com.twitter.android': 'X',
  'com.snapchat.android': 'Snapchat',
  'com.zhiliaoapp.musically': 'TikTok',
  'com.netflix.mediaclient': 'Netflix',
  'com.amazon.avod.thirdpartyclient': 'Prime Video',
  'com.microsoft.teams': 'Teams',
  'com.Slack': 'Slack',
  'com.discord': 'Discord',
  'com.android.camera': 'Camera',
  'com.google.android.GoogleCamera': 'Camera',
  'com.android.vending': 'Play Store',
  'com.google.android.apps.nbu.files': 'Files',
  'com.google.android.deskclock': 'Clock',
  'com.google.android.keep': 'Keep Notes',
  'com.google.android.apps.tachyon': 'Meet',
  'us.zoom.videomeetings': 'Zoom',
  'com.opera.browser': 'Opera',
  'com.opera.mini.native': 'Opera Mini',
  'com.uc.browser.en': 'UC Browser',
  'com.jazz.jazzworld': 'Jazz World',
  'com.zong.customercare': 'My Zong',
  'pk.gov.pitb.covid19': 'COVID-19',
  'com.easypaisa.app': 'Easypaisa',
  'com.jazzcash.app': 'JazzCash',
  'com.financeit.meezan': 'Meezan',
  'com.hbl.android.hblmobilebanking': 'HBL',
  'com.ubercab': 'Uber',
  'com.ubercab.eats': 'Uber Eats',
  'com.foodpanda.android': 'foodpanda',
  'com.bykea.app': 'Bykea',
  'com.daraz.android': 'Daraz',
  'com.olx.pk': 'OLX',
  'com.pakwheels': 'PakWheels',
  'com.app.sharechat': 'ShareChat',
  'com.truecaller': 'Truecaller',
  'com.termux': 'Termux',
  'com.termux.api': 'Termux:API',
  'com.google.android.apps.authenticator2': 'Authenticator',
  'com.google.android.apps.sheets': 'Sheets',
  'com.google.android.apps.play': 'Play Store',
  'com.motach.videoplay': 'Motach Video',
  'com.video.fun.app': 'Video Fun',
  'com.sadapay.app': 'SadaPay',
  'com.instagram.barcelona': 'Threads',
  'pk.upaisa.com': 'UPaisa',
  'com.fiverr.fiverr': 'Fiverr',
  'org.zennolab.android': 'ZennoLab',
  'com.tencent.ig': 'PUBG Mobile',
  'com.tencent.tmgp.pubgmhd': 'PUBG Mobile',
  'com.lemon.lvoverseas': 'CapCut',
  'com.thehive.mbm': 'MBM',
  'com.pakistan.teams': 'Pakistan',
  'com.snapchat.android': 'Snapchat',

  'com.android.shell': 'Shell',
};

const GENERIC_SEGMENTS = new Set([
  'android', 'app', 'apps', 'mobile', 'client', 'release', 'beta', 'free', 'pro', 'plus',
  'main', 'ui', 'tv', 'com', 'org', 'net', 'io', 'me', 'co', 'pk', 'in', 'lite', 'hd',
]);

// "com.motach.videoplay" -> "Motach Videoplay", "com.sadapay.app" -> "Sadapay".
// Only a fallback: the curated map above wins whenever it knows the package.
function prettify(pkg) {
  const parts = String(pkg || '').toLowerCase().split('.').filter(Boolean);
  const meaningful = parts.filter((p) => !GENERIC_SEGMENTS.has(p) && !/^[a-z]?\d+$/.test(p));
  const words = (meaningful.length ? meaningful : parts).slice(0, 2).join(' ');
  return words
    .replace(/[_-]+/g, ' ')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
      .replace(/\b\w/g, (c) => c.toUpperCase());
}

class WirelessAdb {
  constructor() {
    this.adbPath = null;
  }

  /** First working adb: explicit env var, the SDK next to this app, PATH. */
  locate() {
    if (this.adbPath && fs.existsSync(this.adbPath)) return this.adbPath;
    const candidates = [];
    if (process.env.ADB_PATH) candidates.push(process.env.ADB_PATH);
    const sdkRoots = [
      process.env.ANDROID_SDK_ROOT,
      process.env.ANDROID_HOME,
      process.env.LOCALAPPDATA ? path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk') : null,
      process.env.USERPROFILE ? path.join(process.env.USERPROFILE, 'AppData', 'Local', 'Android', 'Sdk') : null,
      process.env.HOME ? path.join(process.env.HOME, 'Android', 'Sdk') : null,
    ].filter(Boolean);
    for (const root of sdkRoots) {
      candidates.push(path.join(root, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb'));
    }
    try {
      const bundled = path.join(app.getAppPath(), '..', 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
      candidates.push(bundled);
    } catch (_) { /* app not ready in tests */ }
    for (const candidate of candidates) {
      try {
        if (candidate && fs.existsSync(candidate)) {
          this.adbPath = candidate;
          return candidate;
        }
      } catch (_) { /* unreadable path: try the next one */ }
    }
    this.adbPath = 'adb';
    return this.adbPath;
  }

  run(args, timeout = 25000) {
    const adb = this.locate();
    return new Promise((resolve) => {
      execFile(adb, args, { windowsHide: true, timeout, maxBuffer: 8 * 1024 * 1024 }, (err, stdout, stderr) => {
        const out = String(stdout || '').trim();
        const errText = String(stderr || '').trim();
        if (err && !out && !errText) {
          resolve({ ok: false, error: err.code === 'ENOENT' ? 'adb not found — install Android platform-tools' : (err.message || 'adb failed'), output: '' });
          return;
        }
        resolve({ ok: !err, output: out, error: errText || (err ? String(err.message || '') : ''), code: err ? err.code : 0 });
      });
    });
  }

  async devices() {
    const res = await this.run(['devices', '-l']);
    if (!res.ok && !res.output) return { ok: false, error: res.error, devices: [] };
    const devices = [];
    for (const line of String(res.output).split(/\r?\n/).slice(1)) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith('*')) continue;
      const [serial, state] = trimmed.split(/\s+/);
      if (!serial) continue;
      const model = (trimmed.match(/model:(\S+)/) || [])[1] || '';
      devices.push({ serial, state, model: model.replace(/_/g, ' ') });
    }
    return { ok: true, devices };
  }

  async status() {
    const found = this.locate();
    const list = await this.devices();
    const usable = list.devices.filter((d) => d.state === 'device');
    return {
      ok: true,
      adb: found,
      adbFound: found !== 'adb' || list.devices.length > 0 || !list.error,
      devices: list.devices,
      serial: usable.length ? usable[0].serial : null,
      error: list.error || '',
    };
  }

  async pair(addr, code) {
    if (!ADDR_RE.test(String(addr || ''))) return { ok: false, error: 'Enter the pairing IP:port shown on the phone' };
    if (!CODE_RE.test(String(code || ''))) return { ok: false, error: 'Enter the 6-digit pairing code' };
    const res = await this.run(['pair', addr, code], 40000);
    const text = `${res.output}\n${res.error}`;
    const failed = /failed|cannot|error|unable|wrong/i.test(text) && !/Successfully paired/i.test(text);
    return {
      ok: !failed && /Successfully paired|already paired/i.test(text) ? true : !failed && res.ok,
      output: res.output,
      error: failed ? (res.output || res.error || 'Pairing failed') : '',
    };
  }

  async connect(addr) {
    if (!ADDR_RE.test(String(addr || ''))) return { ok: false, error: 'Enter the device address as IP:port' };
    const res = await this.run(['connect', addr], 25000);
    const text = `${res.output}\n${res.error}`;
    const ok = /connected to/i.test(text) && !/failed|unable|cannot/i.test(text);
    return { ok, serial: ok ? addr : null, output: res.output, error: ok ? '' : (res.output || res.error || 'Could not connect') };
  }

  async disconnect(addr) {
    const args = ['disconnect'];
    if (addr && ADDR_RE.test(String(addr))) args.push(addr);
    const res = await this.run(args, 15000);
    return { ok: res.ok, output: res.output, error: res.error };
  }

  /** Launchable third-party packages for one device. */
  async apps(serial) {
    if (!serial) return { ok: false, error: 'No phone connected', apps: [] };
    const res = await this.run(['-s', serial, 'shell', 'pm', 'list', 'packages', '-3'], 30000);
    if (!res.ok && !res.output) return { ok: false, error: res.error || 'Could not read the app list', apps: [] };
    const apps = String(res.output)
      .split(/\r?\n/)
      .map((l) => l.trim().replace(/^package:/, ''))
      .filter((pkg) => PKG_RE.test(pkg))
      .map((pkg) => ({ pkg, label: KNOWN_LABELS[pkg] || prettify(pkg) }))
      .sort((a, b) => a.label.localeCompare(b.label));
    return { ok: true, apps, error: '' };
  }

  /** Open the app on the phone itself (its launcher activity). */
  async launch(serial, pkg) {
    if (!serial) return { ok: false, error: 'No phone connected' };
    if (!PKG_RE.test(String(pkg || ''))) return { ok: false, error: 'Unknown package' };
    const res = await this.run(
      ['-s', serial, 'shell', 'monkey', '-p', pkg, '-c', 'android.intent.category.LAUNCHER', '1'],
      20000
    );
    const text = `${res.output}\n${res.error}`;
    const ok = /Events injected: 1|injected/i.test(text) && !/No activities found|aborted|Error/i.test(text);
    return { ok, output: res.output, error: ok ? '' : (text.trim().split('\n').pop() || 'Could not open that app') };
  }
}

module.exports = { WirelessAdb, prettify, KNOWN_LABELS };
