/**
 * Generates build/qr-install.png - the QR printed on the installer welcome page.
 *
 * It points at the permanent APK link, which the GitHub workflow keeps fresh:
 *   https://github.com/aliiraza3321/DuoLink/releases/latest/download/DuoLink.apk
 *
 * Run from desktop/:  node build/make-qr.js
 */

const fs = require('fs');
const path = require('path');
const QRCode = require('qrcode');

const APK_URL = 'https://github.com/aliiraza3321/DuoLink/releases/latest/download/DuoLink.apk';

const OUT = path.join(__dirname, 'qr-install.png');

QRCode.toFile(OUT, APK_URL, {
  errorCorrectionLevel: 'M',
  margin: 0,
  // Ink on the installer's cream paper, so the code belongs to the artwork.
  color: { dark: '#1E1B16FF', light: '#F3EEE2FF' },
  width: 464
}, (err) => {
  if (err) {
    console.error('QR failed:', err.message);
    process.exit(1);
  }
  console.log('wrote', path.basename(OUT), fs.statSync(OUT).size, 'bytes');
});
