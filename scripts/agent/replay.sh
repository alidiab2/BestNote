#!/usr/bin/env bash
# Usage: scripts/agent/replay.sh <corpus-file.json> [pen] [color] [extra am-broadcast args...]
#   pen:   fountain | ballpoint | brush | pencil | highlighter   (default fountain)
#   color: #RRGGBB / #AARRGGBB                                    (default: the tool's colour)
#   extra: e.g. --ez clear true --ef size 8 --ef batch 0 --ef slow 2
#          --ef sensitivity 0 --ei stabilization 100   (INK-04/05 tuning for this replay)
# Replays a handwriting stroke file (tools/handwriting) through the debug build's real touch
# pipeline on the shared emulator, and waits until it has finished. A notebook must be open.
# PKG=<applicationId> targets a side-by-side debug build (default app.bestnote.debug).
# SHOT=<name> takes the screenshot (/tmp/bestnote-shots/<name>.png) before the device lock is released,
# so another agent can't bring its own app to the front in between.
set -euo pipefail
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
SERIAL=${ANDROID_SERIAL:-$($ADB devices | awk '/emulator-/{print $1; exit}')}
[ -z "$SERIAL" ] && { echo "no emulator running (ask the lead to boot one)"; exit 3; }
case $SERIAL in emulator-*) ;; *) echo "refusing to replay on a non-emulator device ($SERIAL)"; exit 3 ;; esac
[ $# -ge 1 ] || { sed -n 2,8p "$0"; exit 2; }
file=$1; pen=${2:-fountain}; color=${3:-}; shift $(( $# >= 3 ? 3 : $# ))
[ -f "$file" ] || { echo "no such file: $file"; exit 2; }
PKG=${PKG:-app.bestnote.debug}   # e.g. PKG=app.bestnote.debug.qaink for a -PdebugIdSuffix build
name=$(basename "$file")
a() { $ADB -s "$SERIAL" "$@"; }

lock=/tmp/bestnote-device.lock
for _ in $(seq 1 120); do mkdir "$lock" 2>/dev/null && break; sleep 5; done
trap 'rmdir "$lock" 2>/dev/null' EXIT

# Bring our app back to the front (other agents share this emulator); the open notebook survives.
a shell am start -n "$PKG/app.bestnote.MainActivity" >/dev/null 2>&1 || true
sleep 1
a push "$file" "/data/local/tmp/$name" >/dev/null
a shell "run-as $PKG mkdir -p files/replay && run-as $PKG cp /data/local/tmp/$name files/replay/$name && run-as $PKG sh -c 'echo queued > files/replay/status'"
args=(--es file "$name" --es pen "$pen")
[ -n "$color" ] && args+=(--es color "$color")
a shell am broadcast -a app.bestnote.debug.REPLAY -p "$PKG" "${args[@]}" "$@" >/dev/null

# Expected duration from the file, plus slack; poll the status file.
dur=$(python3 -I -c 'import json,sys; print(int(json.load(open(sys.argv[1]))["durationMs"]/1000)+30)' "$file" 2>/dev/null || echo 300)
for _ in $(seq 1 $((dur * 2))); do
  st=$(a shell run-as $PKG cat files/replay/status 2>/dev/null | tr -d '\r')
  case $st in
    done*) echo "$st"
           if [ -n "${SHOT:-}" ]; then sleep 0.8; mkdir -p /tmp/bestnote-shots
             a exec-out screencap -p > "/tmp/bestnote-shots/$SHOT.png"; echo "/tmp/bestnote-shots/$SHOT.png"; fi
           exit 0 ;;
    error*) echo "$st"; exit 1 ;;
    queued*) ;;  # receiver not reached (no resumed activity?)
  esac
  sleep 0.5
done
echo "timeout (status: ${st:-none})"; exit 1
