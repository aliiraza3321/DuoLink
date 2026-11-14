/**
 * PhonePC Pair - Desktop Renderer Logic
 * Coordinates tabs, pairing UI, confirmation modal, chat, files, and remote control.
 */

const IC = (n) => `<svg class="ic" aria-hidden="true"><use href="#i-${n}"/></svg>`;

// When index.html is opened outside Electron (UI development / preview),
// window.phonePcApi does not exist. Provide a harmless stub with sensible
// defaults so the layout renders instead of crashing on the first API call.
if (!window.phonePcApi) {
  const defaults = {
    getNetworkInfo: { serverUp: false, lanIp: 'preview', port: 3000, fingerprint: '' },
    getSettings: { autoClipboardSync: false, startMinimized: false },
    getAppInfo: { name: 'DuoLink', version: 'preview', electron: 'n/a', node: 'n/a', platform: 'browser', userDataPath: '…', logPath: '…' },
    getTrustedDevices: [],
    getControlStatus: { allowed: false, isKillSwitched: false },
    healthCheck: { ok: false, error: 'not running in Electron' }
  };
  window.phonePcApi = new Proxy({}, {
    get: (_t, prop) => () => Promise.resolve(defaults[prop] !== undefined ? defaults[prop] : null)
  });
}

document.addEventListener('DOMContentLoaded', async () => {
  // Elements
  const $id = (id) => document.getElementById(id);
  const tabs = document.querySelectorAll('.nav-tab');
  const panes = document.querySelectorAll('.tab-pane');
  const lanIpBadge = document.getElementById('lan-ip-badge');
  const latencyBadge = document.getElementById('latency-badge');
  const globalStatusDot = document.getElementById('global-status-dot');
  const globalStatusText = document.getElementById('global-status-text');
  const killswitchIndicator = document.getElementById('killswitch-indicator');
  const btnResetRateLimit = document.getElementById('btn-reset-rate-limit');
  const btnQuickHealth = document.getElementById('btn-quick-health');

  // Link bar + Overview hero (spec 6.1 / 6.2.1)
  const linkPcName = $id('link-pc-name');
  const linkPhoneName = $id('link-phone-name');
  const heroPhoneName = $id('hero-phone-name');
  const heroSessionLine = $id('hero-session-line');
  const permSummary = $id('perm-summary');
  const btnPermFix = $id('btn-perm-fix');
  const activeFingerprint = $id('active-fingerprint');
  const btnPairAnother = $id('btn-pair-another');
  const qrCard = $id('qr-card');
  const pairingGrid = document.querySelector('.pairing-grid');
  const activityFeed = $id('activity-feed');
  const btnTitlebarKillswitch = $id('btn-titlebar-killswitch');

  // QR elements
  const qrImg = document.getElementById('qr-code-img');
  const qrLoading = document.getElementById('qr-loading-overlay');
  const qrCountdown = document.getElementById('qr-countdown');
  const btnRefreshQr = document.getElementById('btn-refresh-qr');
  const manualUriInput = document.getElementById('manual-uri-input');
  const btnCopyUri = document.getElementById('btn-copy-uri');
  const certFingerprintText = document.getElementById('cert-fingerprint-text');

  // Confirmation elements
  const confirmationBox = document.getElementById('confirmation-box');
  const sessionIdleView = document.getElementById('session-idle-view');
  const sessionBadge = document.getElementById('session-badge');
  const activeSessionBox = document.getElementById('active-session-box');
  const promptDeviceName = document.getElementById('prompt-device-name');
  const displayConfirmationCode = document.getElementById('display-confirmation-code');
  const confirmErrorMsg = document.getElementById('confirm-error-msg');
  const btnApprovePairing = document.getElementById('btn-approve-pairing');
  const btnRejectPairing = document.getElementById('btn-reject-pairing');
  const btnDismissConfirm = document.getElementById('btn-dismiss-confirm');
  const btnDisconnectActive = document.getElementById('btn-disconnect-active');
  const activePhoneName = document.getElementById('active-phone-name');
  const activeSessionId = document.getElementById('active-session-id');
  const activeTimer = document.getElementById('active-timer');

  // Quick Action Tiles
  const tileSendClip = document.getElementById('tile-send-clip');
  const tileOpenChat = document.getElementById('tile-open-chat');
  const tileSendFile = document.getElementById('tile-send-file');
  const tileScreenshot = $id('tile-screenshot');
  const tileFindPhone = $id('tile-find-phone');
  const tileLockPhone = $id('tile-lock-phone');

  // Chat elements
  const chatMessages = document.getElementById('chat-messages');
  const chatInput = document.getElementById('chat-input');
  const btnSendChat = document.getElementById('btn-send-chat');
  const btnClearChat = document.getElementById('btn-clear-chat');

  // Clipboard elements
  const btnSendClipboard = document.getElementById('btn-send-clipboard');
  const btnReadClipboard = document.getElementById('btn-read-clipboard');
  const clipboardPreview = document.getElementById('clipboard-preview');
  const toggleAutoClipboard = document.getElementById('toggle-auto-clipboard');

  // File elements
  const dropZone = document.getElementById('drop-zone');
  const btnSelectFile = document.getElementById('btn-select-file');
  const btnOpenReceived = document.getElementById('btn-open-received');
  const transferProgressContainer = document.getElementById('transfer-progress-container');
  const transferFilename = document.getElementById('transfer-filename');
  const transferPercentage = document.getElementById('transfer-percentage');
  const transferProgressFill = document.getElementById('transfer-progress-fill');
  const transferHistoryList = document.getElementById('transfer-history-list');

  // Control elements
  const toggleAllowControl = document.getElementById('toggle-allow-control');
  const btnTriggerKillswitch = document.getElementById('btn-trigger-killswitch');
  const btnVolDown = document.getElementById('btn-vol-down');
  const btnVolMute = document.getElementById('btn-vol-mute');
  const btnPlayPause = document.getElementById('btn-play-pause');
  const btnVolUp = document.getElementById('btn-vol-up');
  const btnLockPc = document.getElementById('btn-lock-pc');

  // Screen mirror elements
  const screenCanvas = document.getElementById('screen-canvas');
  const screenPlaceholder = document.getElementById('screen-placeholder');
  const btnStartStream = document.getElementById('btn-start-stream');
  const btnStopStream = document.getElementById('btn-stop-stream');
  const btnCaptureScreenshot = document.getElementById('btn-capture-screenshot');
  const btnPhoneBack = document.getElementById('btn-phone-back');
  const btnPhoneHome = document.getElementById('btn-phone-home');
  const btnPhoneRecents = document.getElementById('btn-phone-recents');

  // Trusted devices list
  const trustedDevicesList = document.getElementById('trusted-devices-list');
  const btnRefreshTrusted = document.getElementById('btn-refresh-trusted');

  // Toast container
  const toastContainer = document.getElementById('toast-container');

  // State
  let currentPendingId = null;
  let activeSession = null;
  // Mirror stream liveness (declared early: the disconnect handler below
  // clears it, and it fires before the mirror section is initialized).
  let mirrorLive = false;
  let lastMirrorFrameAt = 0;
  // Frames paused (rotation, heavy game, Wi-Fi blip): keep the last picture
  // on screen and say so — never blank the pane over a few silent seconds.
  let mirrorStale = false;
  let qrTimerInterval = null;
  let remainingSeconds = 180;
  let sessionStartTime = 0;
  let sessionTimerInterval = null;
  let lastClipboardValue = '';
  let autoClipboardInterval = null;
  let lastLatencyToast = 0;

  // Toast Notification Utility.
  // Spec: bottom-right stack, 4s, identical messages merge (the timer just
  // restarts) so a burst of the same event never buries the UI.
  function showToast(message, type = 'info', duration = 4000) {
    const container = toastContainer;
    const existing = Array.from(container.querySelectorAll('.toast'))
      .find(t => t.textContent === message);
    if (existing) {
      existing.classList.remove('fade-out');
      container.appendChild(existing); // move to top of the stack
      clearTimeout(existing._hideTimer);
      existing._hideTimer = setTimeout(() => {
        existing.classList.add('fade-out');
        setTimeout(() => existing.remove(), 300);
      }, duration);
      return;
    }
    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    toast.textContent = message;
    toast.setAttribute('role', type === 'error' ? 'alert' : 'status');
    container.appendChild(toast);
    toast._hideTimer = setTimeout(() => {
      toast.classList.add('fade-out');
      setTimeout(() => toast.remove(), 300);
    }, duration);
  }

  // Themed confirmation dialog. window.confirm() draws Chromium's grey system
  // box on top of the app, ignoring the palette entirely; destructive actions
  // use this Promise-based dialog instead. Falls back to confirm() only if the
  // markup is missing (e.g. an old cached index.html).
  function appConfirm(message, options = {}) {
    const backdrop = $id('app-confirm-backdrop');
    const titleEl = $id('app-confirm-title');
    const msgEl = $id('app-confirm-message');
    const okBtn = $id('app-confirm-ok');
    const cancelBtn = $id('app-confirm-cancel');
    if (!backdrop || !okBtn || !cancelBtn) return Promise.resolve(window.confirm(message));
    const danger = options.danger !== false;
    titleEl.textContent = options.title || 'Are you sure?';
    msgEl.textContent = message;
    okBtn.textContent = options.confirmLabel || 'Continue';
    cancelBtn.textContent = options.cancelLabel || 'Cancel';
    okBtn.classList.toggle('btn-danger', danger);
    okBtn.classList.toggle('btn-primary', !danger);
    const lastFocus = document.activeElement;
    backdrop.classList.remove('hidden');
    okBtn.focus();
    return new Promise((resolve) => {
      let settled = false;
      const onOk = () => done(true);
      const onCancel = () => done(false);
      const onBackdrop = (e) => { if (e.target === backdrop) done(false); };
      const onKey = (e) => {
        if (e.key === 'Escape') {
          e.preventDefault();
          e.stopPropagation();
          done(false);
        } else if (e.key === 'Tab') {
          // Two controls only: keep focus inside the dialog.
          e.preventDefault();
          (document.activeElement === okBtn ? cancelBtn : okBtn).focus();
        }
      };
      const done = (value) => {
        if (settled) return;
        settled = true;
        backdrop.classList.add('hidden');
        document.removeEventListener('keydown', onKey, true);
        backdrop.removeEventListener('mousedown', onBackdrop);
        okBtn.removeEventListener('click', onOk);
        cancelBtn.removeEventListener('click', onCancel);
        if (lastFocus && typeof lastFocus.focus === 'function') lastFocus.focus();
        resolve(value);
      };
      document.addEventListener('keydown', onKey, true);
      backdrop.addEventListener('mousedown', onBackdrop);
      okBtn.addEventListener('click', onOk);
      cancelBtn.addEventListener('click', onCancel);
    });
  }

  // Switch Tab Helper
  const titlebarTitle = document.getElementById('titlebar-page-title');
  function switchTab(tabId) {
    tabs.forEach(t => t.classList.remove('active'));
    panes.forEach(p => p.classList.remove('active'));
    const targetTab = document.querySelector(`.nav-tab[data-tab="${tabId}"]`);
    const targetPane = document.getElementById(tabId);
    if (targetTab) targetTab.classList.add('active');
    if (targetPane) targetPane.classList.add('active');
    // Mockup order: page title → link bar card → content. The link bar is one
    // shared element, so re-home it directly under the active pane's header.
    if (targetPane) {
      const linkBar = document.getElementById('link-bar');
      const paneHeader = targetPane.querySelector('.pane-header');
      if (linkBar) {
        if (paneHeader) paneHeader.insertAdjacentElement('afterend', linkBar);
        else targetPane.prepend(linkBar);
        // The link/latency card belongs to Overview only. Repeating it on every
        // page pushed the actual content down and made each pane look busier
        // than it is. A class (not an inline style) so the stylesheet can win
        // over the base .link-bar rule.
        linkBar.classList.toggle('is-hidden', tabId !== 'tab-pairing');
      }
    }
    // Page title in the overlay title bar follows the active tab (badges excluded).
    if (titlebarTitle && targetTab) {
      const clone = targetTab.cloneNode(true);
      clone.querySelectorAll('.nav-badge').forEach((b) => b.remove());
      titlebarTitle.textContent = clone.textContent.trim().replace(/\s+/g, ' ');
    }
    if (tabId === 'tab-trusted') refreshTrustedDevices();
  }

  // Tab switching events
  tabs.forEach(tab => {
    tab.addEventListener('click', () => switchTab(tab.dataset.tab));
  });
  // Place the link bar under the initial page title (Overview)
  switchTab('tab-pairing');

  // Keyboard shortcuts: Ctrl/Cmd + digit switches sections, in sidebar order.
  // The list is read from the sidebar itself, so adding or removing a page can
  // never leave a stale shortcut behind (that is how the old About/Remote
  // entries kept disappearing from the wrong pages).
  const TAB_ORDER = Array.from(document.querySelectorAll('.nav-tab[data-tab]'))
    .map((t) => t.dataset.tab);
  window.addEventListener('keydown', (e) => {
    if (!(e.ctrlKey || e.metaKey) || e.altKey) return;
    let idx = -1;
    if (e.shiftKey) {
      if (e.key === '!' || e.key === '1') idx = 10;
      else if (e.key === '@' || e.key === '2') idx = 11;
      else return;
    } else {
      const n = parseInt(e.key, 10);
      if (Number.isNaN(n)) return;
      idx = n === 0 ? 9 : n - 1;
    }
    if (idx >= 0 && idx < TAB_ORDER.length) {
      e.preventDefault();
      switchTab(TAB_ORDER[idx]);
    }
  });

  // Initialize network info
  async function loadNetworkInfo() {
    try {
      const netInfo = await window.phonePcApi.getNetworkInfo();
      lanIpBadge.textContent = `LAN: ${netInfo.lanIp}:${netInfo.port}`;
      certFingerprintText.textContent = netInfo.fingerprint;
    } catch (err) {
      console.error('Failed to get network info', err);
      lanIpBadge.textContent = 'LAN: Offline';
    }
  }
  await loadNetworkInfo();

  // Test connection: verify the local HTTPS server AND measure a real
  // WebSocket round trip to the phone (bug #10 — no more "Ping: <1ms").
  async function checkHealthPing() {
    try {
      const res = await window.phonePcApi.healthCheck();
      if (res && res.ok) {
        await window.phonePcApi.measureLatency();
        showToast('Local server healthy — measuring Wi-Fi round trip…', 'success');
      } else {
        showToast(`Health check failed: ${res && res.error || 'no response'}`, 'error');
      }
    } catch (err) {
      showToast(`Health check failed: ${err.message}`, 'error');
    }
  }
  btnQuickHealth.addEventListener('click', checkHealthPing);

  // Live link latency: measured by main over the phone's WebSocket (ws ping/pong).
  window.phonePcApi.on('link-latency', (p) => {
    if (!p || typeof p.rttMs !== 'number') return;
    const ms = Math.max(1, Math.round(p.rttMs));
    latencyBadge.textContent = `Ping: ${ms}ms`;
    const healthLatency = $id('health-latency');
    if (healthLatency) healthLatency.textContent = `${ms} ms`;
    const now = Date.now();
    if (now - lastLatencyToast > 30000) {
      lastLatencyToast = now;
      showToast(`Wi-Fi round trip to phone: ${ms}ms`, 'info', 3000);
    }
  });

  // Load and start QR Token
  async function refreshQr() {
    clearInterval(qrTimerInterval);
    qrLoading.classList.remove('hidden');
    try {
      const tokenData = await window.phonePcApi.generatePairingToken();
      if (tokenData) {
        qrImg.src = tokenData.qrDataUrl;
        manualUriInput.value = tokenData.uri;
        qrLoading.classList.add('hidden');

        remainingSeconds = Math.round(tokenData.expiresInMs / 1000);
        updateCountdownDisplay();
        qrTimerInterval = setInterval(() => {
          remainingSeconds--;
          updateCountdownDisplay();
          if (remainingSeconds <= 0) {
            clearInterval(qrTimerInterval);
            qrCountdown.textContent = 'Token expired. Refreshing...';
            refreshQr();
          }
        }, 1000);
      }
    } catch (err) {
      console.error('Failed to refresh pairing token', err);
      qrCountdown.textContent = 'Error generating pairing QR';
    }
  }

  function updateCountdownDisplay() {
    qrCountdown.textContent = `Expires in: ${remainingSeconds}s`;
  }

  btnRefreshQr.addEventListener('click', () => {
    refreshQr();
    showToast('Pairing QR refreshed with new crypto token');
  });

  btnCopyUri.addEventListener('click', () => {
    navigator.clipboard.writeText(manualUriInput.value);
    btnCopyUri.innerHTML = IC('check');
    showToast('Pairing URI copied to clipboard', 'info');
    setTimeout(() => { btnCopyUri.innerHTML = IC('copy'); }, 2000);
  });

  // Rate Limit Reset Button
  btnResetRateLimit.addEventListener('click', async () => {
    try {
      await window.phonePcApi.resetRateLimit();
      await refreshQr();
      showToast('Rate limits and pending queues cleared!', 'success');
    } catch (err) {
      showToast('Error resetting rate limits', 'error');
    }
  });

  // Session Duration Timer
  function startSessionTimer() {
    sessionStartTime = Date.now();
    clearInterval(sessionTimerInterval);
    sessionTimerInterval = setInterval(() => {
      const elapsed = Math.floor((Date.now() - sessionStartTime) / 1000);
      const mins = String(Math.floor(elapsed / 60)).padStart(2, '0');
      const secs = String(elapsed % 60).padStart(2, '0');
      activeTimer.textContent = `${mins}:${secs}`;
    }, 1000);
  }

  function stopSessionTimer() {
    clearInterval(sessionTimerInterval);
    activeTimer.textContent = '00:00';
  }

  // Pending pairing request handler (Step 6)
  window.phonePcApi.on('pending-pairing-request', (data) => {
    currentPendingId = data.pendingId;
    promptDeviceName.textContent = data.deviceName || 'Android Phone';
    displayConfirmationCode.textContent = data.confirmationCode;
    confirmErrorMsg.classList.add('hidden');
    confirmErrorMsg.textContent = '';

    btnApprovePairing.disabled = false;
    btnApprovePairing.innerHTML = IC('check') + ' Approve Pairing';

    sessionIdleView.classList.add('hidden');
    confirmationBox.classList.remove('hidden');
    sessionBadge.textContent = 'Approval Required';
    sessionBadge.className = 'badge badge-warning';

    globalStatusDot.className = 'status-dot pending';
    globalStatusText.textContent = `Pairing verification with ${data.deviceName}...`;

    showToast(`Pairing request from ${data.deviceName} (Code: ${data.confirmationCode})`, 'warning', 6000);
  });

  // Approve Pairing Click Handler
  btnApprovePairing.addEventListener('click', async () => {
    if (!currentPendingId) {
      confirmationBox.classList.add('hidden');
      sessionIdleView.classList.remove('hidden');
      return;
    }

    btnApprovePairing.disabled = true;
    btnApprovePairing.textContent = 'Approving...';

    try {
      const res = await window.phonePcApi.approvePairing(currentPendingId);
      if (res && res.success) {
        confirmationBox.classList.add('hidden');
        activeSession = res.session;
        activeSessionBox.classList.remove('hidden');
        activePhoneName.textContent = res.session.deviceName || 'Phone';
        const approveModel = $id('hero-model');
        if (approveModel) {
          approveModel.textContent = res.session.modelCode || '';
          approveModel.classList.toggle('hidden', !res.session.modelCode);
        }
        activeSessionId.textContent = res.session.sessionId;
        sessionBadge.textContent = 'Active Link';
        sessionBadge.className = 'badge badge-success';
        globalStatusDot.className = 'status-dot connected';
        globalStatusText.textContent = `Connected: ${res.session.deviceName || 'Phone'}`;
        startSessionTimer();
        showToast(`Connected successfully with ${res.session.deviceName}!`, 'success');
      } else {
        btnApprovePairing.disabled = false;
        btnApprovePairing.innerHTML = IC('check') + ' Retry Approve';
        confirmErrorMsg.textContent = res.error || 'Pairing expired or connection dropped. Try scanning again.';
        confirmErrorMsg.classList.remove('hidden');
        showToast(res.error || 'Pairing approval failed', 'error');
      }
    } catch (err) {
      btnApprovePairing.disabled = false;
      btnApprovePairing.innerHTML = IC('check') + ' Retry Approve';
      confirmErrorMsg.textContent = `Error: ${err.message}`;
      confirmErrorMsg.classList.remove('hidden');
    }
  });

  // Reject Pairing Click Handler
  btnRejectPairing.addEventListener('click', async () => {
    if (currentPendingId) {
      await window.phonePcApi.rejectPairing(currentPendingId);
    }
    confirmationBox.classList.add('hidden');
    sessionIdleView.classList.remove('hidden');
    sessionBadge.textContent = 'Declined';
    sessionBadge.className = 'badge';
    globalStatusDot.className = 'status-dot disconnected';
    globalStatusText.textContent = 'Pairing declined';
    currentPendingId = null;
    showToast('Pairing request declined');
  });

  // Dismiss / Cancel Confirmation Box
  btnDismissConfirm.addEventListener('click', () => {
    confirmationBox.classList.add('hidden');
    sessionIdleView.classList.remove('hidden');
    sessionBadge.textContent = 'Awaiting Phone';
    sessionBadge.className = 'badge';
    currentPendingId = null;
  });

  // Disconnect Active Session
  btnDisconnectActive.addEventListener('click', async () => {
    if (activeSession) {
      await window.phonePcApi.disconnectSession(activeSession.sessionId);
      activeSessionBox.classList.add('hidden');
      sessionIdleView.classList.remove('hidden');
      sessionBadge.textContent = 'Disconnected';
      sessionBadge.className = 'badge';
      globalStatusDot.className = 'status-dot disconnected';
      globalStatusText.textContent = 'Disconnected';
      stopSessionTimer();
      activeSession = null;
      clearPhoneDataOnDisconnect();
      showToast('Phone disconnected');
    }
  });

  // Quick Action Tiles
  tileSendClip.addEventListener('click', () => {
    btnSendClipboard.click();
  });
  tileOpenChat.addEventListener('click', () => {
    switchTab('tab-chat-files');
    chatInput.focus();
  });
  tileSendFile.addEventListener('click', () => {
    switchTab('tab-chat-files');
    btnSelectFile.click();
  });

  // ---- Overview activity feed (with one-tap OTP copy) ----
  function logActivity(text, opts = {}) {
    if (!activityFeed) return;
    if (activityFeed.querySelector('.history-empty')) activityFeed.innerHTML = '';
    const row = document.createElement('div');
    row.className = 'history-item';
    const time = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    row.innerHTML = `<span class="history-icon">${IC(opts.icon || 'activity')}</span>
      <div class="history-details"><strong>${escapeHtml(text)}</strong>
      <span class="history-meta">${time}</span></div>`;
    if (opts.code) {
      const btn = document.createElement('button');
      btn.className = 'btn btn-secondary btn-sm';
      btn.textContent = 'Copy code';
      btn.addEventListener('click', () => {
        navigator.clipboard.writeText(opts.code);
        showToast(`Code ${opts.code} copied`, 'success');
      });
      row.appendChild(btn);
    }
    activityFeed.prepend(row);
    while (activityFeed.children.length > 40) activityFeed.lastElementChild.remove();
  }

  tileScreenshot.addEventListener('click', () => {
    switchTab('tab-screen-mirror');
    setTimeout(() => btnCaptureScreenshot.click(), 150);
  });
  tileFindPhone.addEventListener('click', () => btnFindPhone.click());
  tileLockPhone.addEventListener('click', async () => {
    const res = await window.phonePcApi.phonePower('LOCK');
    if (res && res.ok) showToast('Phone locked', 'success');
    else showToast((res && res.error) || 'Phone not connected', 'error');
  });

  // "Pair another device": reveal the QR card on demand while connected.
  if (btnPairAnother) {
    btnPairAnother.addEventListener('click', () => {
      qrCard.classList.remove('hidden');
      pairingGrid.classList.remove('no-qr');
      refreshQr();
      qrCard.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
  }

  // Everything shown FROM the phone goes stale the moment the phone is gone:
  // the mirror keeps its last painted frame, the gallery keeps its last
  // listing, and battery / temperature keep their last reading. None of that
  // is true any more, so wipe it all in one place.
  function clearPhoneDataOnDisconnect() {
    // Mirror: wipe the painted frame and bring the placeholder back.
    try { ctx.clearRect(0, 0, screenCanvas.width, screenCanvas.height); } catch (_) { /* no ctx yet */ }
    if (screenPlaceholder) screenPlaceholder.classList.remove('hidden');

    // Media gallery: drop items and selection, show the disconnected state.
    mediaItems = [];
    mediaSelected.clear();
    mediaSupported = null;
    const grid = $id('media-grid');
    if (grid) grid.innerHTML = '<div class="history-empty">Phone not connected.</div>';
    updateMediaButtons();

    // Call log + live call state.
    callState = { state: 'IDLE', number: '', name: '', direction: '' };
    renderCallState();
    const recents = $id('call-recents-list');
    if (recents) recents.innerHTML = '<div class="history-empty">Phone not connected.</div>';

    // SMS: threads, open conversation and unread counters.
    smsThreads = [];
    smsByAddr = {};
    smsCurrentAddr = null;
    smsSupported = null;
    const smsList = $id('sms-thread-list');
    if (smsList) smsList.innerHTML = '<div class="history-empty">Phone not connected.</div>';
    const smsMsgs = $id('sms-messages');
    if (smsMsgs) smsMsgs.innerHTML = '<div class="chat-msg system">Phone not connected.</div>';
    const smsBadge = $id('sms-unread-badge');
    if (smsBadge) smsBadge.textContent = '0 unread';
    const navBadge = $id('nav-badge-messages');
    if (navBadge) navBadge.classList.add('hidden');

    // Notifications mirrored from the phone go with it.
    notifState = { paused: notifState.paused, mutedApps: notifState.mutedApps, total: 0, items: [] };
    renderNotifications();

    // Battery + temperature: back to the waiting placeholders.
    const fill = $id('phone-battery-fill');
    if (fill) { fill.style.width = '0%'; fill.style.background = '#34d399'; }
    const bpct = $id('phone-battery-pct');
    if (bpct) bpct.textContent = '—';
    const bbadge = $id('phone-battery-badge');
    if (bbadge) bbadge.textContent = 'Waiting…';
    const btext = $id('phone-battery-text');
    if (btext) btext.textContent = 'No battery data yet';
    const hb = $id('health-battery');
    if (hb) hb.textContent = '—';
    const hbs = $id('health-battery-sub');
    if (hbs) hbs.textContent = 'Waiting for the phone';
    const hl = $id('health-latency');
    if (hl) hl.textContent = '—';
    if (latencyBadge) latencyBadge.textContent = 'Ping: …';

    // The mirror cannot be live without a phone behind it.
    setMirrorLive(false);
    lastMirrorFrameAt = 0;
  }

  // Session status changes from backend. Named so a freshly (re)loaded page
  // can repaint from the CURRENT state too: the event itself only fires on
  // transitions and races page load (the status containers stayed empty).
  function applySessionStatus(data) {
    if (data.status === 'connected') {
      sessionIdleView.classList.add('hidden');
      confirmationBox.classList.add('hidden');
      activeSessionBox.classList.remove('hidden');
      activePhoneName.textContent = data.deviceName || 'Android Phone';
      activeSessionId.textContent = data.sessionId;
      sessionBadge.textContent = 'Active Link';
      sessionBadge.className = 'badge badge-success';
      globalStatusDot.className = 'status-dot connected';
      globalStatusText.textContent = `Connected: ${data.deviceName || 'Android Phone'}`;
      startSessionTimer();
      // Link bar + hero: [PC] ━━●━━ [phone]
      const phoneName = data.deviceName || 'Android Phone';
      linkPhoneName.textContent = phoneName;
      linkPhoneName.classList.remove('link-offline');
      linkBarSetConnected(true);
      heroPhoneName.textContent = phoneName;
      const heroModel = $id('hero-model');
      if (heroModel) {
        heroModel.textContent = data.modelCode || '';
        heroModel.classList.toggle('hidden', !data.modelCode);
      }
      const healthPhone = $id('health-phone');
      if (healthPhone) healthPhone.textContent = phoneName;
      const healthPhoneSub = $id('health-phone-sub');
      if (healthPhoneSub) healthPhoneSub.textContent = data.modelCode || 'Reconnects automatically';
      heroSessionLine.textContent = `Connected — session ${String(data.sessionId || '').slice(0, 8)}…`;
      // QR only shows when no device is paired (or on demand via Pair another device)
      qrCard.classList.add('hidden');
      pairingGrid.classList.add('no-qr');
      logActivity(`Connected to ${phoneName}`, { icon: 'link' });
      window.phonePcApi.getNetworkInfo().then((net) => {
        if (net && net.fingerprint && activeFingerprint) activeFingerprint.textContent = net.fingerprint;
      }).catch(() => {});
      window.phonePcApi.measureLatency().catch(() => {});
    } else {
      activeSessionBox.classList.add('hidden');
      sessionIdleView.classList.remove('hidden');
      sessionBadge.textContent = 'Disconnected';
      sessionBadge.className = 'badge';
      globalStatusDot.className = 'status-dot disconnected';
      globalStatusText.textContent = 'Disconnected';
      stopSessionTimer();
      activeSession = null;
      linkPhoneName.textContent = 'No phone';
      linkPhoneName.classList.add('link-offline');
      linkBarSetConnected(false);
      heroPhoneName.textContent = 'No phone connected';
      const heroModelOff = $id('hero-model');
      if (heroModelOff) { heroModelOff.textContent = ''; heroModelOff.classList.add('hidden'); }
      const healthPhoneOff = $id('health-phone');
      if (healthPhoneOff) healthPhoneOff.textContent = '—';
      const healthPhoneOffSub = $id('health-phone-sub');
      if (healthPhoneOffSub) healthPhoneOffSub.textContent = 'Not connected';
      heroSessionLine.textContent = 'Pair your phone to see live status here.';
      qrCard.classList.remove('hidden');
      pairingGrid.classList.remove('no-qr');
      clearPhoneDataOnDisconnect();
    }
  }
  window.phonePcApi.on('session-status-change', applySessionStatus);
  // Pull the state once at startup: if the phone connected while this page
  // was still loading (or after a reload), no transition event will come.
  window.phonePcApi.getSessionStatus()
    .then((s) => { if (s && s.status === 'connected') applySessionStatus(s); })
    .catch(() => {});

  function linkBarSetConnected(connected) {
    const bar = $id('link-bar');
    if (bar) bar.classList.toggle('is-connected', !!connected);
  }

  // Chat handling
  function appendChatMessage(text, from, isSent) {
    const msg = document.createElement('div');
    msg.className = `chat-msg ${isSent ? 'sent' : 'received'}`;
    const time = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    msg.innerHTML = `
      <div class="msg-header">${isSent ? 'You (PC)' : from} • ${time}</div>
      <div class="msg-body" dir="auto">${linkifyHtml(escapeHtml(text))}</div>
    `;
    chatMessages.appendChild(msg);
    chatMessages.scrollTop = chatMessages.scrollHeight;
  }

  function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  }

  // Turn plain-text URLs (already HTML-escaped) into clickable links so a LAN
  // chat message can be opened without copying it into the browser by hand.
  // A delegated click handler below opens them through the main process.
  function linkifyHtml(escaped) {
    const re = /(https?:\/\/[^\s<>&"']+)/g;
    return escaped.replace(re, (m) => `<a class="chat-link" href="#" data-url="${m}">${m}</a>`);
  }

  const openChatLink = (url) => {
    Promise.resolve(window.phonePcApi.openExternal(url)).then((r) => {
      if (r && r.ok === false) showToast(r.error || 'Could not open that link', 'error');
    }).catch(() => {});
  };
  // Delegated so every pasted link works without re-binding per message.
  ['chat-messages', 'sms-messages'].forEach((id) => {
    const box = $id(id);
    if (!box) return;
    box.addEventListener('click', (e) => {
      const a = e.target.closest('a.chat-link');
      if (!a) return;
      e.preventDefault();
      openChatLink(a.dataset.url || a.textContent.trim());
    });
  });

  btnSendChat.addEventListener('click', async () => {
    const text = chatInput.value.trim();
    if (!text) return;
    await window.phonePcApi.sendChatMessage(text);
    appendChatMessage(text, 'PC', true);
    chatInput.value = '';
  });

  chatInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      btnSendChat.click();
    }
  });

  btnClearChat.addEventListener('click', () => {
    chatMessages.innerHTML = '<div class="chat-msg system">Chat cleared. Messages remain strictly on local Wi-Fi.</div>';
  });

  window.phonePcApi.on('chat-message-received', (data) => {
    appendChatMessage(data.text, data.from, false);
    showToast(`New message from ${data.from}: ${data.text.slice(0, 30)}...`, 'info');
  });

  // Clipboard handling
  btnSendClipboard.addEventListener('click', async () => {
    const text = clipboardPreview.value || await window.phonePcApi.getClipboard();
    await window.phonePcApi.sendClipboardToPhone(text);
    showToast('Pushed PC clipboard to phone!', 'success');
  });

  btnReadClipboard.addEventListener('click', async () => {
    const text = await window.phonePcApi.getClipboard();
    clipboardPreview.value = text;
    showToast('Loaded current PC clipboard', 'info');
  });

  // Auto-sync clipboard (toggle state persisted in settings.json)
  const toggleSettingsAutoClipboard = document.getElementById('toggle-settings-auto-clipboard');

  function startAutoClipboard() {
    clearInterval(autoClipboardInterval);
    autoClipboardInterval = setInterval(async () => {
      const current = await window.phonePcApi.getClipboard();
      if (current && current !== lastClipboardValue) {
        lastClipboardValue = current;
        clipboardPreview.value = current;
        await window.phonePcApi.sendClipboardToPhone(current);
        showToast('Auto-synced new clipboard to phone', 'info');
      }
    }, 2000);
  }

  function stopAutoClipboard() {
    clearInterval(autoClipboardInterval);
    autoClipboardInterval = null;
  }

  function setAutoClipboard(enabled, persist) {
    toggleAutoClipboard.checked = enabled;
    toggleSettingsAutoClipboard.checked = enabled;
    if (enabled) {
      startAutoClipboard();
    } else {
      stopAutoClipboard();
    }
    if (persist) {
      window.phonePcApi.saveSettings({ autoClipboardSync: enabled });
    }
  }

  toggleAutoClipboard.addEventListener('change', () => {
    setAutoClipboard(toggleAutoClipboard.checked, true);
    showToast(toggleAutoClipboard.checked ? 'Clipboard auto-sync enabled' : 'Clipboard auto-sync disabled');
  });
  toggleSettingsAutoClipboard.addEventListener('change', () => {
    setAutoClipboard(toggleSettingsAutoClipboard.checked, true);
  });

  // Load persisted settings + About info
  try {
    const settings = await window.phonePcApi.getSettings();
    setAutoClipboard(!!settings.autoClipboardSync, false);

    // General: start with Windows / minimise to tray
    const tgStartWin = $id('toggle-start-with-windows');
    const tgMinTray = $id('toggle-minimise-to-tray');
    if (tgStartWin) {
      tgStartWin.checked = !!settings.startWithWindows;
      tgStartWin.addEventListener('change', async () => {
        await window.phonePcApi.saveSettings({ startWithWindows: tgStartWin.checked });
        showToast(tgStartWin.checked ? 'DuoLink will start with Windows' : 'Start with Windows disabled');
      });
    }
    if (tgMinTray) {
      tgMinTray.checked = settings.minimiseToTray !== false;
      tgMinTray.addEventListener('change', async () => {
        await window.phonePcApi.saveSettings({ minimiseToTray: tgMinTray.checked });
        showToast(tgMinTray.checked ? 'Closing will minimise to tray' : 'Closing will exit DuoLink');
      });
    }

    // Advanced: open log / check for updates (offline)
    const btnOpenLog = $id('btn-open-log');
    if (btnOpenLog) btnOpenLog.addEventListener('click', () => window.phonePcApi.openLog());
    const btnCheckUpdates = $id('btn-check-updates');
    if (btnCheckUpdates) {
      btnCheckUpdates.addEventListener('click', async () => {
        const res = await window.phonePcApi.checkUpdates();
        showToast(`You're running DuoLink v${(res && res.version) || '1.0.0'} — you're up to date (offline app)`, 'success');
      });
    }
  } catch (err) {
    console.error('Failed to load settings', err);
  }

  // ---------------- Appearance: palettes / follow system / motion ----------------
  // One token set lives in tokens.css; a palette is just a [data-theme] block,
  // so adding a ninth theme is markup + one CSS block, nothing here.
  const themeEl = document.documentElement;
  const themeCards = Array.from(document.querySelectorAll('#theme-gallery [data-theme-id]'));
  const toggleFollowSystem = $id('toggle-follow-system');
  const toggleReduceMotion = $id('toggle-reduce-motion');
  let themeFadeTimer = 0;

  function paintThemeUI(t) {
    if (!t) return;
    const palette = t.palette || t.theme || (t.resolved === 'light' ? 'light' : 'dark');
    if (themeEl.dataset.theme && themeEl.dataset.theme !== palette) {
      // Short colour cross-fade; the reduced-motion rules collapse it to 0ms.
      themeEl.classList.add('theme-switching');
      clearTimeout(themeFadeTimer);
      themeFadeTimer = setTimeout(() => themeEl.classList.remove('theme-switching'), 240);
    }
    themeEl.dataset.theme = palette;
    themeEl.dataset.base = t.base || (t.light || t.resolved === 'light' ? 'light' : 'dark');
    themeEl.dataset.reduceMotion = t.reduceMotion ? '1' : '0';
    themeCards.forEach((card) => {
      card.setAttribute('aria-checked', !t.followSystem && card.dataset.themeId === palette ? 'true' : 'false');
    });
    if (toggleFollowSystem) toggleFollowSystem.checked = !!t.followSystem;
    if (toggleReduceMotion) toggleReduceMotion.checked = !!t.reduceMotion;
  }

  async function pushTheme(patch) {
    try {
      const t = await window.phonePcApi.setTheme(patch);
      if (t) paintThemeUI(t);
    } catch (err) {
      console.error('Theme change failed', err);
    }
  }

  function selectPalette(id) {
    if (!id) return;
    pushTheme({ theme: id, followSystem: false });
    // Keep the gallery keyboard-friendly: focus follows the chosen card.
    const card = themeCards.find((c) => c.dataset.themeId === id);
    if (card) card.focus({ preventScroll: true });
  }

  themeCards.forEach((card, i) => {
    card.addEventListener('click', () => selectPalette(card.dataset.themeId));
    card.addEventListener('keydown', (e) => {
      if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft' && e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
      e.preventDefault();
      const step = (e.key === 'ArrowRight' || e.key === 'ArrowDown') ? 1 : -1;
      const next = themeCards[(i + step + themeCards.length) % themeCards.length];
      if (next) selectPalette(next.dataset.themeId);
    });
  });

  if (toggleFollowSystem) {
    toggleFollowSystem.addEventListener('change', () => pushTheme({ followSystem: toggleFollowSystem.checked }));
  }
  if (toggleReduceMotion) {
    toggleReduceMotion.addEventListener('change', () => pushTheme({ reduceMotion: toggleReduceMotion.checked }));
  }

  // Live updates: system theme flips, other windows, main-process pushes.
  window.phonePcApi.on('theme-changed', (t) => { if (t) paintThemeUI(t); });
  try {
    const initialTheme = await window.phonePcApi.getTheme();
    if (initialTheme) paintThemeUI(initialTheme);
  } catch (_) { /* preview stub: keep pre-paint defaults */ }

  // ---------------- Guide: category switcher ----------------
  const guideItems = Array.from(document.querySelectorAll('.guide-nav-item'));
  const guideSections = Array.from(document.querySelectorAll('.guide-section'));
  function openGuideSection(id) {
    if (!id) return;
    guideItems.forEach((btn) => {
      const on = btn.dataset.guide === id;
      btn.classList.toggle('active', on);
      btn.setAttribute('aria-current', on ? 'true' : 'false');
    });
    guideSections.forEach((sec) => sec.classList.toggle('active', sec.id === id));
    const main = document.querySelector('.main-content');
    if (main) main.scrollTo({ top: 0, behavior: 'auto' });
  }
  guideItems.forEach((btn) => {
    btn.addEventListener('click', () => openGuideSection(btn.dataset.guide));
  });
  if (guideItems.length) openGuideSection(guideItems[0].dataset.guide);
  // ---------------- PC file access: one consent switch, whole machine ------
  const fileAccessToggle = $id('toggle-file-access');
  const fileAccessDrives = $id('file-access-drives');
  const fileAccessHint = $id('file-access-hint');

  function renderFileAccess(res) {
    const full = !!(res && res.fullAccess);
    const drives = Array.isArray(res && res.drives) ? res.drives : [];
    if (fileAccessToggle) fileAccessToggle.checked = full;
    if (fileAccessHint) {
      fileAccessHint.textContent = full
        ? `On — the phone can browse ${drives.length ? drives.join(', ') : 'this PC'} while it is connected.`
        : 'Off — the phone cannot see any files on this PC.';
    }
    if (!fileAccessDrives) return;
    if (!full) {
      fileAccessDrives.innerHTML = '<span class="file-access-chip is-off">Access off</span>';
      return;
    }
    fileAccessDrives.innerHTML = drives.length
      ? drives.map((d) => `<span class="file-access-chip"><svg class="ic" aria-hidden="true"><use href="#i-folder"/></svg>${escapeHtml(d)}</span>`).join('')
      : '<span class="file-access-chip is-off">No drives detected</span>';
  }

  async function loadFileAccess() {
    try {
      const res = await window.phonePcApi.getSharedFolders();
      if (res) renderFileAccess(res);
    } catch (_) { /* preview or test stub */ }
  }

  if (fileAccessToggle) {
    fileAccessToggle.addEventListener('change', async () => {
      const want = fileAccessToggle.checked;
      fileAccessToggle.disabled = true;
      try {
        const res = await window.phonePcApi.setFileAccess(want);
        if (res && res.ok) {
          renderFileAccess({ fullAccess: res.fullAccess, drives: res.drives });
          showToast(want ? 'The phone can browse this PC' : 'Phone file access turned off', 'success');
        } else {
          fileAccessToggle.checked = !want;
          showToast((res && res.error) || 'Could not change file access', 'error');
        }
      } catch (_) {
        fileAccessToggle.checked = !want;
        showToast('Could not change file access', 'error');
      } finally {
        fileAccessToggle.disabled = false;
      }
    });
  }
  loadFileAccess();

  // Guide deep links: the Overview empty state points at specific chapters.
  document.querySelectorAll('[data-guide-link]').forEach((el) => {
    el.addEventListener('click', (e) => {
      e.preventDefault();
      switchTab('tab-guide');
      openGuideSection(el.dataset.guideLink);
    });
  });

  try {
    const info = await window.phonePcApi.getAppInfo();
    // The About tab's version/electron/node/platform rows were removed (app
    // info section); only the fields that still exist in the DOM are painted.
    const aboutName = document.getElementById('about-name');
    if (aboutName) aboutName.textContent = info.name;
    const udPath = document.getElementById('settings-userdata-path');
    if (udPath) udPath.textContent = info.userDataPath;
    const lgPath = document.getElementById('settings-log-path');
    if (lgPath) lgPath.textContent = info.logPath;
    // Link bar: [PC name] ━━●━━ [phone name]
    if (linkPcName && info.hostname) linkPcName.textContent = info.hostname;
  } catch (err) {
    console.error('Failed to load app info', err);
  }

  // Secure-session fingerprint in the Overview session card
  try {
    const net = await window.phonePcApi.getNetworkInfo();
    if (net && net.fingerprint && activeFingerprint) activeFingerprint.textContent = net.fingerprint;
  } catch (_) {}

  window.phonePcApi.on('clipboard-push-received', async (data) => {
    clipboardPreview.value = data.text;
    await window.phonePcApi.setClipboard(data.text);
    lastClipboardValue = data.text;
    const msg = document.createElement('div');
    msg.className = 'chat-msg system';
    msg.textContent = `Clipboard updated from phone (${data.text.length} chars)`;
    chatMessages.appendChild(msg);
    showToast(`Received clipboard from phone (${data.text.length} chars)`, 'success');
  });

  // Files handling
  function addTransferHistoryItem(filename, sizeStr, direction = 'sent') {
    if (transferHistoryList.querySelector('.history-empty')) {
      transferHistoryList.innerHTML = '';
    }
    const item = document.createElement('div');
    item.className = 'history-item';
    item.innerHTML = `
      <span class="history-icon">${direction === 'sent' ? IC('upload') : IC('download')}</span>
      <div class="history-details">
        <strong>${escapeHtml(filename)}</strong>
        <span class="history-meta">${sizeStr} • ${new Date().toLocaleTimeString()}</span>
      </div>
    `;
    transferHistoryList.prepend(item);
  }

  btnSelectFile.addEventListener('click', async () => {
    const result = await window.phonePcApi.selectAndSendFile();
    if (result && result.success) {
      const sizeKb = Math.round(result.size / 1024);
      transferFilename.textContent = `Sent: ${result.filename} (${sizeKb} KB)`;
      transferPercentage.textContent = '100%';
      transferProgressFill.style.width = '100%';
      transferProgressContainer.classList.remove('hidden');
      addTransferHistoryItem(result.filename, `${sizeKb} KB`, 'sent');
      showToast(`Sent ${result.filename} to phone!`, 'success');
    }
  });

  btnOpenReceived.addEventListener('click', async () => {
    await window.phonePcApi.openReceivedFolder();
  });
  // Settings → Security and privacy: same folder shortcut (mockup row)
  const btnOpenReceivedSettings = $id('btn-open-received-settings');
  if (btnOpenReceivedSettings) {
    btnOpenReceivedSettings.addEventListener('click', async () => {
      await window.phonePcApi.openReceivedFolder();
    });
  }

  window.phonePcApi.on('file-transfer-start', (data) => {
    transferFilename.dataset.currentName = data.filename;
    transferFilename.textContent = `Receiving: ${data.filename}`;
    transferPercentage.textContent = '0%';
    transferProgressFill.style.width = '0%';
    transferProgressContainer.classList.remove('hidden');
    showToast(`Receiving ${data.filename} from phone...`, 'info');
  });

  window.phonePcApi.on('file-transfer-progress', (data) => {
    transferPercentage.textContent = `${data.progress}%`;
    transferProgressFill.style.width = `${data.progress}%`;
  });

  window.phonePcApi.on('file-transfer-complete', (data) => {
    transferPercentage.textContent = '100% Completed';
    transferProgressFill.style.width = '100%';
    addTransferHistoryItem(transferFilename.dataset.currentName || data.transferId, 'Completed', 'received');
    showToast('File transfer complete! Saved to Received folder.', 'success');
    setTimeout(() => {
      transferProgressContainer.classList.add('hidden');
    }, 4000);
  });

  window.phonePcApi.on('file-transfer-error', (data) => {
    transferPercentage.textContent = 'Failed';
    showToast(`File transfer failed: ${data && data.error || 'unknown error'}`, 'error');
    setTimeout(() => {
      transferProgressContainer.classList.add('hidden');
    }, 4000);
  });

  window.phonePcApi.on('file-transfer-cancelled', () => {
    transferProgressContainer.classList.add('hidden');
    showToast('File transfer cancelled', 'warning');
  });

  // Drag and drop file support
  dropZone.addEventListener('dragover', (e) => {
    e.preventDefault();
    dropZone.classList.add('drag-active');
  });
  dropZone.addEventListener('dragleave', () => {
    dropZone.classList.remove('drag-active');
  });
  dropZone.addEventListener('drop', async (e) => {
    e.preventDefault();
    dropZone.classList.remove('drag-active');
    const files = e.dataTransfer.files;
    if (files.length > 0) {
      const filePath = files[0].path;
      if (filePath) {
        showToast(`Sending ${files[0].name}...`, 'info');
        const res = await window.phonePcApi.sendDroppedFile(filePath);
        if (res && res.success) {
          showToast(`File ${files[0].name} sent successfully!`, 'success');
        }
      }
    }
  });

  // Control toggles
  toggleAllowControl.addEventListener('change', async () => {
    const res = await window.phonePcApi.setAllowControl(toggleAllowControl.checked);
    toggleAllowControl.checked = res.allowed;
    showToast(res.allowed ? 'Remote input from phone enabled' : 'Remote input disabled', res.allowed ? 'warning' : 'info');
  });

  btnTriggerKillswitch.addEventListener('click', () => {
    killswitchIndicator.classList.remove('hidden');
    toggleAllowControl.checked = false;
    if (btnTitlebarKillswitch) btnTitlebarKillswitch.classList.add('is-active');
    showToast('Emergency Kill Switch Triggered! All remote control halted.', 'error', 5000);
  });

  // Title bar kill switch — always visible, same action (spec 6.1).
  if (btnTitlebarKillswitch) {
    btnTitlebarKillswitch.addEventListener('click', () => {
      btnTriggerKillswitch.click();
    });
  }

  window.phonePcApi.on('kill-switch-triggered', () => {
    killswitchIndicator.classList.remove('hidden');
    toggleAllowControl.checked = false;
    if (btnTitlebarKillswitch) btnTitlebarKillswitch.classList.add('is-active');
    showToast('Emergency Kill Switch Triggered! Remote control killed.', 'error', 5000);
  });

  // Media & System Shortcuts
  btnVolDown.addEventListener('click', () => window.phonePcApi.triggerMediaAction('VOLUME_DOWN'));
  btnVolMute.addEventListener('click', () => window.phonePcApi.triggerMediaAction('VOLUME_MUTE'));
  btnPlayPause.addEventListener('click', () => window.phonePcApi.triggerMediaAction('PLAY_PAUSE'));
  btnVolUp.addEventListener('click', () => window.phonePcApi.triggerMediaAction('VOLUME_UP'));
  btnLockPc.addEventListener('click', () => window.phonePcApi.triggerSystemAction('LOCK_PC'));

  // Screen Canvas & Remote Gestures
  const ctx = screenCanvas.getContext('2d');
  let isDown = false;
  let startX = 0, startY = 0;

  // Reused Image + Blob URL: one decode per frame, no base64 data-URL churn.
  let mirrorImg = null;

  // Stream liveness: the nav buttons (and the mouse-map badge) only work
  // while frames are actually flowing — a dead stream must not look usable.
  function setMirrorLive(live) {
    if (mirrorLive === live) return;
    mirrorLive = live;
    btnPhoneBack.disabled = !live;
    btnPhoneHome.disabled = !live;
    btnPhoneRecents.disabled = !live;
    btnStartStream.disabled = live;
    btnStopStream.disabled = !live;
    const badge = $id('mirror-stream-badge');
    if (badge) {
      badge.textContent = live ? 'Live' : 'Idle';
      badge.className = 'badge' + (live ? ' badge-success' : '');
    }
    paintStreamStatus();
    if (!live) {
      screenPlaceholder.classList.remove('hidden');
      try { ctx.clearRect(0, 0, screenCanvas.width, screenCanvas.height); } catch (_) { /* no ctx */ }
      resetPhoneAudio();
      setMirrorStale(false);
    }
  }

  /**
   * Frames paused while the stream is still nominally live. The last frame
   * STAYS on screen (dimmed, with a note) instead of the pane going black —
   * the picture comes back on its own the moment frames resume.
   */
  function setMirrorStale(stale) {
    if (mirrorStale === !!stale) return;
    mirrorStale = !!stale;
    const note = $id('mirror-stale-note');
    if (note) note.classList.toggle('hidden', !mirrorStale);
    const badge = $id('mirror-stream-badge');
    if (badge && mirrorLive) {
      badge.textContent = mirrorStale ? 'Reconnecting…' : 'Live';
      badge.className = 'badge' + (mirrorStale ? ' badge-warning' : ' badge-success');
    }
    if (screenCanvas) screenCanvas.classList.toggle('mirror-dimmed', mirrorStale);
    paintStreamStatus();
  }

  // ---- Phone sound on the PC ----
  // The phone streams its own playback as PCM16LE binary chunks; we schedule
  // them on a Web Audio clock with a short lead so Wi-Fi jitter plays as
  // silence, not as stutter. If the queue ever falls behind (a stall), we
  // resync forward instead of replaying the backlog — the old "stopped, then
  // fast-forward" artifact must never happen in audio form either.
  let phoneAudioCtx = null;
  let phoneAudioNextAt = 0;
  const PHONE_AUDIO_LEAD = 0.12;   // keep ~120ms queued
  const PHONE_AUDIO_MAX = 1.0;     // more than this queued = stale, resync

  function resetPhoneAudio() {
    phoneAudioNextAt = 0;
  }

  window.phonePcApi.on('phone-audio-chunk', (d) => {
    if (!d || !d.pcm || !d.pcm.length) return;
    // Sound follows the picture: phone audio only plays on the PC while the
    // screen stream is actually live. (Otherwise a stopped mirror kept
    // streaming sound to the speakers.)
    if (!mirrorLive) {
      phoneAudioNextAt = 0;
      return;
    }
    try {
      const rate = d.sampleRate || 44100;
      const ch = Math.max(1, Math.min(2, d.channels || 1));
      if (!phoneAudioCtx || phoneAudioCtx.sampleRate !== rate) {
        const Ctor = window.AudioContext || window.webkitAudioContext;
        if (!Ctor) return;
        if (phoneAudioCtx) phoneAudioCtx.close().catch(() => {});
        phoneAudioCtx = new Ctor({ sampleRate: rate });
        phoneAudioNextAt = 0;
      }
      const ctx = phoneAudioCtx;
      if (ctx.state === 'suspended') ctx.resume().catch(() => {});

      // IPC hands the Buffer over as a Uint8Array; Int16Array needs 2-byte
      // alignment, so an odd byteOffset is copied once.
      const raw = d.pcm;
      let s16;
      if ((raw.byteOffset || 0) % 2 === 0) {
        s16 = new Int16Array(raw.buffer, raw.byteOffset || 0, raw.byteLength >> 1);
      } else {
        s16 = new Int16Array(raw.byteLength >> 1);
        new Uint8Array(s16.buffer).set(raw);
      }
      const frames = Math.floor(s16.length / ch);
      if (!frames) return;

      const buf = ctx.createBuffer(ch, frames, rate);
      for (let c = 0; c < ch; c++) {
        const out = buf.getChannelData(c);
        for (let i = 0, j = c; i < frames; i++, j += ch) out[i] = s16[j] / 32768;
      }

      const now = ctx.currentTime;
      if (phoneAudioNextAt === 0 || phoneAudioNextAt < now || phoneAudioNextAt > now + PHONE_AUDIO_MAX) {
        phoneAudioNextAt = now + PHONE_AUDIO_LEAD;
      }
      const src = ctx.createBufferSource();
      src.buffer = buf;
      src.connect(ctx.destination);
      src.start(phoneAudioNextAt);
      phoneAudioNextAt += buf.duration;
    } catch (_) { /* audio must never break the mirror UI */ }
  });

  // Phone-screen paint pump: at most ONE decode in flight. Assigning img.src
  // faster than the browser can decode cancels every in-flight load, so the
  // pane used to freeze on the last painted frame while frames kept arriving
  // (the "stream is stuck" artifact). Frames that pile up meanwhile collapse
  // to the newest one, so what shows is always fresh, never a stale burst.
  let mirrorDecodeBusy = false;
  let mirrorPendingFrame = null;

  // While the screen is open in its own window the in-app pane stays blank
  // (one picture is enough) but the link liveness keeps updating.
  let mirrorPopoutOpen = false;
  const mirrorPopoutNote = document.getElementById('mirror-popout-note');
  const setMirrorPopout = (open) => {
    mirrorPopoutOpen = !!open;
    if (mirrorPopoutNote) mirrorPopoutNote.classList.toggle('hidden', !mirrorPopoutOpen);
    if (screenCanvas) screenCanvas.style.visibility = mirrorPopoutOpen ? 'hidden' : '';
    if (mirrorPopoutOpen && screenPlaceholder) screenPlaceholder.classList.add('hidden');
  };
  if (window.phonePcApi.onScreenWindowState) {
    window.phonePcApi.onScreenWindowState(setMirrorPopout);
  }

  // Deliberate pop-out: the screen can live in its own always-on-top-free,
  // chrome-less window while this pane keeps the controls. Main sizes that
  // window from the real frame aspect, so a rotated phone opens correctly.
  const btnOpenScreenWindow = $id('btn-open-screen-window');
  if (btnOpenScreenWindow) {
    btnOpenScreenWindow.addEventListener('click', async () => {
      if (!window.phonePcApi || !window.phonePcApi.openScreenWindow) return;
      btnOpenScreenWindow.disabled = true;
      try {
        await window.phonePcApi.openScreenWindow();
      } catch (err) {
        console.warn('Could not open the screen window:', err && err.message ? err.message : err);
      } finally {
        // Main focuses an existing window instead of opening a second one.
        btnOpenScreenWindow.disabled = false;
      }
    });
  }

  // ---- Stream status: measured values only ----
  // ---- Overview quick actions -------------------------------------------
  // Each tile calls the real IPC the matching pane uses; nothing is faked.
  const qaMirror = $id('qa-mirror');
  if (qaMirror) {
    qaMirror.addEventListener('click', async () => {
      switchTab('tab-screen-mirror');
      try { await window.phonePcApi.openScreenWindow(); } catch (_) { /* pane still works */ }
    });
  }
  const qaWebcam = $id('qa-webcam');
  if (qaWebcam) {
    qaWebcam.addEventListener('click', () => {
      switchTab('tab-webcam');
      showToast('Press Start Camera to open the phone camera here', 'info');
    });
  }
  const qaSendFiles = $id('qa-send-files');
  if (qaSendFiles) {
    qaSendFiles.addEventListener('click', () => {
      switchTab('tab-chat-files');
      const pick = $id('btn-pick-file') || $id('btn-send-file') || $id('btn-files-pick');
      if (pick) pick.click();
    });
  }
  const qaClipboard = $id('qa-send-clipboard');
  if (qaClipboard) {
    qaClipboard.addEventListener('click', async () => {
      try {
        const clip = await window.phonePcApi.getClipboard();
        const text = typeof clip === 'string' ? clip : (clip && clip.text) || '';
        if (!text.trim()) { showToast('Clipboard is empty', 'info'); return; }
        const res = await window.phonePcApi.sendClipboardToPhone(text);
        showToast(res && res.ok === false ? `Could not send: ${res.error}` : 'Clipboard sent to the phone',
          res && res.ok === false ? 'error' : 'success');
      } catch (err) {
        showToast('Could not send the clipboard', 'error');
      }
    });
  }
  const qaFindPhone = $id('qa-find-phone');
  if (qaFindPhone) {
    qaFindPhone.addEventListener('click', async () => {
      try {
        await window.phonePcApi.findPhone(true);
        showToast('Ringing the phone…', 'info');
      } catch (_) {
        showToast('Could not reach the phone', 'error');
      }
    });
  }

  // ---- Stream status: measured values only ----
  // Nothing here is invented. Frames counts real decodes over the last second,
  // Picture is the last frame's pixel size, Orientation comes from that size.
  const streamStateText = $id('stream-state-text');
  const mirrorResolutionEl = $id('mirror-resolution');
  const mirrorFpsEl = $id('mirror-fps');
  const mirrorOrientationEl = $id('mirror-orientation');
  const streamLiveDot = $id('stream-live-dot');
  const mirrorFrameStamps = [];
  let mirrorLastDims = null;

  // Bitrate is measured from the actual JPEG bytes that arrived, never estimated.
  let mirrorBytesWindow = 0;
  let mirrorBytesSince = performance.now();

  function recordMirrorFrame(w, h, bytes) {
    const now = performance.now();
    mirrorFrameStamps.push(now);
    while (mirrorFrameStamps.length && now - mirrorFrameStamps[0] > 1000) mirrorFrameStamps.shift();
    if (bytes) mirrorBytesWindow += bytes;
    if (w && h) mirrorLastDims = { w, h };
    if (now - mirrorBytesSince >= 1000) {
      const hudMbps = document.getElementById('hud-mbps');
      if (hudMbps) hudMbps.textContent = ((mirrorBytesWindow * 8) / (now - mirrorBytesSince) / 1000).toFixed(1);
      mirrorBytesWindow = 0;
      mirrorBytesSince = now;
    }
  }

  function paintStreamStatus() {
    if (streamStateText) {
      streamStateText.textContent = mirrorStale ? 'Reconnecting' : (mirrorLive ? 'Live' : 'Idle');
    }
    if (streamLiveDot) {
      streamLiveDot.className = 'stat-dot' + (mirrorStale ? ' wait' : (mirrorLive ? ' live' : ''));
    }
    if (mirrorResolutionEl) {
      mirrorResolutionEl.textContent = mirrorLastDims ? `${mirrorLastDims.w} × ${mirrorLastDims.h}` : '—';
    }
    if (mirrorFpsEl) {
      mirrorFpsEl.textContent = mirrorLive && mirrorFrameStamps.length ? `${mirrorFrameStamps.length} fps` : '—';
    }
    if (mirrorOrientationEl) {
      mirrorOrientationEl.textContent = mirrorLastDims
        ? (mirrorLastDims.w > mirrorLastDims.h ? 'Landscape' : 'Portrait')
        : '—';
    }
    const hudFps = document.getElementById('hud-fps');
    if (hudFps) hudFps.textContent = mirrorFrameStamps.length ? String(mirrorFrameStamps.length) : '—';
    const hudRes = document.getElementById('hud-res');
    if (hudRes) hudRes.textContent = mirrorLastDims ? `${mirrorLastDims.w}×${mirrorLastDims.h}` : '—';
    const hudPing = document.getElementById('hud-ping');
    if (hudPing) {
      const badge = document.getElementById('latency-badge');
      const value = badge ? (badge.textContent.match(/(\d+)/) || [])[1] : null;
      hudPing.textContent = value || '—';
    }
  }
  // The readout ticks twice a second; the frame counter itself is O(1).
  setInterval(paintStreamStatus, 500);

  // Fullscreen: the stage takes the whole window, the control panel hides, and
  // Esc returns to the normal layout. Works in portrait and landscape.
  const streamShell = document.querySelector('.stream-shell');
  const btnMirrorFullscreen = $id('btn-mirror-fullscreen');
  function setMirrorFullscreen(on) {
    if (!streamShell) return;
    streamShell.classList.toggle('is-fullscreen', !!on);
    if (btnMirrorFullscreen) {
      btnMirrorFullscreen.setAttribute('aria-pressed', on ? 'true' : 'false');
      const label = btnMirrorFullscreen.lastChild;
      if (label && label.nodeType === 3) label.textContent = on ? ' Exit' : ' Fullscreen';
    }
  }
  if (btnMirrorFullscreen) {
    btnMirrorFullscreen.addEventListener('click', () => setMirrorFullscreen(!streamShell.classList.contains('is-fullscreen')));
  }
  const btnStreamHud = document.getElementById('btn-stream-hud');
  const streamHud = document.getElementById('stream-hud');
  if (btnStreamHud && streamHud) {
    btnStreamHud.addEventListener('click', () => {
      const hidden = streamHud.classList.toggle('hidden');
      btnStreamHud.setAttribute('aria-pressed', hidden ? 'false' : 'true');
    });
  }
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape' || !streamShell || !streamShell.classList.contains('is-fullscreen')) return;
    setMirrorFullscreen(false);
  });

  window.phonePcApi.on('phone-screen-frame', (data) => {
    const jpeg = data && data.jpeg;
    if (!jpeg || !jpeg.length) return;
    // A frame arrived: the stream is live — recorded before the visibility
    // check so the tab being hidden doesn't hide the liveness either.
    lastMirrorFrameAt = Date.now();
    recordMirrorFrame(data.width, data.height, jpeg.length);
    if (mirrorStale) setMirrorStale(false);
    if (!mirrorLive) setMirrorLive(true);
    // Pop-out owns the picture now: keep liveness, don't paint twice.
    if (mirrorPopoutOpen) return;
    // Only paint while the mirror pane is visible — decoding a 60 fps stream
    // nobody is watching just burns UI-thread time.
    const pane = document.getElementById('tab-screen-mirror');
    if (pane && !pane.classList.contains('active')) return;
    mirrorPendingFrame = data;
    pumpMirrorFrame();
  });

  // ---- H.264 mirror (native resolution, 60 fps) ----
  // The phone hardware-encodes its screen and sends access units; this decodes
  // them off the UI thread through WebCodecs. JPEG frames above remain as the
  // fallback for phones that cannot open an encoder.
  const mirrorH264 = window.createH264Mirror ? window.createH264Mirror({
    canvas: screenCanvas,
    shouldDraw: () => !mirrorPopoutOpen,
    onFrame: (info) => {
      lastMirrorFrameAt = Date.now();
      recordMirrorFrame(info.width, info.height, 0);
      if (mirrorStale) setMirrorStale(false);
      if (!mirrorLive) setMirrorLive(true);
      screenPlaceholder.classList.add('hidden');
      const pane = document.getElementById('tab-screen-mirror');
      if (pane && !pane.classList.contains('active')) return;
      const vp = screenCanvas.parentElement;
      if (vp && vp.classList.contains('mirror-viewport') && info.height > 0 &&
          vp.dataset.frameAspect !== `${info.width}x${info.height}`) {
        vp.dataset.frameAspect = `${info.width}x${info.height}`;
        vp.style.setProperty('--ar', (info.width / info.height).toFixed(5));
        fitMirrorViewport(vp, info.width, info.height);
      }
    }
  }) : null;
  if (mirrorH264) {
    window.phonePcApi.on('phone-h264-config', (d) => mirrorH264.configure(d));
    window.phonePcApi.on('phone-h264-frame', (d) => { if (d && d.nal) mirrorH264.push(d.nal); });
    window.phonePcApi.on('session-status-change', (d) => {
      if (!d || d.status !== 'connected') mirrorH264.reset();
    });
  }

  // createImageBitmap decodes off the UI thread, so 60 fps of JPEGs no longer
  // fights the renderer for time (the old Image + Blob-URL pump decoded on the
  // main thread and topped out near 30 fps). Newest-wins: while a decode runs,
  // newer arrivals replace the pending slot instead of queueing, so the pane
  // always shows the freshest frame and latency cannot accumulate.
  async function pumpMirrorFrame() {
    if (mirrorDecodeBusy || !mirrorPendingFrame) return;
    mirrorDecodeBusy = true;
    try {
      while (mirrorPendingFrame) {
        const data = mirrorPendingFrame;
        mirrorPendingFrame = null;

        screenPlaceholder.classList.add('hidden');
        // Only touch width/height when dimensions actually change — assigning
        // them forces a full canvas reallocation and kills the frame rate.
        const w = data.width || 360, h = data.height || 740;
        if (screenCanvas.width !== w) screenCanvas.width = w;
        if (screenCanvas.height !== h) screenCanvas.height = h;
        // Match the frame box to the phone's real shape so the picture fills
        // the whole container — the fixed 360:740 box left blank bezels on
        // phones with a different aspect.
        const vp = screenCanvas.parentElement;
        if (vp && vp.classList.contains('mirror-viewport') && h > 0 &&
            vp.dataset.frameAspect !== `${w}x${h}`) {
          vp.dataset.frameAspect = `${w}x${h}`;
          vp.style.setProperty('--ar', (w / h).toFixed(5));
          fitMirrorViewport(vp, w, h);
        }

        let bitmap = null;
        try {
          bitmap = await createImageBitmap(new Blob([data.jpeg], { type: 'image/jpeg' }));
        } catch (_) { continue; }
        try { ctx.drawImage(bitmap, 0, 0, w, h); } catch (_) { /* raced a resize */ }
        if (bitmap.close) bitmap.close();
      }
    } finally {
      mirrorDecodeBusy = false;
    }
  }

  // The picture no longer needs pixel maths: the canvas fills its viewport and
  // `object-fit: contain` keeps the phone's aspect ratio, so rotation is just a
  // new frame size. We only remember the dimensions (for the pop-out window)
  // and expose the orientation as a class for styling.
  let mirrorFrameDims = null;
  function fitMirrorViewport(vp, w, h) {
    if (!vp || !w || !h) return;
    mirrorFrameDims = { w, h };
    vp.classList.toggle('landscape', w > h);
    const stage = vp.closest('.stream-stage');
    if (stage) stage.classList.toggle('is-landscape', w > h);
  }

  // ---- Touch the phone from the PC ----
  let downAt = 0;
  let lastControlToast = 0;
  const norm = (e) => {
    const r = screenCanvas.getBoundingClientRect();
    return {
      x: Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)),
      y: Math.min(1, Math.max(0, (e.clientY - r.top) / r.height))
    };
  };

  screenCanvas.addEventListener('contextmenu', (e) => e.preventDefault());

  screenCanvas.addEventListener('mousedown', (e) => {
    if (e.button === 2) { window.phonePcApi.sendPhoneKeyAction('BACK'); return; } // right-click = Back
    if (e.button !== 0) return;
    isDown = true;
    downAt = Date.now();
    const p = norm(e);
    startX = p.x;
    startY = p.y;
  });

  const finishGesture = (e) => {
    if (!isDown) return;
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
  screenCanvas.addEventListener('mouseup', finishGesture);
  window.addEventListener('mouseup', finishGesture); // released outside the canvas

  // Mouse wheel scrolls the phone
  let wheelLock = 0;
  screenCanvas.addEventListener('wheel', (e) => {
    e.preventDefault();
    const now = Date.now();
    if (now - wheelLock < 350) return;
    wheelLock = now;
    const p = norm(e);
    const dy = Math.sign(e.deltaY) * 0.28;
    const clamp = (v) => Math.min(0.95, Math.max(0.05, v));
    window.phonePcApi.sendPhoneGesture({
      type: 'SWIPE', startX: p.x, startY: clamp(p.y + dy / 2), endX: p.x, endY: clamp(p.y - dy / 2), durationMs: 260
    });
  }, { passive: false });

  window.phonePcApi.on('phone-control-status', (st) => {
    if (st.ok) return;
    const now = Date.now();
    if (now - lastControlToast < 4000) return;
    lastControlToast = now;
    const msg = st.reason === 'ACCESSIBILITY_OFF'
      ? 'Phone: turn ON "DuoLink" in Settings > Accessibility. I sent a notification to your phone.'
      : 'Phone could not perform that touch (screen locked, or a secure screen like a password field?).';
    showToast(msg, 'error', 6000);
  });

  btnCaptureScreenshot.addEventListener('click', () => {
    try {
      const dataUrl = screenCanvas.toDataURL('image/png');
      const a = document.createElement('a');
      a.href = dataUrl;
      a.download = `phone-screen-${Date.now()}.png`;
      a.click();
      showToast('Screenshot saved!', 'success');
    } catch (_) {
      showToast('No active screen frame to capture', 'error');
    }
  });

  btnStartStream.addEventListener('click', () => {
    window.phonePcApi.toggleScreenStream(true);
    const badge = $id('mirror-stream-badge');
    if (badge) badge.textContent = 'Starting…';
    showToast('Requested phone to start screen stream');
  });
  btnStopStream.addEventListener('click', () => {
    window.phonePcApi.toggleScreenStream(false);
    setMirrorLive(false);
    showToast('Screen stream stopped');
  });

  // ---- Type on Phone: the PC keyboard drives the phone's focused field ----
  // Clicking this box asks the phone to pop its keyboard open (into whatever
  // field it has focused); every keystroke then rides the link into that field,
  // so the text appears live in the mirror. The phone keyboard also shows on
  // its own afterwards because the field is focused with the IME up.
  const typeOnPhoneInput = $id('type-on-phone-input');
  if (typeOnPhoneInput) {
    let lastSent = '';
    const send = (op, text) => window.phonePcApi.sendPhoneTypeText(
      text === undefined ? { op } : { op, text }
    ).catch(() => null);

    typeOnPhoneInput.addEventListener('focus', () => {
      lastSent = '';
      send('show').then((r) => {
        if (r && r.ok === false) showToast(r.error || 'The phone has no field to type into', 'error');
        else showToast('Phone keyboard opened — type to fill that field', 'success');
      });
    });
    typeOnPhoneInput.addEventListener('blur', () => { send('hide'); });
    typeOnPhoneInput.addEventListener('input', () => {
      const val = typeOnPhoneInput.value;
      if (val === lastSent) return;
      if (val.length > lastSent.length && val.startsWith(lastSent)) {
        send('append', val.slice(lastSent.length));       // plain typing
      } else if (val.length < lastSent.length && lastSent.startsWith(val)) {
        send('delete', String(lastSent.length - val.length)); // backspace / selection cut
      } else {
        send('replace', val);                              // mid-string edit
      }
      lastSent = val;
    });
    typeOnPhoneInput.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter') return;
      e.preventDefault();
      typeOnPhoneInput.value = '';
      lastSent = '';
      showToast('Line sent to the phone', 'success', 1800);
    });
  }

  // A stream that goes quiet (phone mid-rotation, game loading, socket
  // half-dead) must look like a temporary gap, not a dead feature: show the
  // reconnecting overlay instead of wiping the picture and disarming the nav
  // buttons. Only an explicit Stop / disconnect clears the pane for real.
  // Generous gap tolerance: rotation, scene changes and Wi-Fi roams all pause
  // frames briefly without the stream being unhealthy — flashing
  // "Reconnecting…" on every such gap made a working stream look broken.
  setInterval(() => {
    if (!mirrorLive || !lastMirrorFrameAt) return;
    setMirrorStale(Date.now() - lastMirrorFrameAt > 5000);
  }, 1500);

  // ---- Phone power: Lock / Restart / Shut down (PC -> Phone Control pane) ----
  window.phonePcApi.on('phone-power-result', (r) => {
    if (!r) return;
    showToast(r.message || (r.ok ? 'Done' : 'Phone could not perform that action'),
      r.ok ? 'success' : 'error', 7000);
  });

  $id('btn-phone-lock').addEventListener('click', async () => {
    const res = await window.phonePcApi.phonePower('LOCK');
    if (!res.ok) showToast(res.error, 'error');
  });

  $id('btn-phone-restart').addEventListener('click', async () => {
    if (!(await appConfirm('Restart the connected phone now?', { title: 'Restart phone', confirmLabel: 'Restart' }))) return;
    const res = await window.phonePcApi.phonePower('RESTART');
    if (!res.ok) showToast(res.error, 'error');
  });

  $id('btn-phone-shutdown').addEventListener('click', async () => {
    if (!(await appConfirm('Shut down the connected phone now?', { title: 'Shut down phone', confirmLabel: 'Shut down' }))) return;
    const res = await window.phonePcApi.phonePower('SHUTDOWN');
    if (!res.ok) showToast(res.error, 'error');
  });

  const btnFindPhone = document.getElementById('btn-find-phone');
  const findPhoneLabel = document.getElementById('find-phone-label');
  const findPhoneIcon = btnFindPhone ? btnFindPhone.querySelector('use') : null;
  let phoneRinging = false;
  btnFindPhone.addEventListener('click', async () => {
    phoneRinging = !phoneRinging;
    await window.phonePcApi.findPhone(phoneRinging);
    // Only the label and the icon swap: rebuilding the whole button dropped
    // its markup and blew the label out of the fixed-width control.
    if (findPhoneLabel) findPhoneLabel.textContent = phoneRinging ? 'Stop Ringing' : 'Find My Phone';
    if (findPhoneIcon) findPhoneIcon.setAttribute('href', phoneRinging ? '#i-bell-off' : '#i-pin');
    btnFindPhone.classList.toggle('is-ringing', phoneRinging);
    showToast(phoneRinging ? 'Your phone is ringing loudly' : 'Stopped ringing the phone');
  });
  btnPhoneBack.addEventListener('click', () => window.phonePcApi.sendPhoneKeyAction('BACK'));
  btnPhoneHome.addEventListener('click', () => window.phonePcApi.sendPhoneKeyAction('HOME'));
  btnPhoneRecents.addEventListener('click', () => window.phonePcApi.sendPhoneKeyAction('RECENTS'));

  // Trusted Devices Management
  async function refreshTrustedDevices() {
    const list = await window.phonePcApi.getTrustedDevices();
    trustedDevicesList.innerHTML = '';
    if (!list || list.length === 0) {
      trustedDevicesList.innerHTML = '<p class="hint">No trusted devices yet. Pair a phone to add it to your remembered devices.</p>';
      return;
    }

    list.forEach(dev => {
      const row = document.createElement('div');
      row.className = 'toggle-row';
      row.innerHTML = `
        <div class="dev-avatar">${IC('phone')}</div>
        <div class="dev-info">
          <h4>${escapeHtml(dev.deviceName)}${dev.modelCode ? ` <span class="hint" style="font-weight:400">${escapeHtml(dev.modelCode)}</span>` : ''}</h4>
          <p class="hint">Device ID: <code>${dev.deviceId}</code> | Paired: ${new Date(dev.pairedAt).toLocaleDateString()}</p>
        </div>
        <button class="btn btn-danger btn-sm btn-revoke" data-id="${dev.deviceId}">Revoke Trust</button>
      `;
      row.querySelector('.btn-revoke').addEventListener('click', async () => {
        if (await appConfirm(`Revoke trust for ${dev.deviceName}? The phone will be disconnected and require a fresh QR scan.`, { title: 'Revoke trust', confirmLabel: 'Revoke trust' })) {
          await window.phonePcApi.revokeDevice(dev.deviceId);
          refreshTrustedDevices();
          showToast(`Revoked trust for ${dev.deviceName}`);
        }
      });
      trustedDevicesList.appendChild(row);
    });
  }

  btnRefreshTrusted.addEventListener('click', refreshTrustedDevices);

  // ================================================================
  // V2 PANES: Notifications, Messages, Calls, Media, Webcam, Status
  // ================================================================
  const escapeAttr = (v) => escapeHtml(String(v == null ? '' : v)).replace(/"/g, '&quot;');

  /** Clipboard helper shared by the call-log and message "copy" buttons. */
  async function copyText(text, okMessage) {
    if (!text) return;
    try {
      await navigator.clipboard.writeText(String(text));
      showToast(okMessage || 'Copied', 'success');
    } catch (_) {
      showToast('Could not access the clipboard', 'error');
    }
  }

  function timeLabel(ts) {
    const t = Number(ts) || 0;
    if (!t) return '';
    const diff = Date.now() - t;
    if (diff < 60000) return 'just now';
    if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
    if (diff < 86400000) return new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    if (diff < 604800000) return new Date(t).toLocaleDateString([], { month: 'short', day: 'numeric' });
    return new Date(t).toLocaleDateString();
  }

  function fmtBytes(b) {
    const n = Number(b) || 0;
    if (n < 1024) return `${n} B`;
    if (n < 1048576) return `${(n / 1024).toFixed(1)} KB`;
    if (n < 1073741824) return `${(n / 1048576).toFixed(1)} MB`;
    return `${(n / 1073741824).toFixed(2)} GB`;
  }

  // ---------------- 4. Android Notifications mirror ----------------
  let notifState = { paused: false, mutedApps: [], total: 0, items: [] };

  function removeLocalNotif(key) {
    notifState.items = notifState.items.filter((n) => n.key !== key);
    notifState.total = notifState.items.length;
    renderNotifications();
  }

  function renderNotifications() {
    const s = notifState;
    const items = Array.isArray(s.items) ? s.items : [];
    const unreadByApp = {};
    for (const n of items) {
      const app = n.appName || n.packageName || 'App';
      unreadByApp[app] = (unreadByApp[app] || 0) + 1;
    }

    const badge = $id('notif-unread-badge');
    badge.textContent = String(items.length);
    badge.classList.toggle('hidden', items.length === 0);

    // Sidebar badge (spec 6.1 Communicate group)
    const navBadge = $id('nav-badge-notifications');
    if (navBadge) {
      navBadge.textContent = String(items.length);
      navBadge.classList.toggle('hidden', items.length === 0);
    }

    const pauseBtn = $id('btn-notif-pause');
    pauseBtn.innerHTML = s.paused ? IC('bell-off') + ' Resume Sync' : IC('bell-off') + ' Pause Sync';

    // Per-app filter chips: visible apps can be muted, muted apps restored
    const fr = $id('notif-filter-row');
    fr.innerHTML = '';
    Object.keys(unreadByApp).forEach((app) => {
      const entry = items.find((n) => (n.appName || n.packageName || 'App') === app);
      const chip = document.createElement('span');
      chip.className = 'badge';
      chip.textContent = `${app} · ${unreadByApp[app]}`;
      if (entry && entry.packageName) {
        const mute = document.createElement('button');
        mute.className = 'btn btn-icon';
        mute.title = `Mute ${app}`;
        mute.innerHTML = IC('x');
        mute.addEventListener('click', async () => {
          await window.phonePcApi.notifSetAppMuted(entry.packageName, true);
          showToast(`${app} notifications muted`, 'info');
        });
        chip.appendChild(mute);
      }
      fr.appendChild(chip);
    });
    (s.mutedApps || []).forEach((pkg) => {
      const chip = document.createElement('span');
      chip.className = 'badge badge-warning';
      chip.textContent = `${pkg} (muted)`;
      const un = document.createElement('button');
      un.className = 'btn btn-icon';
      un.title = `Unmute ${pkg}`;
      un.innerHTML = IC('check');
      un.addEventListener('click', async () => {
        await window.phonePcApi.notifSetAppMuted(pkg, false);
        showToast('App notifications unmuted', 'info');
      });
      chip.appendChild(un);
      fr.appendChild(chip);
    });

    const list = $id('notif-list');
    if (s.paused) {
      list.innerHTML = '<div class="history-empty">Sync paused — new notifications are not being mirrored. Press Resume to continue.</div>';
      return;
    }
    // Spec 6.2.5: DuoLink's own + ongoing alerts are filtered on the phone;
    // show a small quiet line so the count isn't a mystery.
    const hiddenLine = (s.hiddenCount > 0)
      ? `<div class="notif-hidden-line">${s.hiddenCount} DuoLink alert${s.hiddenCount === 1 ? '' : 's'} hidden</div>`
      : '';
    if (!items.length) {        list.innerHTML = '<div class="history-empty">No notifications mirrored yet. On your phone: Settings → Special app access → Notification access → DuoLink.</div>';
      return;
    }
    list.innerHTML = hiddenLine + items.map((n) => `
      <div class="notif-item" data-key="${escapeAttr(n.key)}">
        <div class="notif-body">
          <div class="notif-app">${escapeHtml(n.appName || 'App')}${n.count > 1 ? ` ×${n.count}` : ''} • ${timeLabel(n.postTime)}${n.isOngoing ? ' • ongoing' : ''}</div>
          <div class="notif-title">${escapeHtml(n.title || '(no title)')}</div>
          ${n.text ? `<div class="notif-text">${escapeHtml(n.text)}</div>` : ''}
          <div class="notif-reply-row hidden">
            <input type="text" placeholder="Reply…" maxlength="4000">
            <button class="btn btn-primary btn-sm" data-act="send">${IC('send')}</button>
          </div>
        </div>
        <div class="notif-actions">
          ${n.canReply ? '<button class="btn btn-secondary btn-sm" data-act="reply">Reply</button>' : ''}
          <button class="btn btn-danger btn-sm" data-act="dismiss" title="Dismiss">${IC('x')}</button>
        </div>
      </div>`).join('');
  }

  $id('notif-list').addEventListener('click', async (e) => {
    const actBtn = e.target.closest('[data-act]');
    if (!actBtn) return;
    const item = actBtn.closest('.notif-item');
    if (!item) return;
    const key = item.dataset.key;
    const act = actBtn.dataset.act;
    if (act === 'dismiss') {
      removeLocalNotif(key);
      const res = await window.phonePcApi.notifDismiss(key);
      if (res && res.ok === false) showToast(`Dismiss failed: ${res.error}`, 'error');
    } else if (act === 'reply') {
      const row = item.querySelector('.notif-reply-row');
      row.classList.toggle('hidden');
      if (!row.classList.contains('hidden')) row.querySelector('input').focus();
    } else if (act === 'send') {
      const row = item.querySelector('.notif-reply-row');
      const input = row.querySelector('input');
      const text = input.value.trim();
      if (!text) return;
      const res = await window.phonePcApi.notifReply(key, text);
      if (res && res.ok) {
        showToast('Reply sent through the phone', 'success');
        input.value = '';
        row.classList.add('hidden');
      } else {
        showToast(`Reply failed: ${(res && res.error) || 'phone not connected'}`, 'error');
      }
    }
  });

  $id('notif-list').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && e.target.matches('.notif-reply-row input')) {
      e.preventDefault();
      e.target.closest('.notif-reply-row').querySelector('[data-act="send"]').click();
    }
  });

  $id('btn-notif-pause').addEventListener('click', async () => {
    const res = await window.phonePcApi.notifSetPaused(!notifState.paused);
    if (res && typeof res.paused === 'boolean') notifState.paused = res.paused;
  });

  $id('btn-notif-clear').addEventListener('click', async () => {
    await window.phonePcApi.notifClearAll();
    showToast('Mirrored notifications cleared', 'info');
  });

  window.phonePcApi.on('notif-list', (snap) => {
    if (!snap) return;
    notifState = {
      paused: !!snap.paused,
      mutedApps: snap.mutedApps || [],
      total: snap.total | 0,
      hiddenCount: snap.hiddenCount | 0,
      items: snap.items || []
    };
    renderNotifications();
  });

  window.phonePcApi.on('notif-posted', (entry) => {
    if (!entry || !entry.key) return;
    const i = notifState.items.findIndex((x) => x.key === entry.key);
    if (i >= 0) notifState.items[i] = entry;
    else notifState.items.unshift(entry);
    notifState.total = notifState.items.length;
    renderNotifications();
    const notifTabOpen = $id('tab-notifications').classList.contains('active');
    if (!notifTabOpen && !notifState.paused) {
      showToast(`${entry.appName}: ${(entry.title || entry.text || '').slice(0, 60)}`, 'info');
    }
  });

  window.phonePcApi.on('notif-removed', (p) => {
    if (!p) return;
    if (p.error) {
      showToast(`Dismiss failed: ${p.error}`, 'error');
      return;
    }
    removeLocalNotif(p.key);
  });

  // ---------------- 5. Messages (SMS) ----------------
  let smsThreads = [];
  let smsCurrentAddr = null;
  let smsByAddr = {};
  let smsSupported = null; // null=unknown, true/false once phone answers
  let smsSending = false;

  function renderSmsThreads() {
    const q = ($id('sms-search').value || '').trim().toLowerCase();
    const list = smsThreads
      .filter((t) => !q || (t.address || '').toLowerCase().includes(q) || (t.snippet || '').toLowerCase().includes(q))
      .sort((a, b) => (b.lastDate || 0) - (a.lastDate || 0));
    const unread = smsThreads.reduce((n, t) => n + (t.unread || 0), 0);
    $id('sms-unread-badge').textContent = `${unread} unread`;
    const navBadge = $id('nav-badge-messages');
    if (navBadge) {
      navBadge.textContent = String(unread);
      navBadge.classList.toggle('hidden', unread === 0);
    }

    const el = $id('sms-thread-list');
    if (!list.length) {
      el.innerHTML = smsSupported === false
        ? '<div class="history-empty">SMS unavailable — grant SMS permission to DuoLink on the phone, then Refresh.</div>'
        : q ? '<div class="history-empty">No conversations match your search.</div>'
        : '<div class="history-empty">No conversations found yet. Press Refresh, and allow SMS access on the phone if prompted.</div>';
      return;
    }
    el.innerHTML = list.map((t) => `
      <div class="history-item" data-addr="${escapeAttr(t.address)}" style="cursor:pointer${t.address === smsCurrentAddr ? ';border-color:var(--brand)' : ''}">
        <span class="history-icon">${IC('chat')}</span>
        <div class="history-details">
          <strong>${escapeHtml(t.address || 'Unknown')}${t.unread ? ` <span class="badge">${t.unread}</span>` : ''}</strong>
          <span class="history-meta">${escapeHtml(t.snippet || '')}${t.snippet ? ' • ' : ''}${timeLabel(t.lastDate)}${t.count ? ` • ${t.count} msgs` : ''}</span>
        </div>
      </div>`).join('');
  }

  function renderSmsMessages() {
    const el = $id('sms-messages');
    const q = ($id('sms-search').value || '').trim().toLowerCase();
    let msgs = (smsCurrentAddr && smsByAddr[smsCurrentAddr]) || [];
    // Same query as the conversation list: while searching, the open thread
    // narrows to matching bubbles so you can find one specific message.
    if (q) msgs = msgs.filter((m) => (m.body || '').toLowerCase().includes(q));
    if (!msgs.length) {
      el.innerHTML = q
        ? '<div class="chat-msg system">No messages in this conversation match your search.</div>'
        : '<div class="chat-msg system">No messages loaded yet — the phone sends history when the conversation opens.</div>';
      return;
    }
    el.innerHTML = msgs.map((m) => {
      const sent = m.type === 'outgoing';
      const label = m.__temp
        ? (m.failed ? 'Failed to send' : 'Sending…')
        : `${sent ? 'To' : 'From'} ${m.address || smsCurrentAddr} • ${m.dateLabel || timeLabel(m.date)}${m.failed ? ' • FAILED' : ''}`;
      const temp = m.__temp || !m.id ? '' : `
          <span class="msg-actions">
            <button class="icon-btn icon-btn-xs" data-msg-copy="${escapeAttr(m.id)}" title="Copy message">${IC('copy')}</button>
            <button class="icon-btn icon-btn-xs icon-btn-danger" data-msg-delete="${escapeAttr(m.id)}" title="Delete from the phone">${IC('trash')}</button>
          </span>`;
      return `
        <div class="chat-msg ${sent ? 'sent' : 'received'}"${m.failed ? ' style="opacity:.6"' : ''}>
          <div class="msg-header">${escapeHtml(label)}${temp}</div>
          <div class="msg-body" dir="auto">${escapeHtml(m.body || '')}</div>
        </div>`;
    }).join('');
    el.scrollTop = el.scrollHeight;
  }

  // Per-bubble actions: copy the text, or ask the phone to delete the row.
  $id('sms-messages').addEventListener('click', async (e) => {
    const del = e.target.closest('[data-msg-delete]');
    if (del) {
      if (!(await appConfirm('Delete this message from the phone?', { title: 'Delete message', confirmLabel: 'Delete' }))) return;
      del.disabled = true;
      const status = $id('sms-send-status');
      status.style.color = '';
      status.textContent = 'Asking the phone to delete that message…';
      const res = await window.phonePcApi.smsRequest({
        op: 'delete', id: del.dataset.msgDelete, address: smsCurrentAddr
      }).catch(() => null);
      if (res && res.ok === false) {
        del.disabled = false;
        status.style.color = '#f87171';
        status.textContent = res.error;
        showToast(res.error, 'error');
      }
      return;
    }
    const copy = e.target.closest('[data-msg-copy]');
    if (copy) {
      const m = ((smsCurrentAddr && smsByAddr[smsCurrentAddr]) || [])
        .find((x) => String(x.id) === copy.dataset.msgCopy);
      copyText(m ? m.body : '', 'Message copied');
    }
  });

  // The phone's answer to a delete request. A success drops the bubble locally
  // and refreshes the thread list so the snippet/count stay truthful.
  window.phonePcApi.on('sms-response', (p) => {
    if (!p || p.op !== 'delete') return;
    const status = $id('sms-send-status');
    if (!p.ok) {
      status.style.color = '#f87171';
      status.textContent = p.error || 'The phone declined that delete';
      showToast(p.error || 'The phone declined that delete', 'error');
      $id('sms-messages').querySelectorAll('[data-msg-delete]').forEach((b) => { b.disabled = false; });
      return;
    }
    const addr = smsCurrentAddr;
    const list = addr && smsByAddr[addr];
    if (list) {
      smsByAddr[addr] = list.filter((m) => String(m.id) !== String(p.id));
      renderSmsMessages();
    }
    status.style.color = '';
    status.textContent = 'Message deleted from the phone';
    showToast('Message deleted', 'success');
    window.phonePcApi.smsRequest({ op: 'threads', limit: 200 }).catch(() => {});
  });

  async function openSmsThread(addr) {
    smsCurrentAddr = addr;
    $id('sms-thread-title').textContent = addr || 'Unknown';
    $id('sms-input').disabled = false;
    $id('btn-sms-send').disabled = false;
    renderSmsThreads();
    renderSmsMessages();
    window.phonePcApi.smsMarkRead(addr);
    const res = await window.phonePcApi.smsRequest({ op: 'messages', address: addr, limit: 300 });
    if (res && res.ok === false) {
      $id('sms-send-status').textContent = `Could not load conversation: ${res.error}`;
    }
  }

  $id('sms-thread-list').addEventListener('click', (e) => {
    const row = e.target.closest('[data-addr]');
    if (row) openSmsThread(row.dataset.addr);
  });

  async function loadSmsThreads() {
    const res = await window.phonePcApi.smsRequest({ op: 'threads', limit: 200 });
    if (res && res.ok === false) {
      $id('sms-thread-list').innerHTML = `<div class="history-empty">${escapeHtml(res.error || 'Phone not connected')}</div>`;
    }
  }

  $id('btn-sms-refresh').addEventListener('click', loadSmsThreads);
  $id('sms-search').addEventListener('input', () => {
    renderSmsThreads();
    renderSmsMessages();
    const clear = $id('sms-search-clear');
    if (clear) clear.classList.toggle('hidden', !$id('sms-search').value);
  });
  const smsClear = $id('sms-search-clear');
  if (smsClear) smsClear.addEventListener('click', () => {
    $id('sms-search').value = '';
    smsClear.classList.add('hidden');
    renderSmsThreads();
    renderSmsMessages();
    $id('sms-search').focus();
  });

  async function sendSms() {
    const addr = smsCurrentAddr;
    const text = ($id('sms-input').value || '').trim();
    if (!addr || !text || smsSending) return;
    smsSending = true;
    $id('btn-sms-send').disabled = true;
    const status = $id('sms-send-status');
    status.textContent = 'Sending…';
    status.style.color = '';

    const temp = {
      id: `temp-${Date.now()}`, address: addr, body: text, date: Date.now(),
      dateLabel: '', type: 'outgoing', __temp: true
    };
    (smsByAddr[addr] = smsByAddr[addr] || []).push(temp);
    renderSmsMessages();

    try {
      const res = await window.phonePcApi.smsSend(addr, text);
      if (!res || res.ok === false) {
        temp.__temp = false;
        temp.failed = true;
        temp.dateLabel = 'Failed';
        renderSmsMessages();
        status.textContent = `Failed: ${(res && res.error) || 'phone not connected'}`;
        status.style.color = '#f87171';
        return;
      }
      $id('sms-input').value = '';
      status.textContent = 'Handed to the phone — awaiting carrier delivery result…';
    } finally {
      smsSending = false;
      $id('btn-sms-send').disabled = false;
    }
  }

  $id('btn-sms-send').addEventListener('click', sendSms);
  $id('sms-input').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); sendSms(); }
  });

  window.phonePcApi.on('sms-response', (p) => {
    if (!p) return;
    if (p.error && !p.op) {
      smsSupported = false;
      $id('sms-thread-list').innerHTML = `<div class="history-empty">${escapeHtml(p.error)}</div>`;
      return;
    }
    smsSupported = true;
    if (p.op === 'threads' && Array.isArray(p.threads)) {
      smsThreads = p.threads;
      renderSmsThreads();
    } else if (p.op === 'messages' && p.address && Array.isArray(p.messages)) {
      const existing = (smsByAddr[p.address] || []).filter((m) => !m.__temp);
      const byId = new Map(existing.map((m) => [m.id, m]));
      for (const m of p.messages) byId.set(m.id, m);
      smsByAddr[p.address] = Array.from(byId.values()).sort((a, b) => (a.date || 0) - (b.date || 0));
      if (p.address === smsCurrentAddr) renderSmsMessages();
    }
  });

  window.phonePcApi.on('sms-sent', (r) => {
    if (!r) return;
    const status = $id('sms-send-status');
    if (r.ok) {
      status.textContent = `Sent to ${r.address}`;
      status.style.color = '#34d399';
      if (smsCurrentAddr === r.address) {
        smsByAddr[r.address] = (smsByAddr[r.address] || []).filter((m) => !m.__temp);
        renderSmsMessages();
        window.phonePcApi.smsRequest({ op: 'messages', address: r.address, limit: 300 });
      }
    } else {
      status.textContent = `Carrier send failed: ${r.error || 'unknown error'}`;
      status.style.color = '#f87171';
      const addr = r.address || smsCurrentAddr;
      const msgs = smsByAddr[addr] || [];
      const temp = [...msgs].reverse().find((m) => m.__temp && !m.failed);
      if (temp) {
        temp.__temp = false;
        temp.failed = true;
        temp.dateLabel = 'Failed';
        if (addr === smsCurrentAddr) renderSmsMessages();
      }
    }
  });

  // ---------------- 6. Phone Calls ----------------
  let callState = { state: 'IDLE', number: '', name: '', direction: '' };

  function renderCallState() {
    const s = callState;
    const badge = $id('call-state-badge');
    badge.textContent = s.state;
    badge.className = 'badge' + (s.state === 'RINGING' ? ' badge-warning' : s.state === 'OFFHOOK' ? ' badge-success' : '');
    $id('call-caller').textContent = s.name || s.number || (s.state === 'IDLE' ? 'No active call' : 'Unknown caller');
    $id('call-caller-sub').textContent = s.state === 'RINGING'
      ? `${s.direction === 'outgoing' ? 'Outgoing' : 'Incoming'} call…`
      : s.state === 'OFFHOOK'
        ? `Call in progress${s.number ? ` with ${s.number}` : ''}.`
        : 'State syncs from the phone in real time (needs the Phone permission).';
    $id('btn-call-answer').disabled = s.state !== 'RINGING';
    const rej = $id('btn-call-reject');
    rej.disabled = s.state === 'IDLE';
    rej.textContent = s.state === 'RINGING' ? 'Reject' : s.state === 'OFFHOOK' ? 'End Call' : 'Reject / End';
  }

  function renderCallRecents(list, error) {
    const el = $id('call-recents-list');
    if (error) {
      el.innerHTML = `<div class="history-empty">${escapeHtml(error)}</div>`;
      return;
    }
    if (!list || !list.length) {
      el.innerHTML = '<div class="history-empty">No recent calls found (grant the Call log permission on the phone, then retry).</div>';
      return;
    }
    el.innerHTML = list.map((c) => {
      const dur = Number(c.durationSec) || 0;
      const num = String(c.number || '');
      return `
      <div class="history-item">
        <span class="history-icon" style="${c.type === 'missed' ? 'color:var(--danger)' : ''}">${IC('phone')}</span>
        <div class="history-details">
          <strong>${escapeHtml(c.name || c.number || 'Unknown')}</strong>
          <span class="history-meta">${escapeHtml(c.type || '')} • ${escapeHtml(c.dateLabel || '')}${dur > 0 ? ` • ${Math.floor(dur / 60)}:${String(dur % 60).padStart(2, '0')}` : ''}</span>
        </div>
        <div class="row-actions">
          ${num ? `<button class="btn btn-secondary btn-sm" data-call="${escapeAttr(num)}">Call back</button>` : ''}
          ${num ? `<button class="icon-btn" data-call-copy="${escapeAttr(num)}" title="Copy number">${IC('copy')}</button>` : ''}
          ${c.id ? `<button class="icon-btn icon-btn-danger" data-call-delete="${escapeAttr(c.id)}" title="Delete from the phone's call log">${IC('trash')}</button>` : ''}
        </div>
      </div>`;
    }).join('');
  }

  // Call back / dial out (spec 6.2.4), plus the per-row copy & delete actions.
  $id('call-recents-list').addEventListener('click', async (e) => {
    const del = e.target.closest('[data-call-delete]');
    if (del) {
      if (!(await appConfirm('Delete this entry from the phone\'s call log?', { title: 'Delete call log entry', confirmLabel: 'Delete' }))) return;
      del.disabled = true;
      const res = await window.phonePcApi.callLogDelete(del.dataset.callDelete).catch(() => null);
      if (res && res.ok === false) {
        del.disabled = false;
        showToast(res.error || 'Could not delete that entry', 'error');
      }
      return;
    }
    const copy = e.target.closest('[data-call-copy]');
    if (copy) {
      copyText(copy.dataset.callCopy, 'Number copied');
      return;
    }
    const btn = e.target.closest('[data-call]');
    if (!btn) return;
    const res = await window.phonePcApi.callAction('DIAL', btn.dataset.call);
    if (res && res.ok === false) showToast(res.error || 'Could not place the call', 'error');
    else showToast('Dialling from your phone…', 'info');
  });

  // The phone confirms (or refuses) a call-log delete - then the list is
  // re-fetched so the row actually leaves the screen.
  window.phonePcApi.on('call-log-delete-result', (p) => {
    if (!p) return;
    if (p.ok) {
      showToast('Entry deleted from the phone', 'success');
      window.phonePcApi.callRecents().catch(() => {});
    } else {
      showToast(p.error || 'The phone declined that delete', 'error');
      $id('call-recents-list').querySelectorAll('[data-call-delete]').forEach((b) => { b.disabled = false; });
    }
  });

  const callDialInput = $id('call-dial-input');
  async function dialFromPc() {
    const number = (callDialInput.value || '').trim();
    if (!number) return;
    const res = await window.phonePcApi.callAction('DIAL', number);
    if (res && res.ok === false) showToast(res.error || 'Could not place the call', 'error');
    else { showToast('Dialling from your phone…', 'info'); callDialInput.value = ''; }
  }
  $id('btn-call-dial').addEventListener('click', dialFromPc);
  callDialInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); dialFromPc(); }
  });

  $id('btn-call-answer').addEventListener('click', async () => {
    const res = await window.phonePcApi.callAction('ANSWER');
    if (res && res.ok === false) showToast(`Answer failed: ${res.error}`, 'error');
  });
  $id('btn-call-reject').addEventListener('click', async () => {
    const action = callState.state === 'OFFHOOK' ? 'END' : 'REJECT';
    const res = await window.phonePcApi.callAction(action);
    if (res && res.ok === false) showToast(`${action} failed: ${res.error}`, 'error');
  });
  $id('btn-call-recents').addEventListener('click', async () => {
    $id('call-recents-list').innerHTML = '<div class="history-empty">Loading call log from the phone…</div>';
    const res = await window.phonePcApi.callRecents();
    if (res && res.ok === false) {
      $id('call-recents-list').innerHTML = `<div class="history-empty">${escapeHtml(res.error || 'Phone not connected')}</div>`;
    }
  });

  window.phonePcApi.on('call-state', (st) => {
    if (!st) return;
    callState = st;
    renderCallState();
    if (st.state === 'RINGING') {
      showToast(`${st.name || st.number || 'Unknown'} is calling`, 'warning', 8000);
    }
  });
  window.phonePcApi.on('call-action-result', (r) => {
    if (!r) return;
    showToast(r.ok ? `${r.action} acknowledged by phone` : `${r.action} failed: ${r.error}`,
      r.ok ? 'success' : 'error');
  });
  window.phonePcApi.on('call-recents', (p) => {
    if (!p) return;
    renderCallRecents(p.recents || [], p.error || null);
  });

  // ---------------- 7. Phone Media browser ----------------
  let mediaItems = [];
  let mediaSupported = null;
  let mediaLoading = false;
  const mediaSelected = new Set();
  const mediaFetchQueue = [];   // { id, name, open } requested from the phone, not yet streaming
  let mediaActiveTransfer = null; // { transferId, id, name, open }
  const mediaLocalPaths = new Map(); // media id -> path on this PC (after first fetch)
  // Preview cache: files pulled ONLY to show in the in-app viewer. They live
  // in a temp cache (never the Received folder, never in history) until the
  // user presses Download, which copies them over for real.
  const mediaPreviewPaths = new Map();

  // ---------------- In-app gallery viewer ----------------
  // Images/videos/audio from the phone open INSIDE DuoLink (duo-media:// URLs
  // served from the Received folder) instead of an external player.
  const mediaViewer = {
    el: $id('media-viewer'), img: $id('media-viewer-img'), video: $id('media-viewer-video'),
    audio: $id('media-viewer-audio'), msg: $id('media-viewer-msg'), name: $id('media-viewer-name'),
    currentId: null, pending: false
  };
  $id('media-viewer-close').addEventListener('click', closeMediaViewer);
  $id('media-viewer-prev').addEventListener('click', () => viewerStep(-1));
  $id('media-viewer-next').addEventListener('click', () => viewerStep(1));
  $id('media-viewer-ext').addEventListener('click', () => {
    const local = (mediaViewer.currentId && mediaLocalPaths.get(mediaViewer.currentId))
      || (mediaViewer.currentId && mediaPreviewPaths.get(mediaViewer.currentId))
      || null;
    if (!local) { showToast('The file has not been fetched yet', 'error'); return; }
    Promise.resolve(window.phonePcApi.openReceivedFile(local))
      .then((r) => { if (r && r.ok === false) showToast(r.error, 'error'); })
      .catch(() => {});
  });
  // Esc closes the viewer; arrow keys page through the gallery.
  document.addEventListener('keydown', (e) => {
    if (!mediaViewer.el || mediaViewer.el.classList.contains('hidden')) return;
    if (e.key === 'Escape') { e.preventDefault(); closeMediaViewer(); }
    else if (e.key === 'ArrowLeft') { e.preventDefault(); viewerStep(-1); }
    else if (e.key === 'ArrowRight') { e.preventDefault(); viewerStep(1); }
  });

  // ---- Transport controls: every button drives the real <video>/<audio> ----
  // Previous/next swap gallery items; play, mute and volume act on the element
  // that is actually loaded, so the bar can never drift out of sync.
  const mediaPlayBtn = $id('media-viewer-play');
  const mediaMuteBtn = $id('media-viewer-mute');
  const mediaVolUpBtn = $id('media-viewer-vol-up');
  const mediaVolDownBtn = $id('media-viewer-vol-down');
  const mediaVolumeInput = $id('media-viewer-volume');
  const mediaTimeEl = $id('media-viewer-time');
  const mediaPlayerBar = $id('media-player-bar');
  const VOLUME_STEP = 0.1;

  function activeMediaEl() {
    if (!mediaViewer) return null;
    for (const el of [mediaViewer.video, mediaViewer.audio]) {
      if (el && el.getAttribute('src') && el.style.display !== 'none') return el;
    }
    return null;
  }

  function setUse(btn, iconId) {
    if (!btn) return;
    const use = btn.querySelector('use');
    if (use) use.setAttribute('href', `#${iconId}`);
  }

  function formatClock(sec) {
    if (!Number.isFinite(sec) || sec < 0) return '0:00';
    const m = Math.floor(sec / 60);
    const s = Math.floor(sec % 60);
    return `${m}:${String(s).padStart(2, '0')}`;
  }

  function paintTransport() {
    const el = activeMediaEl();
    if (mediaPlayerBar) mediaPlayerBar.classList.toggle('hidden', !el);
    if (!el) return;
    const playing = !el.paused && !el.ended;
    setUse(mediaPlayBtn, playing ? 'i-pause' : 'i-play');
    if (mediaPlayBtn) {
      mediaPlayBtn.title = playing ? 'Pause (Space)' : 'Play (Space)';
      mediaPlayBtn.setAttribute('aria-label', playing ? 'Pause' : 'Play');
    }
    const muted = el.muted || el.volume === 0;
    setUse(mediaMuteBtn, muted ? 'i-vol-mute' : 'i-vol-up');
    if (mediaMuteBtn) {
      mediaMuteBtn.title = muted ? 'Unmute (M)' : 'Mute (M)';
      mediaMuteBtn.classList.toggle('is-active', !!el.muted);
    }
    if (mediaVolumeInput && document.activeElement !== mediaVolumeInput) {
      mediaVolumeInput.value = String(Math.round((el.muted ? 0 : el.volume) * 100));
    }
    if (mediaTimeEl) {
      mediaTimeEl.textContent = `${formatClock(el.currentTime)} / ${formatClock(el.duration)}`;
    }
  }

  function togglePlay() {
    const el = activeMediaEl();
    if (!el) return;
    if (el.paused || el.ended) el.play().catch(() => {});
    else el.pause();
  }

  function setVolume(value) {
    const el = activeMediaEl();
    if (!el) return;
    const v = Math.min(1, Math.max(0, value));
    el.volume = v;
    // Touching the volume slider is an explicit "make sound" action.
    if (v > 0) el.muted = false;
    if (v === 0) el.muted = true;
    paintTransport();
  }

  function toggleMute() {
    const el = activeMediaEl();
    if (!el) return;
    if (el.muted || el.volume === 0) {
      el.muted = false;
      if (el.volume === 0) el.volume = 0.6;   // restore something audible
    } else {
      el.muted = true;
    }
    paintTransport();
  }

  // Remember the level before muting so unmute restores it exactly.
  [mediaViewer.video, mediaViewer.audio].forEach((el) => {
    if (!el) return;
    let lastAudible = 1;
    el.addEventListener('volumechange', () => {
      if (!el.muted && el.volume > 0) lastAudible = el.volume;
    });
    el.addEventListener('play', paintTransport);
    el.addEventListener('pause', paintTransport);
    el.addEventListener('ended', paintTransport);
    el.addEventListener('loadedmetadata', paintTransport);
    el.addEventListener('timeupdate', paintTransport);
    // Clicking the picture toggles playback, like a desktop media player.
    el.addEventListener('click', togglePlay);
    // Restore on unmute if the element was muted from zero.
    el.addEventListener('volumechange', () => {
      if (!el.muted && el.volume === 0) el.volume = lastAudible;
    });
  });

  if (mediaPlayBtn) mediaPlayBtn.addEventListener('click', togglePlay);
  if (mediaMuteBtn) mediaMuteBtn.addEventListener('click', toggleMute);
  if (mediaVolUpBtn) mediaVolUpBtn.addEventListener('click', () => setVolume((activeMediaEl() || {}).volume + VOLUME_STEP));
  if (mediaVolDownBtn) mediaVolDownBtn.addEventListener('click', () => setVolume((activeMediaEl() || {}).volume - VOLUME_STEP));
  if (mediaVolumeInput) {
    mediaVolumeInput.addEventListener('input', () => setVolume(Number(mediaVolumeInput.value) / 100));
    // Keyboard arrows should move the slider, not the app.
    mediaVolumeInput.addEventListener('keydown', (e) => e.stopPropagation());
  }

  document.addEventListener('keydown', (e) => {
    if (!mediaViewer.el || mediaViewer.el.classList.contains('hidden')) return;
    const tag = (e.target && e.target.tagName) || '';
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
    if (e.key === ' ' || e.code === 'Space') { e.preventDefault(); togglePlay(); }
    else if (e.key === 'm' || e.key === 'M') { e.preventDefault(); toggleMute(); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setVolume((activeMediaEl() || {}).volume + VOLUME_STEP); }
    else if (e.key === 'ArrowDown') { e.preventDefault(); setVolume((activeMediaEl() || {}).volume - VOLUME_STEP); }
  });

  function mediaMimeOf(item) {
    if (item && item.mime) return item.mime.toLowerCase();
    const ext = ((item && item.name) || '').split('.').pop().toLowerCase();
    const byExt = {
      jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', gif: 'image/gif', webp: 'image/webp',
      mp4: 'video/mp4', webm: 'video/webm', mkv: 'video/x-matroska', mov: 'video/quicktime',
      mp3: 'audio/mpeg', m4a: 'audio/mp4', wav: 'audio/wav', ogg: 'audio/ogg'
    };
    return byExt[ext] || '';
  }

  function mediaViewable(item) {
    const mime = mediaMimeOf(item);
    return mime.startsWith('image/') || mime.startsWith('video/') || mime.startsWith('audio/');
  }

  function viewerMessage(text) {
    const box = mediaViewer.msg;
    if (!box) return;
    if (!text) { box.classList.add('hidden'); box.textContent = ''; return; }
    box.textContent = text;
    box.classList.remove('hidden');
  }

  function viewerRender(item, localPath) {
    const mime = mediaMimeOf(item);
    mediaViewer.currentId = item.id;
    mediaViewer.pending = false;
    mediaViewer.name.textContent = item.name || 'Preview';
    [mediaViewer.img, mediaViewer.video, mediaViewer.audio].forEach((el) => {
      el.style.display = 'none';
      el.removeAttribute('src');
    });
    mediaViewer.video.pause && mediaViewer.video.pause();
    mediaViewer.audio.pause && mediaViewer.audio.pause();
    viewerMessage('');
    Promise.resolve(window.phonePcApi.mediaFileUrl(localPath)).then((r) => {
      if (!r || !r.ok) {
        viewerMessage(r && r.error ? r.error : 'Could not open this file');
        return;
      }
      if (mime.startsWith('image/')) {
        mediaViewer.img.src = r.url;
        mediaViewer.img.style.display = 'block';
      } else if (mime.startsWith('video/')) {
        mediaViewer.video.src = r.url;
        mediaViewer.video.style.display = 'block';
      } else {
        mediaViewer.audio.src = r.url;
        mediaViewer.audio.style.display = 'block';
      }
      setTimeout(paintTransport, 0);
    }).catch(() => viewerMessage('Could not open this file'));
  }

  /** Open one media item in the in-app viewer, pulling it from the phone first
      if this PC has not received it yet. */
  function openMediaViewer(item) {
    if (!item || !mediaViewer.el) return;
    mediaViewer.el.classList.remove('hidden');
    const local = mediaLocalPaths.get(item.id);
    if (local) { viewerRender(item, local); return; }
    // Already pulled once as a preview: reuse the cached copy, no new transfer.
    const preview = mediaPreviewPaths.get(item.id);
    if (preview) { viewerRender(item, preview); return; }
    mediaViewer.currentId = item.id;
    mediaViewer.pending = true;
    mediaViewer.name.textContent = item.name || 'Preview';
    [mediaViewer.img, mediaViewer.video, mediaViewer.audio].forEach((el) => { el.style.display = 'none'; });
    viewerMessage('Fetching a preview from the phone… (nothing is saved yet)');
    fetchIds([item.id], true, true);
  }

  function closeMediaViewer() {
    if (!mediaViewer.el) return;
    mediaViewer.el.classList.add('hidden');
    mediaViewer.video.pause && mediaViewer.video.pause();
    mediaViewer.audio.pause && mediaViewer.audio.pause();
    mediaViewer.currentId = null;
    mediaViewer.pending = false;
    if (mediaPlayerBar) mediaPlayerBar.classList.add('hidden');
  }

  function viewerStep(dir) {
    const list = mediaItems.filter(mediaViewable);
    if (!list.length) return;
    let i = list.findIndex((m) => m.id === mediaViewer.currentId);
    if (i < 0) i = dir > 0 ? -1 : 0;
    const next = list[(i + dir + list.length) % list.length];
    if (next) openMediaViewer(next);
  }

  /** Open a set of ids: viewable types go to the in-app viewer, everything
      else keeps opening in the system's default app. */
  function openMediaItems(ids) {
    const clean = (ids || []).filter(Boolean);
    if (!clean.length) return;
    const viewable = clean.filter((id) => mediaViewable(mediaItems.find((x) => x.id === id)));
    const rest = clean.filter((id) => !viewable.includes(id));
    if (viewable.length) openMediaViewer(mediaItems.find((x) => x.id === viewable[0]));
    if (rest.length) fetchIds(rest, true);
  }

  function updateMediaButtons() {
    const n = mediaSelected.size;
    $id('btn-media-open').disabled = n === 0;
    $id('btn-media-download').disabled = n === 0;
    $id('btn-media-delete').disabled = n === 0;
    $id('btn-media-share').disabled = n === 0;
    const total = mediaItems.length;
    $id('media-count').textContent = total
      ? `${total} items${n ? ` · ${n} selected` : ''}`
      : 'No items loaded';
  }

  function applyThumb(id, src) {
    const grid = $id('media-grid');
    let img = null;
    try { img = grid.querySelector(`img[data-thumb-id="${CSS.escape(id)}"]`); } catch (_) { img = null; }
    if (!img) return;
    img.src = src;
    img.style.display = 'block';
    const ph = img.nextElementSibling;
    if (ph && ph.classList.contains('media-thumb-placeholder')) ph.remove();
  }

  function requestThumbs(items) {
    const targets = items
      .filter((m) => (m.mime || '').startsWith('image/') || (m.mime || '').startsWith('video/'))
      .slice(0, 120);
    targets.forEach((m, i) => {
      // Stagger so the phone's thumbnail queue isn't flooded on open
      setTimeout(() => {
        Promise.resolve(window.phonePcApi.mediaThumb(m.id))
          .then((r) => { if (r && r.dataUrl) applyThumb(m.id, r.dataUrl); })
          .catch(() => {});
      }, i * 30);
    });
  }

  function renderMedia() {
    const grid = $id('media-grid');
    if (!mediaItems.length) {
      grid.innerHTML = mediaSupported === false
        ? '<div class="history-empty">Media access denied on the phone — grant photos/videos permission to DuoLink, then Load again.</div>'
        : '<div class="history-empty">Nothing found. Pick a category and press Load.</div>';
      updateMediaButtons();
      return;
    }
    grid.innerHTML = mediaItems.map((m) => {
      const mime = m.mime || '';
      const phIcon = mime.startsWith('image/') ? 'camera' : mime.startsWith('video/') ? 'play'
        : mime.startsWith('audio/') ? 'vol-up' : 'folder';
      const thumbable = mime.startsWith('image/') || mime.startsWith('video/');
      const sel = mediaSelected.has(m.id);
      return `
      <div class="media-tile${sel ? ' selected' : ''}" data-id="${escapeAttr(m.id)}">
        <input type="checkbox" class="media-check"${sel ? ' checked' : ''}>
        ${thumbable
          ? `<img data-thumb-id="${escapeAttr(m.id)}" alt="" style="display:none"><div class="media-thumb-placeholder">${IC(phIcon)}</div>`
          : `<div class="media-thumb-placeholder">${IC(phIcon)}</div>`}
        <div class="tile-actions">
          <button class="icon-btn" data-media-action="download" title="Save to this PC">${IC('download')}</button>
          <button class="icon-btn" data-media-action="open" title="Open in DuoLink (double-click a tile too)">${IC('external')}</button>
          <button class="icon-btn icon-btn-danger" data-media-action="delete" title="Delete from the phone">${IC('trash')}</button>
        </div>
        <div class="media-meta">
          <div class="media-name">${escapeHtml(m.name || 'file')}</div>
          <div class="media-sub">${fmtBytes(m.size)}${m.date ? ` • ${timeLabel(m.date)}` : ''}</div>
        </div>
      </div>`;
    }).join('');
    updateMediaButtons();
    requestThumbs(mediaItems);
  }

  async function loadMedia() {
    if (mediaLoading) return;
    mediaLoading = true;
    mediaSelected.clear();
    $id('media-count').textContent = 'Loading from the phone…';
    try {
      const res = await window.phonePcApi.mediaRequest({
        kind: $id('media-kind').value,
        search: ($id('media-search').value || '').trim(),
        sort: $id('media-sort').value,
        limit: 300
      });
      if (res && res.ok === false) {
        $id('media-count').textContent = res.error || 'Phone not connected';
        return;
      }
      // Result arrives async via the media-list event
    } finally {
      mediaLoading = false;
    }
  }

  $id('btn-media-load').addEventListener('click', loadMedia);
  $id('media-search').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); loadMedia(); }
  });
  $id('media-kind').addEventListener('change', loadMedia);
  $id('media-sort').addEventListener('change', loadMedia);

  $id('media-grid').addEventListener('click', (e) => {
    // Per-tile action buttons run before the select/deselect toggle so pressing
    // Delete doesn't also flip the tile's checkbox.
    const act = e.target.closest('[data-media-action]');
    if (act) {
      e.preventDefault();
      e.stopPropagation();
      const tile = act.closest('.media-tile');
      if (tile) runMediaAction(act.dataset.mediaAction, [tile.dataset.id], tile);
      return;
    }
    const tile = e.target.closest('.media-tile');
    if (!tile) return;
    // Double-click = open the file in the in-app viewer (single click still
    // ticks/unticks the tile for bulk actions).
    if (e.detail === 2) {
      const item = mediaItems.find((x) => x.id === tile.dataset.id);
      if (item && mediaViewable(item)) { openMediaViewer(item); return; }
    }
    const id = tile.dataset.id;
    if (mediaSelected.has(id)) mediaSelected.delete(id);
    else mediaSelected.add(id);
    tile.classList.toggle('selected', mediaSelected.has(id));
    const cb = tile.querySelector('.media-check');
    if (cb) cb.checked = mediaSelected.has(id);
    updateMediaButtons();
  });

  /**
   * One entry point for every gallery action so the delete confirmation, the
   * "waiting for the phone" status and the tile cleanup live in one place.
   * `op` is download | share | delete; `ids` is always an explicit list so the
   * single-tile buttons and the toolbar bulk buttons share this path.
   */
  async function runMediaAction(op, ids, tile) {
    const clean = (ids || []).filter(Boolean);
    if (!clean.length) return;
    const names = clean.map((id) => {
      const it = mediaItems.find((x) => x.id === id);
      return it && it.name ? it.name : id;
    });

    if (op === 'download') {
      // A file already pulled as a preview is copied out of the cache instead
      // of being transferred from the phone a second time.
      const cached = clean.filter((id) => mediaPreviewPaths.has(id));
      const fresh = clean.filter((id) => !mediaPreviewPaths.has(id));
      if (cached.length) await savePreviewToReceived(cached);
      if (fresh.length) await fetchIds(fresh, false);
      return;
    }

    if (op === 'open') {
      // Open in DuoLink's own viewer (images/videos/audio) or the default app.
      openMediaItems(clean);
      return;
    }

    if (op === 'delete') {
      const what = clean.length === 1 ? `"${names[0]}"` : `${clean.length} item(s)`;
      if (!(await appConfirm(`Delete ${what} from the phone?

Silent when Android allows it; otherwise a system confirmation appears on the phone (tap Allow there).`, { title: 'Delete from phone', confirmLabel: 'Delete' }))) return;
      const status = $id('media-transfer-status');
      status.style.color = '';
      status.textContent = `Asking the phone to delete ${clean.length} item(s)…`;
      const res = await window.phonePcApi.mediaDelete(clean).catch(() => null);
      if (res && res.ok === false) {
        status.textContent = res.error;
        status.style.color = '#f87171';
        showToast(res.error, 'error');
      }
      return;
    }

    if (op === 'share') {
      // The share sheet lives on the PC now: ask the phone which apps can
      // receive the selection and draw the picker here. The phone launches the
      // chosen app directly — no dialog ever appears on the handset.
      openSharePicker(clean);
      return;
    }
  }

  // ---------------- PC-side share picker ----------------
  let sharePickerIds = [];
  let sharePickerTimer = 0;
  let sharePendingLabel = '';   // app the phone is sharing to right now

  function sharePickerStatus(text, isError) {
    const el = $id('share-picker-status');
    el.textContent = text || '';
    el.classList.toggle('is-error', !!isError);
  }

  function closeSharePicker() {
    clearTimeout(sharePickerTimer);
    $id('share-picker').classList.add('hidden');
    sharePickerIds = [];
  }

  function openSharePicker(ids) {
    sharePickerIds = (ids || []).filter(Boolean);
    if (!sharePickerIds.length) return;
    const grid = $id('share-picker-grid');
    grid.innerHTML = '<div class="history-empty">Asking the phone for apps…</div>';
    sharePickerStatus('');
    $id('share-picker').classList.remove('hidden');
    const item = mediaItems.find((x) => x.id === sharePickerIds[0]);
    Promise.resolve(window.phonePcApi.mediaShareTargets({
      ids: sharePickerIds,
      mime: item && item.mime ? item.mime : ''
    })).then((r) => {
      if (r && r.ok === false) sharePickerStatus(r.error || 'Phone not connected', true);
    }).catch(() => sharePickerStatus('Could not reach the phone', true));
    // If the phone never answers, say so instead of spinning forever.
    clearTimeout(sharePickerTimer);
    sharePickerTimer = setTimeout(() => {
      if ($id('share-picker-grid').querySelector('.history-empty')) {
        sharePickerStatus('The phone did not answer — check the connection.', true);
      }
    }, 8000);
  }

  function renderShareTargets(p) {
    if ($id('share-picker').classList.contains('hidden')) return;
    clearTimeout(sharePickerTimer);
    const grid = $id('share-picker-grid');
    if (p && p.error) {
      grid.innerHTML = `<div class="history-empty">${escapeHtml(p.error)}</div>`;
      sharePickerStatus('');
      return;
    }
    const targets = (p && Array.isArray(p.targets)) ? p.targets : [];
    if (!targets.length) {
      grid.innerHTML = '<div class="history-empty">No app on the phone can receive this file type.</div>';
      return;
    }
    grid.innerHTML = targets.map((t) => `
      <button class="share-app" data-pkg="${escapeAttr(t.pkg || '')}" title="${escapeAttr(t.pkg || '')}">
        ${t.icon
          ? `<img src="data:image/png;base64,${t.icon}" alt="">`
          : `<span class="share-app-fallback">${escapeHtml((t.label || '?').trim().charAt(0).toUpperCase())}</span>`}
        <span>${escapeHtml(t.label || t.pkg || 'App')}</span>
      </button>`).join('');
    sharePickerStatus(`${targets.length} app(s) available on the phone`);
  }

  $id('share-picker-grid').addEventListener('click', async (e) => {
    const btn = e.target.closest('.share-app');
    if (!btn || !sharePickerIds.length) return;
    const pkg = btn.dataset.pkg;
    const label = (btn.querySelector('span:last-child') || {}).textContent || pkg;
    const ids = sharePickerIds.slice();
    sharePendingLabel = label;
    sharePickerStatus(`Sharing via ${label} on the phone…`);
    btn.disabled = true;
    try {
      const r = await window.phonePcApi.mediaShare({ ids, target: pkg });
      if (r && r.ok === false) {
        sharePickerStatus(r.error || 'The phone refused', true);
        btn.disabled = false;
        return;
      }
      // Delivered — the phone's MEDIA_RESULT (share) has the real verdict.
      // Keep the dialog open until it lands so a failure is never shown as a
      // success.
      const grid = $id('share-picker-grid');
      grid.querySelectorAll('.share-app').forEach((b) => { b.disabled = true; });
    } catch (_) {
      sharePickerStatus('Could not reach the phone', true);
      btn.disabled = false;
    }
  });
  $id('share-picker-close').addEventListener('click', closeSharePicker);
  $id('share-picker').addEventListener('click', (e) => {
    if (e.target === e.currentTarget) closeSharePicker();
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !$id('share-picker').classList.contains('hidden')) {
      e.preventDefault();
      closeSharePicker();
    }
  });
  window.phonePcApi.on('media-share-targets', renderShareTargets);

  // The phone's verdict on a delete/share (MEDIA_RESULT). Success refreshes the
  // grid so the row disappears instead of lingering until the next Load.
  window.phonePcApi.on('media-result', (p) => {
    if (!p) return;
    const status = $id('media-transfer-status');
    if (!p.ok) {
      const err = p.error || 'The phone declined that action';
      status.style.color = '#f87171';
      status.textContent = err;
      showToast(err, 'error');
      // A failed share must surface in the PC-side picker, not only in a toast.
      if (p.op === 'share') {
        sharePickerStatus(err, true);
        sharePendingLabel = '';
        $id('share-picker-grid').querySelectorAll('.share-app').forEach((b) => { b.disabled = false; });
      }
      return;
    }
    status.style.color = '';
    if (p.op === 'delete') {
      const gone = new Set((p.ids || []).filter(Boolean));
      if (gone.size) {
        mediaItems = mediaItems.filter((it) => !gone.has(it.id));
        gone.forEach((id) => mediaSelected.delete(id));
        renderMedia();
      }
      status.textContent = `Deleted ${gone.size || ''} item(s) from the phone`.replace(/\s+/g, ' ');
      showToast('Deleted from the phone', 'success');
      if (!mediaItems.length) loadMedia();
    } else if (p.op === 'share') {
      // Real verdict from the phone after the PC-side picker chose an app.
      if (!sharePickerIds.length && !sharePendingLabel) { status.textContent = ''; return; }
      if (p.ok) {
        const label = sharePendingLabel || 'the phone';
        closeSharePicker();
        sharePendingLabel = '';
        status.style.color = '';
        status.textContent = `Shared via ${label}`;
        showToast(`Shared via ${label}`, 'success');
      } else {
        const err = p.error || 'The phone could not share that item';
        sharePickerStatus(err, true);
        sharePendingLabel = '';
        $id('share-picker-grid').querySelectorAll('.share-app').forEach((b) => { b.disabled = false; });
        status.style.color = '#f87171';
        status.textContent = err;
        showToast(err, 'error');
      }
    }
  });

  $id('btn-media-delete').addEventListener('click', () =>
    runMediaAction('delete', Array.from(mediaSelected)));
  $id('btn-media-share').addEventListener('click', () =>
    runMediaAction('share', Array.from(mediaSelected)));

  async function fetchSelected(open) {
    const ids = Array.from(mediaSelected);
    if (!ids.length) return;
    if (open) { openMediaItems(ids); return; }
    await fetchIds(ids, false);
  }

  async function fetchIds(ids, open, preview = false) {
    if (!ids.length) return;
    const status = $id('media-transfer-status');
    status.textContent = preview
      ? `Fetching a preview of ${ids.length} file(s) (not saved)…`
      : `Requesting ${ids.length} file(s) from the phone…`;
    for (const id of ids) {
      const item = mediaItems.find((x) => x.id === id);
      mediaFetchQueue.push({ id, name: item ? item.name : '', open, preview });
      const res = await window.phonePcApi.mediaGet(id, {
        preview,
        name: item && item.name ? item.name : ''
      });
      if (res && res.ok === false) {
        mediaFetchQueue.pop();
        status.textContent = `Request failed: ${res.error}`;
        showToast(`Could not fetch ${item ? item.name : id}: ${res.error}`, 'error');
      }
    }
  }

  $id('btn-media-download').addEventListener('click', () => fetchSelected(false));
  $id('btn-media-open').addEventListener('click', () => fetchSelected(true));

  /** Promote previewed file(s) from the temp cache into the Received folder. */
  async function savePreviewToReceived(ids) {
    const status = $id('media-transfer-status');
    for (const id of ids) {
      const src = mediaPreviewPaths.get(id);
      const item = mediaItems.find((x) => x.id === id);
      const label = item && item.name ? item.name : 'file';
      if (!src) continue;
      const res = await window.phonePcApi.savePreviewFile(src).catch(() => null);
      if (res && res.ok) {
        mediaLocalPaths.set(id, res.path);
        mediaPreviewPaths.delete(id);
        status.textContent = `Saved ${label} to the Received folder`;
        showToast(`${label} downloaded`, 'success');
      } else {
        status.textContent = (res && res.error) || `Could not save ${label}`;
        showToast((res && res.error) || `Could not save ${label}`, 'error');
      }
    }
  }

  // Media downloads ride the existing FILE_META/FILE_CHUNK pipeline — correlate
  // them with the pane's own queue so other transfers don't disturb this UI.
  window.phonePcApi.on('file-transfer-start', (data) => {
    const idx = mediaFetchQueue.findIndex((f) => f.name && f.name === data.filename);
    if (idx < 0) return;
    const fetch = mediaFetchQueue.splice(idx, 1)[0];
    mediaActiveTransfer = { ...fetch, transferId: data.transferId };
    $id('media-transfer-status').textContent = `Receiving ${fetch.name}… 0%`;
  });
  window.phonePcApi.on('file-transfer-progress', (data) => {
    if (mediaActiveTransfer && data.transferId === mediaActiveTransfer.transferId) {
      $id('media-transfer-status').textContent = `Receiving ${mediaActiveTransfer.name}… ${data.progress}%`;
    }
  });
  window.phonePcApi.on('file-transfer-complete', (data) => {
    if (!mediaActiveTransfer || data.transferId !== mediaActiveTransfer.transferId) return;
    const { name, open, id, preview } = mediaActiveTransfer;
    mediaActiveTransfer = null;

    // Preview transfer: it landed in the temp cache only — the Received
    // folder and the transfer history are untouched until Download is pressed.
    if (preview) {
      if (data.path && id) mediaPreviewPaths.set(id, data.path);
      $id('media-transfer-status').textContent =
        `Preview ready: ${name} — not saved. Press Download to keep it.`;
      const pItem = id ? mediaItems.find((x) => x.id === id) : null;
      if (open && pItem && mediaViewable(pItem) && data.path &&
          mediaViewer.el && !mediaViewer.el.classList.contains('hidden')) {
        viewerRender(pItem, data.path);
      }
      return;
    }

    $id('media-transfer-status').textContent = `Saved ${name} to the Received folder`;
    showToast(`${name} downloaded`, 'success');
    if (data.path && id) mediaLocalPaths.set(id, data.path);
    if (open && data.path) {
      const item = mediaItems.find((x) => x.id === id);
      if (item && mediaViewable(item) && mediaViewer.el &&
          !mediaViewer.el.classList.contains('hidden')) {
        // The gallery viewer is waiting on this file: show it in-app.
        viewerRender(item, data.path);
        return;
      }
      if (item && mediaViewable(item)) {
        // Open/Download pressed without the viewer: still show it in-app.
        openMediaViewer(item);
        return;
      }
      Promise.resolve(window.phonePcApi.openReceivedFile(data.path))
        .then((r) => { if (r && r.ok === false) showToast(r.error, 'error'); })
        .catch(() => {});
    }
  });
  window.phonePcApi.on('file-transfer-error', (data) => {
    if (mediaActiveTransfer && data.transferId === mediaActiveTransfer.transferId) {
      $id('media-transfer-status').textContent = `Transfer failed: ${data.error}`;
      mediaActiveTransfer = null;
      return;
    }
    // FILE_META-level errors (size limit, unreadable file) carry the filename
    const idx = mediaFetchQueue.findIndex((f) => f.name && data.filename && f.name === data.filename);
    if (idx >= 0) {
      const fetch = mediaFetchQueue.splice(idx, 1)[0];
      $id('media-transfer-status').textContent = `Failed: ${fetch.name} — ${data.error}`;
      showToast(`${fetch.name}: ${data.error}`, 'error');
    }
  });
  window.phonePcApi.on('file-transfer-cancelled', (data) => {
    if (mediaActiveTransfer && data.transferId === mediaActiveTransfer.transferId) {
      $id('media-transfer-status').textContent = `${mediaActiveTransfer.name} cancelled`;
      mediaActiveTransfer = null;
    }
  });

  window.phonePcApi.on('media-list', (res) => {
    if (!res) return;
    mediaItems = Array.isArray(res.items) ? res.items : [];
    mediaSupported = mediaItems.length ? true : (res.error ? false : mediaSupported);
    const err = $id('media-transfer-status');
    err.textContent = res.error ? `Phone reported: ${res.error}` : '';
    err.style.color = res.error ? '#f87171' : '';
    renderMedia();
    if (res.total && res.total > mediaItems.length) {
      $id('media-count').textContent = `${mediaItems.length} of ${res.total} items (page limit reached)`;
    }
  });

  window.phonePcApi.on('media-thumb', (p) => {
    if (p && p.id && p.jpegBase64) applyThumb(p.id, `data:image/jpeg;base64,${p.jpegBase64}`);
  });

  // ---------------- 8. Phone Webcam ----------------
  let webcamActive = false;

  function webcamOpts() {
    const raw = $id('webcam-resolution').value || 'native';
    const torchBox = $id('webcam-torch');
    // "native" => ask the phone for its camera's max supported resolution
    // (matches the phone's own sensor). Otherwise parse WxH as before.
    let width = 0, height = 0;
    if (raw !== 'native') {
      const [w, h] = raw.split('x').map((n) => parseInt(n, 10));
      width = w || 0; height = h || 0;
    }
    return {
      camera: $id('webcam-camera').value,
      width,
      height,
      // 30 is the sweet spot for a phone encoder over Wi-Fi: 60 fps halves the
      // exposure time per frame and doubles the bytes, which is what made the
      // preview feel like it trailed the room.
      fps: parseInt($id('webcam-fps').value, 10) || 30,
      quality: parseInt($id('webcam-quality').value, 10) || 85,
      torch: !!(torchBox && torchBox.checked)
    };
  }

  // Torch flips on the camera that is already streaming. Restarting the
  // session was the reason "turn the light on" meant starting the camera again.
  {
    const torchInput = $id('webcam-torch');
    if (torchInput && window.phonePcApi.webcamTorch) {
      torchInput.addEventListener('change', () => {
        // The phone owns the decision: it applies the flash to whatever camera
        // session is live and ignores it when no camera is open.
        window.phonePcApi.webcamTorch(!!torchInput.checked);
      });
    }
  }

  // Viewfinder HUD: LIVE chip and the resolution/fps actually requested.
  function updateWebcamHud(active) {
    const frame = $id('webcam-frame');
    const hud = $id('webcam-hud');
    const specs = $id('webcam-hud-specs');
    if (frame) frame.classList.toggle('is-live', !!active);
    if (hud) hud.classList.toggle('hidden', !active);
    if (!active || !specs) return;
    const resSel = $id('webcam-resolution');
    const res = resSel && resSel.value && resSel.value !== 'native'
      ? resSel.value.replace('x', ' × ')
      : 'Native';
    const fpsSel = $id('webcam-fps');
    specs.textContent = res + (fpsSel && fpsSel.value ? ` · ${fpsSel.value} fps` : '');
  }

  // ---------------- Webcam preview transport ----------------
  // The picture is pulled from the app's own /webcam.mjpeg endpoint and left to
  // Chromium's native MJPEG decoder. That keeps every frame off the UI thread
  // and off IPC; the old path (one Buffer per frame over IPC, then Blob + URL +
  // decode + img swap in JS) is what made the preview lag behind the phone.
  let webcamPreviewFallback = false;
  let webcamStreamTimer = null;

  function stopWebcamStreamTimer() {
    if (webcamStreamTimer) clearTimeout(webcamStreamTimer);
    webcamStreamTimer = null;
  }

  function useWebcamFrameFallback(reason) {
    const preview = $id('webcam-preview');
    if (webcamPreviewFallback) return;
    webcamPreviewFallback = true;
    stopWebcamStreamTimer();
    preview.onerror = null;
    preview.dataset.stream = '';
    if (preview.dataset.objUrl) {
      URL.revokeObjectURL(preview.dataset.objUrl);
      delete preview.dataset.objUrl;
    }
    preview.removeAttribute('src');
    preview.style.display = 'none';
    window.phonePcApi.webcamPreviewFallback(true);
    if (reason) console.warn('[webcam] MJPEG preview unavailable, using frame fallback:', reason);
  }

  async function attachWebcamStream() {
    const preview = $id('webcam-preview');
    if (!preview || webcamPreviewFallback) return false;
    let info = null;
    try {
      info = await window.phonePcApi.webcamInfo();
    } catch (_) {
      info = null;
    }
    if (!info || !info.mjpegUrl || !info.token) {
      useWebcamFrameFallback('no local stream endpoint');
      return false;
    }
    stopWebcamStreamTimer();
    preview.dataset.stream = '1';
    preview.onerror = () => useWebcamFrameFallback('stream request failed');
    preview.src = `${info.mjpegUrl}?token=${encodeURIComponent(info.token)}`;
    preview.style.display = 'block';
    $id('webcam-placeholder').classList.add('hidden');
    // A multipart response never fires `load`; if no frame has decoded shortly
    // after connecting, fall back instead of showing a black screen forever.
    webcamStreamTimer = setTimeout(() => {
      webcamStreamTimer = null;
      if (webcamActive && preview.naturalWidth === 0) useWebcamFrameFallback('no frame within 4s');
    }, 4000);
    return true;
  }

  // Pause the picture while another page is open: a detached <img> costs no
  // decode time and no Wi-Fi, and reconnecting is a single request.
  document.querySelectorAll('.nav-tab').forEach((tabBtn) => {
    tabBtn.addEventListener('click', () => {
      setTimeout(() => {
        const onWebcamPage = $id('tab-webcam').classList.contains('active');
        const preview = $id('webcam-preview');
        if (!onWebcamPage && preview.dataset.stream === '1') releaseWebcamFrame();
        else if (onWebcamPage && webcamActive && preview.dataset.stream !== '1') attachWebcamStream();
      }, 0);
    });
  });

  function setWebcamUi(active, camera, error) {
    webcamActive = !!active;
    updateWebcamHud(!!active);
    const badge = $id('webcam-status-badge');
    badge.textContent = active ? `Live · ${camera || 'camera'}` : error ? 'Error' : 'Inactive';
    badge.className = 'badge' + (active ? ' badge-success' : error ? ' badge-danger' : '');
    const preview = $id('webcam-preview');
    const ph = $id('webcam-placeholder');
    if (!active) {
      releaseWebcamFrame();
      ph.classList.remove('hidden');
      if (error) showToast(`Webcam error: ${error}`, 'error');
    } else if (preview.dataset.stream !== '1') {
      attachWebcamStream();
    }
  }

  $id('btn-webcam-start').addEventListener('click', async () => {
    const res = await window.phonePcApi.webcamStart(webcamOpts());
    if (res && res.ok === false) {
      showToast(`Webcam start failed: ${res.error}`, 'error');
    } else {
      $id('webcam-status-badge').textContent = 'Starting…';
      showToast('Camera starting on the phone…', 'info');
    }
  });
  $id('btn-webcam-stop').addEventListener('click', async () => {
    await window.phonePcApi.webcamStop();
    setWebcamUi(false, '', '');
    releaseWebcamFrame();
    showToast('Webcam stream stopped', 'info');
  });
  // Switching camera while live restarts capture with the new lens
  $id('webcam-camera').addEventListener('change', () => {
    if (webcamActive) window.phonePcApi.webcamStart(webcamOpts());
  });

  $id('btn-webcam-copy').addEventListener('click', async () => {
    try {
      const info = await window.phonePcApi.webcamInfo();
      if (!info) return;
      const url = `${info.mjpegUrl}?token=${info.token}`;
      $id('webcam-mjpeg-url').value = url;
      await navigator.clipboard.writeText(url);
      showToast('Stream URL copied (fresh session token included)', 'success');
    } catch (_) {
      showToast('Could not copy the stream URL', 'error');
    }
  });

  window.phonePcApi.on('webcam-status', (st) => {
    if (!st) return;
    setWebcamUi(st.active, st.camera, st.error);
  });

  // Zoom/pan/expand toolbar under the preview was removed by request: the
  // webcam picture simply fills its container (object-fit: cover), and that
  // container is sized exactly like the live-stream viewport.
  //
  // Wheel zoom + drag pan (requested): scroll over the picture to zoom in
  // toward the cursor, drag to look around while zoomed, double-click resets.
  (() => {
    const vp = $id('webcam-viewport');
    const preview = $id('webcam-preview');
    if (!vp || !preview) return;
    let zoom = 1, panX = 0, panY = 0;
    const apply = () => {
      preview.style.transformOrigin = 'center center';
      preview.style.transform = `translate(${panX}px, ${panY}px) scale(${zoom})`;
      vp.style.cursor = zoom > 1 ? 'grab' : '';
    };
    const clampPan = () => {
      const r = vp.getBoundingClientRect();
      const maxX = (r.width * (zoom - 1)) / 2;
      const maxY = (r.height * (zoom - 1)) / 2;
      panX = Math.max(-maxX, Math.min(maxX, panX));
      panY = Math.max(-maxY, Math.min(maxY, panY));
    };
    vp.addEventListener('wheel', (e) => {
      if (!webcamActive) return;
      e.preventDefault();
      const prev = zoom;
      zoom = Math.max(1, Math.min(6, zoom * (e.deltaY < 0 ? 1.15 : 1 / 1.15)));
      if (zoom === 1) {
        panX = 0; panY = 0;
      } else if (zoom !== prev) {
        // Keep the point under the cursor fixed while the picture grows:
        // pan' = (cursor-center)(1 - r) + pan·r,  r = zoom/prev.
        const r = vp.getBoundingClientRect();
        const cx = e.clientX - r.left - r.width / 2;
        const cy = e.clientY - r.top - r.height / 2;
        const ratio = zoom / prev;
        panX = cx * (1 - ratio) + panX * ratio;
        panY = cy * (1 - ratio) + panY * ratio;
        clampPan();
      }
      apply();
    }, { passive: false });
    let dragging = false, sx = 0, sy = 0, ox = 0, oy = 0;
    vp.addEventListener('mousedown', (e) => {
      if (zoom <= 1 || e.button !== 0) return;
      dragging = true; sx = e.clientX; sy = e.clientY; ox = panX; oy = panY;
      vp.style.cursor = 'grabbing';
      e.preventDefault();
    });
    window.addEventListener('mousemove', (e) => {
      if (!dragging) return;
      panX = ox + (e.clientX - sx);
      panY = oy + (e.clientY - sy);
      clampPan();
      apply();
    });
    window.addEventListener('mouseup', () => {
      if (dragging) { dragging = false; vp.style.cursor = 'grab'; }
    });
    vp.addEventListener('dblclick', () => { zoom = 1; panX = 0; panY = 0; apply(); });
  })();

  /** Drop the current frame's object URL so stopped/hidden streams leak nothing. */
  function releaseWebcamFrame() {
    webcamPaintPending = null;
    stopWebcamStreamTimer();
    const preview = $id('webcam-preview');
    preview.onerror = null;
    preview.dataset.stream = '';
    if (webcamPreviewFallback) {
      webcamPreviewFallback = false;
      try { window.phonePcApi.webcamPreviewFallback(false); } catch (_) { /* no bridge */ }
    }
    if (preview.dataset.objUrl) {
      URL.revokeObjectURL(preview.dataset.objUrl);
      delete preview.dataset.objUrl;
    }
    preview.removeAttribute('src');
    preview.style.display = 'none';
  }

  // Decode-before-swap paint pump. At phone fps, assigning img.src faster
  // than the browser can decode cancels every in-flight load, so the preview
  // stayed permanently blank (naturalWidth 0) even though frames were
  // arriving fine. Now at most one frame is in flight: we wait for the
  // <img> to finish decoding before handing it another src, and any frames
  // that pile up meanwhile collapse to the newest one.
  //
  // Two Chromium quirks this has to respect:
  //  1) img.decode() NEVER settles while the element is display:none, so the
  //     image must be shown before the decode is awaited.
  //  2) a decode that never settles must not wedge the pump, so every wait
  //     is capped by a timeout.
  let webcamPaintBusy = false;
  let webcamPaintPending = null;

  function decodeWithTimeout(img, ms) {
    return Promise.race([
      img.decode().then(() => true, () => false),
      new Promise((res) => setTimeout(() => res(false), ms))
    ]);
  }

  async function pumpWebcamFrames() {
    if (webcamPaintBusy) return;
    webcamPaintBusy = true;
    try {
      while (webcamPaintPending && $id('tab-webcam').classList.contains('active')) {
        const jpeg = webcamPaintPending;
        webcamPaintPending = null;
        const preview = $id('webcam-preview');
        const prevUrl = preview.dataset.objUrl || null;
        // Blob URL from the raw JPEG bytes: no base64 decode, no data-URL
        // string churn, and the old object URL is revoked after each
        // successful frame so frames don't pile up in memory.
        const url = URL.createObjectURL(new Blob([jpeg], { type: 'image/jpeg' }));
        // Must be visible before decode() is allowed to settle (quirk 1).
        preview.style.display = 'block';
        $id('webcam-placeholder').classList.add('hidden');
        preview.src = url;
        if (await decodeWithTimeout(preview, 2000)) {
          if (prevUrl) URL.revokeObjectURL(prevUrl);
          preview.dataset.objUrl = url;
          if (!webcamActive) setWebcamUi(true, '', '');
        } else {
          // Undecodable or too-slow frame: drop it and keep the last good one.
          URL.revokeObjectURL(url);
          if (prevUrl) {
            preview.src = prevUrl;
          } else {
            preview.style.display = 'none';
            $id('webcam-placeholder').classList.remove('hidden');
          }
        }
      }
    } finally {
      webcamPaintBusy = false;
    }
  }

  window.phonePcApi.on('webcam-frame', (f) => {
    // Only the fallback transport delivers frames by IPC; the normal preview
    // reads them from the MJPEG endpoint.
    if (!webcamPreviewFallback) return;
    if (!f || !f.jpeg || !f.jpeg.length) return;
    // Paint only while the webcam pane is on screen: decoding a 15 fps stream
    // nobody can see just burned UI-thread time and made the app feel frozen.
    if (!$id('tab-webcam').classList.contains('active')) return;
    webcamPaintPending = f.jpeg;
    pumpWebcamFrames();
  });

  // ---------------- 10. Device status & diagnostics ----------------
  // The offscreen mirror window runs hidden; if it cannot acquire the desktop
  // (permission revoked, display switched out) there is no pane to show it in,
  // so surface it as a toast instead of failing silently.
  window.phonePcApi.on('pc-screen-error', (message) => {
    if (message) showToast(`Screen mirror: ${message}`, 'error');
  });
  function renderBattery(b) {
    if (!b || typeof b.level !== 'number') return;
    const fill = $id('phone-battery-fill');
    fill.style.width = `${b.level}%`;
    fill.style.background = b.level <= 20 ? '#f87171' : b.level <= 50 ? '#f59e0b' : '#34d399';
    $id('phone-battery-text').textContent =
      `${b.level}% • ${b.charging ? 'Charging' : 'On battery'}` +
      (typeof b.temperatureC === 'number' && b.temperatureC ? ` • ${b.temperatureC}°C` : '');
    // Optional hero readout + status chip (added for the reorganized status pane)
    const pct = $id('phone-battery-pct');
    if (pct) pct.textContent = b.level;
    const chip = $id('phone-battery-badge');
    if (chip) chip.textContent = b.charging ? 'Charging' : 'On battery';
    // Settings → Phone health stats
    const healthBattery = $id('health-battery');
    if (healthBattery) {
      healthBattery.textContent = `${b.level}%`;
      const sub = $id('health-battery-sub');
      if (sub) {
        sub.textContent = `${b.charging ? 'Charging' : 'On battery'}` +
          (typeof b.temperatureC === 'number' && b.temperatureC ? ` · ${b.temperatureC}°C` : '');
      }
    }
  }

  window.phonePcApi.on('phone-battery', renderBattery);

  window.phonePcApi.on('phone-volume-state', (v) => {
    if (!v) return;
    $id('phone-volume-badge').textContent = v.muted ? `Muted (${v.level}/${v.max})` : `${v.level}/${v.max}`;
    const vfill = $id('phone-volume-fill');
    if (vfill && v.max > 0) {
      vfill.style.width = `${Math.round((v.level / v.max) * 100)}%`;
      vfill.style.background = v.muted ? '#f87171' : '#38bdf8';
    }
  });

  [['btn-phone-vol-down', 'DOWN'], ['btn-phone-vol-mute', 'MUTE'],
   ['btn-phone-vol-unmute', 'UNMUTE'], ['btn-phone-vol-up', 'UP']].forEach(([id, action]) => {
    $id(id).addEventListener('click', async () => {
      const res = await window.phonePcApi.phoneVolume(action);
      if (res && res.ok === false) showToast(`Volume ${action} failed: ${res.error}`, 'error');
    });
  });

  $id('btn-diag-refresh').addEventListener('click', async () => {
    $id('diag-output').innerHTML = '<div class="history-empty">Querying the phone…</div>';
    const res = await window.phonePcApi.diagRequest();
    if (res && res.ok === false) {
      $id('diag-output').innerHTML = `<div class="history-empty">${escapeHtml(res.error || 'Phone not connected')}</div>`;
    }
  });

  window.phonePcApi.on('diag-response', (p) => {
    const el = $id('diag-output');
    if (!p) {
      el.innerHTML = '<div class="history-empty">No response from phone.</div>';
      return;
    }

    const permLabels = {
      camera: 'Camera', readPhoneState: 'Read phone state', answerCalls: 'Answer calls',
      readCallLog: 'Call log', readSms: 'Read SMS', sendSms: 'Send SMS',
      postNotifications: 'Post notifications', notificationAccess: 'Notification access',
      media: 'Media / storage access'
    };
    const svcLabels = {
      link: 'Link service', accessibility: 'Accessibility (remote input)', webcam: 'Camera streaming',
      notificationListener: 'Notification listener', screenMirror: 'Screen mirroring'
    };
    // Service "off" is often a legitimate choice (start it when needed), so it is
    // an amber warn rather than a red failure — only permissions read as failures.
    // state words differ by group: permissions are granted, services are running
    const stateWord = (kind, words) => kind === 'ok' ? words.ok : kind === 'warn' ? words.warn : words.bad;
    const chip = (label, kind, words) =>
      `<div class="diag-chip ${kind}">` +
        '<span class="diag-dot"></span>' +
        `<span class="diag-label">${escapeHtml(label)}</span>` +
        `<span class="diag-state">${escapeHtml(stateWord(kind, words))}</span>` +
      '</div>';
    const section = (title, entries, words) => {
      if (!entries.length) return '';
      const good = entries.filter(([, k]) => k === 'ok').length;
      const all = entries.length;
      const cls = good === all ? 'good' : good === 0 ? 'bad' : '';
      return '<div class="diag-section">' +
        `<div class="diag-section-head"><h4>${escapeHtml(title)}</h4>` +
        `<span class="section-count ${cls}">${good}/${all}</span></div>` +
        `<div class="diag-grid">${entries.map(([l, k]) => chip(l, k, words)).join('')}</div>` +
      '</div>';
    };
    const entries = (obj, labels, kind) => Object.keys(obj || {}).map((k) => [
      labels[k] || k, kind(obj[k])
    ]);
    const PERM_WORDS = { ok: 'Granted', warn: 'Off', bad: 'Missing' };
    const SVC_WORDS = { ok: 'Running', warn: 'Stopped', bad: 'Missing' };
    const ok = (v) => v ? 'ok' : 'bad';
    const on = (v) => v ? 'ok' : 'warn';

    const permEntries = entries(p.permissions, permLabels, ok);
    const svcEntries = entries(p.services, svcLabels, on);
    const dev = p.device || {};

    const permGood = permEntries.filter(([, k]) => k === 'ok').length;
    const svcGood = svcEntries.filter(([, k]) => k === 'ok').length;

    const fact = (label, value, cls) =>
      `<div class="diag-fact"><span class="fact-label">${escapeHtml(label)}</span>` +
      `<span class="fact-value ${cls || ''}">${escapeHtml(String(value))}</span></div>`;

    const devFacts = Object.keys(dev).length ? [
      fact('Model', dev.model || '?'),
      fact('Android', dev.sdk ? `${dev.sdk} (API ${dev.sdk})` : '?'),
      fact('App version', dev.appVersion || '?'),
      fact('Battery', dev.batteryPct != null ? `${dev.batteryPct}%${dev.charging ? ' (charging)' : ''}` : '?'),
      fact('Media volume', dev.volumeLevel != null ? `${dev.volumeLevel} / ${dev.volumeMax}` : '?'),
      fact('Link state', dev.linkState || '?')
    ].join('') : '';

    const linkFacts = p.link ? [
      fact('Connection', p.link.state || '?', (p.link.state || '').toLowerCase() === 'connected' ? 'good' : ''),
      fact('PC screen stream', p.link.pcScreenActive ? 'active' : 'idle')
    ].join('') : '';

    let html = '';
    if (permEntries.length || svcEntries.length) {
      html += '<div class="diag-summary">' +
        `<div class="summary-stat"><strong>${permGood}/${permEntries.length}</strong><span>permissions</span></div>` +
        `<div class="summary-stat"><strong>${svcGood}/${svcEntries.length}</strong><span>services running</span></div>` +
        '<div class="summary-spacer"></div>' +
        `<div class="summary-time">updated ${new Date().toLocaleTimeString()}</div>` +
      '</div>';
    }
    html += section('Permissions', permEntries, PERM_WORDS);
    html += section('Services', svcEntries, SVC_WORDS);
    if (devFacts) html += '<div class="diag-section"><div class="diag-section-head"><h4>Device</h4></div><div class="diag-facts">' + devFacts + '</div></div>';
    if (linkFacts) html += '<div class="diag-section"><div class="diag-section-head"><h4>Link</h4></div><div class="diag-facts">' + linkFacts + '</div></div>';
    if (permEntries.some(([, k]) => k === 'bad')) {
      html += '<div class="diag-footer">Missing permission? On the phone open DuoLink → Settings → ' +
        'Feature Permissions and tap Grant next to the red item.</div>';
    }
    el.innerHTML = html || '<div class="history-empty">Empty diagnostics payload.</div>';

    // Overview: permissions summary + Fix entry point (spec 6.2.1).
    if (permSummary) {
      const missing = permEntries.filter(([, k]) => k === 'bad').map(([l]) => l);
      permSummary.textContent = missing.length === 0
        ? `All ${permGood} permissions granted`
        : `${permGood}/${permEntries.length} granted — needs: ${missing.join(', ')}`;
      permSummary.classList.toggle('bad', missing.length > 0);
      // Settings → Phone health stats
      const healthPerms = $id('health-perms');
      if (healthPerms) {
        healthPerms.textContent = `${permGood} of ${permEntries.length} ready`;
        healthPerms.classList.toggle('bad', missing.length > 0);
      }
      const healthPermsSub = $id('health-perms-sub');
      if (healthPermsSub) {
        healthPermsSub.textContent = missing.length === 0 ? 'All granted' : `Needs: ${missing.join(', ')}`;
      }
    }
  });

  if (btnPermFix) {
    btnPermFix.addEventListener('click', () => {
      switchTab('tab-settings');
      const run = $id('btn-diag-refresh');
      if (run) run.click();
      const health = document.getElementById('settings-phone-health');
      if (health) health.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
  }

  // ---------------- Boot: hydrate all V2 panes from one snapshot ----------------
  async function hydrateV2() {
    try {
      const snap = await window.phonePcApi.featureSnapshot();
      if (snap) {
        if (snap.notifications && Array.isArray(snap.notifications.items) && snap.notifications.items.length) {
          notifState = {
            paused: !!snap.notifications.paused,
            mutedApps: snap.notifications.mutedApps || [],
            total: snap.notifications.total | 0,
            items: snap.notifications.items
          };
          renderNotifications();
        }
        if (snap.sms && Array.isArray(snap.sms.threads) && snap.sms.threads.length) {
          // Empty snapshot = phone was gone when the renderer booted — leave
          // the disconnected placeholders alone rather than overwriting them.
          smsSupported = snap.sms.supported;
          smsThreads = snap.sms.threads;
          renderSmsThreads();
        }
        if (snap.calls && Array.isArray(snap.calls.recents) && snap.calls.recents.length) {
          callState = snap.calls.state || callState;
          renderCallState();
          renderCallRecents(snap.calls.recents, snap.calls.recentsError || null);
        }
        if (snap.media && Array.isArray(snap.media.items) && snap.media.items.length) {
          mediaItems = snap.media.items;
          renderMedia();
        }
        if (snap.phoneBattery) renderBattery(snap.phoneBattery);
        if (snap.webcamActive) setWebcamUi(true, '', '');
      }
    } catch (err) {
      console.error('V2 hydrate failed', err);
    }
    try {
      const info = await window.phonePcApi.webcamInfo();
      if (info && info.mjpegUrl) {
        $id('webcam-mjpeg-url').value = `${info.mjpegUrl}?token=${info.token}`;
      }
    } catch (_) { /* webcam endpoint unavailable */ }
  }

  // Lazy pane loads: fetch data the first time a pane is opened
  const v2TabLoads = {
    'tab-messages': () => { if (!smsThreads.length) loadSmsThreads(); },
    'tab-media': () => { if (!mediaItems.length) loadMedia(); },
    'tab-webcam': () => {
      Promise.resolve(window.phonePcApi.webcamInfo()).then((info) => {
        if (info && info.mjpegUrl) {
          $id('webcam-mjpeg-url').value = `${info.mjpegUrl}?token=${info.token}`;
          setWebcamUi(info.active, info.camera, '');
        }
      }).catch(() => {});
    }
  };
  tabs.forEach((tab) => {
    tab.addEventListener('click', () => {
      const fn = v2TabLoads[tab.dataset.tab];
      if (fn) fn();
    });
  });

  // Reconnection recovery: re-hydrate every V2 pane when the phone comes back
  window.phonePcApi.on('session-status-change', (data) => {
    if (data && data.status === 'connected') {
      hydrateV2();
      // Refresh the Phone status container on every (re)connect.
      setTimeout(() => { window.phonePcApi.diagRequest().catch(() => {}); }, 600);
    }
  });

  hydrateV2();

  // The backend only fires DIAG_REQ when a phone first connects — if this UI
  // booted after the link was already up, that response was missed. Ask for a
  // fresh snapshot so the Phone status container fills without a manual click.
  setTimeout(() => {
    window.phonePcApi.diagRequest().catch(() => {});
  }, 800);

  // Initial QR load
  refreshQr();
});
