#!/usr/bin/env bash
set -euo pipefail
# Prevent concurrent benchmark runs from competing for the same WSL resources.
exec 9>/tmp/smartticketing-benchmark.lock
flock -n 9 || { echo 'Another Linux benchmark is running. Wait for it to finish.' >&2; exit 1; }
project_root=$(cd -- "$(dirname -- "$0")/../.." && pwd)
run_id=${1:?run id required}
[[ "$run_id" =~ ^linux-java21-[a-z0-9-]+$ ]] || { echo 'Invalid run id' >&2; exit 1; }
export BENCH_SMOKE=${2:-false}
export BENCH_SUITE=${3:-booking}
export BENCH_RATE=${4:-50}
export BENCH_DURATION=${5:-30s}
export BENCH_PRE_VUS=${6:-100}
export BENCH_MAX_VUS=${7:-1000}
export BENCH_LOGIN_USERS=${8:-100}
export BENCH_CACHE_MODE=${9:-compare}
[[ "$BENCH_CACHE_MODE" =~ ^(compare|on|off)$ ]] || { echo 'Invalid cache mode' >&2; exit 1; }
export BENCHMARK_OUTPUT="$project_root/benchmark-results/$run_id"
export BENCH_BACKEND_IMAGE="smartticketing-benchmark-backend:$run_id"
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
# Package the very same bootJar in the production Dockerfile's AWS prebuilt target.
# Use a private build context so build/deploy/app.jar in the checkout is never overwritten.
image_context="$BENCHMARK_OUTPUT/image-context"
mkdir -p "$image_context/build/deploy" "$image_context/deploy"
cp "$project_root/Dockerfile" "$image_context/Dockerfile"
cp "$project_root/deploy/backend-entrypoint.sh" "$image_context/deploy/"
sed -i 's/\r$//' "$image_context/deploy/backend-entrypoint.sh"
"${compose[@]}" run --rm -T --no-deps --entrypoint bash runner -c \
    'cp /app/build/libs/*-SNAPSHOT.jar /results/image-context/build/deploy/app.jar'
"${compose[@]}" build backend
expected_sha=$(sha256sum "$image_context/build/deploy/app.jar" | cut -d' ' -f1)
actual_sha=$(docker run --rm --entrypoint sha256sum "$BENCH_BACKEND_IMAGE" /app/app.jar | cut -d' ' -f1)
[[ "$expected_sha" == "$actual_sha" ]] || { echo 'Production image JAR checksum mismatch' >&2; exit 1; }
printf '%s  app.jar\n' "$actual_sha" > "$BENCHMARK_OUTPUT/backend-jar.sha256"
docker run --rm --entrypoint java "$BENCH_BACKEND_IMAGE" -version > "$BENCHMARK_OUTPUT/backend-java.txt" 2>&1
docker pull grafana/k6:2.3.0
"${compose[@]}" up -d --wait --wait-timeout 240 mysql redis
docker image inspect "$BENCH_BACKEND_IMAGE" smartticketing-benchmark:java21-local grafana/k6:2.3.0 mysql:8.4 redis:7-alpine \
    --format '{{.Id}} {{json .RepoDigests}}' > "$BENCHMARK_OUTPUT/images.txt"
python3 "$project_root/scripts/benchmark/container-controller.py" "${compose[@]}"
