#!/usr/bin/env bash
# Usage: scripts/agent/verify.sh :engine:ink :feature:editor
# Builds and unit-tests ONLY the given modules (plus :app:assembleDebug when --app is passed),
# with memory limits so several agents and an emulator fit on an 8 GB machine.
set -euo pipefail
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
tasks=()
for a in "$@"; do
  case $a in
    --app) tasks+=(":app:assembleDebug") ;;
    :*) tasks+=("$a:test") ;;
  esac
done
[ ${#tasks[@]} -eq 0 ] && { echo "usage: verify.sh :module [...] [--app]"; exit 2; }
nice -n 10 ./gradlew "${tasks[@]}" -q \
  -Dorg.gradle.jvmargs="-Xmx1536m -XX:+UseParallelGC" \
  -Dorg.gradle.workers.max=2 \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.daemon.idletimeout=180000
