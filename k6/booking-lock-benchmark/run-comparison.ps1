param(
    [Parameter(Mandatory=$true)][string]$BaselineUrl,
    [Parameter(Mandatory=$true)][string]$OptimizedUrl,
    [Parameter(Mandatory=$true)][string]$BaselineFixture,
    [Parameter(Mandatory=$true)][string]$OptimizedFixture,
    [int]$Vus = 10,
    [int]$Iterations = 10,
    [string]$OutputDir = ".\\benchmark-results\\booking-lock"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$script = Join-Path $root "booking-lock.js"
if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) { throw "k6 명령을 찾을 수 없습니다. 먼저 k6 설치를 확인하세요." }
if ($Vus -lt 1 -or $Iterations -lt 1) { throw "Vus와 Iterations는 1 이상이어야 합니다." }

$fixtureData = @{}
foreach ($path in @($BaselineFixture, $OptimizedFixture)) {
    if (-not (Test-Path $path)) { throw "Fixture 파일이 없습니다: $path" }
    $fixture = Get-Content -Raw -Encoding UTF8 $path | ConvertFrom-Json
    $count = @($fixture.cases).Count
    if (-not $fixture.cases -or $count -lt 1) {
        throw "Fixture에 케이스가 없습니다: $path (cases 배열에 최소 1개 필요)"
    }
    $fixtureData[$path] = $count
}

# Use the same request count on both servers. If fixtures are smaller than the requested
# load, run a clearly labelled smoke/small test instead of failing before k6 starts.
$available = [Math]::Min($fixtureData[$BaselineFixture], $fixtureData[$OptimizedFixture])
$requested = $Vus * $Iterations
if ($available -lt $requested) {
    $oldVus = $Vus
    $oldIterations = $Iterations
    $Vus = [Math]::Min($Vus, $available)
    $Iterations = [Math]::Max(1, [Math]::Floor($available / $Vus))
    Write-Warning "Fixture가 요청 부하보다 적어 테스트 규모를 자동 조정합니다. 요청: $requested, 사용 가능: $available, 실행: VUs=$Vus, Iterations/VU=$Iterations (최대 $($Vus * $Iterations)회). 이 결과는 100회 성능 비교를 대체하지 않는 소규모 확인입니다."
}

$out = Join-Path (Get-Location) $OutputDir
New-Item -ItemType Directory -Force -Path $out | Out-Null
$baselineSummary = Join-Path $out "baseline-summary.json"
$optimizedSummary = Join-Path $out "optimized-summary.json"
$baselineFixtureAbs = (Resolve-Path $BaselineFixture).Path
$optimizedFixtureAbs = (Resolve-Path $OptimizedFixture).Path

Write-Host "1/2 기존 코드 부하 테스트 시작: $BaselineUrl (VUs=$Vus, Iterations/VU=$Iterations)" -ForegroundColor Cyan
& k6 run -e "BASE_URL=$BaselineUrl" -e "FIXTURE=$baselineFixtureAbs" -e "VUS=$Vus" -e "ITERATIONS=$Iterations" -e "SUMMARY_FILE=$baselineSummary" $script
if ($LASTEXITCODE -ne 0) { Write-Warning "기존 코드 실행 중 k6가 비정상 종료 코드 $LASTEXITCODE 를 반환했습니다. 결과 파일을 확인합니다." }
if (-not (Test-Path $baselineSummary)) { throw "기존 코드 summary 생성 실패: $baselineSummary" }

Write-Host "2/2 개선 코드 부하 테스트 시작: $OptimizedUrl (VUs=$Vus, Iterations/VU=$Iterations)" -ForegroundColor Cyan
& k6 run -e "BASE_URL=$OptimizedUrl" -e "FIXTURE=$optimizedFixtureAbs" -e "VUS=$Vus" -e "ITERATIONS=$Iterations" -e "SUMMARY_FILE=$optimizedSummary" $script
if ($LASTEXITCODE -ne 0) { Write-Warning "개선 코드 실행 중 k6가 비정상 종료 코드 $LASTEXITCODE 를 반환했습니다. 결과 파일을 확인합니다." }
if (-not (Test-Path $optimizedSummary)) { throw "개선 코드 summary 생성 실패: $optimizedSummary" }

$report = Join-Path $out "booking-lock-comparison.html"
$reportScript = Join-Path $root "make-report.ps1"
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $reportScript -BaselineSummary $baselineSummary -OptimizedSummary $optimizedSummary -OutputPath $report
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $report)) { throw "비교 리포트 생성에 실패했습니다." }
Write-Host "완료: $report" -ForegroundColor Green
Start-Process $report
