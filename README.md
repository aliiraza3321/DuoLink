<div align="center">

<img src="desktop/renderer/assets/logo_1024.png" alt="" width="88" height="88">

# DuoLink

**Your Android phone and your PC, working as one.**

Messages, calls, notifications, clipboard, files, gallery, screen mirroring, a
phone webcam, and phone apps in windows of their own — over your own Wi-Fi.
No account, no cloud, nothing leaves the network.

[![Windows](https://img.shields.io/badge/Windows-10%20%7C%2011-1f2433?style=flat-square&logo=windows11&logoColor=white)](#download)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white)](#download)
[![APK build](https://github.com/aliiraza3321/DuoLink/actions/workflows/build-apk.yml/badge.svg)](https://github.com/aliiraza3321/DuoLink/actions/workflows/build-apk.yml)

</div>

---

## Contents

- [Download](#download)
- [Requirements](#requirements)
- [Quick start](#quick-start)
- [Features](#features)
- [Android permissions](#android-permissions)
- [Security and privacy](#security-and-privacy)
- [How it works](#how-it-works)
- [Repository layout](#repository-layout)
- [Build from source](#build-from-source)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)

---

## Download

DuoLink is two applications: a desktop client for Windows and a phone client for
Android. Install the desktop app first — its last setup screen shows the QR code
the phone app scans.

### DuoLink for Windows

| File | Version | Size | Requirements |
|---|---|---|---|
| [**DuoLink-Setup-1.1.0.exe**](https://github.com/aliiraza3321/DuoLink/releases/latest/download/DuoLink-Setup-1.1.0.exe) | 1.1.0 | 82 MB | Windows 10 (2004+) or Windows 11, 64-bit |

### DuoLink for Android

| File | Version | Size | Requirements |
|---|---|---|---|
| [**DuoLink.apk**](https://github.com/aliiraza3321/DuoLink/releases/latest/download/DuoLink.apk) | 1.1.0 | 43 MB | Android 8.0 or newer, same Wi-Fi as the PC |

Both links use the repository's `releases/latest` route, so they always serve
the newest published build of each file. Every release carries both downloads,
and the full change history is in
[Releases](https://github.com/aliiraza3321/DuoLink/releases).

> [!NOTE]
> Neither binary is code-signed yet. Windows SmartScreen may warn the first time
> — choose **More info → Run anyway**. On Android, allow installs from your
> browser when prompted.

---

## Requirements

**PC**

- Windows 10 version 2004 or later, or Windows 11 — 64-bit.
- Both devices on the same local network, with client isolation disabled on the
  router (most home networks have it off).
- Port 3000 reachable between the two devices; the installer adds a Windows
  Firewall rule for private networks.

**Phone**

- Android 8.0 (API 26) or newer.
- Permissions for the features you intend to use (see
  [Android permissions](#android-permissions)); nothing is required to pair.

**Optional**

- Android Developer options → Wireless debugging, paired once, to open phone
  apps in their own PC windows.

---

## Quick start

1. **Install the desktop app.** Run `DuoLink-Setup-1.1.0.exe`. Keep
   *Create a desktop shortcut* ticked if you want one, press **Install**, and
   finish. Setup registers a Start Menu shortcut and the firewall rule.
2. **Install the phone app.** Scan the QR code on the installer's last screen,
   or download `DuoLink.apk` above. Allow the install when Android asks.
3. **Pair.** Open DuoLink on the PC. Scan the QR code with the phone and confirm
   the same six-digit code on both screens. The phone reconnects on its own from
   then on, including after a reboot.
4. **Use it.** Mirror the screen, send a file, read messages, or press
   **Open window** to keep the phone picture in a window of its own.

The complete walkthrough, including every permission and the optional app
windows setup, is in [docs/guide/getting-started.md](docs/guide/getting-started.md).

---

## Features

| Area | What you get |
|---|---|
| **Pairing** | One QR scan with a single-use token; certificate fingerprint pinned; the phone reconnects automatically |
| **Screen mirroring** | Live phone screen in the app or in its own resizable window; tap, swipe, scroll and type into it; screenshot, record, volume, fullscreen |
| **Messages** | Read and reply to SMS threads, start a conversation, call a contact, delete a thread |
| **Calls** | Live call state, dialpad and call log; answer, reject, end and call back from the PC |
| **Notifications** | Mirrored as they arrive with the app's icon; inline reply, per-app mute, dismiss |
| **Clipboard** | Two-way copy and paste, optionally synchronised automatically |
| **Files** | Send files by picker or drag-and-drop; browse the PC's folders from the phone |
| **Gallery** | Albums and a day-grouped grid; open, download, share or delete |
| **Webcam** | Use the phone camera in Zoom, Teams or OBS; switch lens, torch, screenshot, record |
| **Apps** | Every installed app with its real name and icon; each opens in its own PC window |
| **Phone control** | Back, Home, Recents, media keys, volume, ring, lock, restart, shut down |

---

## Android permissions

DuoLink asks for a permission only when the matching feature is opened. Nothing
is requested at first launch, and pairing works without any of them.

| Permission | Used for |
|---|---|
| Camera | Scanning the pairing QR code; the webcam feature |
| Notifications | The foreground service that keeps the link alive |
| SMS, Call log, Phone | Messages, call history and dialling |
| Contacts | Names and photos in messages and calls |
| Photos / Media | Gallery browsing, screenshots and file transfers |
| Screen capture (MediaProjection) | Screen mirroring; Android shows its own prompt every session |
| Accessibility service | Remote taps, swipes, typing and system keys on the phone |
| Microphone | The webcam feature with sound |

---

## Security and privacy

- **Local only.** Both apps talk to each other directly over your LAN. There is
  no relay, no account and no telemetry. The desktop app's only outbound request
  is an update check against GitHub Releases.
- **Pinned TLS.** The desktop app serves HTTPS/WSS with a self-signed
  certificate. The QR code carries the certificate's SHA-256 fingerprint, so the
  phone refuses to talk to anything else on the network — including a look-alike
  host.
- **Paired devices only.** A single-use, 32-character token authorises the first
  connection. After that, a 256-bit secret stored in Android's Keystore answers
  an HMAC-SHA256 challenge, and the desktop side rejects any command that does
  not come from a paired socket.
- **Data stays put.** Received files go to `Downloads\DuoLink`. Settings and
  pairings live in the app's own data folder; uninstalling removes both.
- **No silent access.** Screen capture, accessibility control, the microphone
  and the camera are all governed by Android's own permission prompts.

---

## How it works

```
┌───────────────────────────┐        TLS-pinned WSS         ┌──────────────────────────┐
│  DuoLink for Windows      │◄───── local network only ────►│  DuoLink for Android     │
│  Electron main process    │       port 3000, mDNS         │  Kotlin + Compose        │
│  • HTTPS/WSS server       │                               │  • Foreground service    │
│  • Pairing + sessions     │       JSON message types      │  • MediaProjection       │
│  • Files, clipboard, ADB  │       (SMS, CALLS, MEDIA…)    │  • Accessibility input   │
└───────────────────────────┘                               └──────────────────────────┘
```

- **Transport.** One WebSocket per phone. Control traffic is JSON; screen
  frames, H.264 video, audio and file chunks travel on separate binary channels
  so a busy stream never delays a message.
- **Screen mirroring.** The phone encodes with the hardware H.264 encoder at up
  to 60 fps and falls back to a JPEG stream on devices whose encoder cannot feed
  the display surface. The PC decodes with WebCodecs and drops stale frames
  rather than playing them late, so latency stays flat.
- **App windows.** Each phone app is launched onto its own virtual display by a
  helper that runs through the phone's own wireless-debugging shell. No root and
  no second app on the phone.
- **Webcam.** The phone publishes its camera as an MJPEG stream on the LAN with a
  virtual-camera companion on the PC side.

---

## Repository layout

```
desktop/          Electron app: main process, renderer UI, installer, build scripts
android/          Android app: Kotlin, Jetpack Compose, foreground service
docs/             User guide and reference pages
.github/          CI: Android build and release publishing
```

Key desktop entry points:

| Path | Role |
|---|---|
| `desktop/main.js` | Window management, IPC, update check |
| `desktop/src/sockets.js` | WebSocket server, pairing, protocol dispatch |
| `desktop/renderer/` | UI (HTML, CSS, JS) shared by the app and its pop-out windows |
| `desktop/build/installer.nsh` | NSIS installer pages and styling |

---

## Build from source

**Desktop (Electron)**

```bash
cd desktop
npm install
npm start          # run from source
npm run dist       # build the installer into desktop/dist
```

**Android (Gradle)**

```bash
cd android
./gradlew assembleDebug        # APK in android/app/build/outputs/apk/debug
./gradlew assembleRelease      # needs android/keystore.properties
```

CI builds the APK on every push that touches Android or shared files and
publishes it as a release. Documentation-only pushes are skipped.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| Phone cannot find the PC | Both devices must be on the same Wi-Fi. Turn off AP/client isolation on the router. |
| Windows Firewall prompt | Allow DuoLink on **private networks** only. |
| Pairing expires | The QR token is single-use and short-lived; press **Pair another device** for a fresh code. |
| Messages or calls are empty | Grant SMS and Call-log permissions in Android settings. |
| Mirroring shows a black frame | Accept the screen-capture prompt on the phone; it reappears each session. |
| Mirror stutters on a busy network | Prefer the 5 GHz band and keep the phone in range; the stream adapts its bitrate to the link. |
| App list is empty | Open **Apps → Refresh**, and update the APK if it is older than the desktop app. |
| App windows will not open | Re-pair under **Apps → Advanced · Wireless debugging**. |

---

## Contributing

Issues and pull requests are welcome. Please keep changes focused — one feature
or fix per pull request — and describe what you tested on real hardware, since
most of this project depends on device-specific behaviour that CI cannot cover.

<div align="center">

**DuoLink** — two screens, one system.

</div>
