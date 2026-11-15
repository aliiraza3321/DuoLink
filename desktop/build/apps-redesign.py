import io, re

p = 'desktop/renderer/index.html'
s = io.open(p, encoding='utf-8').read()

start = s.index('      <section id="tab-apps"')
end = s.index('      </section>', start) + len('      </section>')

new = '''      <section id="tab-apps" class="tab-pane apps-pane">
        <aside class="apps-rail">
          <div class="apps-rail-head">
            <div>
              <h2>Phone apps</h2>
              <p>Wireless debugging</p>
            </div>
            <span id="adb-dot" class="apps-dot" title="Not connected"></span>
          </div>

          <div class="apps-conn">
            <div class="apps-step">
              <span class="apps-step-n">1</span>
              <div class="apps-step-body">
                <label for="adb-pair-addr">Pair with a code</label>
                <p class="apps-step-hint">Wireless debugging → Pair device with pairing code.</p>
                <input id="adb-pair-addr" class="apps-input" placeholder="192.168.1.40:41234" autocomplete="off">
                <div class="apps-inline">
                  <input id="adb-pair-code" class="apps-input apps-input-code" placeholder="6-digit code" autocomplete="off" maxlength="6">
                  <button id="btn-adb-pair" class="apps-cta">Pair</button>
                </div>
              </div>
            </div>

            <div class="apps-step">
              <span class="apps-step-n">2</span>
              <div class="apps-step-body">
                <label for="adb-connect-addr">Connect to the device</label>
                <p class="apps-step-hint">Use the IP address and port from the same screen.</p>
                <div class="apps-inline">
                  <input id="adb-connect-addr" class="apps-input" placeholder="192.168.1.40:5555" autocomplete="off">
                  <button id="btn-adb-connect" class="apps-cta apps-cta-primary">Connect</button>
                </div>
              </div>
            </div>

            <div class="apps-step">
              <span class="apps-step-n">3</span>
              <div class="apps-step-body">
                <label for="adb-serial">Connected device</label>
                <select id="adb-serial" class="apps-input apps-select"></select>
                <div class="apps-inline">
                  <button id="btn-adb-refresh" class="apps-ghost"><svg class="ic" aria-hidden="true"><use href="#i-refresh"/></svg> Reload apps</button>
                  <button id="btn-adb-disconnect" class="apps-ghost"><svg class="ic" aria-hidden="true"><use href="#i-x"/></svg> Disconnect</button>
                </div>
              </div>
            </div>
          </div>

          <div id="adb-status" class="apps-status">Wireless debugging: off</div>
        </aside>

        <section class="apps-main">
          <header class="apps-toolbar">
            <div class="apps-toolbar-text">
              <h2 id="adb-count">Apps</h2>
              <p id="adb-sub">Connect a phone to list its apps</p>
            </div>
            <div class="apps-search">
              <svg class="ic" aria-hidden="true"><use href="#i-search"/></svg>
              <input type="text" id="adb-search" placeholder="Search apps" autocomplete="off">
            </div>
          </header>
          <div id="adb-apps" class="apps-grid">
            <div class="apps-empty">
              <span class="apps-empty-icon"><svg class="ic" aria-hidden="true"><use href="#i-grid"/></svg></span>
              <strong>No apps yet</strong>
              <p>Pair over wireless debugging on the left, then every app on your phone shows up here — tap one to open it on the phone.</p>
            </div>
          </div>
        </section>
      </section>'''

s = s[:start] + new + s[end:]
io.open(p, 'w', encoding='utf-8').write(s)
print('apps pane replaced')
