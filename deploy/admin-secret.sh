#!/usr/bin/env bash
# Output goes only to SSH stdin, never to a log, artifact, or remote command argument.
set +x
set -euo pipefail
password=${ADMIN_INITIAL_PASSWORD:-}
bytes=$(LC_ALL=C printf '%s' "$password" | wc -c)
if [[ -z "${password//[[:space:]]/}" ]] || (( ${#password} < 4 || bytes > 72 )); then
  echo 'Set ADMIN_INITIAL_PASSWORD in GitHub Actions Secrets (at least 4 characters, at most 72 UTF-8 bytes).' >&2
  exit 1
fi
# Bash quoting preserves dollar signs, quotes, spaces and newlines as literal data.
printf 'export ADMIN_INITIAL_PASSWORD=%q\n' "$password"
