# Redis ON/OFF query performance benchmark

This benchmark is independent of the older k6 scripts in the repository.

## Covered features

| Script | Target |
|---|---|
| `01-main-query.js` | `/api/main` only |\n| `01-movies-query.js` | `/api/movies` only |\n| `01-theaters-query.js` | `/api/theaters` only |\n| `01-common-query-integrated.js` | `/api/main`, `/api/movies`, `/api/theaters` integrated workload |
| `03-showtime-query.js` | `/api/showtimes` |
| `03-seat-map-query.js` | `/api/showtimes/{id}/seats` |
| `05-distance-query.js` | `/api/theaters/nearby?sort=DISTANCE` |

WALK and TRANSIT are intentionally excluded.

## What is being compared

Redis OFF uses the existing MySQL query path.

Redis ON uses the same query path plus a read-only Redis query cache. The TTL is controlled by `APP_CACHE_QUERY_TTL_MS` in `../../../.env`.

The cache is fail-open: when Redis is unavailable, the request falls back to the existing database/calculation path.

The distance cache covers only straight-line distance sorting. Kakao WALK/TRANSIT calls are not cached.

## Full local comparison

Run from the repository root after stopping any existing backend on port 8080:

    powershell -NoProfile -ExecutionPolicy Bypass -File .\k6\redis-benchmark\run-cache-comparison.ps1

Default load:

- 50 iterations/sec
- 30 seconds per feature and cache mode
- 50 pre-allocated VUs
- 200 maximum VUs

Override load, for example:

    powershell -File .\k6\redis-benchmark\run-cache-comparison.ps1 -Rate 100 -Duration 60s -PreAllocatedVUs 100 -MaxVUs 300

The runner:

1. sets cache OFF
2. starts the existing local backend flow
3. runs the three split common-query tests, their integrated test, and the remaining feature tests
4. stops the backend
5. sets cache ON
6. repeats the same four tests
7. generates the HTML comparison report

## Required parameters

Seat-map testing requires a real scheduled showtime ID:

    -ShowtimeId 115260

The showtime test defaults to movie ID 1 and tomorrow's date. Override them when needed:

    -MovieId 14 -TheaterId 1 -Date 2026-10-09

The nearby endpoint is authenticated. Provide either a JWT:

    $env:K6_ACCESS_TOKEN = '...'

or test-account credentials:

    $env:K6_LOGIN_ID = '...'
    $env:K6_PASSWORD = '...'

Coordinates default to Seoul City Hall and can be overridden:

    -Latitude 37.5665 -Longitude 126.9780

## Results

Results are written to:

    benchmark-results/redis-query/

The common-query item is intentionally split into three isolated endpoint tests plus one integrated test. This makes it possible to identify which endpoint causes a regression while retaining the original mixed-workload comparison.\n\nEach feature has separate JSON files:

    01-common-query-off.json
    01-common-query-on.json
    03-showtime-query-off.json
    03-showtime-query-on.json
    03-seat-map-query-off.json
    03-seat-map-query-on.json
    05-distance-query-off.json
    05-distance-query-on.json

Backend stdout/stderr are kept separately under:

    benchmark-results/redis-query/backend/

The final files are:

    redis-cache-report.json
    redis-cache-report.html

The HTML report compares p95, p99, request count, error rate, and percentage improvement for every feature.

## Fair-comparison rule

Run OFF and ON with the same database, application image/code, load rate, duration, query parameters, and machine.

The report uses k6 end-of-test summaries. k6's `handleSummary()` API is used to write the machine-readable JSON result, and the runner uses a constant-arrival-rate workload so the intended iteration rate does not depend on response time.
