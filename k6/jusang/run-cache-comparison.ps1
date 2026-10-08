param(
    [ValidateSet("1-common","1-catalog","3-showtime","3-seat","5-distance","5-walk","5-transit")]
    [string]$Target = "1-common",
    [ValidateSet("smoke","load")]
    [string]$Profile = "load",
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Vus = 10,
    [int]$Iterations = 100,
    [string]$MovieId = "14",
    [string]$TheaterId = "1",
    [string]$ShowtimeId = "115260",
    [string]$Date = "2026-10-09",
    [string]$Lat = "37.5665",
    [string]$Lon = "126.9780",
    [string]$Address = "",
    [switch]$ManageBackend,
    [switch]$OpenReport
)

$ErrorActionPreference = "Stop"
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$reportGenerator = Join-Path $PSScriptRoot "generate-cache-report.ps1"
$resultsDir = Join-Path $PSScriptRoot "cache-comparison-results"
$jsonPath = Join-Path $PSScriptRoot "cache-test-results.json"
$onSummary = Join-Path $resultsDir "cache-on-summary.json"
$offSummary = Join-Path $resultsDir "cache-off-summary.json"
$backendProcess = $null
$managedBackendPids = @()

if (-not (Test-Path $reportGenerator)) { throw "generate-cache-report.ps1           ." }
if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) { throw "k6              ." }

if ($Profile -eq "smoke") { $Vus = 1; $Iterations = 1 }
New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null

