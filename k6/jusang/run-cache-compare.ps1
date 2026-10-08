[CmdletBinding()]
param(
    [ValidateSet("1-common","1-catalog","3-showtime","3-seat","5-distance")]
    [string]$Target = "1-common",

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

    # Project-root cache switch command. Example:
    # .\local.cmd on / .\local.cmd off
    [string]$CacheCommand = "",

    # By default, use the project's local.cmd if it exists.
    [string]$ProjectRoot = "",

    [switch]$SkipCacheSwitch
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

if ([string]::IsNullOrWhiteSpace($CacheCommand)) {
    $candidate = Join-Path $ProjectRoot "local.cmd"
    if (Test-Path $candidate) {
        $CacheCommand = $candidate
    }
}

$script = switch ($Target) {
    "1-common"   { "01-common-query.js" }
    "1-catalog"  { "01-catalog-query.js" }
    "3-showtime" { "03-showtime-query.js" }
    "3-seat"     { "03-seat-map-query.js" }
    "5-distance" { "05-nearby-distance.js" }
}

$outDir = Join-Path $PSScriptRoot ("results\" + (Get-Date -Format "yyyyMMdd-HHmmss") + "-" + $Target)
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

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

function Switch-Cache([string]$mode) {
    if ($SkipCacheSwitch) {
        Write-Host "[CACHE] switch skipped: $mode"
        return
    }

    if ([string]::IsNullOrWhiteSpace($CacheCommand)) {
        throw "Cache switch command was not found. Pass -CacheCommand 'C:\dev\SmartTicketing\SmartTicketing\local.cmd' or use -SkipCacheSwitch."
    }

    if (!(Test-Path $CacheCommand)) {
        throw "Cache command not found: $CacheCommand"
    }

    Write-Host "[CACHE] switching to $mode ..."
    & cmd.exe /c "`"$CacheCommand`" $mode"
    if ($LASTEXITCODE -ne 0) {
        throw "Cache switch failed: $mode"
    }

    Start-Sleep -Seconds 2
}

function Run-K6([string]$label, [string]$summaryPath) {
    Write-Host ""
    Write-Host "========== $label =========="
    Write-Host "Target=$Target VUs=$Vus Iterations=$Iterations"

    $path = Join-Path $PSScriptRoot $script
    k6 run $path "--summary-export=$summaryPath"
    if ($LASTEXITCODE -ne 0) {
        throw "k6 test failed: $label"
    }
}

Write-Host ""
Write-Host "Cache comparison"
Write-Host "Target      : $Target"
Write-Host "BASE_URL    : $BaseUrl"
Write-Host "VUs         : $Vus"
Write-Host "Iterations  : $Iterations"
Write-Host "Result dir  : $outDir"
Write-Host ""

# 1) Cache OFF: establishes the uncached baseline.
Switch-Cache "off"
Run-K6 "CACHE OFF" (Join-Path $outDir "off.json")

# 2) Cache ON, first pass: cold-ish cache.
Switch-Cache "on"
Run-K6 "CACHE ON - COLD" (Join-Path $outDir "on-cold.json")

# 3) Cache ON, second pass: warm cache.
Run-K6 "CACHE ON - WARM" (Join-Path $outDir "on-warm.json")

Write-Host ""
Write-Host "========== RESULT FILES =========="
Get-ChildItem $outDir -Filter "*.json" | Select-Object Name, Length, LastWriteTime | Format-Table -AutoSize

Write-Host ""
Write-Host "Compare p95 and avg from the three summary JSON files."
Write-Host "Result directory: $outDir"
