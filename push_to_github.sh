#!/usr/bin/env bash
set -e
GH_USER="${1:-mr03k}"
REPO_NAME="${2:-motoobd-android10-scanner}"
VISIBILITY="${3:---public}"
REMOTE_URL="https://github.com/${GH_USER}/${REPO_NAME}.git"

if [ ! -d ".git" ]; then
  git init
  git branch -M main
fi

git add .
git commit -m "Initial commit: MotoOBD Telemetry Pro (Android 10 OBD-II Scanner, Modern UI & Handoff Docs)" || true

if command -v gh >/dev/null 2>&1; then
  gh repo create "${GH_USER}/${REPO_NAME}" "$VISIBILITY" --source=. --remote=origin --push
else
  git remote remove origin 2>/dev/null || true
  git remote add origin "$REMOTE_URL"
  git push -u origin main
fi
