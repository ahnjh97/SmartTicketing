# Redis read-query cache A/B benchmark

This benchmark compares repeated read-only catalog requests with the dedicated Redis query cache switched off and on. It does not enable Redis-backed booking queue/rank/summary/dispatch features.

## Scope

- `GET /api/movies?page=0&size=20`
- `GET /api/theaters?page=0&size=20`
- Default: 20 VUs for 30 seconds. Adjust with `VUS` and `DURATION`.
- The cache TTL defaults to 2 seconds. Cache responses are therefore intentionally short-lived.
- Run only against a local/test server and test database, never production.

## 1. Start server with read query cache OFF

In the same PowerShell terminal used to start Spring Boot:

```powershell
$env:APP_CACHE_QUERY_ENABLED = "false"
$env:APP_CACHE_QUERY_TTL_MS = "2000"
```

Restart the server, then in a separate terminal at the repository root:

```powershell
$env:BASE_URL = "http://localhost:8080"
$env:VUS = "20"
$env:DURATION = "30s"
$env:OUT = ".local/query-cache-benchmark/before.json"
$env:LABEL = "before"
$env:CACHE_MODE = "false"

k6 run `
  -e BASE_URL=$env:BASE_URL `
  -e VUS=$env:VUS `
  -e DURATION=$env:DURATION `
  -e OUT=$env:OUT `
  -e LABEL=$env:LABEL `
  -e CACHE_MODE=$env:CACHE_MODE `
  k6/query-cache-benchmark/read-api.js
```

## 2. Start server with read query cache ON

Stop Spring Boot. In its server terminal set:

```powershell
$env:APP_CACHE_QUERY_ENABLED = "true"
$env:APP_CACHE_QUERY_TTL_MS = "2000"
```

Restart Spring Boot. Leave `APP_CACHE_ENABLED` unchanged: the query cache has its own switch and should not enable the other booking Redis features.

Run the second sample:

```powershell
$env:OUT = ".local/query-cache-benchmark/after.json"
$env:LABEL = "after"
$env:CACHE_MODE = "true"

k6 run `
  -e BASE_URL=$env:BASE_URL `
  -e VUS=$env:VUS `
  -e DURATION=$env:DURATION `
  -e OUT=$env:OUT `
  -e LABEL=$env:LABEL `
  -e CACHE_MODE=$env:CACHE_MODE `
  k6/query-cache-benchmark/read-api.js
```

## 3. Compare

```powershell
node scripts/query-cache-benchmark/report.mjs `
  .local/query-cache-benchmark/before.json `
  .local/query-cache-benchmark/after.json `
  .local/query-cache-benchmark/report.html

Invoke-Item .local/query-cache-benchmark/report.html
```

Compare request throughput, mean/p95/p99 response time, and failed-request rate. Repeat the A/B run at least three times and alternate the order (OFF/ON, then ON/OFF) to reduce warm-up/cache/connection bias. Check application logs for `REDIS_QUERY_CACHE_GET` and `REDIS_QUERY_CACHE_PUT` warnings if Redis appears slow.

## Safety notes

- Cache OFF follows the existing MySQL read path.
- Cache ON only activates `RedisQueryCache`; the existing `app.cache.enabled` switch for booking Redis functionality remains unchanged.
- A Redis error falls back to the uncached query path. Cache TTL bounds stale catalog data to the configured TTL.
- This is a read-path performance benchmark; it does not validate booking write concurrency or replace booking integration tests.
