<# 
.SYNOPSIS
Runs only the SmartTicketing waiting-rank / booking queue Redis benchmark and keeps its HTML/JSON report.
.DESCRIPTION
This deliberately delegates to the isolated Linux Java 21 booking suite. It does NOT call
k6/redis-benchmark/run-cache-comparison.ps1, which measures unrelated read-query endpoints.
The suite seeds its own temporary MySQL schema and scoped Redis keys, then measures rank reads
with 100/400/800 VUs against 1,000 waiting rows and checks returned aheadCount values. It also
runs the queue-dispatch correctness scenario. The runner generates the HTML report automatically.
.EXAMPLE
.\scripts\benchmark\run-waiting-rank.ps1
.EXAMPLE
.\scripts\benchmark\run-waiting-rank.ps1 -Smoke
#>
[CmdletBinding()]
param(
    [switch]$Smoke,
    [ValidateSet('compare','on','off')][string]$CacheMode = 'compare',
    [string]$Distribution = 'Ubuntu'
)
$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot 'run-components.ps1'
if (-not (Test-Path $runner)) {
    throw "Waiting-rank runner not found: $runner"
}
$args = @{
    Suite = 'booking'
    CacheMode = $CacheMode
    Distribution = $Distribution
}
if ($Smoke) { $args.Smoke = $true }
Write-Host 'Running the booking waiting-rank suite only.' -ForegroundColor Cyan
Write-Host 'This does not run the unrelated redis-benchmark query comparison.' -ForegroundColor Cyan
& $runner @args
if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) {
    throw "Waiting-rank benchmark failed with exit code $LASTEXITCODE."
}
$latest = Get-ChildItem (Join-Path (Split-Path $PSScriptRoot -Parent | Split-Path -Parent) 'benchmark-results') -Directory -Filter 'linux-java21-*' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($null -eq $latest) { throw 'No benchmark output directory was created.' }
Write-Host ''
Write-Host "Result directory: $($latest.FullName)" -ForegroundColor Green
if (Test-Path (Join-Path $latest.FullName 'index.html')) {
    Write-Host "HTML report: $(Join-Path $latest.FullName 'index.html')" -ForegroundColor Green
} else {
    Write-Host 'HTML report was not created (smoke runs intentionally omit the full report).' -ForegroundColor Yellow
}
