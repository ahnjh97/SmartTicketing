[CmdletBinding()]
param(
    [string]$BaseBranch = "origin/feature/jusang",
    [string]$CandidateBranch = "origin/feature/outbox-metrics",
    [string]$OutputPath = ".\outbox-metrics-comparison.md",
    [string]$BaseK6Json = "",
    [string]$CandidateK6Json = ""
)
$ErrorActionPreference = "Stop"

function Invoke-GitText {
    param([string[]]$GitArgs)
    # Windows PowerShell may treat native-command stderr (such as git fetch progress)
    # as a terminating NativeCommandError when ErrorActionPreference is Stop.
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & git @GitArgs 2>&1
        $gitExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $savedPreference
    }
    if ($gitExitCode -ne 0) {
        throw ("git {0} failed:{1}{2}" -f ($GitArgs -join " "), [Environment]::NewLine, ($output -join [Environment]::NewLine))
    }
    return ($output -join [Environment]::NewLine)
}
function Get-BranchFile {
    param([string]$Ref, [string]$Path)
    $spec = "$($Ref):$Path"
    $output = & git show $spec 2>$null
    if ($LASTEXITCODE -ne 0) { return "" }
    return ($output -join [Environment]::NewLine)
}
function Test-Contains {
    param([string]$Text, [string]$Pattern)
    return [regex]::IsMatch($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
}
function Get-K6Value {
    param($Summary, [string]$Metric, [string]$Field)
    if ($null -eq $Summary.metrics) { return $null }
    $metricObject = $Summary.metrics.PSObject.Properties[$Metric]
    if ($null -eq $metricObject -or $null -eq $metricObject.Value.values) { return $null }
    $fieldObject = $metricObject.Value.values.PSObject.Properties[$Field]
    if ($null -eq $fieldObject -or $null -eq $fieldObject.Value) { return $null }
    return [double]$fieldObject.Value
}

# Fetches remote-tracking refs only. Does not checkout, reset, merge, commit, push,
# or modify either source branch.
Write-Host "원격 브랜치 정보를 갱신하는 중..."
$null = Invoke-GitText @("fetch", "origin", "feature/jusang", "feature/outbox-metrics")
$null = Invoke-GitText @("rev-parse", "--verify", $BaseBranch)
$null = Invoke-GitText @("rev-parse", "--verify", $CandidateBranch)
$baseSha = (Invoke-GitText @("rev-parse", "--short", $BaseBranch)).Trim()
$candidateSha = (Invoke-GitText @("rev-parse", "--short", $CandidateBranch)).Trim()

$numstat = Invoke-GitText @("diff", "--numstat", $BaseBranch, $CandidateBranch)
$files = @()
$addedLines = 0
$deletedLines = 0
foreach ($line in ($numstat -split '\r?\n')) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line -split '\t', 3
    if ($parts.Count -lt 3) { continue }
    # Exclude this reporting script itself so it does not inflate the measured branch diff.
    if ($parts[2] -eq "scripts/compare-outbox-metrics.ps1") { continue }
    $add = 0
    $del = 0
    [void][int]::TryParse($parts[0], [ref]$add)
    [void][int]::TryParse($parts[1], [ref]$del)
    $addedLines += $add
    $deletedLines += $del
    $files += [pscustomobject]@{ Path = $parts[2]; Added = $parts[0]; Deleted = $parts[1] }
}

$baseGradle = Get-BranchFile $BaseBranch "build.gradle"
$candidateGradle = Get-BranchFile $CandidateBranch "build.gradle"
$baseWorker = Get-BranchFile $BaseBranch "src/main/java/smartticketing/service/BookingOutboxWorker.java"
$candidateWorker = Get-BranchFile $CandidateBranch "src/main/java/smartticketing/service/BookingOutboxWorker.java"
$baseStore = Get-BranchFile $BaseBranch "src/main/java/smartticketing/service/BookingOutboxStore.java"
$candidateStore = Get-BranchFile $CandidateBranch "src/main/java/smartticketing/service/BookingOutboxStore.java"
$baseProps = Get-BranchFile $BaseBranch "src/main/resources/application.properties"
$candidateProps = Get-BranchFile $CandidateBranch "src/main/resources/application.properties"

