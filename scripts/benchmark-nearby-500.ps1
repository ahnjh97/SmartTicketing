param(
    [int]$Runs = 500,
    [string]$BaseUrl = "http://localhost:8080",
    [double]$Latitude = 37.5665,
    [double]$Longitude = 126.9780,
    [string]$Address = "서울특별시 중구 세종대로 110",
    [ValidateSet("DISTANCE","TRANSIT","WALK")]
    [string]$Sort = "TRANSIT"
)

$ErrorActionPreference = "Stop"

$endpoint = "$BaseUrl/api/theaters/nearby?latitude=$Latitude&longitude=$Longitude&address=$([uri]::EscapeDataString($Address))&radius=10000&sort=$Sort"
$results = [System.Collections.Generic.List[object]]::new()

Write-Host "=============================================="
Write-Host " SmartTicketing Nearby Theater Benchmark"
Write-Host "=============================================="
Write-Host "Runs    : $Runs"
Write-Host "Endpoint: $endpoint"
Write-Host ""

Write-Host "[Warm-up] 5 requests..."
1..5 | ForEach-Object {
    try {
        Invoke-RestMethod -Uri $endpoint -Method Get -TimeoutSec 120 | Out-Null
    } catch {
        throw "Warm-up request failed: $($_.Exception.Message)"
    }
}

Write-Host "[Benchmark] $Runs requests..."
$overall = [System.Diagnostics.Stopwatch]::StartNew()

for ($i = 1; $i -le $Runs; $i++) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-RestMethod -Uri $endpoint -Method Get -TimeoutSec 120
        $sw.Stop()

        $count = if ($null -eq $response) { 0 } elseif ($response -is [System.Array]) { $response.Count } else { 1 }

        $results.Add([pscustomobject]@{
            Run = $i
            Success = $true
            Status = 200
            ResponseMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 2)
            TheaterCount = $count
        })
    } catch {
        $sw.Stop()
        $results.Add([pscustomobject]@{
            Run = $i
            Success = $false
            Status = 0
            ResponseMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 2)
            TheaterCount = 0
        })
    }

    if ($i % 50 -eq 0) {
        $ok = @($results | Where-Object Success).Count
        $runningAvg = ($results | Measure-Object ResponseMs -Average).Average
        Write-Host ("  {0,4}/{1}  success={2}  avg={3:N2} ms" -f $i, $Runs, $ok, $runningAvg)
    }
}

$overall.Stop()

$success = @($results | Where-Object Success)
$failed = $Runs - $success.Count

if ($success.Count -gt 0) {
    $avg = ($success | Measure-Object ResponseMs -Average).Average
    $min = ($success | Measure-Object ResponseMs -Minimum).Minimum
    $max = ($success | Measure-Object ResponseMs -Maximum).Maximum
    $sorted = @($success.ResponseMs | Sort-Object)
    $p50 = $sorted[[math]::Floor(($sorted.Count - 1) * 0.50)]
    $p95 = $sorted[[math]::Floor(($sorted.Count - 1) * 0.95)]
    $throughput = $success.Count / $overall.Elapsed.TotalSeconds

    Write-Host ""
    Write-Host "=============== RESULT ========================"
    Write-Host ("Requests       : {0}" -f $Runs)
    Write-Host ("Success        : {0}" -f $success.Count)
    Write-Host ("Failed         : {0}" -f $failed)
    Write-Host ("Error rate     : {0:N2}%" -f (($failed / $Runs) * 100))
    Write-Host ("Avg response   : {0:N2} ms" -f $avg)
    Write-Host ("Min response   : {0:N2} ms" -f $min)
    Write-Host ("Max response   : {0:N2} ms" -f $max)
    Write-Host ("P50            : {0:N2} ms" -f $p50)
    Write-Host ("P95            : {0:N2} ms" -f $p95)
    Write-Host ("Throughput     : {0:N2} req/s" -f $throughput)
    Write-Host ("Total elapsed  : {0:N2} s" -f $overall.Elapsed.TotalSeconds)
    Write-Host "================================================"
} else {
    Write-Host "All requests failed."
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = Join-Path $PSScriptRoot "..\benchmark-results\nearby-$Sort-$timestamp.csv"
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
$results | Export-Csv -Path $out -NoTypeInformation -Encoding UTF8
Write-Host ""
Write-Host "Raw results: $out"

Write-Host ""
Write-Host "DB average queries:"
Write-Host @"
SELECT COUNT(*) AS test_count,
       ROUND(AVG(total_api_calls),2) AS avg_api_calls,
       ROUND(AVG(api_response_time_ms),2) AS avg_api_ms,
       ROUND(AVG(db_query_time_ms),2) AS avg_db_ms,
       ROUND(AVG(db_save_time_ms),2) AS avg_db_save_ms,
       ROUND(AVG(total_response_time_ms),2) AS avg_total_ms
FROM nearby_theater_performance_api_original;

SELECT COUNT(*) AS test_count,
       ROUND(AVG(total_api_calls),2) AS avg_api_calls,
       ROUND(AVG(api_response_time_ms),2) AS avg_api_ms,
       ROUND(AVG(db_query_time_ms),2) AS avg_db_ms,
       ROUND(AVG(db_save_time_ms),2) AS avg_db_save_ms,
       ROUND(AVG(total_response_time_ms),2) AS avg_total_ms
FROM nearby_theater_performance_db_sequential;

SELECT COUNT(*) AS test_count,
       ROUND(AVG(total_api_calls),2) AS avg_api_calls,
       ROUND(AVG(api_response_time_ms),2) AS avg_api_ms,
       ROUND(AVG(db_query_time_ms),2) AS avg_db_ms,
       ROUND(AVG(db_save_time_ms),2) AS avg_db_save_ms,
       ROUND(AVG(total_response_time_ms),2) AS avg_total_ms
FROM nearby_theater_performance_db_parallel;
"@
