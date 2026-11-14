# Phone ↔ PC Pairing & Two-Way Control Suite

A production-grade, secure local Wi-Fi pairing and bi-directional control system featuring a **Windows Electron Desktop Application** and a **Native Android Application (Kotlin, Jetpack Compose, Material 3)**.

---

## 5-Line Architecture Plan

1. **Local LAN TLS Host**: The Windows Electron main process starts an HTTPS/WSS server on port 3000 bound to the laptop's private LAN IPv4 with a self-signed TLS certificate.
2. **Ephemeral Pinning QR**: The PC displays an auto-refreshing QR encoding a single-use 32+ character crypto token, the server IP/port, and the certificate's SHA-256 fingerprint (`phonepair://pair?host=...&port=3000&token=...&fp=...`).
3. **CameraX Scan & Mutual Approval**: The Android app scans the QR using CameraX + ML Kit, pins the exact TLS certificate hash (eliminating trust-on-first-use gaps), claims the burned token, and presents matching 6-digit confirmation codes on both screens before PC user approval.
4. **Keystore-Backed Reconnects**: Upon approval, a 256-bit session secret is stored in Android Keystore-backed `EncryptedSharedPreferences`; reconnects authenticate using HMAC-SHA256 challenge-responses without re-scanning QR codes.
5. **Two-Way Control & Media**: Phone acts as a multi-touch trackpad/keyboard via `@nut-tree-fork/nut-js` (with rate limiting and an emergency hotkey kill-switch); PC mirrors phone screen at 720p 15fps via `MediaProjection` and dispatches simulated gestures via an Android `AccessibilityService`.

---

## Phases Overview

- **Phase 1 (Core - Fully Built & Test-Ready)**:
  - Windows Electron app with system tray icon, auto LAN IP detection (bypassing WSL/Hyper-V/VPNs), self-signed TLS cert generation, and WSS server on port 3000.
  - Ephemeral single-use pairing tokens (2-minute TTL, burned on claim).
  - 6-digit confirmation code verification on both screens with PC "Approve" button.
  - Session ID + 256-bit secret stored in Android Keystore.
  - Android Foreground Service with ongoing notification, partial wake lock, Wi-Fi high-performance lock, and exponential backoff auto-reconnects.
  - Persistent "Remember this phone" trusted-devices list on PC with one-click revocation.
- **Phase 2 (Chat, Files & Clipboard - Fully Structured)**:
  - Two-way real-time text chat over local Wi-Fi.
  - Bi-directional file transfer (up to 5GB) with 64KB chunking, progress bar, cancel, and path traversal protection saving to `./received` on PC and MediaStore on Android.
  - Explicit clipboard push (phone → PC via button/share-sheet/notification, and PC → phone).
- **Phase 3 (Phone Controls PC - Fully Structured)**:
  - Touchpad screen: drag to move, tap to left click, double-tap, long-press to right click, two-finger scroll.
  - Direct keyboard typing and special keys (Enter, Esc, Tab, Space, Backspace).
  - Explicit desktop permission toggle (off by default).
  - Rate limiting (60 events/sec), coordinate clamping, and global emergency kill switch: **`Ctrl + Alt + Shift + Q`**.
- **Phase 4 (PC Controls Phone - Fully Structured)**:
  - Live screen mirroring via Android `MediaProjection` (JPEG, max 720p, 10–15 fps, adaptive backpressure frame dropping).
  - Interactive canvas in Electron: click/drag translated to normalized coordinates (0..1).
  - Android `AccessibilityService` dispatches gestures (`performTap`, `performSwipe`) and types text into focused inputs (`ACTION_SET_TEXT`).
  - Remote Android navigation buttons: Back, Home, Recents.

---

## Wire Protocol Specification

Every WebSocket message is sent as a JSON string wrapped in a strict, versioned envelope:

```json
{
  "v": 1,
  "type": "MESSAGE_TYPE",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "ts": 1729245127000,
  "payload": { ... }
}
```

### Message Types & Payloads

