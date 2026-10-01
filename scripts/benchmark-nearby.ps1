[CmdletBinding()]
param(
    [ValidateRange(1, 100000)][int]$Runs = 100,
    [ValidateRange(0, 10000)][int]$WarmupRuns = 20,
    [ValidateRange(1, 100)][int]$Rounds = 3,
    [ValidateSet('stub', 'live')][string]$Mode = 'live',
    [string]$Python = '',
    [string]$EnvFile = (Join-Path $PSScriptRoot '../.env.benchmark'),
    [string]$Fixtures = '',
    [string]$Origins = '',
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
$arguments = @((Join-Path $PSScriptRoot 'benchmark/runner.py'), '--runs', $Runs,
    '--warmup', $WarmupRuns, '--rounds', $Rounds, '--mode', $Mode)
if ($EnvFile) { $arguments += @('--env-file', $EnvFile) }
if ($Fixtures) { $arguments += @('--fixtures', $Fixtures) }
if ($Origins) { $arguments += @('--origins', $Origins) }
if ($PrepareOnly) { $arguments += '--prepare-only' }
& $Python @arguments
if ($LASTEXITCODE -ne 0) { throw "Benchmark exited with code $LASTEXITCODE. See benchmark-results logs." }
