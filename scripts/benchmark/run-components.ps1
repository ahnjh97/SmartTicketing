<#
.SYNOPSIS
Selects isolated Linux Java 21 k6 scenarios and produces HTML reports.
.EXAMPLE
.\scripts\benchmark\run-components.ps1
.EXAMPLE
.\scripts\benchmark\run-components.ps1 -Suite all -Smoke
.EXAMPLE
.\scripts\benchmark\run-components.ps1 -Suite main -Rate 100 -Duration 60s
.EXAMPLE
.\scripts\benchmark\run-components.ps1 -Suite main -CacheMode on -Smoke
.NOTES
Requires the selected WSL distribution. Docker is installed there on first use.
The application, k6, MySQL and Redis start automatically; no .env is required.
Results: benchmark-results/linux-java21-*/index.html. Booking-only smoke omits HTML.
Rate/Duration/PreAllocatedVUs/MaxVUs apply to query tests; LoginUsers to login.
Booking retains its existing 100/400/800 VU load profile.
Each scenario/mode uses a fresh production-image backend and database, then identical warmup.
The production Dockerfile prebuilt target runs app.jar; k6 runs in a separate container.
Redis AOF is enabled as in deployment. Only this run's private Redis is cleared between cases.
This uses the current checkout, not necessarily the image currently deployed to AWS.
Local cache stays enabled with the same TTL in both Redis modes.
CacheMode compare (default) runs both modes; on/off runs only the requested mode.
Independent startups make full suites slower than the previous shared-JVM runner.
Temporary containers and database volumes are removed; downloaded images are cached.
#>
param(
    [string]$Distribution = 'Ubuntu',
    [switch]$Smoke,
    [string]$Suite = '',
    [ValidateSet('compare','on','off')][string]$CacheMode = 'compare',
    [ValidateRange(1,10000)][int]$Rate = 50,
    [ValidatePattern('^[1-9][0-9]*(s|m)$')][string]$Duration = '60s',
    [ValidateRange(1,10000)][int]$PreAllocatedVUs = 100,
    [ValidateRange(1,10000)][int]$MaxVUs = 1000,
    [ValidateRange(1,10000)][int]$LoginUsers = 100,
    [switch]$List
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
[array]$suites = Get-Content (Join-Path $PSScriptRoot 'suites.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$interactive = [string]::IsNullOrWhiteSpace($Suite)
if ($List -or [string]::IsNullOrWhiteSpace($Suite)) {
    Write-Host "`nLinux Java 21 / k6" -ForegroundColor Cyan
    for ($i=0; $i -lt $suites.Count; $i++) { Write-Host "$($i+1). $($suites[$i].name) [$($suites[$i].id)]" }
    if ($List) { return }
    Write-Host '0. 종료'
    do {
        $selection = Read-Host '실행할 번호'
        if ($selection -eq '0') { return }
        $number = 0
        $valid = [int]::TryParse($selection, [ref]$number) -and $number -ge 1 -and $number -le $suites.Count
        if (-not $valid) { Write-Host '목록의 번호를 입력하세요.' -ForegroundColor Yellow }
    } until ($valid)
    $Suite = $suites[$number-1].id
}
if ($Suite -notin $suites.id) { throw "Unknown suite: $Suite. Use -List." }
if ($interactive -and -not $PSBoundParameters.ContainsKey('CacheMode') -and $Suite -ne 'login') {
    Write-Host "`nRedis: 1. ON/OFF 비교 (기본)  2. ON만  3. OFF만  0. 종료"
    do {
        $modeNumber = Read-Host '캐시 모드 번호'
        if ($modeNumber -eq '0') { return }
        if ([string]::IsNullOrWhiteSpace($modeNumber)) { $modeNumber = '1' }
        $modeValid = $modeNumber -in @('1','2','3')
        if (-not $modeValid) { Write-Host '1, 2, 3 중에서 선택하세요.' -ForegroundColor Yellow }
    } until ($modeValid)
    $CacheMode = @{ '1'='compare'; '2'='on'; '3'='off' }[$modeNumber]
}
if ($PreAllocatedVUs -gt $MaxVUs) { throw 'PreAllocatedVUs must not exceed MaxVUs.' }
$Suite = $Suite.ToLowerInvariant()
$CacheMode = $CacheMode.ToLowerInvariant()
Write-Host "Selected: $Suite / Redis: $CacheMode / Smoke: $Smoke. Temporary data; no existing backend or .env required."
$runId = 'linux-java21-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
. (Join-Path $PSScriptRoot 'resolve-wsl-distribution.ps1')
$Distribution = Resolve-BenchmarkWslDistribution -Distribution $Distribution
$linuxRoot = (& wsl -d $Distribution -u root -- wslpath -a $projectRoot.Replace('\', '/') | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or -not $linuxRoot.StartsWith('/')) { throw "Cannot access WSL distribution $Distribution" }
$smokeValue = $Smoke.IsPresent.ToString().ToLowerInvariant()
$startedAt = [DateTimeOffset]::Now.ToString('o')
& wsl -d $Distribution -u root -- bash "$linuxRoot/scripts/benchmark/run-components.sh" $runId $smokeValue $Suite $Rate $Duration $PreAllocatedVUs $MaxVUs $LoginUsers $CacheMode
$result = $LASTEXITCODE
$output = Join-Path $projectRoot "benchmark-results/$runId"
New-Item -ItemType Directory -Force -Path $output | Out-Null
@{ schemaVersion=1; suite=$Suite; cacheMode=$CacheMode; smoke=$Smoke.IsPresent; startedAt=$startedAt; exitCode=$result } |
    ConvertTo-Json | Set-Content (Join-Path $output 'run-info.json') -Encoding utf8
try { & (Join-Path $PSScriptRoot 'update-dashboard.ps1') -Distribution $Distribution }
catch { Write-Warning "측정 결과는 저장됐지만 통합 페이지 갱신에 실패했습니다: $_" }
Write-Host "Results: $output"
if (Test-Path (Join-Path $output 'index.html')) { Write-Host "HTML: $(Join-Path $output 'index.html')" }
if ($result -ne 0) { throw "Linux benchmark returned $result. Inspect $(Join-Path $output 'runner.log') for the original error; also check services.log and cleanup.log in $output." }
