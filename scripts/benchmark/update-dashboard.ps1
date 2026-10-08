param([string]$Distribution = 'Ubuntu')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$resultRoot = Join-Path $projectRoot 'benchmark-results'
New-Item -ItemType Directory -Force -Path $resultRoot | Out-Null
if (Get-Command node -ErrorAction SilentlyContinue) {
    & node (Join-Path $PSScriptRoot 'dashboard.mjs') $resultRoot
} else {
    # The benchmark image already includes Node; no additional Windows install is required.
    . (Join-Path $PSScriptRoot 'resolve-wsl-distribution.ps1')
    $Distribution = Resolve-BenchmarkWslDistribution -Distribution $Distribution
    $linuxRoot = (& wsl -d $Distribution -u root -- wslpath -a $projectRoot.Replace('\', '/') | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $linuxRoot.StartsWith('/')) { throw 'Cannot access WSL for report generation.' }
    & wsl -d $Distribution -u root -- docker run --rm --network none `
        --mount "type=bind,source=$linuxRoot/benchmark-results,target=/results" `
        --mount "type=bind,source=$linuxRoot/scripts/benchmark,target=/report-source,readonly" `
        --entrypoint node smartticketing-benchmark:java21-local /report-source/dashboard.mjs /results
}
if ($LASTEXITCODE -ne 0) { throw 'Dashboard generation failed.' }
Write-Host "통합 결과: $(Join-Path $resultRoot 'index.html')" -ForegroundColor Cyan
