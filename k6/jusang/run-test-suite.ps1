param(
    [ValidateSet("all","common","catalog","showtime","seat","distance","walk","transit")]
    [string]$Target = "all",
    [ValidateSet("smoke","load")]
    [string]$Profile = "smoke",
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
    [switch]$OpenReport
)

$ErrorActionPreference = "Stop"
$runner = Join-Path $PSScriptRoot "run-cache-test.ps1"
$reportPath = Join-Path $PSScriptRoot "test-suite-report.html"
$logDir = Join-Path $PSScriptRoot "results"

if (-not (Test-Path $runner)) { throw "테스트 실행 스크립트를 찾을 수 없습니다: $runner" }
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$targets = switch ($Target) {
    "all"      { @("1-common","1-catalog","3-showtime","3-seat","5-distance","5-walk","5-transit") }
    "common"   { @("1-common") }
    "catalog"  { @("1-catalog") }
    "showtime" { @("3-showtime") }
    "seat"     { @("3-seat") }
    "distance" { @("5-distance") }
    "walk"     { @("5-walk") }
    "transit"  { @("5-transit") }
}

$rows = @()
$started = Get-Date

foreach ($t in $targets) {
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "Running: $t" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan

    $log = Join-Path $logDir "$t-$((Get-Date).ToString('yyyyMMdd-HHmmss')).log"
    $sw = [Diagnostics.Stopwatch]::StartNew()

    try {
        $args = @(
            "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $runner,
            "-Target", $t, "-Profile", $Profile, "-BaseUrl", $BaseUrl,
            "-Vus", "$Vus", "-Iterations", "$Iterations",
            "-MovieId", $MovieId, "-TheaterId", $TheaterId, "-ShowtimeId", $ShowtimeId,
            "-Date", $Date, "-Lat", $Lat, "-Lon", $Lon, "-Address", $Address
        )
        & powershell.exe @args 2>&1 | Tee-Object -FilePath $log
        $exitCode = $LASTEXITCODE
        $status = if ($exitCode -eq 0) { "PASS" } else { "FAIL" }
        $message = if ($exitCode -eq 0) { "테스트 성공" } else { "k6 종료 코드 $exitCode" }
    }
    catch {
        $exitCode = 1
        $status = "FAIL"
        $message = $_.Exception.Message
        $_ | Out-String | Tee-Object -FilePath $log -Append | Write-Host
    }

    $sw.Stop()
    $rows += [pscustomobject]@{
        Target = $t
        Status = $status
        DurationSeconds = [math]::Round($sw.Elapsed.TotalSeconds, 2)
        Message = $message
        Log = Split-Path $log -Leaf
    }

    if ($status -eq "FAIL") { Write-Host "$t : FAIL" -ForegroundColor Red }
    else { Write-Host "$t : PASS" -ForegroundColor Green }
}

$finished = Get-Date
$pass = @($rows | Where-Object Status -eq "PASS").Count
$fail = @($rows | Where-Object Status -eq "FAIL").Count

function HtmlEncode([string]$value) {
    return [System.Net.WebUtility]::HtmlEncode($value)
}

$trs = foreach ($r in $rows) {
    $cls = if ($r.Status -eq "PASS") { "pass" } else { "fail" }
    "<tr class='$cls'><td>$($r.Target)</td><td>$($r.Status)</td><td>$($r.DurationSeconds)s</td><td>$(HtmlEncode $r.Message)</td><td>$($r.Log)</td></tr>"
}

$html = @"
<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>SmartTicketing K6 Test Suite Report</title>
<style>
body{font-family:"Malgun Gothic","Noto Sans KR",Segoe UI,Arial,sans-serif;margin:0;background:#f5f7fb;color:#172033}
.wrap{max-width:1100px;margin:40px auto;padding:0 24px}
.card{background:#fff;border:1px solid #e3e7ef;border-radius:14px;padding:22px;margin:16px 0;box-shadow:0 4px 14px rgba(20,30,50,.05)}
.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:14px}
.metric{padding:18px;border-radius:12px;background:#f8f9fc}.label{font-size:13px;color:#687386}.value{font-size:27px;font-weight:700;margin-top:6px}
table{width:100%;border-collapse:collapse}th,td{padding:12px;border-bottom:1px solid #edf0f5;text-align:left}
.pass td:nth-child(2){font-weight:700}.fail td:nth-child(2){font-weight:700}
.meta{color:#687386;line-height:1.7}
@media(max-width:750px){.grid{grid-template-columns:1fr 1fr}table{font-size:13px}}
</style>
</head>
<body>
<div class="wrap">
<h1>SmartTicketing K6 테스트 결과</h1>
<div class="card meta">
<div><b>실행 범위:</b> $(HtmlEncode $Target)</div>
<div><b>Profile:</b> $(HtmlEncode $Profile) / <b>VUs:</b> $Vus / <b>Iterations:</b> $Iterations</div>
<div><b>BASE_URL:</b> $(HtmlEncode $BaseUrl)</div>
<div><b>시작:</b> $($started.ToString("yyyy-MM-dd HH:mm:ss"))</div>
<div><b>종료:</b> $($finished.ToString("yyyy-MM-dd HH:mm:ss"))</div>
</div>
<div class="card">
<div class="grid">
<div class="metric"><div class="label">전체 테스트</div><div class="value">$($rows.Count)</div></div>
<div class="metric"><div class="label">성공</div><div class="value">$pass</div></div>
<div class="metric"><div class="label">실패</div><div class="value">$fail</div></div>
<div class="metric"><div class="label">성공률</div><div class="value">$(if($rows.Count){[math]::Round($pass/$rows.Count*100,1)}else{0})%</div></div>
</div>
</div>
<div class="card">
<h2>기능별 테스트</h2>
<table>
<tr><th>Target</th><th>결과</th><th>소요시간</th><th>메시지</th><th>로그</th></tr>
$($trs -join "")
</table>
</div>
<div class="card meta">
<b>참고:</b> 각 테스트의 상세 k6 콘솔 결과는 <code>k6/jusang/results</code>에 저장됩니다.
Cache ON/OFF 성능 비교는 <code>generate-cache-report.ps1</code>를 별도로 사용합니다.
</div>
</div>
</body>
</html>
"@

[IO.File]::WriteAllText($reportPath, $html, (New-Object Text.UTF8Encoding($true)))

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "테스트 완료" -ForegroundColor Green
Write-Host "PASS: $pass / FAIL: $fail"
Write-Host "HTML: $reportPath"
Write-Host "LOG : $logDir"
Write-Host "========================================" -ForegroundColor Cyan

if ($OpenReport) { Start-Process $reportPath }
if ($fail -gt 0) { exit 1 }
exit 0
