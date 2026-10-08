param(
    [ValidateSet("all","1-common","1-catalog","3-showtime","3-seat","5-distance","5-walk","5-transit")]
    [string]$Target = "all",
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
$summaryDir = $resultsDir
$targetNames = @("1-common","1-catalog","3-showtime","3-seat","5-distance","5-walk","5-transit")
$targetsToRun = if ($Target -eq "all") { $targetNames } else { @($Target) }
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
    $pids = @($connections | Select-Object -ExpandProperty OwningProcess -Unique)
    $livePids = @()

    foreach ($backendPid in $pids) {
        try {
            $process = Get-Process -Id ([int]$backendPid) -ErrorAction Stop
            if ($process) {
                $livePids += [int]$backendPid
            }
        } catch {
            # The TCP entry can briefly outlive the process. Ignore stale PIDs.
        }
    }

    return @($livePids | Select-Object -Unique)
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
        try { & taskkill.exe /PID ([int]$backendPid) /F /T 2>$null | Out-Null } catch {}
    }

    if ($script:backendProcess) {
        try { & taskkill.exe /PID ([int]$script:backendProcess.Id) /F /T 2>$null | Out-Null } catch {}
        $script:backendProcess = $null
    }

    $script:managedBackendPids = @()

    # Gradle/Java child processes can survive a tree kill. Re-check 8080
    # and terminate only the process actually listening on the test port.
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        $remaining = @(Get-ListeningBackendPids)
        if ($remaining.Count -eq 0) {
            Start-Sleep -Seconds 3
            Write-Host "8080            " -ForegroundColor Green
            return
        }

        foreach ($backendPid in $remaining) {
            try { & taskkill.exe /PID ([int]$backendPid) /F 2>$null | Out-Null } catch {}
        }
        Start-Sleep -Seconds 2
    }

    $remaining = @(Get-ListeningBackendPids)
    throw ("     60               .    PID: {0}" -f ($remaining -join ', '))
}

function Invoke-K6Summary([string]$TestTarget, [string]$Mode, [string]$SummaryPath) {
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
    $env:LOGIN_ID = if ($env:K6_LOGIN_ID) { $env:K6_LOGIN_ID } else { $env:LOGIN_ID }
    $env:PASSWORD = if ($env:K6_PASSWORD) { $env:K6_PASSWORD } else { $env:PASSWORD }

    if ($TestTarget -in @("5-distance", "5-walk", "5-transit") -and (-not $env:LOGIN_ID -or -not $env:PASSWORD)) {
        throw "Nearby tests require K6_LOGIN_ID and K6_PASSWORD. Set them before running the all-target comparison."
    }

    $scriptName = switch ($TestTarget) {
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
    $d = $s.metrics.http_req_duration
    $failed = [double]$s.metrics.http_req_failed.value
    $reqRate = [double]$s.metrics.http_reqs.rate
    $checks = $s.metrics.checks

    $result = [pscustomobject]@{
        http_req_failed_rate_percent = [math]::Round($failed * 100, 4)
        checks_total = [int]$checks.value
        checks_succeeded = [int]$checks.passes
        checks_failed = [int]$checks.fails
        http_req_duration_ms = [ordered]@{
            avg = [math]::Round([double]$d.avg, 2)
            min = [math]::Round([double]$d.min, 2)
            median = [math]::Round([double]$d.med, 2)
            max = [math]::Round([double]$d.max, 2)
            p90 = [math]::Round([double]$d."p(90)", 2)
            p95 = [math]::Round([double]$d."p(95)", 2)
        }
        throughput_req_per_sec = [math]::Round($reqRate, 6)
    }

    if ($result.http_req_duration_ms.avg -le 0 -or $result.throughput_req_per_sec -le 0) {
        throw "k6 summary conversion failed: response time or throughput is zero. Summary: $Path"
    }

    return $result
}

function Improvement([double]$on, [double]$off) {
    if ($off -eq 0) { return 0 }
    return [math]::Round((($off - $on) / $off) * 100, 2)
}

try {
    Write-Host ""
    Write-Host "=== SmartTicketing Redis Cache ON/OFF =================================" -ForegroundColor Green
    Write-Host "Targets=$($targetsToRun -join ', ') Profile=$Profile VUs=$Vus Iterations=$Iterations"

    $allResults = [ordered]@{}

    # Start ON once, then run every feature against the same ON backend.
    if ($ManageBackend) {
        Set-CacheMode $true
        Start-ManagedBackend $true
    } else {
        Set-CacheMode $true
        Write-Host "Cache ON      .                                  ." -ForegroundColor Yellow
    }

    foreach ($testTarget in $targetsToRun) {
        $onSummary = Join-Path $summaryDir ("{0}-cache-on-summary.json" -f $testTarget)
        Write-Host ""
        Write-Host "===== [$testTarget] CACHE ON =====" -ForegroundColor Green
        Invoke-K6Summary $testTarget "CACHE ON" $onSummary
    }

    if ($ManageBackend) {
        Stop-ManagedBackend
        Set-CacheMode $false
        Start-ManagedBackend $false
    } else {
        Set-CacheMode $false
        Write-Host "Cache OFF      .             Enter      ." -ForegroundColor Yellow
        Read-Host "             Enter"
    }

    foreach ($testTarget in $targetsToRun) {
        $offSummary = Join-Path $summaryDir ("{0}-cache-off-summary.json" -f $testTarget)
        Write-Host ""
        Write-Host "===== [$testTarget] CACHE OFF =====" -ForegroundColor Yellow
        Invoke-K6Summary $testTarget "CACHE OFF" $offSummary

        $on = Convert-Summary (Join-Path $summaryDir ("{0}-cache-on-summary.json" -f $testTarget))
        $off = Convert-Summary $offSummary

        $allResults[$testTarget] = [ordered]@{
            cache_on = $on
            cache_off = $off
            comparison = [ordered]@{
                avg_response_time_improvement_percent = Improvement $on.http_req_duration_ms.avg $off.http_req_duration_ms.avg
                median_response_time_improvement_percent = Improvement $on.http_req_duration_ms.median $off.http_req_duration_ms.median
                p95_response_time_improvement_percent = Improvement $on.http_req_duration_ms.p95 $off.http_req_duration_ms.p95
                max_response_time_improvement_percent = Improvement $on.http_req_duration_ms.max $off.http_req_duration_ms.max
                throughput_improvement_percent = [math]::Round((($on.throughput_req_per_sec - $off.throughput_req_per_sec) / $off.throughput_req_per_sec) * 100, 2)
            }
        }
    }

    $data = [ordered]@{
        generated_at = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
        test = [ordered]@{
            target = if ($Target -eq "all") { "all" } else { $Target }
            targets = $targetsToRun
            profile = $Profile
            base_url = $BaseUrl
            vus = $Vus
            iterations = $Iterations
        }
        results = $allResults
        comparison = [ordered]@{
            interpretation = "        Cache ON/OFF                 .                                ."
        }
    }

    $data | ConvertTo-Json -Depth 12 | Set-Content -Path $jsonPath -Encoding UTF8
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
