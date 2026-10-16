#!/usr/bin/env bash
# Publishes the debug APK built on this Mac to the PUBLIC releases repo, where the app's
# in-app updater looks (see docs/release.md). The debug keystore is the same on every build here,
# so the APK installs over the previous one and keeps notebooks.
#
# Usage: scripts/release.sh [--prerelease] [--notes "text" | --notes-file FILE] [--dry-run] [--skip-build]
set -euo pipefail
REPO=${BESTNOTE_RELEASES_REPO:-alidiab2/BestNote-releases}
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
cd "$(dirname "$0")/.."

prerelease=() notes="" dry=0 build=1
while [ $# -gt 0 ]; do
  case $1 in
    --prerelease) prerelease=(--prerelease) ;;
    --notes) notes=$2; shift ;;
    --notes-file) notes=$(cat "$2"); shift ;;
    --dry-run) dry=1 ;;
    --skip-build) build=0 ;;
    *) echo "unknown option $1"; exit 2 ;;
  esac
  shift
done

[ -z "$(git status --porcelain)" ] || { echo "working tree not clean: the version would not match the commit"; exit 1; }
[ "$(git rev-parse --is-shallow-repository)" = false ] || { echo "shallow clone: versionCode would be wrong"; exit 1; }

[ $build = 1 ] && ./gradlew :app:assembleDebug -q

apk=app/build/outputs/apk/debug/app-debug.apk
[ -f "$apk" ] || { echo "no $apk"; exit 1; }

# Read the real values out of the APK so the release always matches what is inside it.
sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
analyzer=$(ls "$sdk"/cmdline-tools/*/bin/apkanalyzer 2>/dev/null | tail -1 || true)
if [ -n "$analyzer" ]; then
  code=$("$analyzer" manifest version-code "$apk")
  name=$("$analyzer" manifest version-name "$apk")
else
  code=$(git rev-list --count HEAD)
  name="0.1.$code-$(git rev-parse --short=7 HEAD)"
fi
tag="v$name"
asset="BestNote-$name.apk"
cp "$apk" "build/$asset" 2>/dev/null || { mkdir -p build && cp "$apk" "build/$asset"; }

body="${notes:-$(git log -10 --pretty='- %s' | grep -v '^- Merge')}

versionCode: $code"

echo "release $tag  versionCode=$code  repo=$REPO  ${prerelease[*]:-stable}"
echo "$body"
[ $dry = 1 ] && { echo "(dry run: nothing published)"; exit 0; }

gh release create "$tag" "build/$asset" --repo "$REPO" --title "BestNote $name" --notes "$body" ${prerelease[@]+"${prerelease[@]}"}
echo "published: https://github.com/$REPO/releases/tag/$tag"
