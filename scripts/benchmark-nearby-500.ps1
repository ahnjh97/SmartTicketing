# Compatibility entry point: all measurement logic lives in benchmark-nearby.ps1.
[CmdletBinding()]
param(
    [int]$Runs = 100,
    [int]$WarmupRuns = 20,
    [int]$Rounds = 3,
    [ValidateSet('stub', 'live')][string]$Mode = 'stub',
    [string]$Python = '',
    [string]$EnvFile = '',
    [string]$Fixtures = '',
    [switch]$PrepareOnly
)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'benchmark-nearby.ps1') @PSBoundParameters
