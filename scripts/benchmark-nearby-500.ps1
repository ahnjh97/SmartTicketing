# Compatibility entry point: measurement lives in benchmark-nearby.ps1.
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
& (Join-Path $PSScriptRoot 'benchmark-nearby.ps1') @PSBoundParameters
