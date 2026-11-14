/**
 * DuoLink capture window preload.
 *
 * The main process drives this window (it never navigates on its own): it is
 * told a desktop source id plus fps/quality/size, and it streams JPEG frames
 * back. contextIsolation stays on - the page has no other privileges.
 */
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('captureApi', {
  /** main pushes (re)configuration - fps, quality, size, source id */
  onConfigure: (cb) => ipcRenderer.on('capture-configure', (_e, opts) => cb(opts)),
  /** main asks the loop to stand down */
  onStop: (cb) => ipcRenderer.on('capture-stop', () => cb()),
  /** ship one encoded frame to the main process */
  sendFrame: (jpeg, width, height, seq) =>
    ipcRenderer.send('capture-frame', { jpeg, width, height, seq }),
  /** report an unrecoverable capture failure so the UI can explain it */
  fail: (message) => ipcRenderer.send('capture-error', String(message || 'Capture failed'))
});
