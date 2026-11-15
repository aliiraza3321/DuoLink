import io

p = 'desktop/renderer/index.html'
s = io.open(p, encoding='utf-8').read()

start = s.index('        <aside class="apps-rail">')
end = s.index('        </aside>', start) + len('        </aside>')

new_rail = '''        <aside class="apps-rail">
          <div class="apps-rail-head">
            <div>
              <h2>Phone apps</h2>
              <p>Open any app in a window on this PC</p>
            </div>
            <span id="adb-dot" class="apps-dot" title="Phone not connected"></span>
          </div>

          <div class="apps-phone" id="apps-phone-card">
            <span class="apps-phone-icon"><svg class="ic" aria-hidden="true"><use href="#i-phone"/></svg></span>
            <div class="apps-phone-text">
              <strong id="apps-phone-name">No phone connected</strong>
              <span id="apps-phone-sub">Connect your phone from Overview</span>
            </div>
            <button id="btn-apps-refresh" class="apps-cta apps-cta-primary">Refresh</button>
          </div>

          <div class="apps-tip">
            <strong>How it works</strong>
            <p>Every app you see is the real app from your phone: tapping one opens it in its own window on this PC, while the phone stays free.</p>
          </div>

          <details class="apps-adv" id="apps-adv">
            <summary>
              <span>Advanced · Wireless debugging</span>
              <svg class="ic" aria-hidden="true"><use href="#i-chevron"/></svg>
            </summary>
            <div class="apps-adv-body">
              <p class="apps-step-hint">Optional. On the phone: Developer options → Wireless debugging → Pair device with pairing code.</p>
              <label for="adb-pair-addr">Pairing address + code</label>
              <input id="adb-pair-addr" class="apps-input" placeholder="192.168.1.40:41234" autocomplete="off">
              <div class="apps-inline">
                <input id="adb-pair-code" class="apps-input apps-input-code" placeholder="6-digit code" autocomplete="off" maxlength="6">
                <button id="btn-adb-pair" class="apps-cta">Pair</button>
              </div>
              <label for="adb-connect-addr">Device address</label>
              <div class="apps-inline">
                <input id="adb-connect-addr" class="apps-input" placeholder="192.168.1.40:5555" autocomplete="off">
                <button id="btn-adb-connect" class="apps-cta">Connect</button>
              </div>
              <label for="adb-serial">adb device</label>
              <select id="adb-serial" class="apps-input apps-select"></select>
              <div class="apps-inline">
                <button id="btn-adb-refresh" class="apps-ghost"><svg class="ic" aria-hidden="true"><use href="#i-refresh"/></svg> Reload apps</button>
                <button id="btn-adb-disconnect" class="apps-ghost"><svg class="ic" aria-hidden="true"><use href="#i-x"/></svg> Disconnect</button>
              </div>
            </div>
          </details>

          <div id="adb-status" class="apps-status">Waiting for your phone</div>
        </aside>'''

s = s[:start] + new_rail + s[end:]
io.open(p, 'w', encoding='utf-8').write(s)
print('apps rail simplified')
