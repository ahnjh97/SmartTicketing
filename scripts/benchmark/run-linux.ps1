param(
    [string]$Distribution = 'Ubuntu',
    [ValidateSet('focus','waiting','seats','showtimes','dispatch')][string]$Suite = 'focus',
    [switch]$Smoke,
    [ValidateSet('compare','on','off')][string]$CacheMode = 'compare',
    [ValidateRange(1,10000)][int]$Rate = 50,
    [ValidatePattern('^[1-9][0-9]*(s|m)$')][string]$Duration = '60s',
    [ValidateRange(1,10000)][int]$PreAllocatedVUs = 100,
    [ValidateRange(1,10000)][int]$MaxVUs = 1000,
    [ValidatePattern('^[a-fA-F0-9]{7,40}$')][string]$BaselineRef = '0b40982b2f04db3a0b0cbb996647f5b2fb1d72c6',
    [switch]$List
)
$ErrorActionPreference = 'Stop'
if ($List) { Write-Host 'focus (전체), waiting (대기순번), seats (좌석도), showtimes (시간표), dispatch (스마트 선점)'; return }
if ($PreAllocatedVUs -gt $MaxVUs) { throw 'PreAllocatedVUs must not exceed MaxVUs.' }
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'resolve-wsl-distribution.ps1')
$Distribution = Resolve-BenchmarkWslDistribution -Distribution $Distribution
$linuxRoot = (& wsl -d $Distribution -u root -- wslpath -a $projectRoot.Replace('\', '/') | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or -not $linuxRoot.StartsWith('/')) { throw 'Cannot access WSL project directory.' }
$runId = 'linux-java21-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
$startedAt = [DateTimeOffset]::Now.ToString('o')
Write-Host "Core 4 / $Suite / before=$BaselineRef (OFF), after=current checkout (ON) / smoke=$Smoke"
& wsl -d $Distribution -u root -- bash "$linuxRoot/scripts/benchmark/run-linux.sh" $runId $Smoke.IsPresent.ToString().ToLowerInvariant() $Suite $Rate $Duration $PreAllocatedVUs $MaxVUs 100 $CacheMode $BaselineRef
$result = $LASTEXITCODE
$output = Join-Path $projectRoot "benchmark-results/$runId"
New-Item -ItemType Directory -Force -Path $output | Out-Null
@{schemaVersion=2; suite=$Suite; cacheMode=$CacheMode; baselineRef=$BaselineRef; smoke=$Smoke.IsPresent; startedAt=$startedAt; exitCode=$result} |
    ConvertTo-Json | Set-Content (Join-Path $output 'run-info.json') -Encoding utf8
if (-not (Test-Path (Join-Path $output 'index.html')) -and (Get-Command node -ErrorAction SilentlyContinue)) {
    & node (Join-Path $PSScriptRoot 'core-report.mjs') $output $Suite $Smoke.IsPresent.ToString().ToLowerInvariant() $CacheMode
}
Write-Host "Report: $output/index.html"
if ($result -ne 0) { throw "Benchmark failed ($result); inspect $output. Failed runs are not improvements." }
