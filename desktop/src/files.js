/**
 * PhonePC Pair - File Transfer Management
 * Path traversal protection, chunked transfer, progress tracking, 5GB limit.
 *
 * V2 upgrades (API-compatible with existing callers):
 *  - Safe temp files: chunks append to `<name>.part`, renamed atomically on
 *    completion, deleted on cancel/failure - a half-written file never appears
 *    under its final name and interrupted transfers leave no corrupt output.
 *  - Integrity: FILE_META may carry `sha256` (hex). When present, the digest is
 *    computed while writing and verified before the file is published.
 *  - History: in-memory + persisted (file names/size/status only - no content).
 *  - Configurable download directory.
 */

const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');

const MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024 * 1024; // 5 GB limit (spec: file sharing up to 5GB)
const MAX_HISTORY = 200;
// Preview cache: files pulled ONLY to show in the in-app viewer. They never
// land in the Received folder (and never enter history) until the user presses
// Download, which promotes them out of this directory.
const PREVIEW_DIR_NAME = 'duolink-preview-cache';
const PREVIEW_MARK_TTL_MS = 90 * 1000;   // filename marked -> FILE_META arriving
const PREVIEW_MAX_FILES = 60;            // oldest evicted beyond this
const PREVIEW_MAX_AGE_MS = 24 * 60 * 60 * 1000;

class FileManager {
  /**
   * @param {string} receivedDir
   * @param {object} [opts]
   * @param {Function} [opts.loadHistory]
   * @param {Function} [opts.saveHistory]
   */
  constructor(receivedDir, opts = {}) {
    this.receivedDir = receivedDir;
    this._loadHistory = opts.loadHistory || (() => []);
    this._saveHistory = opts.saveHistory || (() => {});
    if (!fs.existsSync(this.receivedDir)) {
      fs.mkdirSync(this.receivedDir, { recursive: true });
    }

    // Active incoming file transfers:
    // Map<transferId, { transferId, filename, partPath, destinationPath,
    //                    totalSize, receivedBytes, cancelled, hash, expectedSha256 }>
    this.incomingTransfers = new Map();

    // Preview (view-without-saving) machinery — see header comment.
    this.previewDir = path.join(os.tmpdir(), PREVIEW_DIR_NAME);
    try { fs.mkdirSync(this.previewDir, { recursive: true }); } catch (_) {}
    this._previewNames = new Map(); // filename -> expiry (set by media-get)

    // Transfer history: [{ id, filename, size, direction, status, ts, error? }]
    this.history = Array.isArray(this._loadHistory()) ? this._loadHistory() : [];
  }

  /** Sets the directory future incoming files are written to. */
  setDownloadDir(dir) {
    if (!dir || typeof dir !== 'string') return false;
    const resolved = path.resolve(dir);
    fs.mkdirSync(resolved, { recursive: true });
    this.receivedDir = resolved;
    return true;
  }

