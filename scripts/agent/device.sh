#!/usr/bin/env bash
# Usage: scripts/agent/device.sh install|shot <name>|run <cmd...>
# Serialises access to the shared emulator/device (one agent at a time) with a lock directory.
set -euo pipefail
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
SERIAL=${ANDROID_SERIAL:-$($ADB devices | awk '/emulator-/{print $1; exit}')}
[ -z "$SERIAL" ] && { echo "no emulator running (ask the lead to boot one)"; exit 3; }
lock=/tmp/bestnote-device.lock
for _ in $(seq 1 120); do mkdir "$lock" 2>/dev/null && break; sleep 5; done
trap 'rmdir "$lock" 2>/dev/null' EXIT
case $1 in
  install) $ADB -s "$SERIAL" install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null
           $ADB -s "$SERIAL" shell am start -W -n app.bestnote.debug/app.bestnote.MainActivity >/dev/null; echo installed ;;
  shot)    mkdir -p /tmp/bestnote-shots; f=/tmp/bestnote-shots/${2:-shot}.png
           $ADB -s "$SERIAL" exec-out screencap -p > "$f"; echo "$f" ;;
  run)     shift; $ADB -s "$SERIAL" "$@" ;;
esac