$checks = @(
    [pscustomobject]@{ Name = "Spring Boot Actuator 의존성"; Base = (Test-Contains $baseGradle "spring-boot-starter-actuator"); Candidate = (Test-Contains $candidateGradle "spring-boot-starter-actuator"); What = "운영 지표를 노출할 기반" },
    [pscustomobject]@{ Name = "Micrometer Counter (처리 건수·재시도 횟수)"; Base = (Test-Contains $baseWorker "registry\.counter|Counter\.builder|Counter\.counter"); Candidate = (Test-Contains $candidateWorker "registry\.counter|Counter\.builder|Counter\.counter"); What = "처리 건수와 재시도 횟수 측정" },
    [pscustomobject]@{ Name = "Micrometer Timer (처리 시간)"; Base = (Test-Contains $baseWorker "Timer\.builder|Timer\.record"); Candidate = (Test-Contains $candidateWorker "Timer\.builder|Timer\.record"); What = "작업 처리 시간 측정" },
    [pscustomobject]@{ Name = "Micrometer Gauge (대기 작업 수)"; Base = (Test-Contains $baseWorker "registry\.gauge|Gauge\.builder"); Candidate = (Test-Contains $candidateWorker "registry\.gauge|Gauge\.builder"); What = "현재 대기 중인 Outbox 작업 수 관측" },
    [pscustomobject]@{ Name = "Outbox 미처리 건수 조회"; Base = (Test-Contains $baseStore "pendingCount\s*\("); Candidate = (Test-Contains $candidateStore "pendingCount\s*\("); What = "처리 대기 중인 Outbox 이벤트 수 조회" },
    [pscustomobject]@{ Name = "Outbox/Actuator 설정"; Base = (Test-Contains $baseProps "outbox|management\.endpoints"); Candidate = (Test-Contains $candidateProps "outbox|management\.endpoints"); What = "관련 실행 설정" }
)

