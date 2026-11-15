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

1. Download `DuoLink Setup <version>.exe` from
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

1. Scan the QR from the installer's last page, or open
   [DuoLink.apk](https://github.com/aliiraza3321/DuoLink/releases/latest/download/DuoLink.apk).
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
Choosing one opens it in a window on the PC while the phone stays in your pocket.

**Optional — wireless debugging.** If you prefer the ADB route (or the phone app
isn't connected yet), open **Advanced · Wireless debugging** in the Apps rail:

1. On the phone: **Developer options → Wireless debugging → Pair device with
   pairing code**. Note the IP, the pairing port and the six-digit code.
2. In DuoLink paste the address and the code, press **Pair**.
3. Then paste the **device address** shown on the same screen and press
   **Connect**. The app list appears and stays cached until you press Refresh.

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
| Installer blocked | SmartScreen → **More info → Run anyway**. |

## Uninstall

- **Windows:** Settings → Apps → DuoLink → Uninstall. The branded uninstaller
  removes the app, its settings and its pairings. Files the phone sent you stay in
  `Downloads\DuoLink`.
- **Android:** long-press the app icon → App info → Uninstall.
- **Revoke one phone only:** **Settings → Trusted devices → Revoke trust**. The
  phone must scan the QR again next time.
