param(
 [ValidateSet("smoke","load")][string]$Profile="load",
 [string]$CacheJson="$PSScriptRootcache-test-results.json",
 [string]$NearbyJson="$PSScriptRoot
earby-test-results.json",
 [string]$OutputHtml="$PSScriptRootoptimization-test-report.html"
)
$ErrorActionPreference="Stop"
$cache=Get-Content -Raw -Encoding UTF8 $CacheJson|ConvertFrom-Json
$near=Get-Content -Raw -Encoding UTF8 $NearbyJson|ConvertFrom-Json
function F($v){("{0:N2}" -f [double]$v)}
$labels=@{"1-common"="공통 조회";"1-catalog"="영화/극장 목록";"3-showtime"="상영시간 조회";"3-seat"="좌석맵 조회";"5-distance"="주변 극장 거리";"5-walk"="주변 극장 도보";"5-transit"="주변 극장 대중교통"}
$cacheRows="";$cacheCards=""
foreach($t in $cache.test.targets){
 $r=$cache.results.$t;$c=$r.comparison;$n=$labels[$t]
 $cacheRows+="<tr><td>$n</td><td>$(F $r.cache_on.http_req_duration_ms.avg)</td><td>$(F $r.cache_off.http_req_duration_ms.avg)</td><td>$(F $c.avg_response_time_improvement_percent)%</td><td>$(F $c.p95_response_time_improvement_percent)%</td><td>$(F $c.throughput_improvement_percent)%</td></tr>"
 $cacheCards+="<div class='card'><h2>$n <small>$t · Redis ON/OFF</small></h2><table><tr><th>지표</th><th>ON</th><th>OFF</th><th>개선</th></tr><tr><td>평균</td><td>$($r.cache_on.http_req_duration_ms.avg) ms</td><td>$($r.cache_off.http_req_duration_ms.avg) ms</td><td>$(F $c.avg_response_time_improvement_percent)%</td></tr><tr><td>중앙값</td><td>$($r.cache_on.http_req_duration_ms.median) ms</td><td>$($r.cache_off.http_req_duration_ms.median) ms</td><td>$(F $c.median_response_time_improvement_percent)%</td></tr><tr><td>P95</td><td>$($r.cache_on.http_req_duration_ms.p95) ms</td><td>$($r.cache_off.http_req_duration_ms.p95) ms</td><td>$(F $c.p95_response_time_improvement_percent)%</td></tr><tr><td>처리량</td><td>$($r.cache_on.throughput_req_per_sec) req/s</td><td>$($r.cache_off.throughput_req_per_sec) req/s</td><td>$(F $c.throughput_improvement_percent)%</td></tr></table></div>"
}
$nearRows="";$nearCards=""
foreach($t in $near.test.targets){
 $r=$near.results.$t;$n=$labels[$t]
 $nearRows+="<tr><td>$n</td><td>$(F $r.http_req_duration_ms.avg)</td><td>$(F $r.http_req_duration_ms.p95)</td><td>$(F $r.http_req_duration_ms.max)</td><td>$(F $r.throughput_req_per_sec)</td><td>$($r.http_req_failed_rate_percent)%</td></tr>"
 $nearCards+="<div class='card'><h2>$n <small>$t · 현재 구현 baseline</small></h2><table><tr><th>지표</th><th>값</th></tr><tr><td>평균</td><td>$($r.http_req_duration_ms.avg) ms</td></tr><tr><td>중앙값</td><td>$($r.http_req_duration_ms.median) ms</td></tr><tr><td>P95</td><td>$($r.http_req_duration_ms.p95) ms</td></tr><tr><td>최대</td><td>$($r.http_req_duration_ms.max) ms</td></tr><tr><td>처리량</td><td>$($r.throughput_req_per_sec) req/s</td></tr><tr><td>실패율</td><td>$($r.http_req_failed_rate_percent)%</td></tr><tr><td>Checks</td><td>$($r.checks_succeeded)/$($r.checks_total)</td></tr></table></div>"
}
$html=@"
<!doctype html><html lang='ko'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>SmartTicketing 1·3·5 Optimization Report</title>
<style>body{font-family:Segoe UI,Arial,sans-serif;margin:0;background:#f5f7fb;color:#172033}.wrap{max-width:1180px;margin:36px auto;padding:0 24px}.card{background:#fff;border:1px solid #e3e7ef;border-radius:14px;padding:22px;margin:16px 0}h1{margin-bottom:6px}h2{margin-top:0}small{font-size:12px;color:#687386;font-weight:400}table{width:100%;border-collapse:collapse}th,td{padding:11px;border-bottom:1px solid #edf0f5;text-align:right}th:first-child,td:first-child{text-align:left}.note{color:#687386;line-height:1.6}.tag{display:inline-block;padding:5px 9px;border-radius:8px;background:#f0f3f8;margin-right:6px}</style></head>
<body><div class='wrap'><h1>SmartTicketing 1·3·5 최적화 후보 테스트</h1>
<p class='note'>Profile: $Profile · 1/3은 Redis Cache ON/OFF 비교, 5는 현재 구현 baseline 측정입니다.</p>
<div class='card'><h2>1·3 Redis 비교 요약</h2><table><tr><th>기능</th><th>ON 평균(ms)</th><th>OFF 평균(ms)</th><th>평균 개선</th><th>P95 개선</th><th>처리량 개선</th></tr>$cacheRows</table></div>
$cacheCards
<div class='card'><h2>5 추가 후보 요약</h2><table><tr><th>기능</th><th>평균(ms)</th><th>P95(ms)</th><th>최대(ms)</th><th>처리량(req/s)</th><th>실패율</th></tr>$nearRows</table></div>
$nearCards
<div class='card'><h2>해석 기준</h2><p class='note'><span class='tag'>1·3</span> Redis ON/OFF의 동일 조건 차이로 캐시 적용 효과를 판단합니다.</p><p class='note'><span class='tag'>5</span> 현재는 Redis 캐시를 적용하지 않은 baseline입니다. 특히 도보/대중교통의 응답시간과 P95가 높으면 외부 경로 API 결과 캐싱 후보로 볼 수 있습니다.</p></div>
</div></body></html>
"@
Set-Content -Path $OutputHtml -Value $html -Encoding UTF8
Write-Host "Unified report created: $OutputHtml"
