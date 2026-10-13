#!/usr/bin/env bash
# Output goes only to SSH stdin, never to logs, artifacts, or command arguments.
set +x
set -euo pipefail
for name in MAIL_HOST MAIL_USERNAME MAIL_PASSWORD MAIL_FROM; do
  value=${!name:-}
  if [[ -z "${value//[[:space:]]/}" || "$value" == *$'\n'* || "$value" == *$'\r'* ]]; then
    echo "Set $name in GitHub Actions Secrets (non-empty, single line)." >&2
    exit 1
  fi
done
port=${MAIL_PORT:-587}
if [[ ! "$port" =~ ^[0-9]{1,5}$ ]] || (( 10#$port < 1 || 10#$port > 65535 )); then
  echo 'Set MAIL_PORT to a valid SMTP port in GitHub Actions Variables.' >&2
  exit 1
fi
# Validate every field before emitting any credentials. Bash quoting treats
# dollar signs, quotes and command substitutions in passwords as literal data.
for name in MAIL_HOST MAIL_USERNAME MAIL_PASSWORD MAIL_FROM; do
  printf 'export %s=%q\n' "$name" "${!name}"
done
printf 'export MAIL_PORT=%q\n' "$port"
printf 'export MAIL_SMTP_AUTH=true\nexport MAIL_STARTTLS=true\n'
