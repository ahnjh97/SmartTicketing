param(
    [Parameter(Mandatory=$true)][string]$BaselineSummary,
    [Parameter(Mandatory=$true)][string]$OptimizedSummary,
    [Parameter(Mandatory=$true)][string]$OutputPath
)
$ErrorActionPreference = "Stop"
$b = Get-Content -Raw -Encoding UTF8 $BaselineSummary | ConvertFrom-Json
$o = Get-Content -Raw -Encoding UTF8 $OptimizedSummary | ConvertFrom-Json
function Metric($summary, $name, $key) {
    $metric = $summary.metrics.PSObject.Properties[$name]
    if ($null -eq $metric -or $null -eq $metric.Value.values) { return $null }
    $prop = $metric.Value.values.PSObject.Properties[$key]
    if ($null -eq $prop) { return $null }
    return [double]$prop.Value
}
function CountMetric($summary, $name) {
    $v = Metric $summary $name "count"
    if ($null -eq $v) { return 0.0 }
    return $v
}
function Fmt($v, $digits=2) {
    if ($null -eq $v -or [double]::IsNaN([double]$v)) { return "N/A" }
    return ([double]$v).ToString("N$digits", [Globalization.CultureInfo]::InvariantCulture)
}
function Improvement($before, $after, $lowerIsBetter=$true) {
    if ($null -eq $before -or $null -eq $after -or $before -eq 0) { return "N/A" }
    if ($null -eq $lowerIsBetter) { return "참고" }
    if ($lowerIsBetter) { $pct = (($before - $after) / $before) * 100 }
    else { $pct = (($after - $before) / $before) * 100 }
    return (Fmt $pct) + "%"
}
$rows = @(
    @{ Label="요청 지연시간 평균 (ms)"; B=(Metric $b "http_req_duration" "avg"); O=(Metric $o "http_req_duration" "avg"); Lower=$true },
    @{ Label="요청 지연시간 p95 (ms)"; B=(Metric $b "http_req_duration" "p(95)"); O=(Metric $o "http_req_duration" "p(95)"); Lower=$true },
    @{ Label="요청 지연시간 p99 (ms)"; B=(Metric $b "http_req_duration" "p(99)"); O=(Metric $o "http_req_duration" "p(99)"); Lower=$true },
    @{ Label="요청 지연시간 최대 (ms)"; B=(Metric $b "http_req_duration" "max"); O=(Metric $o "http_req_duration" "max"); Lower=$true },
    @{ Label="요청 처리량 (요청/초)"; B=(Metric $b "http_reqs" "rate"); O=(Metric $o "http_reqs" "rate"); Lower=$false },
    @{ Label="HTTP 요청 수"; B=(CountMetric $b "http_reqs"); O=(CountMetric $o "http_reqs"); Lower=$null },
    @{ Label="예매 성공 (201)"; B=(CountMetric $b "booking_created"); O=(CountMetric $o "booking_created"); Lower=$null },
    @{ Label="좌석 충돌 (409)"; B=(CountMetric $b "booking_conflict"); O=(CountMetric $o "booking_conflict"); Lower=$null },
    @{ Label="예상 외 응답"; B=(CountMetric $b "booking_unexpected"); O=(CountMetric $o "booking_unexpected"); Lower=$true }
)
$tbody = foreach ($r in $rows) {
    $bv = if ($null -eq $r.B) { "N/A" } else { Fmt $r.B }
    $ov = if ($null -eq $r.O) { "N/A" } else { Fmt $r.O }
    $imp = Improvement $r.B $r.O $r.Lower
    "<tr><th>$([System.Net.WebUtility]::HtmlEncode($r.Label))</th><td>$bv</td><td>$ov</td><td>$imp</td></tr>"
}
$baseUrl = [System.Net.WebUtility]::HtmlEncode([string]$b.state.testRunDurationMs)
$stamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
$html = @"
<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>좌석 선점 잠금 성능 비교</title>
<style>
:root{color-scheme:light}body{font-family:"Malgun Gothic","맑은 고딕","Apple SD Gothic Neo",sans-serif;max-width:1100px;margin:32px auto;padding:0 20px;color:#202124;line-height:1.55}
h1{font-size:26px}p{color:#4b5563}.meta{background:#f3f4f6;border-radius:8px;padding:12px 16px}
table{border-collapse:collapse;width:100%;margin:22px 0;font-variant-numeric:tabular-nums}th,td{border:1px solid #d1d5db;padding:11px;text-align:right}th:first-child{text-align:left}thead th{background:#f3f4f6}
.note{background:#fff7ed;border-left:4px solid #ea580c;padding:12px 16px}.small{font-size:13px;color:#6b7280}
</style>
</head>
<body>
<h1>좌석 선점 잠금 성능 비교 리포트</h1>
<p>생성 시각: $stamp</p>
<div class="meta">
<strong>기존 코드</strong>: $( [System.Net.WebUtility]::HtmlEncode([string]$b.metrics.http_req_duration.values.'p(95)') ) ms p95<br>
<strong>개선 코드</strong>: $( [System.Net.WebUtility]::HtmlEncode([string]$o.metrics.http_req_duration.values.'p(95)') ) ms p95<br>
<strong>부하 설정</strong>: VU/반복 수는 실행 스크립트의 설정을 확인하세요.
</div>
<table>
<thead><tr><th>측정 지표</th><th>기존 코드</th><th>개선 코드</th><th>개선율</th></tr></thead>
<tbody>
$($tbody -join "`n")
</tbody>
</table>
<div class="note"><strong>해석 주의</strong><br>
201은 선점 성공, 409는 비즈니스 충돌 응답으로 집계합니다. 두 결과의 409 비율이 크게 다르면 처리량·지연시간만으로 성능을 단정하지 마세요. 예상 외 응답은 401/403/404/5xx 등을 포함하므로 fixture와 서버 로그를 확인하세요. 비교 대상 서버는 같은 DB 구성, 데이터 규모, JVM 설정 및 부하 조건을 사용해야 합니다.
</div>
<p class="small">원본 k6 요약 파일: baseline-summary.json, optimized-summary.json. 이 리포트는 UTF-8 인코딩으로 저장됩니다.</p>
</body>
</html>
"@
$encoding = New-Object System.Text.UTF8Encoding($true)
[System.IO.File]::WriteAllText([System.IO.Path]::GetFullPath($OutputPath), $html, $encoding)
