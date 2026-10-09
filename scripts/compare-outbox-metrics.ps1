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
    $output = & git @GitArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ("git {0} 실패:{1}{2}" -f ($GitArgs -join " "), [Environment]::NewLine, ($output -join [Environment]::NewLine))
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
Write-Host "원격 브랜치 정보 갱신 중..."
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
    [pscustomobject]@{ Name = "Spring Boot Actuator dependency"; Base = (Test-Contains $baseGradle "spring-boot-starter-actuator"); Candidate = (Test-Contains $candidateGradle "spring-boot-starter-actuator"); What = "운영 지표 엔드포인트 기반" },
    [pscustomobject]@{ Name = "Micrometer Counter"; Base = (Test-Contains $baseWorker "Counter\.builder|Counter\.counter"); Candidate = (Test-Contains $candidateWorker "Counter\.builder|Counter\.counter"); What = "처리 건수 계측 코드" },
    [pscustomobject]@{ Name = "Micrometer Timer"; Base = (Test-Contains $baseWorker "Timer\.builder|Timer\.record"); Candidate = (Test-Contains $candidateWorker "Timer\.builder|Timer\.record"); What = "작업 처리 시간 계측 코드" },
    [pscustomobject]@{ Name = "Micrometer Gauge"; Base = (Test-Contains $baseWorker "Gauge\.builder"); Candidate = (Test-Contains $candidateWorker "Gauge\.builder"); What = "현재 대기량 등 상태 계측 코드" },
    [pscustomobject]@{ Name = "Outbox pendingCount()"; Base = (Test-Contains $baseStore "pendingCount\s*\("); Candidate = (Test-Contains $candidateStore "pendingCount\s*\("); What = "미처리 Outbox 건수 조회" },
    [pscustomobject]@{ Name = "Outbox/Actuator configuration"; Base = (Test-Contains $baseProps "outbox|management\.endpoints"); Candidate = (Test-Contains $candidateProps "outbox|management\.endpoints"); What = "관련 운영 설정" }
)

$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add("# SmartTicketing 브랜치 개선 비교")
$lines.Add("")
$lines.Add("- 기준: $BaseBranch ($baseSha)")
$lines.Add("- 후보: $CandidateBranch ($candidateSha)")
$lines.Add("- 생성 시각: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
$lines.Add("")
$lines.Add("> 이 보고서는 코드 차이와 관측 기능을 정량화합니다. 코드 추가량만으로 응답속도 향상이나 처리량 증가를 단정하지 않습니다. 성능 개선율은 동일 조건의 k6 결과를 제공한 경우에만 계산합니다.")
$lines.Add("")
$lines.Add("## 1. 전체 코드 차이")
$lines.Add("")
$lines.Add("| 지표 | 결과 |")
$lines.Add("|---|---:|")
$lines.Add("| 변경 파일 수 | $($files.Count) |")
$lines.Add("| 추가 라인 | $addedLines |")
$lines.Add("| 삭제 라인 | $deletedLines |")
$lines.Add("| 순 라인 증감 | $($addedLines - $deletedLines) |")
$lines.Add("")
$lines.Add("## 2. Outbox 운영 관측 기능")
$lines.Add("")
$lines.Add("| 검사 항목 | feature/jusang | feature/outbox-metrics | 설명 |")
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
$lines.Add("- 정적 검사에서 확인된 항목: 기준 $baseFeatures/$($checks.Count), 후보 $candidateFeatures/$($checks.Count)")
$lines.Add("- 확인 항목 수 변화: $deltaLabel")
$lines.Add("")
$lines.Add("## 3. 파일별 변경량")
$lines.Add("")
if ($files.Count -eq 0) {
    $lines.Add("파일 차이가 없습니다.")
} else {
    $lines.Add("| 파일 | 추가 라인 | 삭제 라인 |")
    $lines.Add("|---|---:|---:|")
    foreach ($file in $files) {
        $lines.Add("| $($file.Path) | $($file.Added) | $($file.Deleted) |")
    }
}
$lines.Add("")

if ($BaseK6Json -and $CandidateK6Json) {
    if (!(Test-Path $BaseK6Json)) { throw "기준 k6 JSON을 찾을 수 없습니다: $BaseK6Json" }
    if (!(Test-Path $CandidateK6Json)) { throw "후보 k6 JSON을 찾을 수 없습니다: $CandidateK6Json" }
    $baseK6 = Get-Content $BaseK6Json -Raw | ConvertFrom-Json
    $candidateK6 = Get-Content $CandidateK6Json -Raw | ConvertFrom-Json
    $metrics = @(
        [pscustomobject]@{ Metric = "http_req_duration"; Field = "avg"; Label = "평균 요청시간(ms)"; LowerBetter = $true },
        [pscustomobject]@{ Metric = "http_req_duration"; Field = "p(95)"; Label = "p95 요청시간(ms)"; LowerBetter = $true },
        [pscustomobject]@{ Metric = "http_req_duration"; Field = "p(99)"; Label = "p99 요청시간(ms)"; LowerBetter = $true },
        [pscustomobject]@{ Metric = "http_req_failed"; Field = "rate"; Label = "요청 실패율(비율)"; LowerBetter = $true },
        [pscustomobject]@{ Metric = "http_reqs"; Field = "rate"; Label = "처리량(req/s)"; LowerBetter = $false }
    )
    $lines.Add("## 4. k6 실측 성능 비교")
    $lines.Add("")
    $lines.Add("| 지표 | 기준 | 후보 | 변화율 | 결과 |")
    $lines.Add("|---|---:|---:|---:|---|")
    foreach ($metric in $metrics) {
        $baseValue = Get-K6Value $baseK6 $metric.Metric $metric.Field
        $candidateValue = Get-K6Value $candidateK6 $metric.Metric $metric.Field
        if ($null -eq $baseValue -or $null -eq $candidateValue) {
            $lines.Add("| $($metric.Label) | N/A | N/A | N/A | JSON metric 누락 |")
            continue
        }
        if ($baseValue -eq 0) {
            $changeText = "N/A (기준 0)"
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
    $lines.Add("> 두 k6 실행은 같은 데이터, 환경, 시나리오, VU 수, 지속 시간이어야 비교할 수 있습니다.")
} else {
    $lines.Add("## 4. k6 실측 성능 비교")
    $lines.Add("")
    $lines.Add("k6 결과 JSON을 입력하지 않아 지연시간/실패율/처리량 개선율은 산출하지 않았습니다.")
    $lines.Add("기준과 후보를 각각 실행해 k6 --summary-export 결과를 저장한 다음 아래처럼 실행하세요:")
    $lines.Add("")
    $lines.Add("    .\scripts\compare-outbox-metrics.ps1 -BaseK6Json .\baseline-summary.json -CandidateK6Json .\outbox-summary.json")
}
$lines.Add("")

$report = $lines -join [Environment]::NewLine
$fullOutputPath = [System.IO.Path]::GetFullPath($OutputPath)
[System.IO.File]::WriteAllText($fullOutputPath, $report, [System.Text.UTF8Encoding]::new($true))
Write-Host ""
Write-Host "보고서 생성: $fullOutputPath" -ForegroundColor Green
Write-Host "변경 파일 $($files.Count)개, 추가 $addedLines줄, 삭제 $deletedLines줄"
Write-Host "성능 개선율은 k6 JSON 두 개를 전달했을 때만 계산됩니다."
