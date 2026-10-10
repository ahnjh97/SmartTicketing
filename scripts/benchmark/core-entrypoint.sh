#!/usr/bin/env bash
set -euo pipefail
trap 'find /results -type f -name fixture.json -delete 2>/dev/null || true' EXIT
selection="${BENCH_SUITE:-focus}"
status=0
run_case() (
    export BENCH_SUITE=$1 BOOKING_LOAD_OUTPUT="/results/$1/run"
    mkdir -p "$BOOKING_LOAD_OUTPUT"
    ./gradlew bookingRedisBenchmark -PbenchmarkInstrumentation --no-daemon --console=plain -Dorg.gradle.jvmargs=-Xmx512m 2>&1 | tee "/results/$1/runner.log"
)
if [[ "$selection" == focus || "$selection" == waiting || "$selection" == dispatch ]]; then
    export BENCH_BOOKING_CASE="$selection"
    run_case booking || status=2
fi
for item in seats showtimes; do
    if [[ "$selection" == focus || "$selection" == "$item" ]]; then run_case "$item" || status=2; fi
done
node scripts/benchmark/core-report.mjs /results "$selection" "${BOOKING_LOAD_SMOKE:-false}" "${BENCH_CACHE_MODE:-compare}" || status=2
exit "$status"
