#!/usr/bin/env bash
set -euo pipefail

revision=${1:?Pass the tested commit SHA}
[[ "$revision" =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid commit SHA' >&2; exit 1; }
artifact_id=${2:?Pass the workflow run ID and attempt}
images_sha256=${3:?Pass the verified image archive SHA256}
backend_fingerprint=${4:?Pass the verified backend fingerprint}
frontend_fingerprint=${5:?Pass the verified frontend fingerprint}
[[ "$artifact_id" =~ ^[0-9]+-[0-9]+$ ]] || { echo 'Invalid artifact ID' >&2; exit 1; }
[[ "$images_sha256" =~ ^[0-9a-f]{64}$ ]] || { echo 'Invalid archive SHA256' >&2; exit 1; }
[[ "$backend_fingerprint" =~ ^[0-9a-f]{64}$ && "$frontend_fingerprint" =~ ^[0-9a-f]{64}$ ]] || { echo 'Invalid image fingerprint' >&2; exit 1; }
declare -F image_fingerprint >/dev/null || { echo 'Load image-fingerprint.sh before deploy.sh' >&2; exit 1; }
artifact="$HOME/.smartticketing-artifacts/$artifact_id.tar.gz"
cd "$HOME/SmartTicketing"

# Also protects the server if a previous SSH session outlives its Actions job.
exec 9> "$HOME/.smartticketing-deploy.lock"
flock -w 900 9

diagnostics() {
  result=$?
  rm -f -- "$artifact" || true
  if (( result != 0 )); then
    echo "Deployment failed (exit $result). Container status and recent logs:"
    docker compose ps -a || true
    docker compose logs --no-color --tail=100 mysql redis backend frontend nginx || true
  fi
  exit "$result"
}
trap diagnostics EXIT

# Reject missing or incomplete uploads before changing the deployed checkout.
echo 'Verifying the uploaded image archive...'
printf '%s  %s\n' "$images_sha256" "$artifact" | sha256sum --check

git fetch origin main
# Never report success for a different revision than the one CI tested.
if [[ "$(git rev-parse origin/main)" != "$revision" ]]; then
  echo 'main has moved. Run the workflow for the latest main commit.' >&2
  exit 1
fi
echo 'Loading the images validated by CI...'
docker load --input "$artifact" < /dev/null
export BACKEND_IMAGE="smartticketing-backend:$revision"
export FRONTEND_IMAGE="smartticketing-frontend:$revision"
actual_backend=$(image_fingerprint "$BACKEND_IMAGE")
actual_frontend=$(image_fingerprint "$FRONTEND_IMAGE")
[[ "$actual_backend" == "$backend_fingerprint" ]] || { echo "Backend content/config mismatch: expected=$backend_fingerprint actual=$actual_backend" >&2; exit 1; }
[[ "$actual_frontend" == "$frontend_fingerprint" ]] || { echo "Frontend content/config mismatch: expected=$frontend_fingerprint actual=$actual_frontend" >&2; exit 1; }

git checkout main
git reset --hard "$revision"

# Validate without printing resolved secrets. Never rebuild or pull application images.
docker compose config --quiet
docker compose up -d --no-build --pull never --wait --wait-timeout 600 mysql redis backend frontend

# Recreate the proxy so it resolves the new backend/frontend container addresses.
# Certificate/config errors are caught before replacing the existing proxy.
docker compose run --rm -T --no-deps nginx nginx -t < /dev/null
docker compose up -d --no-deps --force-recreate nginx
docker compose exec -T nginx nginx -t < /dev/null
docker compose ps
