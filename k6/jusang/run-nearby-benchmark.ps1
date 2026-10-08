param(
    [ValidateSet("all","5-distance","5-walk","5-transit")]
    [string]$Target = "all",
    [ValidateSet("smoke","load")]
    [string]$Profile = "load",
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Vus = 10,
    [int]$Iterations = 100,
    [string]$Lat = "37.5665",
    [string]$Lon = "126.9780",
    [string]$Address = "",
    [switch]$ManageBackend,
    [switch]$OpenReport
)

$ErrorActionPreference = "Stop"
$resultsDir = Join-Path $PSScriptRoot "nearby-benchmark-results"
$jsonPath = Join-Path $PSScriptRoot "nearby-test-results.json"
$reportPath = Join-Path $PSScriptRoot "nearby-test-report.html"
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "...."))
$targets = @("5-distance","5-walk","5-transit")
if ($Target -ne "all") { $targets = @($Target) }
if ($Profile -eq "smoke") { $Vus = 1; $Iterations = 1 }

if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) { throw "k6가 설치되어 있지 않습니다." }
if (-not $env:K6_LOGIN_ID -or -not $env:K6_PASSWORD) { throw "Nearby 테스트에는 K6_LOGIN_ID와 K6_PASSWORD가 필요합니다." }
New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null

$script:backendProcess = $null
$script:managedBackendPids = @()

function Get-ListeningBackendPids {
    @(Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique |
        ForEach-Object {
            try { if (Get-Process -Id ([int]$_) -ErrorAction Stop) { [int]$_ } } catch {}
        } | Select-Object -Unique)
}
function Wait-Backend {
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri "$BaseUrl/api/main" -UseBasicParsing -TimeoutSec 3
            if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 500 -and (Get-ListeningBackendPids).Count -gt 0) {
                $script:managedBackendPids = @(Get-ListeningBackendPids); Start-Sleep 5; return
            }
        } catch {}
        Start-Sleep 2
    }
    throw "백엔드가 120초 안에 준비되지 않았습니다."
}
function Start-ManagedBackend {
    if ((Get-ListeningBackendPids).Count -gt 0) { throw "8080 포트가 이미 사용 중입니다." }
    $script:backendProcess = Start-Process powershell.exe -ArgumentList @("-NoProfile","-ExecutionPolicy","Bypass","-File",(Join-Path $root "scriptslocal-dev.ps1"),"-Action","on") -PassThru -WindowStyle Minimized
    Wait-Backend
}
function Stop-ManagedBackend {
    $pids = @($script:managedBackendPids + @(Get-ListeningBackendPids) | Where-Object { $_ } | Select-Object -Unique)
    foreach ($p in $pids) { try { & taskkill.exe /PID ([int]$p) /F /T 2>$null | Out-Null } catch {} }
    if ($script:backendProcess) { try { & taskkill.exe /PID ([int]$script:backendProcess.Id) /F /T 2>$null | Out-Null } catch {} }
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        if ((Get-ListeningBackendPids).Count -eq 0) { return }
        foreach ($p in Get-ListeningBackendPids) { try { & taskkill.exe /PID ([int]$p) /F 2>$null | Out-Null } catch {} }
        Start-Sleep 2
    }
    throw "백엔드 종료에 실패했습니다."
}
function Convert-Summary([string]$Path) {
    $s = Get-Content -Raw -Encoding UTF8 $Path | ConvertFrom-Json
    $d = $s.metrics.http_req_duration
    $failed = [double]$s.metrics.http_req_failed.value
    $rate = [double]$s.metrics.http_reqs.rate
    $checks = $s.metrics.checks
    [pscustomobject]@{
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
        throughput_req_per_sec = [math]::Round($rate, 6)
    }
}

try {
    if ($ManageBackend) { Start-ManagedBackend }

    $results = [ordered]@{}
    foreach ($t in $targets) {
        $env:BASE_URL=$BaseUrl; $env:VUS="$Vus"; $env:ITERATIONS="$Iterations"
        $env:LAT=$Lat; $env:LON=$Lon; $env:ADDRESS=$Address
        $env:LOGIN_ID=$env:K6_LOGIN_ID; $env:PASSWORD=$env:K6_PASSWORD
        $scriptName = switch ($t) {
            "5-distance" {"05-nearby-distance.js"}
            "5-walk" {"05-nearby-walk.js"}
            "5-transit" {"05-nearby-transit.js"}
        }
        $summary = Join-Path $resultsDir "$t-summary.json"
        Write-Host "===== [$t] NEARBY BASELINE =====" -ForegroundColor Cyan
        & k6 run (Join-Path $PSScriptRoot $scriptName) --summary-export $summary
        if ($LASTEXITCODE -ne 0) { throw "[$t] k6 테스트가 실패했습니다." }
        $results[$t] = Convert-Summary $summary
    }

    [ordered]@{
        generated_at=(Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
        test=[ordered]@{ targets=$targets; profile=$Profile; base_url=$BaseUrl; vus=$Vus; iterations=$Iterations }
        results=$results
        interpretation="5번은 아직 Redis 캐시 ON/OFF 대상이 아니므로 현재 구현의 baseline만 측정합니다. 응답시간이 높거나 외부 경로 API 호출 비용이 큰 항목이 캐시 후보입니다."
    } | ConvertTo-Json -Depth 12 | Set-Content -Path $jsonPath -Encoding UTF8

    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "generate-nearby-report.ps1")
    if ($LASTEXITCODE -ne 0) { throw "Nearby HTML 보고서 생성에 실패했습니다." }
    if ($OpenReport) { Start-Process $reportPath }
} finally {
    if ($ManageBackend) { Stop-ManagedBackend }
}
