/**
 * PhonePC Pair - Network Interface Detection & IP Validation
 */

const os = require('os');

// Common virtual/VPN adapter names to ignore
const IGNORED_ADAPTER_PATTERNS = [
  /vEthernet/i,
  /VirtualBox/i,
  /WSL/i,
  /Hyper-V/i,
  /VMware/i,
  /Tailscale/i,
  /docker/i,
  /tap/i,
  /tun/i,
  /nord/i,
  /proton/i,
  /wireguard/i,
  /wg[0-9]*/i,
  /Loopback/i
];

/**
 * Checks if an IPv4 address is within private ranges (RFC 1918) or localhost.
 * @param {string} ip
 * @returns {boolean}
 */
function isPrivateIp(ip) {
  if (!ip) return false;
  // Handle IPv6 mapped IPv4 like ::ffff:192.168.1.5
  if (ip.startsWith('::ffff:')) {
    ip = ip.substring(7);
  }

  if (ip === '127.0.0.1' || ip === '::1' || ip === 'localhost') {
    return true;
  }

  const parts = ip.split('.').map(Number);
  if (parts.length !== 4 || parts.some(p => isNaN(p) || p < 0 || p > 255)) {
    return false;
  }

  // 10.0.0.0 - 10.255.255.255
  if (parts[0] === 10) return true;

  // 172.16.0.0 - 172.31.255.255
  if (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) return true;

  // 192.168.0.0 - 192.168.255.255
  if (parts[0] === 192 && parts[1] === 168) return true;

  return false;
}

/**
 * Discovers the active local Wi-Fi or Ethernet LAN IPv4 address.
 * Allows override via process.env.LAN_IP.
 * @returns {string}
 */
function getLocalLanIp() {
  if (process.env.LAN_IP && isPrivateIp(process.env.LAN_IP)) {
    return process.env.LAN_IP.trim();
  }

  const interfaces = os.networkInterfaces();
  const candidates = [];

  for (const [name, addrs] of Object.entries(interfaces)) {
    if (!addrs) continue;

    // Check if adapter name matches virtual/VPN adapter blacklists
    const isIgnored = IGNORED_ADAPTER_PATTERNS.some(pattern => pattern.test(name));
    if (isIgnored) continue;

    for (const addr of addrs) {
      // Look for non-internal IPv4
      if (addr.family === 'IPv4' && !addr.internal) {
        if (isPrivateIp(addr.address)) {
          // Prefer Wi-Fi or Ethernet names
          const isWifi = /wi-?fi|wlan|wireless/i.test(name);
          const isEthernet = /eth|ethernet|local area connection/i.test(name);
          candidates.push({
            address: addr.address,
            name,
            score: isWifi ? 10 : isEthernet ? 8 : 5
          });
        }
      }
    }
  }

  if (candidates.length > 0) {
    // Sort descending by score
    candidates.sort((a, b) => b.score - a.score);
    return candidates[0].address;
  }

  return '127.0.0.1';
}

module.exports = {
  isPrivateIp,
  getLocalLanIp
};
