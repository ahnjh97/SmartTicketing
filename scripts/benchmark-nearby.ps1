[CmdletBinding()]
param(
    [ValidateRange(1, 100000)][int]$Runs,
    [ValidateRange(0, 10000)][int]$WarmupRuns,
    [ValidateRange(1, 100)][int]$Rounds,
    [ValidateSet('plan', 'stub', 'live')][string]$Mode = 'plan',
    [ValidateSet('parallel', 'all')][string]$Comparison = 'parallel',
    [string]$Python = '',
    [string]$EnvFile = '',
    [string]$Catalog = '',
    [string]$Fixtures = '',
    [string]$Origins = '',
    [ValidateSet('fixed', 'variable')][string]$DelayProfile = 'variable',
    [ValidateRange(0, 60000)][int]$RouteMs = 100,
    [ValidateRange(0, 60000)][int]$SearchMs = 50,
    [int]$Seed = 2026,
    [ValidateRange(0, 100000)][int]$BudgetSearch = 30,
    [ValidateRange(0, 100000)][int]$BudgetTransit = 600,
    [ValidateRange(0, 100000)][int]$BudgetWalk = 150,
    [switch]$PrepareOnly
)
$ErrorActionPreference = 'Stop'
if (!$Python) {
    $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
    $bundledPython = Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
    if ($pythonCommand -and $pythonCommand.Source -notmatch 'WindowsApps') {
        $Python = $pythonCommand.Source
    } elseif (Test-Path -LiteralPath $bundledPython) {
        $Python = $bundledPython
    } else {
        throw 'Python 3.12+ is required. Pass -Python with the executable path.'
    }
}
$arguments = @((Join-Path $PSScriptRoot 'benchmark/runner.py'), '--mode', $Mode,
    '--comparison', $Comparison, '--delay-profile', $DelayProfile, '--route-ms', $RouteMs,
    '--search-ms', $SearchMs, '--seed', $Seed, '--budget-search', $BudgetSearch,
    '--budget-transit', $BudgetTransit, '--budget-walk', $BudgetWalk)
if ($PSBoundParameters.ContainsKey('Runs')) { $arguments += @('--runs', $Runs) }
if ($PSBoundParameters.ContainsKey('WarmupRuns')) { $arguments += @('--warmup', $WarmupRuns) }
if ($PSBoundParameters.ContainsKey('Rounds')) { $arguments += @('--rounds', $Rounds) }
if ($EnvFile) { $arguments += @('--env-file', $EnvFile) }
if ($Catalog) { $arguments += @('--catalog', $Catalog) }
if ($Fixtures) { $arguments += @('--fixtures', $Fixtures) }
if ($Origins) { $arguments += @('--origins', $Origins) }
if ($PrepareOnly) { $arguments += '--prepare-only' }
& $Python @arguments
if ($LASTEXITCODE -ne 0) { throw "Benchmark exited with code $LASTEXITCODE. See benchmark-results logs." }
