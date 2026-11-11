#!/usr/bin/env bash
# Downloads the Monaco editor (the editor of VS Code) into app/src/main/assets/monaco.
# Runs in the GitHub Action. For a local build run it once: bash scripts/fetch-monaco.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
cd "$TMP"
npm pack monaco-editor@0.52.2 --silent
tar xzf monaco-editor-*.tgz
rm -rf "$ROOT/app/src/main/assets/monaco"
mkdir -p "$ROOT/app/src/main/assets/monaco"
cp -r package/min/vs "$ROOT/app/src/main/assets/monaco/vs"
echo "Monaco installed: $(du -sh "$ROOT/app/src/main/assets/monaco" | cut -f1)"
