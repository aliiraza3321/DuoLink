/**
 * PhonePC Pair - Self-Signed TLS Certificate Generation & SHA-256 Fingerprinting
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const selfsigned = require('selfsigned');

/**
 * Computes SHA-256 fingerprint of a DER certificate.
 * @param {string} certPem 
 * @returns {string} 64-character uppercase hex string
 */
function computeSha256Fingerprint(certPem) {
  // Extract base64 content between PEM header/footer
  const cleanBase64 = certPem
    .replace(/-----BEGIN CERTIFICATE-----/g, '')
    .replace(/-----END CERTIFICATE-----/g, '')
    .replace(/\s+/g, '');
  const derBuffer = Buffer.from(cleanBase64, 'base64');
  return crypto.createHash('sha256').update(derBuffer).digest('hex').toUpperCase();
}

/**
 * Gets or creates the TLS credentials.
 * Persists in the provided storage directory (e.g. app.getPath('userData')).
 * @param {string} storageDir
 * @returns {Promise<{ cert: string, key: string, fingerprint: string }>}
 */
async function getOrCreateTlsCert(storageDir) {
  const certsDir = path.join(storageDir, 'certs');
  if (!fs.existsSync(certsDir)) {
    fs.mkdirSync(certsDir, { recursive: true });
  }

  const certPath = path.join(certsDir, 'cert.pem');
  const keyPath = path.join(certsDir, 'key.pem');

  if (fs.existsSync(certPath) && fs.existsSync(keyPath)) {
    try {
      const cert = fs.readFileSync(certPath, 'utf8');
      const key = fs.readFileSync(keyPath, 'utf8');
      const fingerprint = computeSha256Fingerprint(cert);
      return { cert, key, fingerprint };
    } catch (err) {
      console.warn('[Cert] Error reading existing certificates, regenerating...', err);
    }
  }

  // Generate new self-signed certificate valid for 10 years
  const attrs = [{ name: 'commonName', value: 'PhonePC-Local-Server' }];
  const pems = await selfsigned.generate(attrs, {
    days: 3650,
    keySize: 2048,
    algorithm: 'sha256'
  });

  fs.writeFileSync(certPath, pems.cert, 'utf8');
  fs.writeFileSync(keyPath, pems.private, 'utf8');

  const fingerprint = computeSha256Fingerprint(pems.cert);
  console.log(`[Cert] Generated new self-signed certificate. Fingerprint SHA-256: ${fingerprint}`);

  return {
    cert: pems.cert,
    key: pems.private,
    fingerprint
  };
}

module.exports = {
  getOrCreateTlsCert,
  computeSha256Fingerprint
};
