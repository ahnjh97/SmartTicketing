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
$required = $Vus * $Iterations
foreach ($path in @($BaselineFixture, $OptimizedFixture)) {
    if (-not (Test-Path $path)) { throw "Fixture 파일이 없습니다: $path" }
    $fixture = Get-Content -Raw -Encoding UTF8 $path | ConvertFrom-Json
    if (-not $fixture.cases -or @($fixture.cases).Count -lt $required) {
        throw "Fixture 케이스가 부족합니다: $path (필요 최소 $required, 실제 $(@($fixture.cases).Count))"
    }
}
$out = Join-Path (Get-Location) $OutputDir
New-Item -ItemType Directory -Force -Path $out | Out-Null
$baselineSummary = Join-Path $out "baseline-summary.json"
$optimizedSummary = Join-Path $out "optimized-summary.json"
$baselineFixtureAbs = (Resolve-Path $BaselineFixture).Path
$optimizedFixtureAbs = (Resolve-Path $OptimizedFixture).Path

Write-Host "1/2 기존 코드 부하 테스트 시작: $BaselineUrl" -ForegroundColor Cyan
& k6 run -e "BASE_URL=$BaselineUrl" -e "FIXTURE=$baselineFixtureAbs" -e "VUS=$Vus" -e "ITERATIONS=$Iterations" -e "SUMMARY_FILE=$baselineSummary" $script
if ($LASTEXITCODE -ne 0) { Write-Warning "기존 코드 실행 중 k6가 비정상 종료 코드 $LASTEXITCODE 를 반환했습니다. 결과 파일을 확인합니다." }
if (-not (Test-Path $baselineSummary)) { throw "기존 코드 summary 생성 실패: $baselineSummary" }

Write-Host "2/2 개선 코드 부하 테스트 시작: $OptimizedUrl" -ForegroundColor Cyan
& k6 run --summary-mode=full -e "BASE_URL=$OptimizedUrl" -e "FIXTURE=$optimizedFixtureAbs" -e "VUS=$Vus" -e "ITERATIONS=$Iterations" -e "SUMMARY_FILE=$optimizedSummary" $script
if ($LASTEXITCODE -ne 0) { Write-Warning "개선 코드 실행 중 k6가 비정상 종료 코드 $LASTEXITCODE 를 반환했습니다. 결과 파일을 확인합니다." }
if (-not (Test-Path $optimizedSummary)) { throw "개선 코드 summary 생성 실패: $optimizedSummary" }

$report = Join-Path $out "booking-lock-comparison.html"
$reportScript = Join-Path $root "make-report.ps1"
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $reportScript -BaselineSummary $baselineSummary -OptimizedSummary $optimizedSummary -OutputPath $report
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $report)) { throw "비교 리포트 생성에 실패했습니다." }
Write-Host "완료: $report" -ForegroundColor Green
Start-Process $report
