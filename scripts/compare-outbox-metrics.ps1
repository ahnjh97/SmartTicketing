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
Write-Host "Refreshing remote branch references..."
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
    [pscustomobject]@{ Name = "Spring Boot Actuator dependency"; Base = (Test-Contains $baseGradle "spring-boot-starter-actuator"); Candidate = (Test-Contains $candidateGradle "spring-boot-starter-actuator"); What = "Operational metrics endpoint support" },
    [pscustomobject]@{ Name = "Micrometer Counter"; Base = (Test-Contains $baseWorker "registry\.counter|Counter\.builder|Counter\.counter"); Candidate = (Test-Contains $candidateWorker "registry\.counter|Counter\.builder|Counter\.counter"); What = "Processed item counters" },
    [pscustomobject]@{ Name = "Micrometer Timer"; Base = (Test-Contains $baseWorker "Timer\.builder|Timer\.record"); Candidate = (Test-Contains $candidateWorker "Timer\.builder|Timer\.record"); What = "Job processing duration metrics" },
    [pscustomobject]@{ Name = "Micrometer Gauge"; Base = (Test-Contains $baseWorker "registry\.gauge|Gauge\.builder"); Candidate = (Test-Contains $candidateWorker "registry\.gauge|Gauge\.builder"); What = "Current queue/backlog gauges" },
    [pscustomobject]@{ Name = "Outbox pendingCount()"; Base = (Test-Contains $baseStore "pendingCount\s*\("); Candidate = (Test-Contains $candidateStore "pendingCount\s*\("); What = "Pending Outbox item count" },
    [pscustomobject]@{ Name = "Outbox/Actuator configuration"; Base = (Test-Contains $baseProps "outbox|management\.endpoints"); Candidate = (Test-Contains $candidateProps "outbox|management\.endpoints"); What = "Related runtime configuration" }
)