  /** Sanitizes filename to prevent directory traversal and invalid chars. */
  sanitizeFilename(rawName) {
    if (!rawName || typeof rawName !== 'string') {
      return `file_${Date.now()}.bin`;
    }
    let safe = path.basename(rawName).trim();
    safe = safe.replace(/[/\\?%*:|"<>]/g, '_');
    if (!safe || safe === '.' || safe === '..') {
      safe = `file_${Date.now()}.bin`;
    }
    return safe;
  }

  /**
   * Tags the next FILE_META with this filename as a PREVIEW transfer: its
   * bytes go to the temp cache instead of the Received folder. The renderer
   * calls this from media-get (it knows the name before FILE_META arrives).
   */
  markPreview(name) {
    if (!name || typeof name !== 'string') return;
    this._previewNames.set(name, Date.now() + PREVIEW_MARK_TTL_MS);
    // Keep the marker map from growing without bound.
    if (this._previewNames.size > 200) {
      const now = Date.now();
      for (const [k, exp] of this._previewNames) if (exp <= now) this._previewNames.delete(k);
    }
  }

  _consumePreview(rawName) {
    if (!rawName) return false;
    const exp = this._previewNames.get(rawName);
    if (exp === undefined) return false;
    this._previewNames.delete(rawName);
    if (exp <= Date.now()) return false; // stale marker: treat as a real download
    return true;
  }

  /** True when the path lives in the Received folder or the preview cache. */
  isAllowedMediaPath(p) {
    if (!p || typeof p !== 'string') return false;
    const norm = (v) => path.resolve(v).toLowerCase();
    const target = norm(p);
    const roots = [norm(this.receivedDir), norm(this.previewDir)];
    return roots.some((r) => target.startsWith(r + path.sep));
  }

  /**
   * Move a previewed file from the temp cache into the Received folder and
   * record it in history — the "Download" button. Copies instead of re-fetching
   * because the bytes already crossed the LAN once.
   * @returns {{ok: boolean, path?: string, error?: string}}
   */
  promotePreview(previewPath) {
    try {
      const src = path.resolve(String(previewPath || ''));
      const cacheRoot = path.resolve(this.previewDir);
      if (!src.startsWith(cacheRoot + path.sep)) {
        return { ok: false, error: 'That file is not a previewed item' };
      }
      if (!fs.existsSync(src)) return { ok: false, error: 'The preview is no longer cached' };
      const name = this.sanitizeFilename(path.basename(src));
      const dest = this._getNonCollidingPath(path.join(this.receivedDir, name));
      fs.copyFileSync(src, dest);
      const size = fs.statSync(dest).size;
      try { fs.unlinkSync(src); } catch (_) {}
      this._addHistory({
        id: `preview-${Date.now()}`,
        filename: path.basename(dest),
        size,
        direction: 'incoming',
        status: 'completed',
        integrity: 'preview-promoted'
      });
      return { ok: true, path: dest };
    } catch (err) {
      return { ok: false, error: err.message };
    }
  }

  /** Evict old/over-cap preview files so the cache never grows forever. */
  _prunePreviewDir() {
    try {
      const files = fs.readdirSync(this.previewDir)
        .map((f) => {
          const p = path.join(this.previewDir, f);
          try { return { p, m: fs.statSync(p).mtimeMs }; } catch (_) { return null; }
        })
        .filter(Boolean)
        .sort((a, b) => a.m - b.m); // oldest first
      const now = Date.now();
      while (files.length > PREVIEW_MAX_FILES ||
             (files.length && now - files[0].m > PREVIEW_MAX_AGE_MS)) {
        const victim = files.shift();
        if (!victim) break;
        try { fs.unlinkSync(victim.p); } catch (_) {}
        if (now - victim.m <= PREVIEW_MAX_AGE_MS && files.length <= PREVIEW_MAX_FILES) break;
      }
    } catch (_) { /* cache problems must never break a transfer */ }
  }

  _addHistory(entry) {
    this.history.unshift({ ts: Date.now(), ...entry });
    if (this.history.length > MAX_HISTORY) this.history.length = MAX_HISTORY;
    try { this._saveHistory(this.history); } catch (_) {}
  }

  getHistory() {
    return this.history;
  }

  /**
   * Starts an incoming file transfer.
   * @param {string} transferId
   * @param {string} filename
   * @param {number} totalSize
   * @param {object} [meta] - { sha256?: string }
   * @returns {{ success: boolean, error?: string }}
   */
  startIncomingTransfer(transferId, filename, totalSize, meta = {}) {
    if (!Number.isFinite(totalSize) || totalSize < 0) {
      return { success: false, error: 'Invalid file size' };
    }
    if (totalSize > MAX_FILE_SIZE_BYTES) {
      return { success: false, error: 'File exceeds 5 GB maximum size limit' };
    }

    // Preview transfers were tagged by media-get before FILE_META arrived:
    // they land in the temp cache, use a fixed name (re-preview overwrites) and
    // never enter the transfer history.
    const preview = this._consumePreview(filename) || this._consumePreview(path.basename(String(filename || '')));
    const safeName = this.sanitizeFilename(filename);
    // Exports/uploads from the phone can name a destination folder inside one
    // of the PC's shared roots; the socket layer validated it before we get
    // here, and anything unexpected falls back to the normal received folder.
    let baseDir = preview ? this.previewDir : this.receivedDir;
    if (!preview && typeof meta.destinationDir === 'string' && meta.destinationDir) {
      try {
        if (fs.statSync(meta.destinationDir).isDirectory()) baseDir = meta.destinationDir;
      } catch (_) { /* keep the received folder */ }
    }
    let destinationPath;
    if (preview) {
      destinationPath = path.join(baseDir, safeName);
      for (const p of [destinationPath, destinationPath + '.part']) {
        try { if (fs.existsSync(p)) fs.unlinkSync(p); } catch (_) {}
      }
    } else {
      destinationPath = this._getNonCollidingPath(path.join(baseDir, safeName));
    }
    const partPath = destinationPath + '.part';

    // Initialize clean temp file
    fs.writeFileSync(partPath, Buffer.alloc(0));

    const expectedSha256 = typeof meta.sha256 === 'string' && /^[0-9a-fA-F]{64}$/.test(meta.sha256)
      ? meta.sha256.toLowerCase()
      : null;

    this.incomingTransfers.set(transferId, {
      transferId,
      filename: safeName,
      partPath,
      destinationPath,
      totalSize,
      receivedBytes: 0,
      cancelled: false,
      hash: crypto.createHash('sha256'),
      expectedSha256,
      preview
    });

    return { success: true, preview, destinationPath };
  }

  /**
   * Writes a chunk to an active transfer.
   * @returns {{ success: boolean, progress?: number, finished?: boolean,
   *             error?: string, path?: string, integrityOk?: boolean }}
   */
  writeChunk(transferId, chunk) {
    const transfer = this.incomingTransfers.get(transferId);
    if (!transfer) {
      return { success: false, error: 'Transfer not found or expired' };
    }
    if (transfer.cancelled) {
      return { success: false, error: 'Transfer was cancelled' };
    }

    let buffer;
    try {
      buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk, 'base64');
    } catch (_) {
      return { success: false, error: 'Invalid chunk encoding' };
    }

    if (transfer.receivedBytes + buffer.length > transfer.totalSize) {
      this.cancelTransfer(transferId);
      return { success: false, error: 'File size exceeded declared size' };
    }

    try {
      fs.appendFileSync(transfer.partPath, buffer);
    } catch (err) {
      this.cancelTransfer(transferId);
      return { success: false, error: `Write failed: ${err.message}` };
    }
    transfer.hash.update(buffer);
    transfer.receivedBytes += buffer.length;

    const progress = Math.min(100, Math.round((transfer.receivedBytes / transfer.totalSize) * 100));

    if (transfer.receivedBytes >= transfer.totalSize) {
      // Verify integrity before publishing the file
      const digest = transfer.hash.digest('hex');
      if (transfer.expectedSha256 && digest !== transfer.expectedSha256) {
        try { fs.unlinkSync(transfer.partPath); } catch (_) {}
        this.incomingTransfers.delete(transferId);
        this._addHistory({
          id: transferId, filename: transfer.filename, size: transfer.totalSize,
          direction: 'incoming', status: 'failed', error: 'SHA-256 mismatch'
        });
        return { success: false, finished: true, integrityOk: false, error: 'SHA-256 integrity check failed' };
      }

      // Atomic publish: .part -> final name
      try {
        fs.renameSync(transfer.partPath, transfer.destinationPath);
      } catch (err) {
        try { fs.unlinkSync(transfer.partPath); } catch (_) {}
        this.incomingTransfers.delete(transferId);
        return { success: false, error: `Finalize failed: ${err.message}` };
      }

      this.incomingTransfers.delete(transferId);
      if (transfer.preview) {
        // Preview: published into the cache only — no Received-folder entry,
        // no history row, and the cache is trimmed on every publish.
        this._prunePreviewDir();
        return { success: true, progress: 100, finished: true, path: transfer.destinationPath, integrityOk: true, preview: true };
      }
      this._addHistory({
        id: transferId, filename: transfer.filename, size: transfer.totalSize,
        direction: 'incoming', status: 'completed',
        integrity: transfer.expectedSha256 ? 'sha256-verified' : 'size-only'
      });
      return { success: true, progress: 100, finished: true, path: transfer.destinationPath, integrityOk: true };
    }

    return { success: true, progress, finished: false };
  }

  /** Cancels and cleans up an in-flight file transfer. */
  cancelTransfer(transferId) {
    const transfer = this.incomingTransfers.get(transferId);
    if (transfer) {
      transfer.cancelled = true;
      try {
        if (fs.existsSync(transfer.partPath)) fs.unlinkSync(transfer.partPath);
        else if (fs.existsSync(transfer.destinationPath)) fs.unlinkSync(transfer.destinationPath);
      } catch (err) {
        console.error('[FileManager] Error cleaning up cancelled transfer', err);
      }
      this.incomingTransfers.delete(transferId);
      this._addHistory({
        id: transferId, filename: transfer.filename, size: transfer.totalSize,
        direction: 'incoming', status: 'cancelled'
      });
      return true;
    }
    return false;
  }

  /** Records an outgoing transfer in history (called by the sender). */
  recordOutgoing(id, filename, size, status, error) {
    this._addHistory({ id, filename, size, direction: 'outgoing', status, error: error || undefined });
  }

  _getNonCollidingPath(targetPath) {
    if (!fs.existsSync(targetPath)) {
      return targetPath;
    }
    const dir = path.dirname(targetPath);
    const ext = path.extname(targetPath);
    const base = path.basename(targetPath, ext);
    let counter = 1;

    while (fs.existsSync(path.join(dir, `${base}_(${counter})${ext}`))) {
      counter++;
    }
    return path.join(dir, `${base}_(${counter})${ext}`);
  }
}

module.exports = {
  FileManager,
  MAX_FILE_SIZE_BYTES
};
