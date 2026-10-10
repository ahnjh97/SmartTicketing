#!/usr/bin/env bash
# Output goes only to SSH stdin, never to logs, artifacts, or command arguments.
set +x
set -euo pipefail
key=${TOSS_SECRET_KEY:-}
if [[ -z "${key//[[:space:]]/}" ]]; then
  echo 'Set TOSS_SECRET_KEY in GitHub Actions Secrets.' >&2
  exit 1
fi
# Compose receives the exported value in the remote deployment shell.
printf 'export TOSS_SECRET_KEY=%q\n' "$key"
