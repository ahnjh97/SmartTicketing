#!/usr/bin/env bash
set -euo pipefail
trap 'find /results -type f -name fixture.json -delete 2>/dev/null || true' EXIT
run_suite() (
    export BENCH_SUITE=$1
    result_root=$2
    mkdir -p "$result_root"
    if [[ "$result_root" != /results ]]; then
        for artifact in images.txt backend-jar.sha256 backend-java.txt; do
            if [[ -f "/results/$artifact" ]]; then cp "/results/$artifact" "$result_root/$artifact"; fi
        done
    fi
    export BOOKING_LOAD_OUTPUT="$result_root/run"
    status=0
    ./gradlew bookingRedisBenchmark --no-daemon --console=plain -Dorg.gradle.jvmargs=-Xmx512m 2>&1 | tee "$result_root/runner.log" || status=$?
    if [[ "$BENCH_SUITE" != booking ]]; then
        node scripts/benchmark/query-report.mjs "$BOOKING_LOAD_OUTPUT" "$result_root" || status=2
    elif [[ "$status" != 0 ]]; then
        exit "$status"
    elif [[ "${BOOKING_LOAD_SMOKE:-false}" == true ]]; then
        node -e 'const fs=require("fs");const root=process.argv[1];const mode=process.env.BENCH_CACHE_MODE||"compare"; const runs=mode==="compare"?["01-off","02-on"]:[`01-${mode}`]; for(const run of runs){const d=JSON.parse(fs.readFileSync(`${root}/run/${run}/dispatch.json`));const v=JSON.parse(fs.readFileSync(`${root}/run/${run}/validation.json`));if(d.metrics.operation_success.values.fails||v.assigned!==v.dispatchCases||v.duplicateActiveSeats)process.exit(2);}console.log("Linux Java 21 booking smoke passed for the selected cache mode");' "$result_root" || status=2
    else
        node scripts/benchmark/redis-report.mjs "$BOOKING_LOAD_OUTPUT" "$result_root" > "$result_root/summary.json"
        node -e 'const r=require(process.argv[1]+"/results.json");if(r.fair===false||r.rows.some(x=>x.failed||x.wrong||x.httpFailed||x.exitCode)||r.validation.some(x=>x.assigned!==x.dispatchCases||x.duplicateActiveSeats)||r.evidence.some(x=>x.outboxErrors||x.connectionRefused)){console.error("Load thresholds failed; HTML includes all failures.");process.exit(2);}' "$result_root" || status=2
    fi
    exit "$status"
)
if [[ "${BENCH_SUITE:-booking}" == all ]]; then
    status=0
    run_suite booking /results/booking || status=2
    run_suite queries-login /results/queries || status=2
    node -e 'const fs=require("fs"); const items=["booking","queries"].map(n=>fs.existsSync(`/results/${n}/index.html`)?`<li><a href="${n}/index.html">${n} HTML</a></li>`:`<li>${n}: HTML 없음 (smoke 또는 실행 실패). <a href="${n}/runner.log">로그</a></li>`).join("");fs.writeFileSync("/results/index.html",`<!doctype html><meta charset="utf-8"><title>k6 전체 결과</title><h1>k6 전체 결과</h1><p>종료 코드: ${process.argv[1]} (0: 성공, 그 외: 로그 확인)</p><ul>${items}</ul>`);' "$status"
    exit "$status"
else
    run_suite "${BENCH_SUITE:-booking}" /results
fi
