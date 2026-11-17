<div align="center">

<img src="desktop/renderer/assets/logo_1024.png" alt="" width="96" height="96">

# DuoLink

**Your Android phone and your PC, working as one.**

Messages, calls, notifications, clipboard, files, photos, screen mirroring, a
phone webcam and phone apps in windows of their own — between Android and
Windows. Local-first, end-to-end paired, no account and no cloud.

[![Latest release](https://img.shields.io/github/v/release/aliiraza3321/DuoLink?label=download&style=flat-square)](https://github.com/aliiraza3321/DuoLink/releases/latest)
[![APK build](https://github.com/aliiraza3321/DuoLink/actions/workflows/build-apk.yml/badge.svg)](https://github.com/aliiraza3321/DuoLink/actions/workflows/build-apk.yml)
[![Platforms](https://img.shields.io/badge/desktop-Windows%2010%20%7C%2011-1f2433?style=flat-square)](#download)
[![Android](https://img.shields.io/badge/android-8.0%2B-3ddc84?style=flat-square)](#download)

</div>

DuoLink keeps the phone in your pocket and the PC on your desk, but treats them
as one machine. Pair once by scanning a QR code; after that the phone reconnects
on its own over your local network, and everything below works from the desktop
window — no cloud, no account, no data leaving your Wi-Fi.

---

## Why DuoLink

Phone Link handles some notifications, KDE Connect handles the clipboard,
LocalSend handles files, and screen mirroring is another app entirely. DuoLink
puts all of it behind one window, with one pairing, and adds the things those
tools never do: reading and replying to SMS, dialling and answering calls,
browsing the phone's photos, using the phone as a webcam, and opening phone apps
in their own window on the PC.

Everything runs on your LAN. The desktop app hosts a TLS-pinned WebSocket
server; the phone connects to it directly. Nothing is relayed.

## Download

| | Download | Needs |
|---|---|---|
| **Windows (x64)** | [DuoLink Setup 1.1.0 (.exe, 82 MB)](https://github.com/aliiraza3321/DuoLink/releases/download/apk-1.1.0/DuoLink-Setup-1.1.0.exe) | Windows 10 (2004+) or Windows 11, 64-bit |
| **Android** | [DuoLink 1.1.0 (.apk, 43 MB)](https://github.com/aliiraza3321/DuoLink/releases/download/apk-1.1.0/DuoLink-1.1.0.apk) | Android 8.0 or newer, same Wi-Fi as the PC |

Both files are attached to the [1.1.0 release](https://github.com/aliiraza3321/DuoLink/releases/tag/apk-1.1.0);
[releases/latest](https://github.com/aliiraza3321/DuoLink/releases/latest) always
points at the newest build.

> [!NOTE]
> The Windows installer isn't code-signed yet, so SmartScreen may show
> **Windows protected your PC** the first time — choose **More info**, then
> **Run anyway**. On Android, allow installs from your browser when prompted.

The installer's last screen shows a QR code for the Android app: scan it with
your phone's camera to open the download page.

## What you can do

| | Feature | What it does |
|---|---|---|
| 📱 | **Pairing** | Scan one QR code. The certificate fingerprint is pinned, the token is single-use, and the phone reconnects later with a Keystore-backed secret. |
| 💬 | **Messages** | Read every SMS thread, reply from the keyboard, delete a message, start a new conversation or call the number — all without touching the phone. |
| 📞 | **Calls** | Live call state with Answer / Reject / End, a full dialpad, the phone's call log with direction and duration, and one-tap message or call-back. |
| 🔔 | **Notifications** | The phone's notifications mirrored live, with app icons, per-app mute, inline replies and dismiss. |
| 📋 | **Clipboard** | Two-way clipboard: copy here, paste there. Optional auto-sync of whatever you copy on the PC. |
| 📁 | **Files** | Send files by picker or drag-and-drop, watch progress, open what the phone sends you. |
| 📷 | **Gallery** | Browse albums, thumbnails grouped by day, open, download, share to a phone app or delete from the phone. |
| 🖥️ | **Screen mirroring** | The phone screen in its own resizable window, with mouse and keyboard input, recording, screenshots and volume control. |
| 🎥 | **Phone as webcam** | Use the phone's camera in Zoom, Teams, Discord or OBS, with torch and lens switching. |
| 🧩 | **Apps** | Every app on the phone, with its real name and icon. Open one and it gets a Windows window of its own — ten apps open means ten windows, each showing only that app — while the phone stays free for anything else. |
| ⚙️ | **Settings** | Themes (dark and light, both matched to the Android app), trusted devices, phone health and permissions, diagnostics. |

## Screens

| Page | What's on it |
|---|---|
| **Overview** | Connection state, QR pairing, the paired phone's battery and permissions, quick actions. |
| **Messages** | Conversation list on the left, the thread and a rounded composer on the right. |
| **Calls** | Recent / Contacts, search, and the dialpad with live call state. |
| **Notifications** | Live mirror with per-app filters and reply. |
| **Gallery** | Album sidebar, day-grouped photo grid, select / download / share / delete. |
| **Phone Screen** | The mirror, plus a toolbar for record, screenshot, copy screenshot and volume. |
| **Apps** | The phone's app grid and search; open as many apps as you like, one window each. The one-time wireless-debugging pairing lives under *Advanced*. |
| **Settings** | General, security and privacy, trusted devices, phone health, diagnostics. |

## Quick start

1. **Install on the PC.** Run `DuoLink Setup <version>.exe`, press **Install**,
   and optionally untick *Add a desktop shortcut*. Setup adds a Start Menu
   shortcut, a desktop shortcut and a firewall rule for your local network only.
2. **Install on the phone.** Scan the QR on the installer's last screen, or
   download `DuoLink.apk` from the releases page, and allow the install.
3. **Pair.** Open DuoLink on the PC, scan the QR with the phone, confirm the
   six-digit code on both screens, and approve the phone on the PC. From then on
   the phone reconnects by itself whenever both are on the same network.
4. **Turn on app windows (once).** Open **Apps** on the PC and expand
   **Advanced · Wireless debugging**. Turn on **Developer options → Wireless
   debugging** on the phone, press **Pair this phone** and type the six-digit
   code the phone shows. After that, tapping any app in the grid opens it in its
   own window; close the window and the app's display on the phone closes too.

The full walkthrough — every permission, every page and every troubleshooting
step — is in **[docs/guide/getting-started.md](docs/guide/getting-started.md)**.

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

- **Pairing** — the PC shows a QR encoding host, port, a single-use token and the
  SHA-256 fingerprint of its self-signed certificate. The phone pins that
  fingerprint, so a look-alike server on the network cannot intercept a session.
- **Sessions** — after approval a 256-bit secret is stored in Android's Keystore
  encrypted preferences. Reconnects use an HMAC-SHA256 challenge/response.
- **Transports** — one WebSocket per phone carries every feature; media and
  screen frames use their own binary channels so chat never stutters.
- **App windows** — the phone answers with its launchable app list (real label
  and icon). Opening one starts a small helper on the phone through its *own*
  wireless-debugging session (`app_process` as the shell user). The helper makes
  a virtual display, launches the app onto it with `am start --display`, and
  streams its frames back to the PC, so each open app has a display and a window
  of its own; taps, scrolls and keys in the window go back to that display. No
  root and no second app on the phone.

## Build from source

Desktop (Electron):

```bash
cd desktop
npm install
npm run dist          # Windows installer in desktop/dist
npm start             # run from source
```

Android (Gradle):

```bash
cd android
./gradlew assembleDebug        # APK in android/app/build/outputs/apk/debug
./gradlew assembleRelease      # needs android/keystore.properties
```

The installer is cut from a clean checkout (`npm run dist`) and attached to a
GitHub release together with the APK — 1.1.0 lives on the `apk-1.1.0` release.
CI also builds the APK on every push to a non-documentation file and publishes
it as its own `apk-<version>-<build>` release, marked latest, so
`releases/latest/download/DuoLink.apk` always serves the newest build.

## Privacy

No account, no telemetry, no cloud. Sessions and settings stay on your PC and in
the phone's Keystore. Nothing is uploaded except the update check against GitHub
Releases. Uninstalling removes the app, its settings and its pairings; files you
received stay in `Downloads\DuoLink`.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Phone won't pair | Both devices must be on the same Wi-Fi. Turn off "client isolation" / AP isolation on the router. |
| Windows Firewall prompt | Allow DuoLink on **private networks** only. |
| Messages or calls are empty | On the phone: Settings → Apps → DuoLink → Permissions → SMS and Call log. |
| Mirroring shows a black frame | Grant the screen-capture prompt on the phone when it appears. |
| App list is empty | Open **Apps** → **Refresh**; if the phone app is older than the PC, update the APK. |
| App windows won't open | Re-pair wireless debugging: **Apps → Advanced · Wireless debugging → Pair this phone**, and turn wireless debugging back on in Developer options. |
| App window stays black | Unlock the phone and try another app; a locked phone can stop the app's display from drawing. |

## Documentation

- [Getting started](docs/guide/getting-started.md) — install, pair, tour, permissions.
- [Architecture](README.md#how-it-works) — how the two apps talk.

## Contributing

Issues and pull requests are welcome. Please keep changes focused: one feature or
fix per pull request, and describe what you tested on real hardware.

<div align="center">

**DuoLink** — two screens, one system.

</div>
