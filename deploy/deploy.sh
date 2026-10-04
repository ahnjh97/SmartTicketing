#!/usr/bin/env bash
set -euo pipefail

revision=${1:?Pass the tested commit SHA}
[[ "$revision" =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid commit SHA' >&2; exit 1; }
cd "$HOME/SmartTicketing"

# Also protects the server if a previous SSH session outlives its Actions job.
exec 9> "$HOME/.smartticketing-deploy.lock"
flock -w 900 9

diagnostics() {
  result=$?
  if (( result != 0 )); then
    echo "Deployment failed (exit $result). Container status and recent logs:"
    docker compose ps -a || true
    docker compose logs --no-color --tail=100 mysql redis backend frontend nginx || true
  fi
  exit "$result"
}
trap diagnostics EXIT

git fetch origin main
# Never report success for a different revision than the one CI tested.
if [[ "$(git rev-parse origin/main)" != "$revision" ]]; then
  echo 'main has moved. Run the workflow for the latest main commit.' >&2
  exit 1
fi
git checkout main
git reset --hard "$revision"

# Validate without printing resolved secrets. Build before replacing running services.
docker compose config --quiet
docker compose build backend frontend
docker compose up -d --wait --wait-timeout 600 mysql redis backend frontend

# Recreate the proxy so it resolves the new backend/frontend container addresses.
# Certificate/config errors are caught before replacing the existing proxy.
docker compose run --rm -T --no-deps nginx nginx -t < /dev/null
docker compose up -d --no-deps --force-recreate nginx
docker compose exec -T nginx nginx -t < /dev/null
docker compose ps