| Message Type | Direction | Payload Schema | Description |
|---|---|---|---|
| `PAIR_REQUEST` | Phone → PC | `{"token": string, "deviceName": string, "deviceId": string}` | Claims one-time pairing token. |
| `PAIR_CONFIRM_CODE` | PC → Phone | `{"confirmationCode": string, "pendingId": string}` | Sends 6-digit code for user verification. |
| `PAIR_RESULT` | PC → Phone | `{"success": bool, "sessionId"?: string, "sessionSecret"?: string, "deviceName"?: string, "error"?: string}` | Approval confirmation with 256-bit session secret. |
| `RECONNECT_CHALLENGE_REQUEST` | Phone → PC | `{"deviceId": string, "sessionId": string}` | Requests reconnect challenge nonce. |
| `RECONNECT_CHALLENGE_RESPONSE` | PC → Phone | `{"challengeNonce": string}` | 32-byte crypto nonce from server. |
| `RECONNECT_CHALLENGE_RESPONSE` | Phone → PC | `{"deviceId": string, "proof": string}` | Proof: `HMAC-SHA256(challengeNonce, sessionSecret)`. |
| `RECONNECT_RESULT` | PC → Phone | `{"success": bool, "sessionId"?: string, "permissions"?: object, "error"?: string}` | Restores active session upon valid HMAC. |
| `DISCONNECT` | Either | `{}` | Gracefully ends session. |
| `HEARTBEAT_PING` / `_PONG` | Either | `{}` | Ping/pong keepalive every 15 seconds. |
| `CHAT_MESSAGE` | Either | `{"text": string, "senderName": string}` | Two-way text chat. |
| `CLIPBOARD_PUSH` | Either | `{"text": string}` | Clipboard content push. |
| `FILE_META` | Either | `{"transferId": string, "filename": string, "totalSize": number}` | Declares filename and file size (max 5GB). |
| `FILE_CHUNK` | Either | `{"transferId": string, "chunkBase64": string}` | 64KB base64 file chunk. |
| `FILE_ACK` | Either | `{"transferId": string, "success": bool, "error"?: string}` | Acknowledges transfer initialization. |
| `FILE_CANCEL` | Either | `{"transferId": string}` | Aborts transfer and deletes partial file. |
| `PC_MOUSE_MOVE` | Phone → PC | `{"dx": number, "dy": number}` | Relative trackpad cursor movement. |
| `PC_MOUSE_CLICK` | Phone → PC | `{"button": "left"|"right"|"middle", "double"?: bool}` | Mouse click action. |
| `PC_MOUSE_SCROLL` | Phone → PC | `{"deltaY": number}` | Two-finger scroll delta. |
| `PC_KEY_INPUT` | Phone → PC | `{"text"?: string, "specialKey"?: string}` | Types text or presses special key (e.g. ENTER). |
| `PHONE_SCREEN_START` / `_STOP`| PC → Phone | `{}` | Requests start/stop of screen stream. |
| `PHONE_SCREEN_FRAME` | Phone → PC | `{"jpegBase64": string, "width": number, "height": number}` | Live 720p screen frame. |
| `PHONE_INPUT_GESTURE` | PC → Phone | `{"type": "TAP"|"SWIPE", "startX": float, "startY": float, "endX"?: float, "endY"?: float}` | Normalized (0..1) touch gesture. |
| `PHONE_KEY_ACTION` | PC → Phone | `{"action": "BACK"|"HOME"|"RECENTS"}` | Dispatches Android global navigation. |

---

## Windows Desktop Setup & Build

