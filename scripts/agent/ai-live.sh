#!/usr/bin/env bash
# ONE real Smart Board call with the owner's DeepSeek key (limited budget: the owner asked for few calls).
# Usage: scripts/agent/ai-live.sh IMAGE [mode=equation] [template=study-columns] [notes=ar] [terms=he] [note="text to the AI"] [rtl=1] [model=deepseek-flash]
# Output in build/ai-live/<timestamp>/: notes.json (parsed elements), raw.txt (provider answer, key redacted), summary.txt.
set -euo pipefail
cfg="$HOME/.config/bestnote"
[ -f "$cfg/deepseek.env" ] || { echo "no key in $cfg/deepseek.env"; exit 1; }
used=$(cut -d/ -f1 "$cfg/ai-budget"); cap=$(cut -d/ -f2 "$cfg/ai-budget")
[ "$used" -lt "$cap" ] || { echo "AI budget used up ($used/$cap) — ask the lead"; exit 3; }
img=$(cd "$(dirname "$1")" && pwd)/$(basename "$1"); shift
cd "$(dirname "$0")/../.."
out="$PWD/build/ai-live/$(date +%Y%m%d-%H%M%S)"
{ echo "image=$img"; echo "out=$out"; for a in "$@"; do echo "$a"; done; } > "$cfg/live-request"
echo "$((used + 1))/$cap" > "$cfg/ai-budget"   # counted before the call: a failed call still costs
until [ "$(pgrep -f GradleWrapperMain | wc -l)" -le 1 ]; do sleep 15; done
JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17} ./gradlew -q :ai:vision-openai:testDebugUnitTest --tests '*LiveBoardTest*' --rerun || true
rm -f "$cfg/live-request"
echo "budget: $(cat "$cfg/ai-budget")"; cat "$out/summary.txt" 2>/dev/null || echo "no output (see Gradle log)"; echo "$out"
