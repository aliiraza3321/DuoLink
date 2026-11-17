#!/usr/bin/env bash
# Installs the debug APK, starts the app-window shell helper over adb and checks
# that real frames come back for one app. Usage: tools/appwin-run.sh <package>
set -u
cd "$(dirname "$0")/.." || exit 1

ADB="${LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe"
APK="android/app/build/outputs/apk/debug/app-debug.apk"
PKG_APP="${1:-com.whatsapp}"
WAIT="${2:-120}"

# `adb wait-for-device` errors out when two transports are attached, so poll
# instead. ADB_SERIAL picks one explicitly.
SERIAL="${ADB_SERIAL:-}"
deadline=$((SECONDS + WAIT))
while [ $SECONDS -lt $deadline ]; do
  if [ -n "$SERIAL" ]; then
    "$ADB" -s "$SERIAL" get-state > /dev/null 2>&1 && break
    SERIAL="${ADB_SERIAL:-}"
  else
    SERIAL=$("$ADB" devices | awk 'NR > 1 && $2 == "device" {print $1; exit}')
    [ -n "$SERIAL" ] && break
  fi
  sleep 1
done
if [ -z "$SERIAL" ] || ! "$ADB" -s "$SERIAL" get-state > /dev/null 2>&1; then
  echo "NO_DEVICE"
  exit 3
fi
ADB_DEV=("$ADB" -s "$SERIAL")
echo "device: $SERIAL"

"${ADB_DEV[@]}" install -r "$APK" | tail -1
BASE=$("${ADB_DEV[@]}" shell pm path com.phonepc.pairing | tr -d '\r' | sed 's/package://')
echo "apk on device: $BASE"

"${ADB_DEV[@]}" shell pkill -f com.phonepc.pairing.apps.ShellHost 2>/dev/null
rm -f .shell-host.log
MSYS_NO_PATHCONV=1 "${ADB_DEV[@]}" shell "CLASSPATH=$BASE app_process /system/bin com.phonepc.pairing.apps.ShellHost tok123" > .shell-host.log 2>&1 &
HOST_PID=$!
for _ in $(seq 1 40); do
  PORT=$(sed -n 's/^ready \([0-9]*\).*/\1/p' .shell-host.log | head -1)
  [ -n "$PORT" ] && break
  sleep 0.5
done
if [ -z "${PORT:-}" ]; then
  echo "helper never said ready; log:"; cat .shell-host.log; kill $HOST_PID 2>/dev/null; exit 4
fi
echo "helper ready on $PORT"
MSYS_NO_PATHCONV=1 "${ADB_DEV[@]}" forward "tcp:$PORT" "tcp:$PORT" > /dev/null

node tools/appwin-check.mjs "$PORT" "$PKG_APP" 540 1080 240 tok123 "${WAIT_MS:-6000}"; RC=$?
echo "--- helper log"
cat .shell-host.log
kill $HOST_PID 2>/dev/null
MSYS_NO_PATHCONV=1 "${ADB_DEV[@]}" forward --remove "tcp:$PORT" 2>/dev/null
exit $RC
