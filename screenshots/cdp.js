const fs = require('fs');
async function connect() {
  const list = await (await fetch('http://127.0.0.1:9222/json')).json();
  const page = list.find((t) => /index\.html/.test(t.url)) || list[0];
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  let id = 0; const pend = new Map();
  await new Promise((r) => (ws.onopen = r));
  ws.onmessage = (e) => { const m = JSON.parse(e.data); if (m.id && pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); } };
  const send = (m, p = {}) => new Promise((res) => { const i = ++id; pend.set(i, res); ws.send(JSON.stringify({ id: i, method: m, params: p })); });
  const evaluate = async (e) => { const r = await send('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true }); return r.result && r.result.exceptionDetails ? 'EX:' + JSON.stringify(r.result.exceptionDetails.exception||{}).slice(0,160) : r.result.result.value; };
  const shot = async (f) => { const s = await send('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(f, Buffer.from(s.result.data, 'base64')); };
  return { send, evaluate, shot, close: () => ws.close() };
}
module.exports = { connect };
