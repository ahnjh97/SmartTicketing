# Compatibility entry point: booking benchmarks now use the production Docker image.
param(
    [string]$K6Binary,
    [string]$JavaBinary,
    [string]$Output,
    [switch]$Smoke,
    [string]$Distribution = 'Ubuntu',
    [ValidateSet('compare','on','off')][string]$CacheMode = 'compare'
)
$ErrorActionPreference = 'Stop'
if ($K6Binary -or $JavaBinary -or $Output) {
    throw 'Windows K6Binary/JavaBinary/Output overrides are retired. Run scripts/benchmark/run-components.ps1 -Suite booking instead; it manages container runtimes and a unique result folder.'
}
& (Join-Path $PSScriptRoot 'run-components.ps1') -Suite booking -Distribution $Distribution -CacheMode $CacheMode -Smoke:$Smoke
