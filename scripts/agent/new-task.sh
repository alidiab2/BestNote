#!/usr/bin/env bash
# Usage: scripts/agent/new-task.sh INK-03 highlighter
# Creates an isolated git worktree + branch for one task, so parallel agents never share files.
set -euo pipefail
id=$(echo "$1" | tr '[:upper:]' '[:lower:]'); slug=$2
root=$(git rev-parse --show-toplevel)
dir="$root/../BestNote-worktrees/$id"
branch="ws/$id-$slug"
git -C "$root" fetch -q origin main 2>/dev/null || true
git -C "$root" worktree add -q -b "$branch" "$dir" origin/main 2>/dev/null || git -C "$root" worktree add -q -b "$branch" "$dir" main
cp "$root/local.properties" "$dir/" 2>/dev/null || true
echo "$dir"
