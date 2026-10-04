#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/image-fingerprint.sh"

# Exercise both Docker image stores in isolated CI daemons before deployment.
# Nothing is installed or reconfigured on the production host.
for containerd_store in false true; do
  (
    name="image-compat-$containerd_store"
    trap 'command docker logs "$name"; command docker rm -fv "$name"' EXIT
    command docker run -d --privileged --name "$name" \
      -e DOCKER_TLS_CERTDIR= docker:29-dind \
      --feature "containerd-snapshotter=$containerd_store"
    ready=false
    for attempt in {1..30}; do
      if command docker exec "$name" docker info >/dev/null 2>&1; then
        ready=true
        break
      fi
      sleep 1
    done
    [[ "$ready" == true ]] || { echo "Compatibility daemon did not start: $name" >&2; exit 1; }
    command docker exec -i "$name" docker load < build/images/images.tar.gz
    docker() { command docker exec "$name" docker "$@"; }
    actual_backend=$(image_fingerprint "$BACKEND_IMAGE")
    actual_frontend=$(image_fingerprint "$FRONTEND_IMAGE")
    test "$actual_backend" = "$(cat build/images/backend.fingerprint)"
    test "$actual_frontend" = "$(cat build/images/frontend.fingerprint)"
    echo "Image content/config verified with containerd-snapshotter=$containerd_store"
  )
done