$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add("# SmartTicketing 브랜치 비교 보고서")
$lines.Add("")
$lines.Add("- 기준 브랜치: $BaseBranch ($baseSha)")
$lines.Add("- 비교 대상 브랜치: $CandidateBranch ($candidateSha)")
$lines.Add("- 생성 시각: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
$lines.Add("")
$lines.Add("> 이 보고서는 코드 변경량과 모니터링 코드의 존재 여부를 비교합니다. 코드 추가만으로 성능 향상을 입증할 수는 없습니다. 실제 성능 비교는 동일한 조건으로 실행한 k6 결과가 있어야 가능합니다.")
$lines.Add("")
$lines.Add("## 1. 전체 코드 변경 요약")
$lines.Add("")
$lines.Add("| 항목 | 값 |")
$lines.Add("|---|---:|")
$lines.Add("| 변경된 파일 수 | $($files.Count) |")
$lines.Add("| 추가된 줄 수 | $addedLines |")
$lines.Add("| 삭제된 줄 수 | $deletedLines |")
$lines.Add("| 순증감 줄 수 | $($addedLines - $deletedLines) |")
$lines.Add("")
$lines.Add("## 2. Outbox 모니터링 기능 비교")
$lines.Add("")
$lines.Add("| 검사 항목 | 기준 브랜치 | 비교 대상 브랜치 | 의미 |")
$lines.Add("|---|---:|---:|---|")
foreach ($check in $checks) {
    $baseState = if ($check.Base) { "있음" } else { "없음" }
    $candidateState = if ($check.Candidate) { "있음" } else { "없음" }
    $lines.Add("| $($check.Name) | $baseState | $candidateState | $($check.What) |")
}
$baseFeatures = @($checks | Where-Object { $_.Base }).Count
$candidateFeatures = @($checks | Where-Object { $_.Candidate }).Count
$featureDelta = $candidateFeatures - $baseFeatures
$deltaLabel = if ($featureDelta -gt 0) { "$featureDelta개 증가" } elseif ($featureDelta -lt 0) { "$([math]::Abs($featureDelta))개 감소" } else { "변화 없음" }
$lines.Add("")
$lines.Add("- 확인된 모니터링 항목: 기준 브랜치 $baseFeatures/$($checks.Count), 비교 대상 브랜치 $candidateFeatures/$($checks.Count)")
$lines.Add("- 확인 항목 수 변화: $deltaLabel")
$lines.Add("")
$lines.Add("## 3. 파일별 변경 내역")
$lines.Add("")
if ($files.Count -eq 0) {
    $lines.Add("변경된 파일이 없습니다.")
} else {
    $lines.Add("| 파일 | 추가된 줄 수 | 삭제된 줄 수 |")
    $lines.Add("|---|---:|---:|")
    foreach ($file in $files) {
        $lines.Add("| $($file.Path) | $($file.Added) | $($file.Deleted) |")
    }
}
$lines.Add("")
$lines.Add("## 4. 현재 결과 해석")
$lines.Add("")
if ($candidateFeatures -gt $baseFeatures) {
    $lines.Add("- **모니터링 기능:** 후보 브랜치에서 확인된 모니터링 항목이 $($candidateFeatures - $baseFeatures)개 늘었습니다. 이는 관측 기능의 추가이며, 그 자체로 예매 성능 향상을 뜻하지는 않습니다.")
} elseif ($candidateFeatures -lt $baseFeatures) {
    $lines.Add("- **모니터링 기능:** 후보 브랜치에서 확인된 모니터링 항목이 $([math]::Abs($candidateFeatures - $baseFeatures))개 줄었습니다. 삭제 원인을 검토하세요.")
} else {
    $lines.Add("- **모니터링 기능:** 확인된 항목 수는 기준 브랜치와 같습니다.")
}
if ($deletedLines -gt $addedLines) {
    $lines.Add("- **코드 변경 주의:** 후보 브랜치에서 추가한 줄보다 삭제한 줄이 $($deletedLines - $addedLines)줄 많습니다. 특히 대기열 코드와 테스트 삭제가 기능 동작에 영향을 주는지 확인해야 합니다.")
}
if (-not ($BaseK6Json -and $CandidateK6Json)) {
    $lines.Add("- **실제 성능:** k6 결과가 아직 없어 응답 시간·실패율·처리량이 개선됐는지 판정할 수 없습니다.")
}
$lines.Add("")

if ($BaseK6Json -and $CandidateK6Json) {
    if (!(Test-Path $BaseK6Json)) { throw "기준 브랜치 k6 JSON 파일을 찾을 수 없습니다: $BaseK6Json" }
    if (!(Test-Path $CandidateK6Json)) { throw "비교 대상 브랜치 k6 JSON 파일을 찾을 수 없습니다: $CandidateK6Json" }
    $baseK6 = Get-Content $BaseK6Json -Raw | ConvertFrom-Json
    $candidateK6 = Get-Content $CandidateK6Json -Raw | ConvertFrom-Json
    $metrics = @(
        [pscustomobject]@{ Metric = "http_req_duration"; Field = "avg"; Label = "평균 응답 시간 (밀리초)"; LowerBetter = $true },
        [pscustomobject]@{ Metric = "http_req_duration"; Field = "p(95)"; Label = "95백분위 응답 시간 (밀리초)"; LowerBetter = $true },
        [pscustomobject]@{ Metric = "http_req_duration"; Field = "p(99)"; Label = "99백분위 응답 시간 (밀리초)"; LowerBetter = $true },
        [pscustomobject]@{ Metric = "http_req_failed"; Field = "rate"; Label = "요청 실패율"; LowerBetter = $true },
        [pscustomobject]@{ Metric = "http_reqs"; Field = "rate"; Label = "초당 처리 요청 수 (건/초)"; LowerBetter = $false }
    )
    $lines.Add("## 4. k6 measured performance comparison")
    $lines.Add("")
    $lines.Add("| 지표 | 기준 브랜치 | 비교 대상 브랜치 | 변화율 | 판정 |")
    $lines.Add("|---|---:|---:|---:|---|")
    foreach ($metric in $metrics) {
        $baseValue = Get-K6Value $baseK6 $metric.Metric $metric.Field
        $candidateValue = Get-K6Value $candidateK6 $metric.Metric $metric.Field
        if ($null -eq $baseValue -or $null -eq $candidateValue) {
            $lines.Add("| $($metric.Label) | 측정값 없음 | 측정값 없음 | 계산 불가 | JSON에 해당 지표가 없음 |")
            continue
        }
        if ($baseValue -eq 0) {
            $changeText = "계산 불가 (기준값이 0)"
            $resultText = "판정 불가"
        } else {
            $change = (($candidateValue - $baseValue) / [math]::Abs($baseValue)) * 100
            $changeText = "{0:N2}%" -f $change
            if ([math]::Abs($candidateValue - $baseValue) -lt 0.000001) {
                $resultText = "변화 없음"
            } elseif (($metric.LowerBetter -and $candidateValue -lt $baseValue) -or (!$metric.LowerBetter -and $candidateValue -gt $baseValue)) {
                $resultText = "개선"
            } else {
                $resultText = "악화"
            }
        }
        $lines.Add("| $($metric.Label) | {0:N3} | {1:N3} | {2} | {3} |" -f $baseValue, $candidateValue, $changeText, $resultText)
    }
    $lines.Add("")
    $lines.Add("> 두 k6 실행은 데이터, 환경, 테스트 시나리오, 가상 사용자 수(VU), 실행 시간을 동일하게 맞춰야 비교할 수 있습니다.")
} else {
    $lines.Add("## 4. k6 measured performance comparison")
    $lines.Add("")
    $lines.Add("k6 결과 JSON이 제공되지 않아 응답 시간, 실패율, 처리량의 개선율을 계산하지 않았습니다.")
    $lines.Add("두 브랜치에서 동일한 조건으로 k6 테스트를 실행하고 결과 JSON을 저장한 뒤 다음 명령을 실행하세요:")
    $lines.Add("")
    $lines.Add("    .\scripts\compare-outbox-metrics.ps1 -BaseK6Json .\baseline-summary.json -CandidateK6Json .\outbox-summary.json")
}
$lines.Add("")

$report = $lines -join [Environment]::NewLine
$fullOutputPath = [System.IO.Path]::GetFullPath($OutputPath)
[System.IO.File]::WriteAllText($fullOutputPath, $report, [System.Text.UTF8Encoding]::new($true))
Write-Host ""
Write-Host "보고서 생성 완료: $fullOutputPath" -ForegroundColor Green
Write-Host "변경 파일: $($files.Count)개; 추가: $addedLines줄; 삭제: $deletedLines줄"
Write-Host "k6 결과 JSON 두 개가 모두 있을 때만 성능 변화율을 계산합니다."
