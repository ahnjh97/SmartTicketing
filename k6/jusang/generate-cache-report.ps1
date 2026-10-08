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

$results = $data.results
if ($null -eq $results) {
    throw "결과 JSON에 기능별 results 데이터가 없습니다."
}

$labels = [ordered]@{
    "1-common" = "공통 조회"
    "1-catalog" = "영화 목록"
    "3-showtime" = "상영시간 조회"
    "3-seat" = "좌석맵 조회"
    "5-distance" = "거리순 극장 조회"
    "5-walk" = "도보 극장 조회"
    "5-transit" = "대중교통 극장 조회"
}

$cards = ""
$rows = ""
foreach ($target in $data.test.targets) {
    $r = $results.$target
    if ($null -eq $r) { throw "결과 JSON에 $target 데이터가 없습니다." }

    $on = $r.cache_on
    $off = $r.cache_off
    $cmp = $r.comparison

    $required = @(
        $on.http_req_duration_ms.avg, $on.http_req_duration_ms.median,
        $on.http_req_duration_ms.p95, $on.throughput_req_per_sec,
        $off.http_req_duration_ms.avg, $off.http_req_duration_ms.median,
        $off.http_req_duration_ms.p95, $off.throughput_req_per_sec
    )
    if ($required | Where-Object { $null -eq $_ }) {
        throw "$target 결과의 성능 지표가 비어 있습니다."
    }

    $name = $labels[$target]
    $cards += @"
<div class="card">
<h2>$name <span class="target">$target</span></h2>
<div class="grid">
<div class="metric"><div class="label">평균 응답시간 개선</div><div class="value">$(Fmt $cmp.avg_response_time_improvement_percent)%</div></div>
<div class="metric"><div class="label">P95 개선</div><div class="value">$(Fmt $cmp.p95_response_time_improvement_percent)%</div></div>
<div class="metric"><div class="label">처리량 개선</div><div class="value">$(Fmt $cmp.throughput_improvement_percent)%</div></div>
</div>
<table>
<tr><th>지표</th><th>Cache ON</th><th>Cache OFF</th><th>개선</th></tr>
<tr><td>평균 응답시간</td><td>$($on.http_req_duration_ms.avg) ms</td><td>$($off.http_req_duration_ms.avg) ms</td><td>$(Fmt $cmp.avg_response_time_improvement_percent)%</td></tr>
<tr><td>중앙값</td><td>$($on.http_req_duration_ms.median) ms</td><td>$($off.http_req_duration_ms.median) ms</td><td>$(Fmt $cmp.median_response_time_improvement_percent)%</td></tr>
<tr><td>P95</td><td>$($on.http_req_duration_ms.p95) ms</td><td>$($off.http_req_duration_ms.p95) ms</td><td>$(Fmt $cmp.p95_response_time_improvement_percent)%</td></tr>
<tr><td>최대 응답시간</td><td>$($on.http_req_duration_ms.max) ms</td><td>$($off.http_req_duration_ms.max) ms</td><td>$(Fmt $cmp.max_response_time_improvement_percent)%</td></tr>
<tr><td>처리량</td><td>$($on.throughput_req_per_sec) req/s</td><td>$($off.throughput_req_per_sec) req/s</td><td>$(Fmt $cmp.throughput_improvement_percent)%</td></tr>
<tr><td>실패율</td><td>$($on.http_req_failed_rate_percent)%</td><td>$($off.http_req_failed_rate_percent)%</td><td>-</td></tr>
<tr><td>Checks</td><td>$($on.checks_succeeded)/$($on.checks_total)</td><td>$($off.checks_succeeded)/$($off.checks_total)</td><td>-</td></tr>
</table>
</div>
"@

    $rows += "<tr><td>$name</td><td>$($on.http_req_duration_ms.avg)</td><td>$($off.http_req_duration_ms.avg)</td><td>$($cmp.avg_response_time_improvement_percent)%</td><td>$($cmp.p95_response_time_improvement_percent)%</td><td>$($cmp.throughput_improvement_percent)%</td></tr>"
}

$html = @"
<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>SmartTicketing Redis Cache Test Report</title>
<style>
body{font-family:Segoe UI,Arial,sans-serif;margin:0;background:#f5f7fb;color:#172033}
.wrap{max-width:1180px;margin:40px auto;padding:0 24px}
h1{margin-bottom:8px}.sub{color:#687386;margin-bottom:28px}
.card{background:#fff;border:1px solid #e3e7ef;border-radius:14px;padding:22px;margin:16px 0;box-shadow:0 4px 14px rgba(20,30,50,.05)}
.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin-bottom:18px}
.metric{padding:18px;border-radius:12px;background:#f8f9fc}.label{font-size:13px;color:#687386}.value{font-size:27px;font-weight:700;margin-top:6px}
table{width:100%;border-collapse:collapse}th,td{padding:12px;border-bottom:1px solid #edf0f5;text-align:right}th:first-child,td:first-child{text-align:left}
.target{font-size:12px;color:#687386;font-weight:400;margin-left:8px}
.summary{overflow-x:auto}.note{color:#687386;line-height:1.6}
@media(max-width:750px){.grid{grid-template-columns:1fr}}
</style>
</head>
<body>
<div class="wrap">
<h1>SmartTicketing Redis Cache 기능별 부하테스트</h1>
<div class="sub">Profile: $($data.test.profile) · VUs: $($data.test.vus) · Iterations: $($data.test.iterations) · BASE_URL: $($data.test.base_url)</div>

<div class="card summary">
<h2>전체 기능 요약</h2>
<table>
<tr><th>기능</th><th>ON 평균(ms)</th><th>OFF 평균(ms)</th><th>평균 개선</th><th>P95 개선</th><th>처리량 개선</th></tr>
$rows
</table>
</div>

$cards

<div class="card">
<h2>테스트 방식</h2>
<p class="note">동일한 k6 조건으로 각 기능을 Redis Cache ON과 OFF에서 각각 측정했습니다. 모든 기능의 ON 측정을 완료한 뒤 OFF 측정을 진행하여 기능별 결과를 비교합니다.</p>
<p class="note">Cache ON/OFF는 Smart-booking summary cache 모드만 전환하며 QR Redis는 계속 ON 상태입니다.</p>
</div>
</div>
</body>
</html>
"@

Set-Content -Path $OutputHtml -Value $html -Encoding UTF8
Write-Host "HTML report created: $OutputHtml"
