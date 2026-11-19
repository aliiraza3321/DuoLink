# Getting Started

This guide installs DuoLink on Windows and Android, pairs the two, and walks
through each page of the desktop app and the permissions it needs.

## System requirements

| Platform | Minimum | Notes |
| :--- | :--- | :--- |
| **Windows PC** | Windows 10 (2004 or newer) or Windows 11, 64-bit | Windows 11 gives the best results for the phone webcam and the Mica window backdrop. |
| **Android phone** | Android 8.0 (API 26) or newer | Android 11+ is needed for wireless debugging and app windows; Android 10+ for internal audio while mirroring. |
| **Network** | Shared Wi-Fi or a USB cable | Both devices must reach each other. Guest Wi-Fi with client isolation blocks pairing. |

## Install on Windows

1. Download [DuoLink Setup 1.1.0 (.exe)](https://github.com/aliiraza3321/DuoLink/releases/latest/download/DuoLink-Setup-1.1.0.exe)
   — or pick the newest installer from
   [GitHub Releases](https://github.com/aliiraza3321/DuoLink/releases/latest).
2. Run it. The installer isn't code-signed yet, so SmartScreen may show
   **Windows protected your PC** — choose **More info**, then **Run anyway**.
3. On the welcome page press **Install**. Setup:
   - installs DuoLink into `C:\Program Files\DuoLink` (**Change** picks another folder),
   - adds a Start Menu shortcut, and a desktop shortcut unless you untick
     **Add a desktop shortcut**,
   - adds a Windows Firewall rule for private networks only,
   - starts DuoLink in the tray when you sign in (turn off later in
     **Settings → General → Start with Windows**).
4. The last page shows **DuoLink is ready** with a QR code for the Android app,
   plus the link `github.com/aliiraza3321/DuoLink/releases/latest`. Scan it, then
   press **Open DuoLink**.

## Install on Android

1. Scan the QR from the installer's last page, or download
   [DuoLink 1.1.0 (.apk)](https://github.com/aliiraza3321/DuoLink/releases/latest/download/DuoLink.apk).
   The two apps must be the same version.
2. Open the downloaded file. If Android warns about an unknown developer, choose
   **Settings → Allow from this source**, then install.
3. Launch DuoLink and leave it on the pairing screen.

## Pair the phone

1. On the PC open **Overview**. A QR code is shown on the left card.
2. On the phone tap **Scan QR** and point it at the code.
3. Both screens show the same **six-digit code** — check that they match.
4. Approve the phone on the PC. DuoLink stores a session secret in the phone's
   Keystore, so from now on the phone reconnects by itself on the same network.

> [!TIP]
> If pairing fails, check that both devices are on the same Wi-Fi and that the
> router isn't using AP/client isolation. A USB cable also works for the first
> connection.

## Permissions, and why each one is asked

| Permission | Used for | Page |
| :--- | :--- | :--- |
| **SMS** | Reading threads, sending replies, deleting messages | Messages |
| **Call log** | Recent calls, directions and durations | Calls |
| **Phone state** | Live ringing / in-call state, answer and reject | Calls |
| **Notification access** | Mirroring notifications with app icons, replies, dismiss | Notifications |
| **Media / photos** | Album browsing, thumbnails, downloads, share and delete | Gallery |
| **Camera** | Phone as a webcam, QR scanning on the phone | Webcam |
| **Accessibility** | Mirror input: taps, swipes and typing from the PC | Phone Screen |
| **Screen capture** | The mirror stream (Android asks every session) | Phone Screen |

Open **Settings → Phone health and permissions → Run diagnostics** at any time to
see exactly which of these the phone has granted.

## A tour of the desktop app

### Overview
Connection state and latency, the pairing QR, the paired phone's battery and
permission summary, and quick actions for the things you do most.

### Messages
Conversations on the left with search, unread counts and the latest snippet; the
open thread on the right with a rounded composer. **+** starts a message to a new
number, the handset button calls the person you're talking to, and each bubble
can be copied or deleted from the phone.

### Calls
**Recent / Contacts**, a search box, and rows with direction arrows, duration and
quick actions. The dialpad on the right types into the number field; **Text**
opens a message to that number, **Call** dials it. Live call state sits above the
dialpad with Answer and End.

### Notifications
The phone's notifications as they arrive, with app icons, per-app mute, inline
reply where Android allows it, and dismiss.

### Chat & Files
Send files by picker or drag-and-drop, watch transfer progress, and open what the
phone sends. The clipboard card pushes text both ways.

### Gallery
Albums on the left (All photos, Videos, Audio, All media) with counts, and a
grid grouped by day on the right. **Select** turns on checkboxes;
**Open**, **Download**, **Share on PC** and **Delete** act on the selection.

### Phone Screen
The mirror opens in this pane and can be popped out into its own window. The
toolbar records video, takes screenshots, copies the last screenshot to the
clipboard, and controls the phone's media volume. The PC keyboard types into the
phone when the mirror window has focus.

### Webcam
Pick a lens, start the stream, and DuoLink exposes it to Windows so Zoom, Teams,
Discord and OBS can use it as a camera. Torch toggles the phone's flash.

### Apps
Every launchable app on the phone, with its real name and icon, searchable.
Choosing one opens it in a window of its own on the PC: open ten apps and you get
ten windows, each showing only that app, while the phone stays free for anything
else. Closing a window closes that app's display on the phone.

#### App windows: the one-time wireless-debugging pairing
App windows run on the phone's own wireless debugging, so the phone needs
Developer options once. Android 11 or newer is required.

1. On the phone open **Settings → About phone** and tap **Build number** seven
   times, then go to **Developer options → Wireless debugging** and turn it on.
   Keep the phone on the **Wireless debugging** screen for the next step.
2. On the PC open **Apps**, expand **Advanced · Wireless debugging** and press
   **Pair this phone**.
3. Type the **six-digit code** the phone is showing and confirm. DuoLink pairs,
   connects, and the app grid loads.

After that the pairing is remembered. Wireless debugging has to stay on for the
phone to start new app displays; if it is turned off, switch it on again and the
existing pairing is reused — you only type a code the first time.

If the automatic pairing can't find the phone, the manual route still works:
enter the **IP:pairing-port** and code under **Pairing address + code**, press
**Pair**, then enter the **device address** shown on the same phone screen and
press **Connect**. The grid stays cached until you press **Refresh**.

### Settings
Appearance (dark and light, matched to the Android app), start with Windows,
minimise to tray, security and privacy, received-files folder, PC file access,
trusted devices with revoke, phone health and permissions, diagnostics, log and
update checks.

## Troubleshooting

| Problem | Fix |
| :--- | :--- |
| QR never matches | Both devices on the same Wi-Fi, no VPN, router isolation off. |
| Phone keeps disconnecting | Disable battery optimisation for DuoLink on the phone, and keep the app's foreground notification. |
| Empty Messages or Calls | Grant SMS and Call log permissions on the phone, then press Refresh. |
| Black mirror window | Accept the screen-capture prompt on the phone; restart the mirror. |
| Gallery shows nothing | Grant photos/videos access, then pick an album again. |
| Apps list empty | Open **Apps → Refresh**; update the phone APK if the PC is newer. |
| App windows won't open | Enable **Developer options → Wireless debugging** on the phone, then **Apps → Advanced · Wireless debugging → Pair this phone**. |
| App window stays black | Unlock the phone and try another app; the app's display can't draw while the device is locked mid-launch. |
| First app window takes a few seconds | The phone is starting the app on a fresh display; later apps open faster. |
| Installer blocked | SmartScreen → **More info → Run anyway**. |

## Uninstall

- **Windows:** Settings → Apps → DuoLink → Uninstall. The branded uninstaller
  removes the app, its settings and its pairings. Files the phone sent you stay in
  `Downloads\DuoLink`.
- **Android:** long-press the app icon → App info → Uninstall.
- **Revoke one phone only:** **Settings → Trusted devices → Revoke trust**. The
  phone must scan the QR again next time.
