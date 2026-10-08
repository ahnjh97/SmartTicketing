#!/usr/bin/env bash
set -euo pipefail
project_root=$(cd -- "$(dirname -- "$0")/../.." && pwd)
run_id=${1:?run id required}
[[ "$run_id" =~ ^linux-java21-[a-z0-9-]+$ ]] || { echo 'Invalid run id' >&2; exit 1; }
export BENCH_SMOKE=${2:-false}
export BENCHMARK_OUTPUT="$project_root/benchmark-results/$run_id"
export BENCH_DB_PASSWORD=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
mkdir -p "$BENCHMARK_OUTPUT"
if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
    echo 'Installing official Ubuntu Docker packages in WSL (first run only)...'
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y docker.io docker-compose-v2
fi
docker info >/dev/null 2>&1 || service docker start
compose=(docker compose --project-name "stbench-$run_id" --env-file /dev/null -f "$project_root/scripts/benchmark/compose.linux.yml")
cleanup() {
    result=$?
    trap - EXIT
    "${compose[@]}" logs --no-color mysql redis > "$BENCHMARK_OUTPUT/services.log" 2>&1 || true
    # Only this generated project's containers/network/temporary volume are removed.
    if ! "${compose[@]}" down --volumes --remove-orphans > "$BENCHMARK_OUTPUT/cleanup.log" 2>&1; then
        echo "Cleanup failed; inspect $BENCHMARK_OUTPUT/cleanup.log" >&2
        result=1
    fi
    exit "$result"
}
trap cleanup EXIT
"${compose[@]}" build runner
"${compose[@]}" up -d --wait --wait-timeout 240 mysql redis
docker image inspect smartticketing-benchmark:java21-local mysql:8.4 redis:7-alpine \
    --format '{{.Id}} {{json .RepoDigests}}' > "$BENCHMARK_OUTPUT/images.txt"
"${compose[@]}" run --rm -T runner
