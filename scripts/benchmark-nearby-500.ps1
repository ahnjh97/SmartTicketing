param(
    [int]$Runs = 500,
    [int]$WarmupRuns = 5,
    [int]$Port = 8080,
    [string]$Sort = "TRANSIT",
    [double]$Latitude = 37.5665,
    [double]$Longitude = 126.9780,
    [string]$Address = "서울특별시 중구 세종대로 110"
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$originalBranch = (git -C $repo branch --show-current).Trim()
$branches = @(
    @{ Name = "perf/api-original"; Label = "API Original" },
    @{ Name = "perf/db-sequential"; Label = "DB Sequential" },
    @{ Name = "feature/jusang"; Label = "DB Parallel" }
)

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outDir = Join-Path $repo "benchmark-results"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$summaryPath = Join-Path $outDir "benchmark-500-summary-$timestamp.csv"
$rawPath = Join-Path $outDir "benchmark-500-raw-$timestamp.csv"
$allRaw = [System.Collections.Generic.List[object]]::new()
$summary = [System.Collections.Generic.List[object]]::new()

function Stop-App($process) {
    if ($null -ne $process -and !$process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }
}

function Wait-Server($baseUrl) {
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        try {
            $null = Invoke-WebRequest -Uri "$baseUrl/api/theaters/nearby?latitude=$Latitude&longitude=$Longitude&address=$([uri]::EscapeDataString($Address))&radius=10000&sort=DISTANCE" -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop
            return
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "Server did not become ready within 120 seconds."
}

try {
    foreach ($b in $branches) {
        Write-Host ""
        Write-Host "===== $($b.Label) / $($b.Name) ====="

        git -C $repo checkout $b.Name
        if ($LASTEXITCODE -ne 0) { throw "checkout failed: $($b.Name)" }

        Write-Host "[BUILD] bootJar"
        & "$repo\gradlew.bat" -q bootJar
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed: $($b.Name)" }

        $jar = Get-ChildItem "$repo\build\libs\*.jar" | Where-Object { $_.Name -notmatch "plain" } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($null -eq $jar) { throw "Executable jar not found." }

        $logPath = Join-Path $outDir "server-$($b.Name.Replace('/','-'))-$timestamp.log"
        $server = Start-Process -FilePath "java.exe" -ArgumentList @("-jar", $jar.FullName, "--server.port=$Port") -WorkingDirectory $repo -RedirectStandardOutput $logPath -RedirectStandardError $logPath -PassThru

        try {
            Wait-Server "http://localhost:$Port"
            Write-Host "[READY]"

            $endpoint = "http://localhost:$Port/api/theaters/nearby?latitude=$Latitude&longitude=$Longitude&address=$([uri]::EscapeDataString($Address))&radius=10000&sort=$Sort"

            Write-Host "[WARMUP] $WarmupRuns"
            1..$WarmupRuns | ForEach-Object { $null = Invoke-RestMethod -Uri $endpoint -Method Get -TimeoutSec 120 }

            Write-Host "[TEST] $Runs requests"
            $branchSw = [System.Diagnostics.Stopwatch]::StartNew()

            for ($i = 1; $i -le $Runs; $i++) {
                $sw = [System.Diagnostics.Stopwatch]::StartNew()
                $ok = $false
                $status = 0
                $count = 0
                try {
                    $response = Invoke-RestMethod -Uri $endpoint -Method Get -TimeoutSec 120
                    $ok = $true
                    $status = 200
                    $count = if ($null -eq $response) { 0 } elseif ($response -is [System.Array]) { $response.Count } else { 1 }
                } catch {
                    $status = 0
                } finally {
                    $sw.Stop()
                }

                $allRaw.Add([pscustomobject]@{ Branch=$b.Name; Label=$b.Label; Run=$i; Success=$ok; Status=$status; ResponseMs=[math]::Round($sw.Elapsed.TotalMilliseconds,2); TheaterCount=$count })

                if ($i % 50 -eq 0) {
                    $rowsNow = @($allRaw | Where-Object { $_.Branch -eq $b.Name -and $_.Success })
                    $avgNow = if ($rowsNow.Count) { ($rowsNow | Measure-Object ResponseMs -Average).Average } else { 0 }
                    Write-Host ("  {0}/{1} success={2} avg={3:N2}ms" -f $i,$Runs,$rowsNow.Count,$avgNow)
                }
            }

            $branchSw.Stop()
            $rows = @($allRaw | Where-Object { $_.Branch -eq $b.Name })
            $okRows = @($rows | Where-Object Success)
            $failed = $Runs - $okRows.Count
            if ($okRows.Count -eq 0) { throw "All requests failed on $($b.Name)." }

            $sorted = @($okRows.ResponseMs | Sort-Object)
            $avg = ($okRows | Measure-Object ResponseMs -Average).Average
            $min = ($okRows | Measure-Object ResponseMs -Minimum).Minimum
            $max = ($okRows | Measure-Object ResponseMs -Maximum).Maximum
            $p50 = $sorted[[math]::Floor(($sorted.Count - 1) * .50)]
            $p95 = $sorted[[math]::Floor(($sorted.Count - 1) * .95)]
            $throughput = $okRows.Count / $branchSw.Elapsed.TotalSeconds

            $summary.Add([pscustomobject]@{ Branch=$b.Name; Label=$b.Label; Requests=$Runs; Success=$okRows.Count; Failed=$failed; ErrorRatePct=[math]::Round(($failed/$Runs)*100,2); AvgResponseMs=[math]::Round($avg,2); MinResponseMs=[math]::Round($min,2); MaxResponseMs=[math]::Round($max,2); P50Ms=[math]::Round($p50,2); P95Ms=[math]::Round($p95,2); ThroughputReqPerSec=[math]::Round($throughput,2); TotalElapsedSec=[math]::Round($branchSw.Elapsed.TotalSeconds,2) })
        } finally {
            Write-Host "[STOP]"
            Stop-App $server
            Start-Sleep -Seconds 2
        }
    }

    $allRaw | Export-Csv -Path $rawPath -NoTypeInformation -Encoding UTF8
    $summary | Export-Csv -Path $summaryPath -NoTypeInformation -Encoding UTF8

    $base = $summary | Where-Object { $_.Branch -eq "perf/api-original" } | Select-Object -First 1
    Write-Host ""
    Write-Host "================ FINAL SUMMARY ================"
    $summary | Format-Table Branch,Requests,Success,ErrorRatePct,AvgResponseMs,MinResponseMs,MaxResponseMs,P50Ms,P95Ms,ThroughputReqPerSec -AutoSize

    if ($null -ne $base -and [double]$base.AvgResponseMs -gt 0) {
        Write-Host ""
        Write-Host "Improvement vs API Original:"
        foreach ($r in $summary) {
            $improvement = (($base.AvgResponseMs - $r.AvgResponseMs) / $base.AvgResponseMs) * 100
            Write-Host ("  {0,-20} {1,8:N2}%  avg={2:N2}ms" -f $r.Label,$improvement,$r.AvgResponseMs)
        }
    }

    Write-Host ""
    Write-Host "Summary: $summaryPath"
    Write-Host "Raw    : $rawPath"
}
finally {
    try { git -C $repo checkout $originalBranch | Out-Null } catch {}
}
