<#
.SYNOPSIS
Runs an interactive WSL benchmark menu, or explicit comparison options.
.EXAMPLE
.\scripts\benchmark\run-site.ps1 -Smoke
.EXAMPLE
.\scripts\benchmark\run-site.ps1 -Mode compare -Comparison all -Refs before,final -Smoke
.EXAMPLE
.\scripts\benchmark\run-site.ps1 -BackendImage smartticketing-backend:COMMIT -FrontendImage smartticketing-frontend:COMMIT -Smoke
.NOTES
No production credentials or volumes are used. No CPU/memory caps are applied.
Existing API/component benchmarks remain under -Mode components.
#>
[CmdletBinding()]
param(
    [string]$Distribution = 'Ubuntu',
    [ValidateSet('site','compare','components')][string]$Mode = 'site',
    [ValidateSet('db','redis','overall','all')][string]$Comparison = 'all',
    [switch]$Menu,
    [string[]]$Refs = @(),
    [string]$Suite = 'all',
    [switch]$Smoke,
    [ValidateRange(1,10000)][int]$Users = 100,
    [int[]]$UserLevels = @(),
    [ValidateRange(1,100000)][double]$P95LimitMs = 1000,
    [ValidateRange(1,100)][int]$Clients = 10,
    [ValidatePattern('^[1-9][0-9]*(s|m)$')][string]$Duration = '60s',
    [ValidateSet('branch','on','off','compare')][string]$CacheMode = 'branch',
    [string]$BackendImage = '',
    [string]$FrontendImage = '',
    [ValidateRange(1,10)][int]$Repeats = 1,
    [ValidateRange(1,10000)][int]$Rate = 50,
    [ValidateRange(1,10000)][int]$PreAllocatedVUs = 100,
    [ValidateRange(1,10000)][int]$MaxVUs = 1000,
    [ValidateRange(1,10000)][int]$LoginUsers = 100,
    [switch]$List
)
$ErrorActionPreference = 'Stop'
if ($List) {
    Write-Host 'site: deployment stack, all / browse / smart / manual / login'
    Write-Host 'compare: -Comparison db / redis / overall / all; before,final refs (redis: final only)'
    Write-Host 'components: existing booking / main / movies / theaters / showtimes / seats / distance / login / all'
    return
}
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if ($Menu -or $PSBoundParameters.Count -eq 0) {
    . (Join-Path $PSScriptRoot 'menu.ps1')
    try { $selection = Read-BenchmarkMenu $projectRoot }
    catch [OperationCanceledException] { Write-Host $_.Exception.Message; return }
    foreach ($key in $selection.Keys) { Set-Variable -Name $key -Value $selection[$key] }
}
if ($Mode -eq 'components') {
    $componentMode = if ($CacheMode -eq 'branch') { 'compare' } else { $CacheMode }
    & (Join-Path $PSScriptRoot 'run-components.ps1') -Distribution $Distribution -Suite $Suite -Smoke:$Smoke -CacheMode $componentMode -Duration $Duration -Rate $Rate -PreAllocatedVUs $PreAllocatedVUs -MaxVUs $MaxVUs -LoginUsers $LoginUsers
    return
}
$requiredRefs = if ($Comparison -eq 'redis') { 1 } else { 2 }
if ($Mode -eq 'compare' -and $Refs.Count -ne $requiredRefs) { throw 'Redis comparison requires one final ref; other comparisons require before,final refs.' }
if ($Mode -eq 'site' -and $Refs.Count -gt 1) { throw 'Site accepts at most one ref.' }
if ($Suite -notin @('all','browse','smart','manual','login')) { throw 'Use -Mode components for the existing individual API benchmarks.' }
if ($CacheMode -eq 'compare') { throw 'Use -Mode compare -Comparison redis -Refs final, or -Mode components -CacheMode compare.' }
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'resolve-wsl-distribution.ps1')
$Distribution = Resolve-BenchmarkWslDistribution -Distribution $Distribution
$linuxRoot = (& wsl -d $Distribution -u root -- wslpath -a $projectRoot.Replace('\', '/') | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or -not $linuxRoot.StartsWith('/')) { throw 'Cannot resolve the WSL project path.' }
$arguments = @('--mode',$Mode,'--comparison',$Comparison,'--suite',$Suite,'--users',"$Users",'--clients',"$Clients",'--duration',$Duration,'--cache-mode',$CacheMode,'--repeats',"$Repeats")
foreach ($ref in $Refs) { $arguments += @('--ref',$ref) }
if ($Smoke) { $arguments += '--smoke' }
if ($BackendImage) { $arguments += @('--backend-image',$BackendImage) }
if ($FrontendImage) { $arguments += @('--frontend-image',$FrontendImage) }
if ($UserLevels.Count) { $arguments += @('--user-levels',($UserLevels -join ','),'--p95-limit-ms',"$P95LimitMs") }
& wsl -d $Distribution -u root -- bash "$linuxRoot/scripts/benchmark/run-site.sh" @arguments
if ($LASTEXITCODE -ne 0) { throw 'Run failed; inspect the printed result directory. Failed runs are not performance improvements.' }
