param(
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$Rate = 50,
    [string]$Duration = '30s',
    [int]$PreAllocatedVUs = 100,
    [int]$MaxVUs = 1000,
    [int]$MovieId = 1,
    [int]$TheaterId = 0,
    [int]$ShowtimeId = 0,
    [string]$Date = '',
    [double]$Latitude = 37.5665,
    [double]$Longitude = 126.9780
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$resultRoot = Join-Path $root 'benchmark-results\redis-query'
$logRoot = Join-Path $resultRoot 'backend'
$k6Root = Join-Path $root 'k6\redis-benchmark'

if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) { throw 'k6 is not installed or is not in PATH.' }
if ([string]::IsNullOrWhiteSpace($Date)) { $Date = (Get-Date).AddDays(1).ToString('yyyy-MM-dd') }

$listener = Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue
if ($listener) { throw 'Port 8080 is already in use. Stop the existing backend before starting the cache comparison.' }

New-Item -ItemType Directory -Force -Path $resultRoot, $logRoot | Out-Null

$tests = @(
    @{ Name = '01-common-query'; File = '01-common-query.js' },
    @{ Name = '03-showtime-query'; File = '03-showtime-query.js' },
    @{ Name = '03-seat-map-query'; File = '03-seat-map-query.js' },
    @{ Name = '05-distance-query'; File = '05-distance-query.js' }
)

function Set-CacheMode([string]$Mode) {
    $action = if ($Mode -eq 'on') { 'set-on' } else { 'set-off' }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'scripts\local-dev.ps1') -Action $action
    if ($LASTEXITCODE -ne 0) { throw "Failed to set cache mode: $Mode" }
}

function Start-Backend([string]$Mode) {
    Set-CacheMode $Mode
    $stdout = Join-Path $logRoot ('backend-' + $Mode + '.log')
    $stderr = Join-Path $logRoot ('backend-' + $Mode + '.error.log')
    Remove-Item $stdout, $stderr -Force -ErrorAction SilentlyContinue

    $startArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
        (Join-Path $root 'scripts\local-dev.ps1'), '-Action', $Mode)
    $process = Start-Process -FilePath powershell.exe -ArgumentList $startArgs -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru

    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Milliseconds 500
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri ($BaseUrl + '/api/health/readiness') -TimeoutSec 2
            if ($response.StatusCode -eq 200) {
                Write-Host ('Backend ready: cache ' + $Mode) -ForegroundColor Green
                return $process
            }
        } catch {
            if ($process.HasExited) { throw "Backend exited before readiness. See $stdout and $stderr" }
        }
    }
    throw "Backend readiness timeout for cache $Mode. See $stdout and $stderr"
}

function Stop-Backend {
    $ports = Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue
    $pids = @($ports | Select-Object -ExpandProperty OwningProcess -Unique)
    foreach ($pidValue in $pids) {
        if ($pidValue -and $pidValue -ne 0) { & taskkill.exe /PID $pidValue /T /F | Out-Null }
    }
    Start-Sleep -Seconds 2
}

function Run-K6([string]$Mode, [hashtable]$Test) {
    $resultFile = Join-Path $resultRoot ($Test.Name + '-' + $Mode + '.json')
    $env:BASE_URL = $BaseUrl
    $env:CACHE_MODE = $Mode
    $env:RESULT_FILE = $resultFile
    $env:REDIS_BENCH_RATE = [string]$Rate
    $env:REDIS_BENCH_DURATION = $Duration
    $env:REDIS_BENCH_PRE_VUS = [string]$PreAllocatedVUs
    $env:REDIS_BENCH_MAX_VUS = [string]$MaxVUs
    $env:K6_MOVIE_ID = [string]$MovieId
    $env:K6_DATE = $Date
    $env:K6_LATITUDE = [string]$Latitude
    $env:K6_LONGITUDE = [string]$Longitude

    if ($TheaterId -gt 0) { $env:K6_THEATER_ID = [string]$TheaterId } else { Remove-Item Env:K6_THEATER_ID -ErrorAction SilentlyContinue }
    if ($ShowtimeId -gt 0) { $env:K6_SHOWTIME_ID = [string]$ShowtimeId } else { Remove-Item Env:K6_SHOWTIME_ID -ErrorAction SilentlyContinue }

    Write-Host ('k6 ' + $Mode + ' -> ' + $Test.Name) -ForegroundColor Cyan
    & k6 run (Join-Path $k6Root $Test.File) --no-usage-report
    if ($LASTEXITCODE -ne 0) { throw "k6 failed: $($Test.Name) / $Mode" }
    if (-not (Test-Path $resultFile)) { throw "Expected JSON result was not created: $resultFile" }
}

try {
    foreach ($mode in @('off', 'on')) {
        $backend = $null
        try {
            $backend = Start-Backend $mode
            foreach ($test in $tests) { Run-K6 $mode $test }
        } finally {
            Stop-Backend
            if ($backend -and -not $backend.HasExited) { try { $backend.Kill() } catch {} }
        }
    }

    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'generate-redis-query-report.ps1') -ResultRoot $resultRoot
    if ($LASTEXITCODE -ne 0) { throw 'Report generation failed.' }

    Write-Host ''
    Write-Host 'Redis ON/OFF comparison completed.' -ForegroundColor Green
    Write-Host ('HTML report: ' + (Join-Path $resultRoot 'redis-cache-report.html'))
} finally {
    Remove-Item Env:CACHE_MODE -ErrorAction SilentlyContinue
    Remove-Item Env:RESULT_FILE -ErrorAction SilentlyContinue
    Remove-Item Env:REDIS_BENCH_RATE -ErrorAction SilentlyContinue
    Remove-Item Env:REDIS_BENCH_DURATION -ErrorAction SilentlyContinue
    Remove-Item Env:REDIS_BENCH_PRE_VUS -ErrorAction SilentlyContinue
    Remove-Item Env:REDIS_BENCH_MAX_VUS -ErrorAction SilentlyContinue
}
