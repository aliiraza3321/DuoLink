/**
 * Pop-out phone-screen viewer.
 *
 * The window is deliberately chrome-less — the pane IS the screen — so this
 * renderer only paints the frames the main process forwards here and turns
 * pointer gestures on the canvas into the same normalized payload the Control
 * Phone pane sends, letting either view drive the phone interchangeably.
 */
(() => {
  const canvas = document.getElementById('frame');
  const ctx = canvas.getContext('2d');

  // An app window (Phone apps → tap an app) has a real title bar with the app's
  // own name, so the hover controls and the drag grip of the chrome-less
  // mirror pop-out are not drawn: one window, one set of controls.
  const params = new URLSearchParams(location.search);
  const appLabel = params.get('app') === '1' ? (params.get('label') || '') : '';
  // An app window shows one app's own display, streamed on its own session.
  const appSession = params.get('app') === '1' ? (params.get('session') || '') : '';
  if (params.get('app') === '1') {
    document.body.classList.add('app-window');
    if (appLabel) document.title = `${appLabel} \u00b7 DuoLink`;
  }

  /** Paints one of an app display's JPEG frames, sizing the canvas to it. */
  const appPaint = (bytes) => {
    const url = URL.createObjectURL(new Blob([bytes], { type: 'image/jpeg' }));
    if (lastUrl) URL.revokeObjectURL(lastUrl);
    lastUrl = url;
    img = img || new Image();
    img.onload = () => {
      if (canvas.width !== img.naturalWidth || canvas.height !== img.naturalHeight) {
        canvas.width = img.naturalWidth;
        canvas.height = img.naturalHeight;
      }
      try { ctx.drawImage(img, 0, 0); } catch (_) {}
      canvas.style.display = 'block';
      lastFrameAt = Date.now();
      document.body.classList.remove('stale');
    };
    img.src = url;
  };

  // One Image + one Blob URL at a time, decode-gated: assigning a src faster
  // than the browser can decode cancels every in-flight load and leaves the
  // pane blank or frozen, so at most one frame decodes while newer arrivals
  // collapse into a single pending slot (newest wins).
  let img = null;
  let lastUrl = null;
  let lastFrameAt = 0;
  let paintBusy = false;
  let pendingFrame = null;

  function pump() {
    if (paintBusy || !pendingFrame) return;
    const data = pendingFrame;
    pendingFrame = null;
    paintBusy = true;

    const w = data.width || canvas.width;
    const h = data.height || canvas.height;
    // Only reallocating when the size changes keeps the canvas from tearing
    // down and re-allocating its backing store on every single frame.
    if (canvas.width !== w) canvas.width = w;
    if (canvas.height !== h) canvas.height = h;

    const url = URL.createObjectURL(new Blob([data.jpeg], { type: 'image/jpeg' }));
    if (lastUrl) URL.revokeObjectURL(lastUrl);
    lastUrl = url;

    let settled = false;
    const done = () => {
      if (settled) return;
      settled = true;
      clearTimeout(guard);
      paintBusy = false;
      if (pendingFrame) pump();
    };
    // A decode that never settles must not wedge the pump.
    const guard = setTimeout(done, 500);

    img = img || new Image();
    img.onload = () => {
      try { ctx.drawImage(img, 0, 0, w, h); } catch (_) { /* frame raced a resize */ }
      done();
    };
    img.onerror = done;
    img.src = url;

    if (canvas.style.display !== 'block') canvas.style.display = 'block';
    lastFrameAt = Date.now();
  }

  function paint(data) {
    pendingFrame = data;
    if (document.body.classList.contains('stale')) document.body.classList.remove('stale');
    pump();
  }

  // Frames paused (rotation, heavy game, Wi-Fi blip): keep the LAST picture
  // on screen, dimmed, with a "Reconnecting…" pill — a few silent seconds must
  // never turn the window into a black rectangle. The threshold is generous
  // on purpose: a healthy stream can easily gap longer than a keyframe interval
  // (rotation, scene change, Wi-Fi roam) and a false "Reconnecting…" flash
  // erodes trust in a stream that never dropped. Only a real disconnect
  // (below) wipes the frame for good.
  setInterval(() => {
    if (lastFrameAt && Date.now() - lastFrameAt > 7000) {
      document.body.classList.add('stale');
    }
  }, 1500);

  if (window.phonePcApi && window.phonePcApi.on && appSession) {
    // One app, one display, one stream: nothing of the phone's own screen.
    window.phonePcApi.on('app-window-frame', (d) => {
      if (!d || d.session !== appSession || !d.jpeg) return;
      try {
        const raw = atob(d.jpeg);
        const bytes = new Uint8Array(raw.length);
        for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
        appPaint(bytes);
      } catch (_) {}
    });
    window.phonePcApi.on('app-window-closed', (d) => {
      if (d && d.session === appSession) window.close();
    });
    window.phonePcApi.on('session-status-change', (d) => {
      if (d && d.status !== 'connected') window.close();
    });
  } else if (window.phonePcApi && window.phonePcApi.on) {
    window.phonePcApi.on('phone-screen-frame', (d) => {
      if (d && d.jpeg && d.jpeg.length) paint(d);
    });
    // H.264 stream: the decoder paints straight onto the canvas.
    const mirror = window.createH264Mirror ? window.createH264Mirror({
      canvas,
      onFrame: () => {
        lastFrameAt = Date.now();
        document.body.classList.remove('stale');
        if (canvas.style.display !== 'block') canvas.style.display = 'block';
      }
    }) : null;
    if (mirror) {
      window.phonePcApi.on('phone-h264-config', (d) => mirror.configure(d));
      window.phonePcApi.on('phone-h264-frame', (d) => { if (d && d.nal) mirror.push(d.nal); });
    }
    // The connection is (back): any "Reconnecting…" pill from a frame gap is
    // over — clear it instead of waiting for the next frame to arrive.
    window.phonePcApi.on('phone-control-status', () => {
      if (lastFrameAt) document.body.classList.remove('stale');
    });
    // Phone gone: never leave a stale frame of its screen on the desktop.
    window.phonePcApi.on('session-status-change', (d) => {
      if (d && d.status === 'connected') {
        document.body.classList.remove('stale');
        return;
      }
      if (d && d.status !== 'connected') {
        canvas.style.display = 'none';
        lastFrameAt = 0;
        document.body.classList.remove('stale');
        if (lastUrl) { URL.revokeObjectURL(lastUrl); lastUrl = null; }
      }
    });
  }

  // ---- Gestures: identical payload shape to the Control Phone pane ----
  const norm = (e) => {
    const r = canvas.getBoundingClientRect();
    return {
      x: Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)),
      y: Math.min(1, Math.max(0, (e.clientY - r.top) / r.height))
    };
  };

  let isDown = false;
  let startX = 0;
  let startY = 0;
  let downAt = 0;

  canvas.addEventListener('contextmenu', (e) => e.preventDefault());

  canvas.addEventListener('mousedown', (e) => {
    if (appSession) return;
    if (e.button === 2) { window.phonePcApi.sendPhoneKeyAction('BACK'); return; }
    if (e.button !== 0) return;
    isDown = true;
    downAt = Date.now();
    const p = norm(e);
    startX = p.x;
    startY = p.y;
  });

  const finish = (e) => {
    if (appSession || !isDown) return;
    isDown = false;
    const p = norm(e);
    const held = Date.now() - downAt;
    const moved = Math.abs(p.x - startX) >= 0.02 || Math.abs(p.y - startY) >= 0.02;
    window.phonePcApi.sendPhoneGesture({
      type: moved ? 'SWIPE' : (held > 600 ? 'LONG_PRESS' : 'TAP'),
      startX,
      startY,
      endX: p.x,
      endY: p.y,
      durationMs: Math.min(1200, Math.max(120, held))
    });
  };
  canvas.addEventListener('mouseup', finish);
  window.addEventListener('mouseup', finish);

  let wheelLock = 0;
  canvas.addEventListener('wheel', (e) => {
    if (appSession) return;
    e.preventDefault();
    const now = Date.now();
    if (now - wheelLock < 350) return;
    wheelLock = now;
    const p = norm(e);
    const dy = Math.sign(e.deltaY) * 0.28;
    const clamp = (v) => Math.min(0.95, Math.max(0.05, v));
    window.phonePcApi.sendPhoneGesture({
      type: 'SWIPE',
      startX: p.x, startY: clamp(p.y + dy / 2),
      endX: p.x, endY: clamp(p.y - dy / 2),
      durationMs: 260
    });
  }, { passive: false });

  // An app window's own input: the PC's mouse on that app's display, in that
  // display's pixels (the phone injects them on its display, not the phone's).
  if (appSession) {
    const at = (e) => {
      const r = canvas.getBoundingClientRect();
      if (!r.width || !r.height) return { x: 0, y: 0 };
      return {
        x: Math.min(canvas.width, Math.max(0, ((e.clientX - r.left) / r.width) * canvas.width)),
        y: Math.min(canvas.height, Math.max(0, ((e.clientY - r.top) / r.height) * canvas.height))
      };
    };
    let down = false;
    canvas.addEventListener('mousedown', (e) => {
      if (e.button !== 0) return;
      down = true;
      const p = at(e);
      window.phonePcApi.appWindowInput(appSession, 'DOWN', p.x, p.y);
    });
    canvas.addEventListener('mousemove', (e) => {
      if (!down) return;
      const p = at(e);
      window.phonePcApi.appWindowInput(appSession, 'MOVE', p.x, p.y);
    });
    const up = (e) => {
      if (!down) return;
      down = false;
      const p = at(e);
      window.phonePcApi.appWindowInput(appSession, 'UP', p.x, p.y);
    };
    canvas.addEventListener('mouseup', up);
    window.addEventListener('mouseup', up);
    canvas.addEventListener('wheel', (e) => {
      e.preventDefault();
      const now = Date.now();
      if (now - wheelLock < 260) return;
      wheelLock = now;
      const p = at(e);
      const step = Math.sign(e.deltaY) * (canvas.height * 0.22);
      window.phonePcApi.appWindowInput(appSession, 'DOWN', p.x, p.y);
      window.phonePcApi.appWindowInput(appSession, 'MOVE', p.x, p.y - step / 2);
      window.phonePcApi.appWindowInput(appSession, 'UP', p.x, p.y - step);
    }, { passive: false });
  }

  window.addEventListener('beforeunload', () => {
    if (lastUrl) URL.revokeObjectURL(lastUrl);
  });

  // Frameless window: Esc is the way out (there is no title-bar close button).
  window.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') window.close();
  });

  // ---- Laptop keyboard -> phone ------------------------------------------
  // The window IS the phone, so while it has focus every keystroke is typed
  // into the phone's focused field (WhatsApp, notes, search boxes...), the way
  // a hardware keyboard works. Clicking the picture arms it; clicking away
  // (window blur) disarms it so shortcuts still work elsewhere.
  let typingArmed = false;
  let keyboardShown = false;

  function keyboardAppend(text) {
    if (!text) return;
    window.phonePcApi.sendPhoneTypeText({ op: 'append', text });
    if (!keyboardShown) {
      keyboardShown = true;
      window.phonePcApi.sendPhoneTypeText({ op: 'show' });
    }
  }

  canvas.addEventListener('click', () => {
    typingArmed = true;
    document.body.classList.add('typing');
  });
  window.addEventListener('blur', () => {
    typingArmed = false;
    document.body.classList.remove('typing');
  });

  window.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') { window.close(); return; }
    if (!typingArmed) return;

    if (e.ctrlKey || e.metaKey) {
      if (e.key.toLowerCase() === 'v') {
        e.preventDefault();
        navigator.clipboard.readText()
          .then((t) => keyboardAppend(t))
          .catch(() => {});
      }
      return; // leave Ctrl/Cmd shortcuts alone
    }

    if (e.key === 'Backspace') {
      e.preventDefault();
      window.phonePcApi.sendPhoneTypeText({ op: 'delete', count: 1 });
      // main.js reads the repeat count from `text`, not `count`.
      window.phonePcApi.sendPhoneTypeText({ op: 'delete', text: '1' });
      return;
    }
    if (e.key === 'Enter') {
      e.preventDefault();
      window.phonePcApi.sendPhoneKeyAction('ENTER');
      return;
    }
    if (e.key === 'Tab' || e.key === 'Shift' || e.key.startsWith('Arrow') || e.key.startsWith('F')) return;
    if (e.key.length === 1) {
      e.preventDefault();
      keyboardAppend(e.key);
    }
  });

  // Ask main for the last frame the moment this script is up: the window
  // paints a real picture immediately instead of sitting blank (and tripping
  // the stale pill) until the phone's next frame happens to arrive.
  if (window.phonePcApi && window.phonePcApi.screenWindowReady) {
    window.phonePcApi.screenWindowReady().catch(() => {});
  }

  // Hover-revealed window controls (minimize / close) — the pane stays a pure
  // phone picture until the mouse comes near the top-right corner.
  const btnMin = document.getElementById('btn-win-min');
  const btnClose = document.getElementById('btn-win-close');
  if (btnMin) btnMin.addEventListener('click', () => {
    if (window.phonePcApi && window.phonePcApi.minimizeScreenWindow) {
      window.phonePcApi.minimizeScreenWindow();
    }
  });
  const btnMax = document.getElementById('btn-win-max');
  if (btnMax) btnMax.addEventListener('click', () => {
    if (window.phonePcApi && window.phonePcApi.maximizeScreenWindow) {
      window.phonePcApi.maximizeScreenWindow();
    }
  });
  if (btnClose) btnClose.addEventListener('click', () => window.close());

  // ---- Tools: copy screenshot, screenshot, record video, volume ----
  const noteEl = document.getElementById('tool-note');
  let noteTimer = null;
  const note = (text) => {
    if (!noteEl) return;
    noteEl.textContent = text;
    noteEl.classList.add('show');
    clearTimeout(noteTimer);
    noteTimer = setTimeout(() => noteEl.classList.remove('show'), 2800);
  };

  // The canvas only holds a picture once a frame has been painted; a black
  // rectangle is not a screenshot worth saving.
  const hasPicture = () => canvas.style.display === 'block' && canvas.width > 1 && canvas.height > 1 && lastFrameAt > 0;
  const snapshot = () => {
    try { return hasPicture() ? canvas.toDataURL('image/png') : ''; } catch (_) { return ''; }
  };

  const copyBtn = document.getElementById('tool-copy');
  if (copyBtn) copyBtn.addEventListener('click', async () => {
    const dataUrl = snapshot();
    if (!dataUrl) return note('No picture yet');
    const res = await window.phonePcApi.copyShotImage(dataUrl).catch(() => null);
    note(res && res.ok ? 'Screenshot copied' : (res && res.error) || 'Could not copy');
  });

  const shotBtn = document.getElementById('tool-shot');
  if (shotBtn) shotBtn.addEventListener('click', async () => {
    const dataUrl = snapshot();
    if (!dataUrl) return note('No picture yet');
    const res = await window.phonePcApi.saveShotImage(dataUrl).catch(() => null);
    note(res && res.ok ? `Saved ${res.path.split(/[\\/]/).pop()}` : (res && res.error) || 'Could not save');
  });

  const recBtn = document.getElementById('tool-rec');
  let recorder = null;
  let recChunks = [];
  const mimeType = () => {
    const options = ['video/webm;codecs=vp9', 'video/webm;codecs=vp8', 'video/webm'];
    for (const type of options) {
      if (window.MediaRecorder && MediaRecorder.isTypeSupported(type)) return type;
    }
    return '';
  };
  const stopRecording = () => {
    if (recorder && recorder.state !== 'inactive') recorder.stop();
  };
  if (recBtn) recBtn.addEventListener('click', async () => {
    if (recorder) { stopRecording(); return; }
    const type = mimeType();
    if (!type || !canvas.captureStream) return note('Recording is not available here');
    try {
      const stream = canvas.captureStream(30);
      recorder = new MediaRecorder(stream, { mimeType: type, videoBitsPerSecond: 6000000 });
      recChunks = [];
      recorder.ondataavailable = (e) => { if (e.data && e.data.size) recChunks.push(e.data); };
      recorder.onstop = async () => {
        document.body.classList.remove('recording');
        const blob = new Blob(recChunks, { type });
        recorder = null;
        recChunks = [];
        if (!blob.size) return note('Nothing was recorded');
        const bytes = new Uint8Array(await blob.arrayBuffer());
        const res = await window.phonePcApi.saveShotVideo(bytes).catch(() => null);
        note(res && res.ok ? `Saved ${res.path.split(/[\\/]/).pop()}` : (res && res.error) || 'Could not save the recording');
      };
      recorder.start(1000);
      document.body.classList.add('recording');
      note('Recording  ·  press again to stop');
    } catch (e) {
      recorder = null;
      document.body.classList.remove('recording');
      note(e && e.message ? e.message : 'Could not start recording');
    }
  });

  const volBtn = document.getElementById('tool-vol');
  const volPop = document.getElementById('vol-pop');
  const volRange = document.getElementById('vol-range');
  const volMute = document.getElementById('vol-mute');
  if (volBtn && volPop) {
    volBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      volPop.hidden = !volPop.hidden;
      volBtn.setAttribute('aria-expanded', String(!volPop.hidden));
    });
    document.addEventListener('click', (e) => {
      if (volPop.hidden) return;
      if (volPop.contains(e.target) || volBtn.contains(e.target)) return;
      volPop.hidden = true;
      volBtn.setAttribute('aria-expanded', 'false');
    });
  }
  if (volRange) volRange.addEventListener('change', async () => {
    const level = Number(volRange.value);
    await window.phonePcApi.phoneVolume('SET', level).catch(() => null);
    note(`Volume ${level}`);
  });
  if (volMute) volMute.addEventListener('click', async () => {
    await window.phonePcApi.phoneVolume('MUTE').catch(() => null);
    note('Muted');
  });
  if (window.phonePcApi && window.phonePcApi.on) {
    window.phonePcApi.on('phone-volume-state', (st) => {
      const level = st && (st.value ?? st.level);
      if (volRange && typeof level === 'number') volRange.value = String(level);
    });
  }

  window.addEventListener('beforeunload', stopRecording);
})();
