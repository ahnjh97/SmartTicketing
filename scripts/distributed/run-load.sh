#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${BASE_URL:?Set BASE_URL to http://SERVER_A_IP:8088}"
: "${NODES:?Set NODES=1 or NODES=2 to label the actual running configuration}"
[[ "$NODES" == 1 || "$NODES" == 2 ]]
test -f .local/distributed/fixture.json
run="$(date -u +%Y%m%dT%H%M%SZ)-${NODES}nodes-${SUITE:-query}-${RATE:-10}rate"
for phase in warmup measurement; do
  duration="${DURATION:-3m}"
  if [[ "$phase" == warmup ]]; then duration=60s; fi
  docker run --rm --user "$(id -u):$(id -g)" \
    -v "$PWD/scripts/distributed:/scripts:ro" -v "$PWD/.local/distributed:/results" \
    -e BASE_URL -e NODES -e SUITE="${SUITE:-query}" -e RATE="${RATE:-10}" \
    -e MAX_VUS="${MAX_VUS:-100}" -e P95_MS="${P95_MS:-1000}" \
    -e DURATION="$duration" -e PHASE="$phase" -e RUN_ID="$run-$phase" \
    grafana/k6:2.3.0 run /scripts/load.js
done
echo "Results: .local/distributed/$run-measurement.json"
