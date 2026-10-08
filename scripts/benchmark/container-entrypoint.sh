#!/usr/bin/env bash
set -euo pipefail
trap 'find /results/run -type f -name fixture.json -delete 2>/dev/null || true' EXIT
./gradlew bookingRedisBenchmark --no-daemon --console=plain -Dorg.gradle.jvmargs=-Xmx512m 2>&1 | tee /results/runner.log
if [[ "${BOOKING_LOAD_SMOKE:-false}" == true ]]; then
    node -e 'const fs=require("fs"); for(const run of ["01-off","02-on"]){const d=JSON.parse(fs.readFileSync(`/results/run/${run}/dispatch.json`));const v=JSON.parse(fs.readFileSync(`/results/run/${run}/validation.json`));if(d.metrics.operation_success.values.fails||v.assigned!==v.dispatchCases||v.duplicateActiveSeats)process.exit(2);} console.log("Linux Java 21 smoke passed in both modes");'
else
    node scripts/benchmark/redis-report.mjs /results/run /results > /results/summary.json
    node -e 'const r=require("/results/results.json");if(r.rows.some(x=>x.failed||x.wrong||x.httpFailed||x.exitCode)||r.validation.some(x=>x.assigned!==x.dispatchCases||x.duplicateActiveSeats)||r.evidence.some(x=>x.outboxErrors||x.connectionRefused)){console.error("Load thresholds failed; HTML includes all failures.");process.exit(2);}'
fi
