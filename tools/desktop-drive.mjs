// Drives the running DuoLink window over the Chrome DevTools Protocol, so a
// test can press the same buttons a person would. Start the app with
// `DuoLink.exe --remote-debugging-port=9222` first.
//
//   node tools/desktop-drive.mjs "document.title"
const port = process.env.CDP_PORT || 9222;

const targets = await fetch(`http://127.0.0.1:${port}/json`).then((r) => r.json());
const page = targets.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
if (!page) {
  console.error('no page target on port ' + port);
  process.exit(2);
}

const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0;
const pending = new Map();
ws.addEventListener('message', (event) => {
  const msg = JSON.parse(event.data);
  const done = pending.get(msg.id);
  if (done) {
    pending.delete(msg.id);
    done(msg);
  }
});

await new Promise((resolve, reject) => {
  ws.addEventListener('open', resolve);
  ws.addEventListener('error', reject);
});

function send(method, params) {
  const messageId = ++id;
  ws.send(JSON.stringify({ id: messageId, method, params }));
  return new Promise((resolve) => pending.set(messageId, resolve));
}

// Two extra modes, so hover states and real button presses can be checked:
//   node tools/desktop-drive.mjs --mouse 400 300
//   node tools/desktop-drive.mjs --click 400 300
const argv = process.argv.slice(2);
if (argv[0] === '--mouse' || argv[0] === '--click') {
  const x = Number(argv[1]);
  const y = Number(argv[2]);
  await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y, button: 'none' });
  if (argv[0] === '--click') {
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
  }
  await new Promise((resolve) => setTimeout(resolve, 350));
  const state = await send('Runtime.evaluate', {
    expression: `(() => { const el = document.elementFromPoint(${x}, ${y}); const t = document.getElementById('webcam-tools'); return JSON.stringify({ under: el && (el.id || el.className || el.tagName), toolsOpacity: t && getComputedStyle(t).opacity, toolsPointer: t && getComputedStyle(t).pointerEvents }); })()`,
    returnByValue: true,
  });
  ws.close();
  console.log(state.result?.result?.value);
  process.exit(0);
}

const expression = argv.join(' ');
const res = await send('Runtime.evaluate', {
  expression,
  awaitPromise: true,
  returnByValue: true,
  userGesture: true,
});
ws.close();
if (res.error) {
  console.error(JSON.stringify(res.error));
  process.exit(1);
}
const result = res.result?.result;
if (res.result?.exceptionDetails) {
  console.error(JSON.stringify(res.result.exceptionDetails).slice(0, 600));
  process.exit(1);
}
console.log(typeof result?.value === 'string' ? result.value : JSON.stringify(result?.value));