function Set-CacheMode([bool]$Enabled) {
    $mode = if ($Enabled) { "set-on" } else { "set-off" }
    & cmd.exe /d /c "`"$root\local.cmd`" $mode"
    if ($LASTEXITCODE -ne 0) { throw "           : $mode" }
}

function Get-ListeningBackendPids {
    $connections = @(Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue)
    return @($connections | Select-Object -ExpandProperty OwningProcess -Unique)
}

function Wait-Backend {
    param([int]$TimeoutSeconds = 120)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    Write-Host ("         :    {0} " -f $TimeoutSeconds) -ForegroundColor Yellow

    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri "$BaseUrl/api/main" -UseBasicParsing -TimeoutSec 3
            if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 500) {
                $pids = @(Get-ListeningBackendPids)
                if ($pids.Count -gt 0) {
                    $script:managedBackendPids = @($pids)
                    Write-Host ("             (PID: {0})" -f ($pids -join ', ')) -ForegroundColor Green
                    Start-Sleep -Seconds 5
                    return
                }
            }
        } catch {}
        Start-Sleep -Seconds 2
    }

    throw ("     {0}                  ." -f $TimeoutSeconds)
}

function Wait-BackendStopped {
    param([int]$TimeoutSeconds = 60)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    Write-Host ("         :    {0} " -f $TimeoutSeconds) -ForegroundColor Yellow

    while ((Get-Date) -lt $deadline) {
        $pids = @(Get-ListeningBackendPids)
        if ($pids.Count -eq 0) {
            Start-Sleep -Seconds 3
            Write-Host "8080            " -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 2
    }

    $remaining = @(Get-ListeningBackendPids)
    throw ("     {0}               .    PID: {1}" -f $TimeoutSeconds, ($remaining -join ', '))
}

function Start-ManagedBackend([bool]$Enabled) {
    $existing = @(Get-ListeningBackendPids)
    if ($existing.Count -gt 0) {
        throw ("8080               . PID: {0}. IDE              ." -f ($existing -join ', '))
    }

    $action = if ($Enabled) { "on" } else { "off" }
    $script:backendProcess = Start-Process powershell.exe -ArgumentList @(
        "-NoProfile","-ExecutionPolicy","Bypass","-File",
        (Join-Path $root "scripts\local-dev.ps1"),"-Action",$action
    ) -PassThru -WindowStyle Minimized

    Wait-Backend
}

function Stop-ManagedBackend {
    $pids = @($script:managedBackendPids + @(Get-ListeningBackendPids) | Where-Object { $_ } | Select-Object -Unique)

    foreach ($backendPid in $pids) {
        try { & taskkill.exe /PID ([int]$backendPid) /T /F | Out-Null } catch {}
    }

    if ($script:backendProcess) {
        try { & taskkill.exe /PID $script:backendProcess.Id /T /F | Out-Null } catch {}
        $script:backendProcess = $null
    }

    $script:managedBackendPids = @()
    Wait-BackendStopped
}

function Invoke-K6Summary([string]$Mode, [string]$SummaryPath) {
    $env:BASE_URL = $BaseUrl
    $env:VUS = "$Vus"
    $env:ITERATIONS = "$Iterations"
    $env:MOVIE_ID = $MovieId
    $env:THEATER_ID = $TheaterId
    $env:SHOWTIME_ID = $ShowtimeId
    $env:DATE = $Date
    $env:LAT = $Lat
    $env:LON = $Lon
    $env:ADDRESS = $Address

    $scriptName = switch ($Target) {
        "1-common" { "01-common-query.js" }
        "1-catalog" { "01-catalog-query.js" }
        "3-showtime" { "03-showtime-query.js" }
        "3-seat" { "03-seat-map-query.js" }
        "5-distance" { "05-nearby-distance.js" }
        "5-walk" { "05-nearby-walk.js" }
        "5-transit" { "05-nearby-transit.js" }
    }

    $scriptPath = Join-Path $PSScriptRoot $scriptName
    Write-Host "[$Mode] k6   : $scriptName" -ForegroundColor Cyan
    & k6 run $scriptPath --summary-export $SummaryPath
    if ($LASTEXITCODE -ne 0) { throw "[$Mode] k6       " }
}

function Convert-Summary([string]$Path) {
    $s = Get-Content -Raw -Encoding UTF8 $Path | ConvertFrom-Json
    $d = $s.metrics.http_req_duration.values
    $failed = $s.metrics.http_req_failed.values.rate
    $reqRate = $s.metrics.http_reqs.values.rate
    $checks = $s.metrics.checks.values

    [pscustomobject]@{
        http_req_failed_rate_percent = [math]::Round($failed * 100, 4)
        checks_total = [int]$checks.count
        checks_succeeded = [int]$checks.passes
        checks_failed = [int]$checks.fails
        http_req_duration_ms = [ordered]@{
            avg = [math]::Round($d.avg, 2)
            min = [math]::Round($d.min, 2)
            median = [math]::Round($d.med, 2)
            max = [math]::Round($d.max, 2)
            p90 = [math]::Round($d."p(90)", 2)
            p95 = [math]::Round($d."p(95)", 2)
        }
        throughput_req_per_sec = [math]::Round($reqRate, 6)
    }
}

function Improvement([double]$on, [double]$off) {
    if ($off -eq 0) { return 0 }
    return [math]::Round((($off - $on) / $off) * 100, 2)
}

try {
    Write-Host ""
    Write-Host "=== SmartTicketing Redis Cache ON/OFF       ===" -ForegroundColor Green
    Write-Host "Target=$Target Profile=$Profile VUs=$Vus Iterations=$Iterations"

    if ($ManageBackend) {
        Set-CacheMode $true
        Start-ManagedBackend $true
    } else {
        Set-CacheMode $true
        Write-Host "Cache ON      .                                  ." -ForegroundColor Yellow
    }

    Invoke-K6Summary "CACHE ON" $onSummary

    if ($ManageBackend) {
        Stop-ManagedBackend
        Set-CacheMode $false
        Start-ManagedBackend $false
    } else {
        Set-CacheMode $false
        Write-Host "Cache OFF      .             Enter      ." -ForegroundColor Yellow
        Read-Host "             Enter"
    }

    Invoke-K6Summary "CACHE OFF" $offSummary

    $on = Convert-Summary $onSummary
    $off = Convert-Summary $offSummary

    $data = [ordered]@{
        generated_at = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
        test = [ordered]@{
            target = $Target
            profile = $Profile
            base_url = $BaseUrl
            vus = $Vus
            iterations = $Iterations
        }
        results = [ordered]@{
            cache_on = $on
            cache_off = $off
        }
        comparison = [ordered]@{
            avg_response_time_improvement_percent = Improvement $on.http_req_duration_ms.avg $off.http_req_duration_ms.avg
            median_response_time_improvement_percent = Improvement $on.http_req_duration_ms.median $off.http_req_duration_ms.median
            p95_response_time_improvement_percent = Improvement $on.http_req_duration_ms.p95 $off.http_req_duration_ms.p95
            max_response_time_improvement_percent = Improvement $on.http_req_duration_ms.max $off.http_req_duration_ms.max
            throughput_improvement_percent = [math]::Round((($on.throughput_req_per_sec - $off.throughput_req_per_sec) / $off.throughput_req_per_sec) * 100, 2)
            interpretation = "        Cache ON/OFF                 .                                ."
        }
    }

    $data | ConvertTo-Json -Depth 8 | Set-Content -Path $jsonPath -Encoding UTF8
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $reportGenerator
    if ($LASTEXITCODE -ne 0) { throw "HTML          " }

    Write-Host ""
    Write-Host "  !" -ForegroundColor Green
    Write-Host "JSON : $jsonPath"
    Write-Host "HTML : $(Join-Path $PSScriptRoot 'cache-test-report.html')"

    if ($OpenReport) {
        Start-Process (Join-Path $PSScriptRoot "cache-test-report.html")
    }
}
finally {
    if ($ManageBackend) { Stop-ManagedBackend }
}
