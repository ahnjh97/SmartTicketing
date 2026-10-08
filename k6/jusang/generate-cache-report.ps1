param(
    [string]$InputJson = "$PSScriptRoot\cache-test-results.json",
    [string]$OutputHtml = "$PSScriptRoot\cache-test-report.html"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $InputJson)) {
    throw "결과 JSON 파일을 찾을 수 없습니다: $InputJson"
}

$data = Get-Content -Raw -Encoding UTF8 $InputJson | ConvertFrom-Json

function Fmt($value, $digits = 2) {
    return ("{0:N$digits}" -f [double]$value)
}

$on = $data.results.cache_on
$off = $data.results.cache_off
$cmp = $data.comparison

if ($null -eq $on -or $null -eq $off) {
    throw "결과 JSON에 cache_on/cache_off 데이터가 없습니다."
}

$required = @(
    $on.http_req_duration_ms.avg,
    $on.http_req_duration_ms.median,
    $on.http_req_duration_ms.p95,
    $on.throughput_req_per_sec,
    $off.http_req_duration_ms.avg,
    $off.http_req_duration_ms.median,
    $off.http_req_duration_ms.p95,
    $off.throughput_req_per_sec
)

if ($required | Where-Object { $null -eq $_ }) {
    throw "결과 JSON의 성능 지표가 비어 있습니다."
}

$html = @"
<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>SmartTicketing Cache Test Report</title>
<style>
body{font-family:Segoe UI,Arial,sans-serif;margin:0;background:#f5f7fb;color:#172033}
.wrap{max-width:1100px;margin:40px auto;padding:0 24px}
h1{margin-bottom:8px}.sub{color:#687386;margin-bottom:28px}
.card{background:#fff;border:1px solid #e3e7ef;border-radius:14px;padding:22px;margin:16px 0;box-shadow:0 4px 14px rgba(20,30,50,.05)}
.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}
.metric{padding:18px;border-radius:12px;background:#f8f9fc}.label{font-size:13px;color:#687386}.value{font-size:27px;font-weight:700;margin-top:6px}
table{width:100%;border-collapse:collapse}th,td{padding:12px;border-bottom:1px solid #edf0f5;text-align:right}th:first-child,td:first-child{text-align:left}
.on{font-weight:700}.good{font-weight:700}.note{color:#687386;line-height:1.6}
@media(max-width:750px){.grid{grid-template-columns:1fr}}
</style>
</head>
<body>
<div class="wrap">
<h1>SmartTicketing Redis Cache 부하테스트</h1>
<div class="sub">Target: $($data.test.target) · VUs: $($data.test.vus) · Iterations: $($data.test.iterations) · BASE_URL: $($data.test.base_url)</div>

<div class="card">
<h2>핵심 결과</h2>
<div class="grid">
<div class="metric"><div class="label">평균 응답시간 개선</div><div class="value">$(Fmt $cmp.avg_response_time_improvement_percent)%</div></div>
<div class="metric"><div class="label">P95 응답시간 개선</div><div class="value">$(Fmt $cmp.p95_response_time_improvement_percent)%</div></div>
<div class="metric"><div class="label">처리량 개선</div><div class="value">$(Fmt $cmp.throughput_improvement_percent)%</div></div>
</div>
</div>

<div class="card">
<h2>Cache ON / OFF 비교</h2>
<table>
<tr><th>지표</th><th>Cache ON</th><th>Cache OFF</th><th>개선</th></tr>
<tr><td>평균 응답시간</td><td>$($on.http_req_duration_ms.avg) ms</td><td>$($off.http_req_duration_ms.avg) ms</td><td>$(Fmt $cmp.avg_response_time_improvement_percent)%</td></tr>
<tr><td>중앙값</td><td>$($on.http_req_duration_ms.median) ms</td><td>$($off.http_req_duration_ms.median) ms</td><td>$(Fmt $cmp.median_response_time_improvement_percent)%</td></tr>
<tr><td>P95</td><td>$($on.http_req_duration_ms.p95) ms</td><td>$($off.http_req_duration_ms.p95) ms</td><td>$(Fmt $cmp.p95_response_time_improvement_percent)%</td></tr>
<tr><td>최대 응답시간</td><td>$($on.http_req_duration_ms.max) ms</td><td>$($off.http_req_duration_ms.max) ms</td><td>$(Fmt $cmp.max_response_time_improvement_percent)%</td></tr>
<tr><td>처리량</td><td>$($on.throughput_req_per_sec) req/s</td><td>$($off.throughput_req_per_sec) req/s</td><td>$(Fmt $cmp.throughput_improvement_percent)%</td></tr>
<tr><td>실패율</td><td>$($on.http_req_failed_rate_percent)%</td><td>$($off.http_req_failed_rate_percent)%</td><td>-</td></tr>
</table>
</div>

<div class="card">
<h2>테스트 검증</h2>
<p>Cache ON: checks $($on.checks_succeeded)/$($on.checks_total), HTTP 실패율 $($on.http_req_failed_rate_percent)%</p>
<p>Cache OFF: checks $($off.checks_succeeded)/$($off.checks_total), HTTP 실패율 $($off.http_req_failed_rate_percent)%</p>
<p class="note">$($cmp.interpretation)</p>
</div>
</div>
</body>
</html>
"@

Set-Content -Path $OutputHtml -Value $html -Encoding UTF8
Write-Host "HTML report created: $OutputHtml"