$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add("# SmartTicketing Branch Comparison")
$lines.Add("")
$lines.Add("- Baseline: $BaseBranch ($baseSha)")
$lines.Add("- Candidate: $CandidateBranch ($candidateSha)")
$lines.Add("- Generated at: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
$lines.Add("")
$lines.Add("> This report quantifies code differences and instrumentation presence. Added lines alone do not prove better latency or throughput. Performance deltas are calculated only when comparable k6 results are supplied.")
$lines.Add("")
$lines.Add("## 1. Overall code diff")
$lines.Add("")
$lines.Add("| Metric | Value |")
$lines.Add("|---|---:|")
$lines.Add("| Changed files | $($files.Count) |")
$lines.Add("| Lines added | $addedLines |")
$lines.Add("| Lines deleted | $deletedLines |")
$lines.Add("| Net line change | $($addedLines - $deletedLines) |")
$lines.Add("")
$lines.Add("## 2. Outbox observability features")
$lines.Add("")
$lines.Add("| Check | feature/jusang | feature/outbox-metrics | Meaning |")
$lines.Add("|---|---:|---:|---|")
foreach ($check in $checks) {
    $baseState = if ($check.Base) { "Present" } else { "Missing" }
    $candidateState = if ($check.Candidate) { "Present" } else { "Missing" }
    $lines.Add("| $($check.Name) | $baseState | $candidateState | $($check.What) |")
}
$baseFeatures = @($checks | Where-Object { $_.Base }).Count
$candidateFeatures = @($checks | Where-Object { $_.Candidate }).Count
$featureDelta = $candidateFeatures - $baseFeatures
$deltaLabel = if ($featureDelta -gt 0) { "$featureDelta added" } elseif ($featureDelta -lt 0) { "$([math]::Abs($featureDelta)) removed" } else { "No change" }
$lines.Add("")
$lines.Add("- Static checks present: baseline $baseFeatures/$($checks.Count), candidate $candidateFeatures/$($checks.Count)")
$lines.Add("- Change in number of detected checks: $deltaLabel")
$lines.Add("")
$lines.Add("## 3. Per-file changes")
$lines.Add("")
if ($files.Count -eq 0) {
    $lines.Add("No file differences found.")
} else {
    $lines.Add("| File | Lines added | Lines deleted |")
    $lines.Add("|---|---:|---:|")
    foreach ($file in $files) {
        $lines.Add("| $($file.Path) | $($file.Added) | $($file.Deleted) |")
    }
}
$lines.Add("")

if ($BaseK6Json -and $CandidateK6Json) {
    if (!(Test-Path $BaseK6Json)) { throw "Baseline k6 JSON not found: $BaseK6Json" }
    if (!(Test-Path $CandidateK6Json)) { throw "Candidate k6 JSON not found: $CandidateK6Json" }
    $baseK6 = Get-Content $BaseK6Json -Raw | ConvertFrom-Json
    $candidateK6 = Get-Content $CandidateK6Json -Raw | ConvertFrom-Json
    $metrics = @(
        [pscustomobject]@{ Metric = "http_req_duration"; Field = "avg"; Label = "Average request duration (ms)"; LowerBetter = $true },
        [pscustomobject]@{ Metric = "http_req_duration"; Field = "p(95)"; Label = "p95 request duration (ms)"; LowerBetter = $true },
        [pscustomobject]@{ Metric = "http_req_duration"; Field = "p(99)"; Label = "p99 request duration (ms)"; LowerBetter = $true },
        [pscustomobject]@{ Metric = "http_req_failed"; Field = "rate"; Label = "Request failure rate"; LowerBetter = $true },
        [pscustomobject]@{ Metric = "http_reqs"; Field = "rate"; Label = "Throughput (req/s)"; LowerBetter = $false }
    )
    $lines.Add("## 4. k6 measured performance comparison")
    $lines.Add("")
    $lines.Add("| Metric | Baseline | Candidate | Change | Assessment |")
    $lines.Add("|---|---:|---:|---:|---|")
    foreach ($metric in $metrics) {
        $baseValue = Get-K6Value $baseK6 $metric.Metric $metric.Field
        $candidateValue = Get-K6Value $candidateK6 $metric.Metric $metric.Field
        if ($null -eq $baseValue -or $null -eq $candidateValue) {
            $lines.Add("| $($metric.Label) | N/A | N/A | N/A | Metric missing from JSON |")
            continue
        }
        if ($baseValue -eq 0) {
            $changeText = "N/A (baseline is 0)"
            $resultText = "Cannot assess"
        } else {
            $change = (($candidateValue - $baseValue) / [math]::Abs($baseValue)) * 100
            $changeText = "{0:N2}%" -f $change
            if ([math]::Abs($candidateValue - $baseValue) -lt 0.000001) {
                $resultText = "No change"
            } elseif (($metric.LowerBetter -and $candidateValue -lt $baseValue) -or (!$metric.LowerBetter -and $candidateValue -gt $baseValue)) {
                $resultText = "Improved"
            } else {
                $resultText = "Regressed"
            }
        }
        $lines.Add("| $($metric.Label) | {0:N3} | {1:N3} | {2} | {3} |" -f $baseValue, $candidateValue, $changeText, $resultText)
    }
    $lines.Add("")
    $lines.Add("> Both k6 runs must use the same data, environment, scenario, VU count, and duration.")
} else {
    $lines.Add("## 4. k6 measured performance comparison")
    $lines.Add("")
    $lines.Add("No k6 result JSON was provided, so latency, failure-rate, and throughput improvement percentages were not calculated.")
    $lines.Add("Run both branches and export k6 summaries, then run the following command:")
    $lines.Add("")
    $lines.Add("    .\scripts\compare-outbox-metrics.ps1 -BaseK6Json .\baseline-summary.json -CandidateK6Json .\outbox-summary.json")
}
$lines.Add("")

$report = $lines -join [Environment]::NewLine
$fullOutputPath = [System.IO.Path]::GetFullPath($OutputPath)
[System.IO.File]::WriteAllText($fullOutputPath, $report, [System.Text.UTF8Encoding]::new($true))
Write-Host ""
Write-Host "Report created: $fullOutputPath" -ForegroundColor Green
Write-Host "Changed files: $($files.Count); added lines: $addedLines; deleted lines: $deletedLines"
Write-Host "Performance percentages are calculated only when both k6 JSON files are provided."
