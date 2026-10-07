#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  setup)
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y redis-server
    ;;
  start) ;;
  *) echo 'Use setup or start.' >&2; exit 2 ;;
esac
command -v redis-server >/dev/null || { echo 'Run local.cmd setup first.' >&2; exit 1; }
# Ubuntu's package binds Redis to loopback. Preserve its configuration and data.
service redis-server start
test "$(redis-cli -h 127.0.0.1 ping)" = PONG
redis-server --version
