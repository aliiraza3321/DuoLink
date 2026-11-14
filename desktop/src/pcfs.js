/**
 * PcFileSystem — the only code allowed to touch the PC's disk on behalf of the
 * phone. Everything the phone asks for is validated here; nothing outside an
 * approved root is ever listed, read, renamed or deleted.
 *
 * Roots come from Settings → Phone file access (`sharedFolders` in
 * settings.json). A folder outside those roots can be opened only after the
 * person sitting at the PC approves it in a native dialog; approval adds the
 * folder to the same list, so it is explicit and revocable.
 */

const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const { shell } = require('electron');

const MAX_ENTRIES = 5000;

/** Case-insensitive "is child of root" check that cannot be fooled by `..`. */
function isInside(root, target) {
  const rel = path.relative(path.resolve(root), path.resolve(target));
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel));
}

function sanitizeName(name) {
  // Reject separators instead of silently taking the basename: a name like
  // "../escape" must fail loudly, not turn into a folder called "escape".
  const raw = String(name || '').trim();
  if (!raw || raw === '.' || raw === '..') return null;
  if (raw.includes('\0')) return null;
  if (/[\/:*?"<>|]/.test(raw)) return null;
  if (raw.length > 200) return null;
  return raw;
}

function sizeLabel(bytes) {
  const n = Number(bytes) || 0;
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`;
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

class PcFileSystem {
  /**
   * @param {object} opts
   * @param {() => object} opts.loadSettings
   * @param {(patch: object) => object} opts.saveSettings
   * @param {import('electron').Dialog} opts.dialog
   * @param {() => import('electron').BrowserWindow|null} opts.getWindow
   * @param {(msg: string) => void} [opts.log]
   */
  constructor({ loadSettings, saveSettings, dialog, getWindow, log }) {
    this.loadSettings = loadSettings;
    this.saveSettings = saveSettings;
    this.dialog = dialog;
    this.getWindow = getWindow;
    this.log = log || (() => {});
    this.approving = new Set();
  }

  /** Defaults used until the user edits the list in Settings. */
  defaultRoots() {
    const home = require('electron').app.getPath('home');
    const candidates = [
      path.join(require('electron').app.getPath('downloads'), 'DuoLink'),
      path.join(home, 'Documents'),
      path.join(home, 'Pictures'),
      path.join(home, 'Videos')
    ];
    const out = [];
    for (const dir of candidates) {
      try {
        if (fs.statSync(dir).isDirectory() && !out.includes(dir)) out.push(dir);
      } catch (_) { /* folder does not exist on this PC */ }
    }
    // Always leave at least one usable root, even on a stripped-down profile.
    if (!out.length) {
      try { out.push(require('electron').app.getPath('downloads')); } catch (_) {}
    }
    return out;
  }

  /** True when the PC owner allowed the phone to browse the whole machine. */
  fullAccess() {
    const s = this.loadSettings();
    return s.fileAccessAll === true;
  }

  setFullAccess(on) {
    this.saveSettings({ fileAccessAll: !!on });
    this._tempRoots = [];
    return this.fullAccess();
  }

  roots() {
    const s = this.loadSettings();
    // Full access: every mounted drive is a root, so the phone can reach the
    // same places Explorer can. Windows ACLs still decide what is readable.
    if (s.fileAccessAll === true) return this.drives().map((d) => path.resolve(d));
    const configured = Array.isArray(s.sharedFolders) ? s.sharedFolders : null;
    const list = configured && configured.length ? configured : (configured ? [] : this.defaultRoots());
    const seen = new Set();
    return list
      .filter((p) => typeof p === 'string' && p)
      .map((p) => path.resolve(p))
      .filter((p) => {
        if (seen.has(p.toLowerCase())) return false;
        seen.add(p.toLowerCase());
        try { return fs.statSync(p).isDirectory(); } catch (_) { return false; }
      });
  }

  setRoots(list) {
    const clean = [];
    const seen = new Set();
    for (const p of Array.isArray(list) ? list : []) {
      if (typeof p !== 'string' || !p) continue;
      const abs = path.resolve(p);
      try { if (!fs.statSync(abs).isDirectory()) continue; } catch (_) { continue; }
      if (seen.has(abs.toLowerCase())) continue;
      seen.add(abs.toLowerCase());
      clean.push(abs);
    }
    this.saveSettings({ sharedFolders: clean });
    return clean;
  }

  /** The root that contains `target`, or null. */
  rootFor(target) {
    return this.roots().find((root) => isInside(root, target)) || null;
  }

  /** Fixed drives, so the phone can show "This PC" style places. */
  drives() {
    const out = [];
    for (let i = 67; i <= 90; i++) {
      const letter = String.fromCharCode(i) + ':\\';
      try {
        if (fs.statSync(letter).isDirectory()) out.push(letter);
      } catch (_) { /* drive not mounted */ }
    }
    return out;
  }

  /**
   * Validates a path from the phone.
   * @returns {{ok: true, path: string}|{ok: false, error: string, needsApproval?: boolean}}
   */
  validate(requestPath, { mustExist = true } = {}) {
    if (typeof requestPath !== 'string' || !requestPath.trim()) {
      return { ok: false, error: 'No path given' };
    }
    if (requestPath.includes('\0')) return { ok: false, error: 'Invalid path' };
    const abs = path.resolve(requestPath);
    const root = this.rootForAny(abs);
    if (!root) return { ok: false, error: 'That folder is not shared with the phone', needsApproval: true };
    if (mustExist) {
      try {
        const st = fs.statSync(abs);
        if (!st.isDirectory() && !st.isFile()) return { ok: false, error: 'Not a file or folder' };
      } catch (_) {
        return { ok: false, error: 'The item no longer exists' };
      }
    }
    return { ok: true, path: abs, root };
  }

  /** Native approval prompt for a folder the phone is not allowed to open yet. */
  async requestApproval(target) {
    const abs = path.resolve(target);
    if (this.rootFor(abs)) return { ok: true, path: abs };
    if (this.approving.has(abs)) return { ok: false, error: 'Waiting for approval on the PC' };
    this.approving.add(abs);
    try {
      const res = await this.dialog.showMessageBox(this.getWindow(), {
        type: 'question',
        buttons: ['Share this folder', 'Cancel'],
        defaultId: 0,
        cancelId: 1,
        noLink: true,
        title: 'Share a folder with your phone',
        message: 'Your phone wants to browse a folder that is not shared.',
        detail: `${abs}\n\nThe phone will be able to list, download from and upload to this folder (and everything inside it) until you remove it in Settings → Phone file access.`,
        checkboxLabel: 'Remember this folder',
        checkboxChecked: true
      });
      if (res.response !== 0) return { ok: false, error: 'Access denied on the PC' };
      if (res.checkboxChecked) {
        const roots = this.roots();
        this.setRoots([...roots, abs]);
      } else {
        // One-time approval (checkbox cleared): keep it valid for this run
        // without writing it to settings.
        this._tempRoots = [...(this._tempRoots || []), abs];
      }
      return { ok: true, path: abs };
    } finally {
      this.approving.delete(abs);
    }
  }

  _rootListWithTemp() {
    return [...this.roots(), ...(this._tempRoots || [])];
  }

  rootForAny(target) {
    return this._rootListWithTemp().find((root) => isInside(root, target)) || null;
  }

  /** Directory listing for the phone's browser. */
  async list(requestPath) {
    let target = requestPath;
    let check;
    if (!target) {
      // Root screen: shared places + drives.
      return this._rootListing();
    }
    check = this.validate(target, { mustExist: false });
    if (!check.ok && check.needsApproval) {
      const approval = await this.requestApproval(target);
      if (!approval.ok) return { ok: false, error: approval.error };
      check = this.validate(target, { mustExist: true });
    }
    if (!check.ok) return { ok: false, error: check.error };
    const abs = check.path;
    let stat;
    try { stat = await fsp.stat(abs); } catch (_) { return { ok: false, error: 'The folder no longer exists' }; }
    if (!stat.isDirectory()) return { ok: false, error: 'That is a file, not a folder' };

    let dirents;
    try { dirents = await fsp.readdir(abs, { withFileTypes: true }); } catch (err) {
      return { ok: false, error: `Cannot read this folder: ${err.code || err.message}` };
    }
    const entries = [];
    for (const d of dirents.slice(0, MAX_ENTRIES)) {
      const full = path.join(abs, d.name);
      let size = 0;
      let modified = 0;
      try {
        const st = await fsp.stat(full);
        size = st.size;
        modified = st.mtimeMs;
      } catch (_) { continue; }
      const dot = d.name.lastIndexOf('.');
      entries.push({
        name: d.name,
        path: full,
        dir: d.isDirectory(),
        size,
        sizeLabel: d.isDirectory() ? '' : sizeLabel(size),
        ext: d.isDirectory() || dot < 1 ? '' : d.name.slice(dot + 1).toLowerCase(),
        modified: Math.round(modified)
      });
    }
    entries.sort((a, b) => (a.dir === b.dir ? a.name.localeCompare(b.name, undefined, { numeric: true }) : (a.dir ? -1 : 1)));
    const parent = path.dirname(abs);
    const root = this.rootForAny(abs);
    return {
      ok: true,
      path: abs,
      parent: root && path.resolve(abs) !== path.resolve(root) ? parent : null,
      root: root || null,
      entries,
      truncated: dirents.length > MAX_ENTRIES
    };
  }

  _rootListing() {
    const full = this.loadSettings().fileAccessAll === true;
    const roots = this.roots().map((p) => ({
      name: path.basename(p) || p,
      path: p,
      dir: true,
      size: 0,
      sizeLabel: '',
      ext: '',
      modified: 0,
      place: 'shared'
    }));
    const shared = new Set(roots.map((r) => r.path.toLowerCase()));
    const drives = this.drives()
      .filter((d) => !shared.has(path.resolve(d).toLowerCase()))
      .map((d) => ({ name: d, path: d, dir: true, size: 0, sizeLabel: '', ext: '', modified: 0, place: 'drive' }));
    return { ok: true, path: null, parent: null, root: null, entries: [...roots, ...drives], places: true, fullAccess: full };
  }

  /** Copies files/folders into `destDir`, never overwriting an existing name. */
  async copy(paths, destDir) {
    return this._transfer(paths, destDir, false);
  }

  /** Moves files/folders into `destDir` (rename, falling back to copy+delete). */
  async move(paths, destDir) {
    return this._transfer(paths, destDir, true);
  }

  async _transfer(paths, destDir, move) {
    const list = Array.isArray(paths) ? paths.filter((p) => typeof p === 'string' && p) : [];
    if (!list.length) return { ok: false, error: 'Nothing is selected' };
    if (list.length > 200) return { ok: false, error: 'Select at most 200 items at once' };
    if (destDir == null) return { ok: false, error: 'No destination folder' };
    const dest = await this._validatedDir(destDir);
    if (!dest.ok) return { ok: false, error: dest.error };
    let done = 0;
    const errors = [];
    for (const p of list) {
      const check = this.validate(p, { mustExist: true });
      if (!check.ok) { errors.push(`${path.basename(String(p))}: ${check.error}`); continue; }
      const name = path.basename(check.path);
      if (path.resolve(check.path).toLowerCase() === path.resolve(check.root).toLowerCase()) {
        errors.push(`${name}: drive roots cannot be transferred`);
        continue;
      }
      if (isInside(check.path, dest.path)) {
        errors.push(`${name}: cannot be placed inside itself`);
        continue;
      }
      const target = this._freePath(path.join(dest.path, name));
      try {
        if (move) {
          try {
            await fsp.rename(check.path, target);
          } catch (err) {
            if (err.code !== 'EXDEV') throw err;
            await fsp.cp(check.path, target, { recursive: true, errorOnExist: true, force: false });
            await fsp.rm(check.path, { recursive: true, force: true });
          }
        } else {
          await fsp.cp(check.path, target, { recursive: true, errorOnExist: true, force: false });
        }
        done++;
      } catch (err) {
        errors.push(`${name}: ${this._fsError(err)}`);
      }
    }
    const result = {
      ok: done > 0,
      copied: move ? 0 : done,
      moved: move ? done : 0,
      failed: errors.length,
      errors: errors.slice(0, 10),
      dest: dest.path
    };
    if (!done) result.error = errors[0] || 'Nothing could be transferred';
    return result;
  }

  /** Turns a Node fs error into something a phone user can act on. */
  _fsError(err) {
    const code = err && err.code;
    if (code === 'EPERM' || code === 'EACCES') return 'Windows denied access to that item';
    if (code === 'EBUSY') return 'the file is open in another program';
    if (code === 'ENOENT') return 'the item no longer exists';
    if (code === 'ENOSPC') return 'the drive is full';
    if (code === 'ENAMETOOLONG') return 'the name is too long for this folder';
    return (err && err.message) || 'unknown error';
  }

  /** A name in `dest` that does not exist yet — copy/move never overwrite. */
  _freePath(target) {
    if (!fs.existsSync(target)) return target;
    const dir = path.dirname(target);
    const ext = path.extname(target);
    const base = path.basename(target, ext);
    for (let i = 1; i < 1000; i++) {
      const candidate = path.join(dir, `${base} (${i})${ext}`);
      if (!fs.existsSync(candidate)) return candidate;
    }
    return path.join(dir, `${base} (${Date.now()})${ext}`);
  }

  /**
   * Recursive name search under one root (or every root when `rootPath` is
   * null). Depth and result count are capped so a search on C:\ cannot hang
   * the app; unreadable folders are skipped, exactly like Explorer.
   */
  async search(query, rootPath, opts = {}) {
    const q = String(query || '').trim().toLowerCase();
    if (q.length < 2) return { ok: false, error: 'Type at least 2 characters to search' };
    let bases;
    if (rootPath) {
      const check = this.validate(rootPath, { mustExist: true });
      if (!check.ok) return { ok: false, error: check.error };
      bases = [check.path];
    } else {
      bases = this._rootListWithTemp();
    }
    if (!bases.length) return { ok: false, error: 'Nothing is authorized to search' };
    const limit = Math.min(500, Math.max(20, parseInt(opts.limit, 10) || 200));
    const skip = new Set(['$recycle.bin', 'system volume information']);
    const out = [];
    const queue = bases.map((dir) => ({ dir, depth: 0 }));
    while (queue.length && out.length < limit) {
      const { dir, depth } = queue.shift();
      let dirents;
      try { dirents = await fsp.readdir(dir, { withFileTypes: true }); } catch (_) { continue; }
      for (const d of dirents) {
        if (out.length >= limit) break;
        if (!d.isDirectory() && !d.isFile()) continue;
        if (skip.has(d.name.toLowerCase())) continue;
        const full = path.join(dir, d.name);
        if (d.isDirectory() && depth < 6) queue.push({ dir: full, depth: depth + 1 });
        if (!d.name.toLowerCase().includes(q)) continue;
        let size = 0;
        let modified = 0;
        try {
          const st = await fsp.stat(full);
          size = st.size;
          modified = Math.round(st.mtimeMs);
        } catch (_) { continue; }
        const dot = d.name.lastIndexOf('.');
        out.push({
          name: d.name,
          path: full,
          dir: d.isDirectory(),
          size,
          sizeLabel: d.isDirectory() ? '' : sizeLabel(size),
          ext: d.isDirectory() || dot < 1 ? '' : d.name.slice(dot + 1).toLowerCase(),
          modified
        });
      }
    }
    return {
      ok: true,
      path: rootPath || null,
      query: q,
      entries: out,
      truncated: out.length >= limit,
      searched: bases.length
    };
  }

  async mkdir(parentPath, name) {
    const check = await this._validatedDir(parentPath);
    if (!check.ok) return check;
    const safe = sanitizeName(name);
    if (!safe) return { ok: false, error: 'That folder name cannot be used' };
    const dest = path.join(check.path, safe);
    if (!isInside(check.root, dest)) return { ok: false, error: 'Invalid destination' };
    try { await fsp.mkdir(dest); return { ok: true, path: dest }; } catch (err) {
      return { ok: false, error: err.code === 'EEXIST' ? 'A folder with that name already exists' : `Could not create folder: ${err.code || err.message}` };
    }
  }

  async rename(targetPath, newName) {
    const check = this.validate(targetPath, { mustExist: true });
    if (!check.ok) return { ok: false, error: check.error };
    const safe = sanitizeName(newName);
    if (!safe) return { ok: false, error: 'That name cannot be used' };
    if (path.resolve(check.path).toLowerCase() === path.resolve(check.root).toLowerCase()) {
      return { ok: false, error: 'A drive or shared folder cannot be renamed from the phone' };
    }
    const dest = path.join(path.dirname(check.path), safe);
    if (!isInside(check.root, dest)) return { ok: false, error: 'Invalid destination' };
    try { await fsp.rename(check.path, dest); return { ok: true, path: dest }; } catch (err) {
      return { ok: false, error: err.code === 'EEXIST' ? 'An item with that name already exists' : `Rename failed: ${this._fsError(err)}` };
    }
  }

  /**
   * Deletes to the Windows Recycle Bin by default (recoverable); the phone can
   * ask for a permanent delete, which is a separate, confirmed choice.
   * Drive roots and shared roots are never deletable from the phone.
   */
  async remove(targetPath, opts = {}) {
    const permanent = !!(opts && opts.permanent);
    const check = this.validate(targetPath, { mustExist: true });
    if (!check.ok) return { ok: false, error: check.error };
    if (path.resolve(check.path).toLowerCase() === path.resolve(check.root).toLowerCase()) {
      return { ok: false, error: 'A drive or shared folder cannot be deleted from the phone' };
    }
    try {
      if (permanent) await fsp.rm(check.path, { recursive: true, force: true });
      else await shell.trashItem(check.path);
      return { ok: true, permanent };
    } catch (err) {
      return { ok: false, error: `Delete failed: ${this._fsError(err)}` };
    }
  }

  /** File metadata for a download request. */
  async openForDownload(targetPath) {
    const check = this.validate(targetPath, { mustExist: true });
    if (!check.ok) return { ok: false, error: check.error };
    let stat;
    try { stat = await fsp.stat(check.path); } catch (_) { return { ok: false, error: 'File no longer exists' }; }
    if (!stat.isFile()) return { ok: false, error: 'Only files can be downloaded' };
    const MAX = 5 * 1024 * 1024 * 1024;
    if (stat.size > MAX) return { ok: false, error: 'File is larger than the 5 GB limit' };
    return { ok: true, path: check.path, name: path.basename(check.path), size: stat.size };
  }

  /** Destination folder for an upload, validated against the shared roots. */
  async resolveUploadDir(dirPath) {
    const check = await this._validatedDir(dirPath);
    if (!check.ok) return check;
    return { ok: true, path: check.path };
  }

  async _validatedDir(dirPath) {
    const check = this.validate(dirPath, { mustExist: false });
    if (!check.ok && check.needsApproval) {
      const approval = await this.requestApproval(dirPath);
      if (!approval.ok) return { ok: false, error: approval.error };
      const retry = this.validate(dirPath, { mustExist: true });
      if (!retry.ok) return { ok: false, error: retry.error };
      return { ok: true, path: retry.path, root: this.rootForAny(retry.path) };
    }
    if (!check.ok) return { ok: false, error: check.error };
    let stat;
    try { stat = await fsp.stat(check.path); } catch (_) { return { ok: false, error: 'Folder no longer exists' }; }
    if (!stat.isDirectory()) return { ok: false, error: 'Destination is not a folder' };
    return { ok: true, path: check.path, root: this.rootForAny(check.path) };
  }
}

module.exports = { PcFileSystem, isInside, sanitizeName, sizeLabel };
