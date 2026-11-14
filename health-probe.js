// Screenshots the Phone-health card at several narrow widths and reports overflow.
const { connect } = require('./screenshots/cdp.js');
const fs = require('fs');

(async () => {
  const c = await connect();
  const widths = [1000, 900, 820];
  const report = [];

  for (const w of widths) {
    await c.send('Emulation.setDeviceMetricsOverride', { width: w, height: 640, deviceScaleFactor: 1, mobile: false });
    await new Promise((r) => setTimeout(r, 700));
    const info = await c.evaluate(`(() => {
      const tab = document.getElementById('tab-settings');
      const wide = tab.querySelector('.settings-card-wide');
      const r = wide ? wide.getBoundingClientRect() : null;
      const over = r ? [...wide.querySelectorAll('*')].filter((k) => {
        const kr = k.getBoundingClientRect();
        return kr.width > 0 && (kr.right > r.right - 1 || k.scrollWidth > k.clientWidth + 2);
      }).slice(0, 6).map((k) => ((k.className || '').toString().slice(0, 22) + '|' + (k.textContent || '').trim().slice(0, 16) + '|clip=' + (k.scrollWidth - k.clientWidth))) : [];
      return JSON.stringify({ w: window.innerWidth, tabW: Math.round(tab.getBoundingClientRect().width), cardW: r ? Math.round(r.width) : 0, over });
    })()`);
    report.push(info);
    try {
      const shot = await c.send('Page.captureScreenshot', { format: 'png' });
      if (shot && shot.result && shot.result.data) fs.writeFileSync(`health-${w}.png`, Buffer.from(shot.result.data, 'base64'));
    } catch (_) { /* screenshot is optional */ }
  }

  await c.send('Emulation.clearDeviceMetricsOverride', {});
  console.log(report.join('\n'));
  c.close();
  process.exit(0);
})().catch((e) => { console.log('ERR', e.message); process.exit(1); });
