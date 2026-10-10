#!/usr/bin/env bash
set -euo pipefail
project_root=$(cd -- "$(dirname -- "$0")/../.." && pwd)
exec 9>/tmp/smartticketing-benchmark.lock
flock -n 9 || { echo 'Another benchmark is running.' >&2; exit 1; }
exec python3 "$project_root/scripts/benchmark/run-site.py" "$@"
