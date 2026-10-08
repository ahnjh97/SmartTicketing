<#
.SYNOPSIS
Runs the isolated Linux Java 21 Redis comparison and produces an HTML report.
.EXAMPLE
.\scripts\benchmark\run-linux.ps1
.EXAMPLE
.\scripts\benchmark\run-linux.ps1 -Smoke
.NOTES
Requires the selected WSL distribution. Docker is installed there on first use.
The application, k6, MySQL and Redis start automatically; no .env is required.
Full results: benchmark-results/linux-java21-*/index.html. Smoke checks omit HTML.
Temporary containers and database volumes are removed; downloaded images are cached.
#>
param(
    [string]$Distribution = 'Ubuntu-26.04',
    [switch]$Smoke
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runId = 'linux-java21-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
$linuxRoot = (& wsl -d $Distribution -u root -- wslpath -a $projectRoot.Replace('\', '/') | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or -not $linuxRoot.StartsWith('/')) { throw "Cannot access WSL distribution $Distribution" }
$smokeValue = $Smoke.IsPresent.ToString().ToLowerInvariant()
& wsl -d $Distribution -u root -- bash "$linuxRoot/scripts/benchmark/run-linux.sh" $runId $smokeValue
$result = $LASTEXITCODE
$output = Join-Path $projectRoot "benchmark-results/$runId"
Write-Host "Results: $output"
if (-not $Smoke -and (Test-Path (Join-Path $output 'index.html'))) { Write-Host "HTML: $(Join-Path $output 'index.html')" }
if ($result -ne 0) { throw "Linux benchmark returned $result. Inspect the report and logs in $output." }
