  // ---------------- 10. Phone apps (catalog from the phone) ----------------
  // The phone answers with every launchable app, its official label and a
  // 96px icon (APP_CATALOG). Tapping one asks the phone to launch it and the
  // PC opens its mirror window, so the app runs in a window here.
  let phoneApps = [];
  let adbApps = [];
  let adbSerial = null;
  let adbBusy = false;
  let appsBusy = false;

  function appsIconSrc(app) {
    return app && app.icon ? `data:image/png;base64,${app.icon}` : '';
  }

  function adbSetStatus(text, tone) {
    const el = $id('adb-status');
    const dot = $id('adb-dot');
    if (el) {
      el.textContent = text;
      el.style.color = tone === 'error' ? 'var(--danger)' : tone === 'ok' ? 'var(--success)' : '';
    }
    if (dot) {
      dot.className = 'apps-dot' + (tone === 'ok' ? ' ok' : tone === 'error' ? ' error' : tone === 'busy' ? ' busy' : '');
      dot.title = text;
    }
  }

  function appsSetPhoneCard(name, sub, connected) {
    const card = $id('apps-phone-card');
    if (!card) return;
    $id('apps-phone-name').textContent = name;
    $id('apps-phone-sub').textContent = sub;
    card.classList.toggle('offline', !connected);
  }

  function appsTone(pkg) {
    const str = String(pkg || '');
    let h = 0;
    for (let i = 0; i < str.length; i++) h = (h * 31 + str.charCodeAt(i)) % 9973;
    return 't' + (h % 6);
  }

  function appCard(app) {
    const icon = appsIconSrc(app);
    const glyph = icon
      ? `<img class="app-icon" src="${icon}" alt="">`
      : `<span class="app-glyph ${appsTone(app.pkg)}">${escapeHtml(rfInitial(app.label))}</span>`;
    return `<button class="app-card" data-pkg="${escapeAttr(app.pkg)}" title="${escapeAttr(app.label)} — ${escapeAttr(app.pkg)}">
        ${glyph}
        <span class="app-name">${escapeHtml(app.label)}</span>
      </button>`;
  }

  function renderApps() {
    const wrap = $id('adb-apps');
    if (!wrap) return;
    const list = phoneApps.length ? phoneApps : adbApps;
    const q = ($id('adb-search').value || '').trim().toLowerCase();
    const shown = q
      ? list.filter((a) => String(a.label || '').toLowerCase().includes(q) || String(a.pkg || '').toLowerCase().includes(q))
      : list;
    if ($id('adb-count')) $id('adb-count').textContent = list.length ? `${list.length} apps` : 'Apps';
    if ($id('adb-sub')) {
      $id('adb-sub').textContent = list.length
        ? 'Tap an app to open it in a window on this PC'
        : 'Connect your phone to list its apps';
    }

    if (!shown.length) {
      wrap.innerHTML = `<div class="apps-empty">
        <span class="apps-empty-icon"><svg class="ic" aria-hidden="true"><use href="#i-${q ? 'search' : 'grid'}"/></svg></span>
        <strong>${q ? 'No apps match' : 'No apps yet'}</strong>
        <p>${q
          ? 'Try a different name.'
          : 'Connect your phone from Overview. Every app on it then shows up here, with its own icon.'}</p>
      </div>`;
      return;
    }
    wrap.innerHTML = shown.map(appCard).join('');
  }

  async function appsLoadCatalog() {
    if (appsBusy) return;
    appsBusy = true;
    adbSetStatus('Reading apps from your phone\u2026', 'busy');
    try {
      await window.phonePcApi.appCatalog();
    } catch (_) {
      adbSetStatus('Could not ask the phone for its apps', 'error');
    } finally {
      appsBusy = false;
    }
  }

  async function appsRefresh() {
    const status = await window.phonePcApi.getSessionStatus().catch(() => null);
    const connected = !!(status && status.status === 'connected');
    if (connected) {
      appsSetPhoneCard(status.deviceName || 'Your phone', status.modelCode || 'Connected over Wi-Fi', true);
      return appsLoadCatalog();
    }
    appsSetPhoneCard('No phone connected', 'Connect from Overview — or use Wireless debugging below', false);
    return adbRefreshAll(!!adbSerial);
  }

  // ---- catalog + launch results from the phone ----
  window.phonePcApi.on('app-catalog', (p) => {
    if (!p) return;
    if (p.error && !(p.apps || []).length) {
      adbSetStatus(p.error, 'error');
      return;
    }
    phoneApps = (p.apps || []).slice().sort((a, b) => String(a.label).localeCompare(String(b.label)));
    renderApps();
    adbSetStatus(`Connected \u00b7 ${phoneApps.length} apps from your phone`, 'ok');
  });

  window.phonePcApi.on('app-launch-result', async (p) => {
    if (!p) return;
    if (!p.ok) {
      showToast(p.error || 'The phone could not open that app', 'error');
      return;
    }
    // The app is now on the phone's screen: show it here, in its own window.
    const res = await window.phonePcApi.openScreenWindow().catch(() => null);
    showToast(res && res.ok === false ? (res.error || 'Could not open the window') : 'Opening in a window on this PC', res && res.ok === false ? 'error' : 'success');
  });

  // ---- Wireless debugging fallback (advanced) ----
  async function adbLoadDevices() {
    const res = await window.phonePcApi.adbStatus().catch(() => null);
    const select = $id('adb-serial');
    if (!res) { adbSetStatus('adb is unavailable', 'error'); return null; }
    const devices = (res.devices || []).filter((d) => d.state === 'device');
    select.innerHTML = devices.length
      ? devices.map((d) => `<option value="${escapeAttr(d.serial)}">${escapeHtml(d.model || d.serial)} \u00b7 ${escapeHtml(d.serial)}</option>`).join('')
      : '<option value="">No device connected</option>';
    adbSerial = res.serial || (devices[0] ? devices[0].serial : null);
    if (adbSerial) select.value = adbSerial;
    select.disabled = !devices.length;
    $id('btn-adb-disconnect').disabled = !devices.length;
    $id('btn-adb-refresh').disabled = !devices.length;
    if (adbSerial) {
      adbSetStatus(`adb connected \u00b7 ${adbSerial.split('.').pop() || adbSerial}`, 'ok');
    } else if (!phoneApps.length) {
      const adbOk = res.adb && res.adb !== 'adb';
      adbSetStatus(adbOk ? 'Waiting for your phone' : 'adb not found \u2014 install Android platform-tools', adbOk ? '' : 'error');
    }
    return adbSerial;
  }

  async function adbLoadApps() {
    if (adbBusy || !adbSerial) return;
    adbBusy = true;
    const res = await window.phonePcApi.adbApps(adbSerial).catch(() => null);
    adbBusy = false;
    if (!res || !res.ok) {
      adbSetStatus((res && res.error) || 'Could not read the app list', 'error');
      return;
    }
    adbApps = res.apps || [];
    if (!phoneApps.length) renderApps();
    adbSetStatus(`adb \u00b7 ${adbApps.length} apps`, 'ok');
  }

  async function adbRefreshAll(loadApps) {
    const serial = await adbLoadDevices();
    if (serial && loadApps) adbLoadApps();
    return serial;
  }

  if ($id('btn-adb-connect')) {
    $id('btn-adb-connect').addEventListener('click', async () => {
      const addr = ($id('adb-connect-addr').value || '').trim();
      if (!addr) return adbSetStatus('Enter the IP:port from Wireless debugging', 'error');
      adbSetStatus('Connecting\u2026', 'busy');
      const res = await window.phonePcApi.adbConnect(addr).catch(() => null);
      if (!res || !res.ok) return adbSetStatus((res && res.error) || 'Could not connect', 'error');
      showToast(`Connected to ${addr}`, 'success');
      adbRefreshAll(true);
    });

    $id('btn-adb-pair').addEventListener('click', async () => {
      const addr = ($id('adb-pair-addr').value || '').trim();
      const code = ($id('adb-pair-code').value || '').trim();
      adbSetStatus('Pairing\u2026', 'busy');
      const res = await window.phonePcApi.adbPair(addr, code).catch(() => null);
      if (!res || !res.ok) return adbSetStatus((res && res.error) || 'Pairing failed', 'error');
      $id('adb-pair-code').value = '';
      showToast('Paired. Now press Connect with the device address.', 'success');
      adbSetStatus('Paired \u00b7 enter the device address and press Connect', 'ok');
      adbRefreshAll(false);
    });

    $id('btn-adb-disconnect').addEventListener('click', async () => {
      await window.phonePcApi.adbDisconnect(adbSerial || '').catch(() => null);
      adbApps = [];
      renderApps();
      adbRefreshAll(false);
    });

    $id('btn-adb-refresh').addEventListener('click', () => adbRefreshAll(true));
    $id('adb-serial').addEventListener('change', () => {
      adbSerial = $id('adb-serial').value || null;
      adbApps = [];
      if (!phoneApps.length) renderApps();
      if (adbSerial) adbLoadApps();
    });
  }

  if ($id('btn-apps-refresh')) $id('btn-apps-refresh').addEventListener('click', appsRefresh);
  if ($id('adb-search')) $id('adb-search').addEventListener('input', renderApps);
  if ($id('adb-search')) $id('adb-search').addEventListener('keydown', (e) => {
    if (e.key !== 'Enter') return;
    const first = $id('adb-apps').querySelector('[data-pkg]');
    if (first) first.click();
  });

  if ($id('adb-apps')) $id('adb-apps').addEventListener('click', async (e) => {
    const cell = e.target.closest('[data-pkg]');
    if (!cell) return;
    const pkg = cell.dataset.pkg;
    cell.disabled = true;
    try {
      if (phoneApps.length) {
        await window.phonePcApi.launchPhoneApp(pkg);
        return;
      }
      if (!adbSerial) return;
      const res = await window.phonePcApi.adbLaunch(adbSerial, pkg).catch(() => null);
      if (res && res.ok) {
        await window.phonePcApi.openScreenWindow().catch(() => null);
        showToast('Opening in a window on this PC', 'success');
      } else {
        showToast((res && res.error) || 'Could not open that app', 'error');
      }
    } finally {
      cell.disabled = false;
    }
  });

  // Load lazily: the pane only talks to the phone when it is opened.
  let appsLoaded = false;
  document.querySelectorAll('.nav-tab[data-tab="tab-apps"]').forEach((tab) => {
    tab.addEventListener('click', () => {
      if (appsLoaded) {
        window.phonePcApi.getSessionStatus().then((s) => {
          if (s && s.status === 'connected' && phoneApps.length) return;
          appsRefresh();
        }).catch(() => {});
        return;
      }
      appsLoaded = true;
      appsRefresh();
    });
  });