### Prerequisites
- Windows 10 or 11 (64-bit)
- Node.js 18.x or 20.x LTS ([nodejs.org](https://nodejs.org))
- Visual Studio Build Tools (C++ Desktop development workload for native `@nut-tree-fork/nut-js` compilation)

### Quick Start (Development)
```bash
# Navigate to desktop app directory
cd desktop

# Install dependencies (exact versions pinned)
npm install

# If native nut-js bindings need rebuilding for your Electron version:
npm run rebuild-native

# Start the desktop application
npm start
```

### Building the Windows NSIS Installer (.exe)
```bash
# In /desktop directory:
npm run dist
```
The installer executable will be generated in `desktop/dist/PhonePC Control Suite Setup 1.0.0.exe`.

### Windows Firewall Configuration
Run PowerShell **as Administrator** to open port 3000 on your Private Wi-Fi network:

```powershell
New-NetFirewallRule -DisplayName "PhonePC Local WSS Server" -Direction Inbound -LocalPort 3000 -Protocol TCP -Action Allow -Profile Private
```

To verify the rule:
```powershell
Get-NetFirewallRule -DisplayName "PhonePC Local WSS Server" | Format-Table Name, DisplayName, Enabled, Direction, Action, Profile
```

---

## Android App Setup & Installation

### Prerequisites
- Android Studio Ladybug (2024.2+) or Hedgehog/Iguana
- JDK 17 (set in `Project Structure > SDK Location > Gradle Settings > Gradle JDK`)
- Android device running Android 8.0 (API 26) through Android 14 (API 34)

### Building the Debug APK
Open a terminal in the `/android` directory:

```bash
# On Linux / macOS:
./gradlew assembleDebug

# On Windows:
.\gradlew.bat assembleDebug
```

The APK will be output at:
`android/app/build/outputs/apk/debug/app-debug.apk`

### Installing the APK on Phone
1. Connect phone via USB with **USB Debugging** enabled in Developer Options.
2. Install via ADB:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. Or copy `app-debug.apk` directly to phone storage and install it via Files app. When prompted, enable **"Install unknown apps"** in Android Settings.

---

## Troubleshooting Guide

### 1. Phone cannot connect to PC
- **Private Wi-Fi Only**: Ensure both phone and laptop are connected to the exact same Wi-Fi SSID.
- **Router Client Isolation (AP Isolation)**: Some public or guest Wi-Fi networks (e.g. university, hotel, cafe) isolate wireless clients from communicating with each other. Disable "AP Isolation" or "Client Isolation" in your home router settings, or test using your phone's Wi-Fi hotspot.
- **Firewall Blocking**: Execute the PowerShell firewall command above to ensure port 3000 is open.

### 2. Wrong IP picked on PC
- If you have Docker, WSL, Hyper-V, or VPN/Tailscale adapters active, the desktop app automatically ignores them. If you have multiple physical Wi-Fi/Ethernet adapters, you can override the IP manually by setting an environment variable before launching:
  ```cmd
  set LAN_IP=192.168.1.50
  npm start
  ```

### 3. QR Token Expired
- Pairing tokens expire after 2 minutes for security. The desktop window displays a live countdown timer and auto-refreshes. If needed, click **"Refresh QR"** in the desktop UI.

### 4. Certificate Fingerprint Mismatch
- If you re-installed the desktop app or cleared `%APPDATA%/phonepc-desktop/certs`, a new TLS certificate was generated. Re-scan the new QR code so your phone updates its pinned SHA-256 fingerprint.

### 5. App Killed in Background by OEM Battery Policies
Aggressive battery-saver managers terminate background services when the screen turns off:
- **Samsung One UI**: *Settings > Apps > PhonePC Pair > Battery > Select "Unrestricted"*. Also check *Settings > Battery > Background usage limits > Never auto-sleeping apps*.
- **Xiaomi (MIUI / HyperOS)**: *Settings > Apps > Manage Apps > PhonePC Pair > Battery Saver > Select "No restrictions"*. Enable *Autostart*.
- **Oppo / Realme (ColorOS)**: *Settings > Battery > App Battery Management > PhonePC Pair > Allow background activity & Allow auto-launch*.
- **Vivo (FuntouchOS)**: *Settings > Battery > High background power consumption > Allow PhonePC Pair*.
- **Infinix / Tecno (HiOS / XOS)**: *Phone Master > Auto-start management > Enable PhonePC Pair*. Also disable "Smart Power Saver" for the app.

### 6. Accessibility Service Disabled
- For Phase 4 (PC controlling phone), Android requires manual user authorization:
  *Open Android Settings > Accessibility > Downloaded apps / Installed services > PhonePC Pair > Toggle ON*.

### 7. Screen Share Appears Blank or Black
- Android security policy flags banking apps, password managers, and DRM video players with `FLAG_SECURE`. MediaProjection is legally restricted from capturing these windows; they intentionally render as black rectangles for privacy.

---

## Automated Tests & Verification Report

### What Was Automated and Verified:
1. **Desktop Smoke Test Suite (`desktop/test/smoke.test.js`)**:
   - **Network filter**: Tested that `192.168.x.x`, `10.x.x.x`, `172.16-31.x.x`, and `127.0.0.1` are accepted, while public IPs like `8.8.8.8` are rejected.
   - **Protocol envelope**: Tested version matching (`v: 1`), envelope parsing, and strict rejection of unknown message types.
   - **Certificate & Fingerprint**: Generated 2048-bit self-signed RSA cert with `selfsigned` and verified that the computed 64-character SHA-256 fingerprint matches the DER bytes.
   - **Pairing token lifecycle**: Verified token generation, claim verification, immediate token burning upon claim (preventing replay attacks), 6-digit confirmation code generation, and approval issuance of `sessionId` + 256-bit secret.
   - **Reconnect HMAC challenge-response**: Verified that `HMAC-SHA256(challengeNonce, sessionSecret)` passes constant-time verification while fraudulent proofs fail.
   - **Path traversal defense & file chunking**: Verified that malicious names like `../../../../calc.exe` are sanitized to `calc.exe`, and chunked assembly writes complete files to `./received`.
   - **Execution output**: 100% passed (exited with code 0).

2. **Android Unit Tests (`TokenParserTest.kt`, `ProtocolTest.kt`)**:
   - URI parameter extraction, port bounds checking (1..65535), private IP gatekeeping, token length (>= 32), fingerprint length (64 hex characters), and HMAC-SHA256 calculation.

### What the User Must Verify on Hardware:
- Physical Wi-Fi network routing between laptop and phone.
- CameraX barcode scanning against your laptop monitor.
- Mouse movement on Windows desktop via `@nut-tree-fork/nut-js` (requires OS permission for accessibility/input simulation).
- MediaProjection screen cast consent dialog on your specific Android device.


## Trackpad & PC Screen control (current build)

**Trackpad (relative, like a laptop)**
- Drag one finger: the cursor moves by the finger's delta (never teleports); a sensitivity slider (0.6x-3x) and an Accel on/off toggle tune the feel.
- Tap = left click, double-tap = double click, long press (no movement) = right click with haptics, two-finger tap = right click.
- Two fingers slide = smooth vertical scroll; **Drag** toggle holds the left button for drag-and-drop.
- Deltas reset when a finger lifts or a second finger joins, so the cursor never jumps.

**PC Screen view (absolute, like a touchscreen)**
- One gesture recognizer owns the viewport: tap places the cursor and clicks, drag re-aims continuously, two fingers scroll, Drag toggle supports drag-and-drop.
- Touch mapping uses the exact visible picture rectangle for Fit and Fill, so letterboxing and any PC aspect ratio map correctly.
- The PC reports its real primary-display size; cursor positions are clamped to it.

**Stream telemetry**
- The **Stats** button in Control Phone shows measured fps, Mbps and link ping over the live picture.
