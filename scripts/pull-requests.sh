#!/usr/bin/env bash
# Pulls the app's feature requests / bug reports into docs/requests/ so they can be read and fixed.
#   scripts/pull-requests.sh            open issues from GitHub (needs `gh auth login`), with their files
#   scripts/pull-requests.sh --adb      the device's local copy instead (USB debugging), for requests not sent yet
# Env: REQUESTS_REPO=owner/name (default alidiab2/BestNote-requests)
# Output: docs/requests/requests.md and docs/requests/files/*. Open the screenshots to see the problem.
# Fix an item, then close its issue (a commit saying "Fixes <repo>#N" does it) and the app marks it done.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/docs/requests"
mkdir -p "$out/files"

if [ "${1:-}" = "--adb" ]; then
  ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
  [ -x "$ADB" ] || ADB=$(command -v adb)
  for pkg in app.bestnote app.bestnote.debug; do
    src=/sdcard/Android/data/$pkg/files/requests
    if "$ADB" shell "[ -f $src/requests.json ]" 2>/dev/null; then
      "$ADB" pull "$src/requests.json" "$out/requests.json" >/dev/null
      "$ADB" pull "$src/requests.md" "$out/requests.md" >/dev/null
      "$ADB" pull "$src/files/." "$out/files/" >/dev/null 2>&1 || true
      echo "pulled from $pkg -> $out"
      grep -E '^(### |[0-9]+ open)' "$out/requests.md"
      exit 0
    fi
  done
  echo "no requests found on the device (connect it with USB debugging and save a request first)"; exit 1
fi

repo=${REQUESTS_REPO:-alidiab2/BestNote-requests}
issues=$(gh api "repos/$repo/issues?labels=app-request&state=open&per_page=100" --paginate)
REPO=$repo OUT=$out python3 - "$issues" <<'PY'
import json, os, re, subprocess, sys
repo, out = os.environ["REPO"], os.environ["OUT"]
raw = sys.argv[1]
# --paginate prints one JSON array per page
items, dec, i = [], json.JSONDecoder(), 0
while i < len(raw):
    while i < len(raw) and raw[i].isspace(): i += 1
    if i >= len(raw): break
    page, i = dec.raw_decode(raw, i); items += page
items = [x for x in items if "pull_request" not in x]
lines = [f"# BestNote requests ({repo})", "", f"{len(items)} open. Newest first. Screenshots and other files are in docs/requests/files/.", ""]
for it in sorted(items, key=lambda x: -x["number"]):
    body = it.get("body") or ""
    def fetch(m):
        bang, label, url = m.group(1), m.group(2), m.group(3)
        mm = re.match(rf"https://github.com/{re.escape(repo)}/blob/[^/]+/(.+?)(\?raw=true)?$", url)
        if not mm: return m.group(0)
        path = mm.group(1)
        name = f"{it['number']}-" + path.replace("/", "_")
        dest = os.path.join(out, "files", name)
        if not os.path.exists(dest):
            data = subprocess.run(["gh", "api", f"repos/{repo}/contents/{path}", "-H", "Accept: application/vnd.github.raw"], capture_output=True)
            if data.returncode == 0: open(dest, "wb").write(data.stdout)
        return f"{bang}[{label}](files/{name})"
    body = re.sub(r"(!?)\[([^\]]*)\]\(([^)]+)\)", fetch, body)
    labels = ", ".join(l["name"] for l in it["labels"] if l["name"] != "app-request")
    lines += [f"## #{it['number']}: {it['title']}", "", f"- type: {labels}", f"- url: {it['html_url']}", f"- opened: {it['created_at']}", "", body.strip(), ""]
open(os.path.join(out, "requests.md"), "w").write("\n".join(lines))
print(f"{len(items)} open request(s) -> {out}/requests.md")
for it in sorted(items, key=lambda x: -x["number"]): print(f"  #{it['number']} {it['title']}")
PY
