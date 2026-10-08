param(
    [string]$InputJson = "$PSScriptRoot
earby-test-results.json",
    [string]$OutputHtml = "$PSScriptRoot
earby-test-report.html"
)
$ErrorActionPreference="Stop"
$data=Get-Content -Raw -Encoding UTF8 $InputJson | ConvertFrom-Json
$labels=@{"5-distance"="거리순 극장 조회";"5-walk"="도보 경로 극장 조회";"5-transit"="대중교통 경로 극장 조회"}
$cards=""
foreach($t in $data.test.targets){
  $r=$data.results.$t; $name=$labels[$t]
  $cards += "<div class='card'><h2>$name <span>$t</span></h2><table><tr><th>지표</th><th>값</th></tr><tr><td>평균</td><td>$($r.http_req_duration_ms.avg) ms</td></tr><tr><td>중앙값</td><td>$($r.http_req_duration_ms.median) ms</td></tr><tr><td>P95</td><td>$($r.http_req_duration_ms.p95) ms</td></tr><tr><td>최대</td><td>$($r.http_req_duration_ms.max) ms</td></tr><tr><td>처리량</td><td>$($r.throughput_req_per_sec) req/s</td></tr><tr><td>실패율</td><td>$($r.http_req_failed_rate_percent)%</td></tr><tr><td>Checks</td><td>$($r.checks_succeeded)/$($r.checks_total)</td></tr></table></div>"
}
$html="<!doctype html><html lang='ko'><head><meta charset='utf-8'><title>SmartTicketing Nearby Baseline</title><style>body{font-family:Segoe UI,Arial;margin:0;background:#f5f7fb;color:#172033}.wrap{max-width:1000px;margin:40px auto;padding:0 24px}.card{background:#fff;border:1px solid #e3e7ef;border-radius:14px;padding:22px;margin:16px 0}table{width:100%;border-collapse:collapse}th,td{padding:12px;border-bottom:1px solid #edf0f5;text-align:right}th:first-child,td:first-child{text-align:left}span{font-size:12px;color:#687386}.note{line-height:1.6;color:#687386}</style></head><body><div class='wrap'><h1>SmartTicketing 5번 추가 후보 Baseline</h1><p class='note'>Profile: $($data.test.profile) · VUs: $($data.test.vus) · Iterations: $($data.test.iterations)</p><p class='note'>$($data.interpretation)</p>$cards</div></body></html>"
Set-Content -Path $OutputHtml -Value $html -Encoding UTF8
