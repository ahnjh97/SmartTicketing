#requires -Version 7.0
<#
.SYNOPSIS
Runs the booking waiting-rank benchmark on baseline and improved refs using isolated git worktrees.
.DESCRIPTION
Does not switch or modify the user's current checkout, and never writes to the existing root index.html.
Each branch gets its own clean worktree, backend build, temporary MySQL/Redis and k6 run.
Writes benchmark-results/waiting-rank-comparison.html and .json plus raw artifacts per branch.
#>
[CmdletBinding()]
param(
    [string]$BaselineRef = 'origin/feature/jusang',
    [string]$ImprovedRef = 'origin/improve/waiting-rank-redis-v2',
    [string]$Distribution = 'Ubuntu',
    [switch]$SkipFetch
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$resultsRoot = Join-Path $repoRoot 'benchmark-results'
$runId = 'waiting-rank-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,6)
$comparisonRoot = Join-Path $resultsRoot "waiting-rank-comparison/$runId"
$worktreeRoot = Join-Path ([IO.Path]::GetTempPath()) "smartticketing-$runId"
New-Item -ItemType Directory -Force -Path $comparisonRoot,$worktreeRoot | Out-Null

function Invoke-Git {
    param([string[]]$GitArgs, [switch]$AllowFailure)
    & git -C $repoRoot @GitArgs
    if ($LASTEXITCODE -ne 0 -and -not $AllowFailure) { throw "git $($GitArgs -join ' ') failed ($LASTEXITCODE)" }
}
function Get-RefCommit([string]$Ref) {
    $sha = (& git -C $repoRoot rev-parse --verify "$Ref^{commit}" 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $sha) { throw "Cannot resolve '$Ref'. Fetch the branch and confirm it exists." }
    return $sha
}
function Run-Branch([string]$Label,[string]$Ref) {
    $sha = Get-RefCommit $Ref
    $wt = Join-Path $worktreeRoot $Label
    $dest = Join-Path $comparisonRoot $Label
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Write-Host ([Environment]::NewLine + "=== $Label : $Ref ($($sha.Substring(0,12))) ===") -ForegroundColor Cyan
    Invoke-Git @('worktree','add','--detach',$wt,$sha)
    $benchmarkRoot = Join-Path $wt 'benchmark-results'
    $before = @()
    if (Test-Path $benchmarkRoot) {
        $before = @(Get-ChildItem $benchmarkRoot -Directory -Filter 'linux-java21-*' | ForEach-Object Name)
    }
    $exitCode = 0
    Push-Location $wt
    try {
        & (Join-Path $wt 'scripts/benchmark/run-linux.ps1') -Suite booking -CacheMode compare -Distribution $Distribution
        $exitCode = $LASTEXITCODE
    } catch {
        $exitCode = 1
        $_ | Out-String | Set-Content (Join-Path $dest 'orchestration-error.txt') -Encoding utf8
        Write-Warning "$Label benchmark failed: $_"
    } finally { Pop-Location }
    $runDir = $null
    if (Test-Path $benchmarkRoot) {
        $candidates = @(Get-ChildItem $benchmarkRoot -Directory -Filter 'linux-java21-*' | Where-Object { $_.Name -notin $before } | Sort-Object LastWriteTime -Descending)
        if ($candidates.Count -gt 0) { $runDir = $candidates[0].FullName }
    }
    if ($runDir) {
        Copy-Item (Join-Path $runDir '*') $dest -Recurse -Force
    } else {
        "No linux-java21 result directory was created. ExitCode=$exitCode" | Set-Content (Join-Path $dest 'result-missing.txt') -Encoding utf8
    }
    $info = $null; $results = $null; $summary = $null
    foreach ($file in @('run-info.json','results.json','summary.json')) {
        $p = Join-Path $dest $file
        if (Test-Path $p) {
            try {
                $v = Get-Content $p -Raw -Encoding utf8 | ConvertFrom-Json
                if ($file -eq 'run-info.json') { $info = $v }
                elseif ($file -eq 'results.json') { $results = $v }
                elseif ($file -eq 'summary.json') { $summary = $v }
            } catch { Write-Warning "Cannot parse $p : $_" }
        }
    }
    return [ordered]@{
        label=$Label; ref=$Ref; commit=$sha; exitCode=$exitCode
        status=if ($exitCode -eq 0 -and $results) {'completed'} else {'failed-or-incomplete'}
        artifacts="waiting-rank-comparison/$runId/$Label"
        runInfo=$info; results=$results; summary=$summary
        resultDirectory=$runDir
    }
}

try {
    if (-not $SkipFetch) {
        Write-Host 'Fetching comparison refs from origin...' -ForegroundColor Cyan
        Invoke-Git @('fetch','origin','+refs/heads/feature/jusang:refs/remotes/origin/feature/jusang','+refs/heads/improve/waiting-rank-redis-v2:refs/remotes/origin/improve/waiting-rank-redis-v2')
    }
    $baseline = Run-Branch 'baseline' $BaselineRef
    $improved = Run-Branch 'improved' $ImprovedRef
    $payload = [ordered]@{
        schemaVersion=1; comparisonId=$runId; generatedAt=[DateTimeOffset]::Now.ToString('o')
        suite='booking waiting-rank'; baseline=$baseline; improved=$improved
        note='Only completed results with compatible scenarios should be compared. Metrics absent from source artifacts are not measured.'
    }
    $jsonPath = Join-Path $resultsRoot 'waiting-rank-comparison.json'
    $runJsonPath = Join-Path $comparisonRoot 'comparison.json'
    $json = $payload | ConvertTo-Json -Depth 100
    $json | Set-Content $runJsonPath -Encoding utf8
    $json | Set-Content $jsonPath -Encoding utf8
    $generator = Join-Path $PSScriptRoot 'waiting-rank-comparison-report.mjs'
    if (Get-Command node -ErrorAction SilentlyContinue) {
        & node $generator $runJsonPath (Join-Path $resultsRoot 'waiting-rank-comparison.html')
        if ($LASTEXITCODE -ne 0) { throw 'HTML report generation failed.' }
    } else {
        . (Join-Path $PSScriptRoot 'resolve-wsl-distribution.ps1')
        $Distribution = Resolve-BenchmarkWslDistribution -Distribution $Distribution
        $linuxRoot = (& wsl -d $Distribution -u root -- wslpath -a $repoRoot.Replace('\','/') | Out-String).Trim()
        if ($LASTEXITCODE -ne 0 -or -not $linuxRoot.StartsWith('/')) { throw 'Cannot resolve repo path in WSL to generate HTML.' }
        $dockerArgs = @('run','--rm','--network','none','--mount',"type=bind,source=$linuxRoot/benchmark-results,target=/results",'--mount',"type=bind,source=$linuxRoot/scripts/benchmark,target=/report-source,readonly",'--entrypoint','node','smartticketing-benchmark:java21-local','/report-source/waiting-rank-comparison-report.mjs',"/results/waiting-rank-comparison/$runId/comparison.json",'/results/waiting-rank-comparison.html')
        & wsl -d $Distribution -u root -- docker @dockerArgs
        if ($LASTEXITCODE -ne 0) { throw 'HTML report generation failed in WSL container.' }
    }
    Write-Host ([Environment]::NewLine + 'Comparison complete.') -ForegroundColor Green
    Write-Host "HTML: $(Join-Path $resultsRoot 'waiting-rank-comparison.html')" -ForegroundColor Green
    Write-Host "JSON: $jsonPath" -ForegroundColor Green
    Write-Host "Raw runs: $comparisonRoot" -ForegroundColor Green
    if ($baseline.status -ne 'completed' -or $improved.status -ne 'completed') {
        Write-Warning 'At least one branch did not complete. The report will show failures and will not present a performance win as proven.'
        exit 1
    }
} finally {
    foreach ($label in @('baseline','improved')) {
        $wt = Join-Path $worktreeRoot $label
        if (Test-Path $wt) { Invoke-Git @('worktree','remove','--force',$wt) -AllowFailure }
    }
    Invoke-Git @('worktree','prune') -AllowFailure
    if (Test-Path $worktreeRoot) { Remove-Item $worktreeRoot -Recurse -Force -ErrorAction SilentlyContinue }
}
