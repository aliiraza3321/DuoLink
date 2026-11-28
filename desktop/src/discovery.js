/**
 * LanDiscovery — answers a paired phone that is looking for this PC again.
 *
 * The phone stores the PC's IP at pairing time. DHCP hands out a new address
 * after a router reboot, a Wi-Fi reconnect or a hotspot swap, and every later
 * reconnect then dialled an address nobody answers on: the phone's socket had
 * dropped in the background and the link could never come back until the user
 * paired again.
 *
 * The phone broadcasts a tiny UDP probe carrying the device id it paired with;
 * this responder answers only for a device it actually knows, and the reply
 * travels from the PC's real address, so the phone learns the current IP. A
 * rogue responder gains nothing: the WebSocket is still pinned to this PC's
 * TLS certificate.
 */

const dgram = require('dgram');
const os = require('os');

const DISCOVERY_PORT = 3001;
const PROBE_PREFIX = 'DUOLINK_DISCOVER';

class LanDiscovery {
  /**
   * @param {object} opts
   * @param {() => number} opts.getPort  the live WSS port
   * @param {import('./pairing').PairingManager} opts.pairingManager
   * @param {(msg: string) => void} [opts.log]
   */
  constructor({ getPort, pairingManager, log }) {
    this.getPort = getPort;
    this.pairingManager = pairingManager;
    this.log = log || (() => {});
    this.socket = null;
  }

  start() {
    if (this.socket) return;
    const socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    this.socket = socket;
    socket.on('error', (err) => {
      this.log(`udp error: ${err.code || err.message}`);
      try { socket.close(); } catch (_) {}
      if (this.socket === socket) this.socket = null;
    });
    socket.on('message', (raw, rinfo) => this._handle(raw, rinfo, socket));
    socket.bind(DISCOVERY_PORT, '0.0.0.0', () => {
      try { socket.setBroadcast(true); } catch (_) {}
      this.log(`listening on udp/${DISCOVERY_PORT}`);
    });
  }

  stop() {
    const socket = this.socket;
    this.socket = null;
    if (!socket) return;
    try { socket.close(); } catch (_) {}
  }

  _handle(raw, rinfo, socket) {
    const text = String(raw || '').trim();
    if (!text.startsWith(PROBE_PREFIX)) return;
    // "DUOLINK_DISCOVER 1 android-abcdef123456" — the id is the last token.
    const tokens = text.split(/\s+/);
    const deviceId = tokens[tokens.length - 1];
    if (!deviceId || deviceId === '1') return;
    const trusted = this.pairingManager &&
      this.pairingManager.trustedDevices &&
      this.pairingManager.trustedDevices[deviceId];
    if (!trusted) {
      this.log(`probe from unknown device ${deviceId} (${rinfo.address})`);
      return;
    }
    const reply = JSON.stringify({
      app: 'DuoLink',
      v: 1,
      port: this.getPort(),
      name: os.hostname()
    });
    const buf = Buffer.from(reply, 'utf8');
    try {
      socket.send(buf, rinfo.port, rinfo.address);
      this.log(`told ${trusted.deviceName || deviceId} (${rinfo.address}) where we are`);
    } catch (err) {
      this.log(`reply failed: ${err.message}`);
    }
  }
}

module.exports = { LanDiscovery, DISCOVERY_PORT };
