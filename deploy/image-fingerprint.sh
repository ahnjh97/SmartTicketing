#!/usr/bin/env bash

# Image .Id can be a config, manifest, or index digest depending on the Docker
# image store. Compare layer content and runtime configuration instead.
image_fingerprint() {
  local metadata field format
  format='{{json .RootFS.Layers}}|{{json .Os}}|{{json .Architecture}}|{{json (or .Variant "")}}'
  for field in User Env Entrypoint Cmd WorkingDir ExposedPorts Volumes Labels StopSignal Healthcheck Shell OnBuild ArgsEscaped; do
    # Missing/null/empty values have the same runtime meaning for these fields.
    format+="|{{json (or .Config.$field \"\")}}"
  done
  metadata=$(docker image inspect --format "$format" "${1:?Pass an image reference}") || return
  [[ -n "$metadata" ]] || { echo 'Empty image metadata' >&2; return 1; }
  printf '%s\n' "$metadata" | sha256sum | cut -d ' ' -f 1
}

# Also supports concatenation before deploy.sh in the SSH stdin script.
if [[ "${BASH_SOURCE[0]:-}" == "$0" ]]; then
  set -euo pipefail
  image_fingerprint "$@"
fi
