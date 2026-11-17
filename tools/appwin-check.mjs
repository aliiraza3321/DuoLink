/**
 * End-to-end check for DuoLink's per-app windows.
 *
 * Talks to the shell helper that runs on the phone (started over adb here, in
 * the product started by the app's own wireless-debugging session):
 *
 *   node tools/appwin-check.mjs <port> <package> [width] [height] [dpi]
 *
 * Sends `V <pkg> w h dpi`, collects frames for a few seconds and writes the
 * last one to .appwin-frame.jpg so the result can be looked at.
 */
import { createConnection } from 'node:net';
import { writeFileSync } from 'node:fs';

const [port, pkg, w = '540', h = '1080', dpi = '240', token = 'tok123', waitMs = '6000'] =
  process.argv.slice(2);
if (!port || !pkg) {
  console.error('usage: node tools/appwin-check.mjs <port> <package> [w] [h] [dpi] [token] [waitMs]');
  process.exit(2);
}

const socket = createConnection({ host: '127.0.0.1', port: Number(port) }, () => {
  socket.write(token + '\n');
  socket.write(`V ${pkg} ${w} ${h} ${dpi}\n`);
});

let buffered = Buffer.alloc(0);
let frames = 0;
let bytes = 0;
let last = null;
const started = Date.now();
let firstFrameMs = null;

socket.on('data', (chunk) => {
  buffered = Buffer.concat([buffered, chunk]);
  while (buffered.length >= 4) {
    const length = buffered.readUInt32BE(0);
    if (buffered.length < 4 + length) break;
    const kind = buffered.readUInt8(4);
    const body = buffered.subarray(4 + 1 + 8, 4 + length);
    buffered = buffered.subarray(4 + length);
    if (kind === 1) {
      if (firstFrameMs === null) firstFrameMs = Date.now() - started;
      frames += 1;
      bytes += body.length;
      last = Buffer.from(body);
    }
  }
});

socket.on('error', (e) => console.log('socket error:', e.message));

setTimeout(() => {
  if (last) writeFileSync('.appwin-frame.jpg', last);
  console.log(
    `frames=${frames} bytes=${bytes} first=${firstFrameMs ?? '-'}ms ` +
      `${last ? 'frame=.appwin-frame.jpg' : 'no frame'}`
  );
  socket.write('Q\n');
  socket.end();
  process.exit(frames > 0 ? 0 : 1);
}, Number(waitMs));
