#!/usr/bin/env bash
set -euo pipefail

: "${FRONTEND_IMAGE:?Set FRONTEND_IMAGE}"
name=frontend-check
port=${FRONTEND_CHECK_PORT:-8088}
work=$(mktemp -d)
cleanup() {
  result=$?
  docker logs "$name" || true
  docker rm -f "$name" || true
  rm -f "$work/index.html" "$work/runtime-config.js"
  rmdir "$work" || true
  exit "$result"
}
trap cleanup EXIT
docker run -d --name "$name" -p "127.0.0.1:$port:80" \
  -e VITE_KAKAO_MAP_JS_KEY=ciRuntimeKey "$FRONTEND_IMAGE"

# Published ports can reset a connection while the entrypoint is still starting
# Nginx. Retry all unsuccessful probes, with a fixed deadline and process check.
ready=false
for attempt in {1..30}; do
  if [[ "$(docker inspect --format '{{.State.Running}}' "$name")" != true ]]; then
    echo 'Frontend container exited before becoming ready' >&2
    exit 1
  fi
  if curl --fail --silent --show-error --connect-timeout 1 --max-time 3 \
       "http://127.0.0.1:$port/" -o "$work/index.html" &&
     curl --fail --silent --show-error --connect-timeout 1 --max-time 3 \
       "http://127.0.0.1:$port/runtime-config.js" -o "$work/runtime-config.js"; then
    ready=true
    break
  fi
  echo "Waiting for frontend HTTP readiness ($attempt/30)..."
  sleep 1
done
[[ "$ready" == true ]] || { echo 'Frontend HTTP readiness timed out' >&2; exit 1; }
grep -q '/runtime-config.js' "$work/index.html"
grep -q '"kakaoMapJsKey":"ciRuntimeKey"' "$work/runtime-config.js"
echo 'Frontend HTTP and runtime configuration verified'
