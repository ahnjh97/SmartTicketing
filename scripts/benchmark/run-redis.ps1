param(
    [Parameter(Mandatory=$true)][string]$K6Binary,
    [string]$JavaBinary,
    [string]$Output = 'build/redis-benchmark-full',
    [switch]$Smoke
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$benchmarkOutput = [IO.Path]::GetFullPath((Join-Path $projectRoot $Output))
if (-not $benchmarkOutput.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Output must be within this project.'
}
if (Test-Path -LiteralPath $benchmarkOutput) { throw 'Choose a fresh output directory to preserve previous results.' }
if (-not (Test-Path -LiteralPath $K6Binary -PathType Leaf)) { throw 'Provide the path to the official k6 executable.' }
# Reuse the isolated runtime when installed; never change JAVA_HOME or the project toolchain.
if (-not $JavaBinary -and -not $env:BOOKING_LOAD_JAVA -and [Environment]::OSVersion.Platform -eq 'Win32NT') {
    $localRuntime = Join-Path $projectRoot 'build/benchmark-runtime/jdk-26.0.2.1+1-jre/bin/java.exe'
    if (Test-Path -LiteralPath $localRuntime -PathType Leaf) { $JavaBinary = $localRuntime }
}
$previous = @{}
$settings = @{
    BOOKING_LOAD_TEST = 'true'
    BOOKING_LOAD_SMOKE = $Smoke.IsPresent.ToString().ToLowerInvariant()
    BOOKING_LOAD_OUTPUT = $benchmarkOutput
    K6_BINARY = (Resolve-Path -LiteralPath $K6Binary).Path
}
if ($JavaBinary) {
    if (-not (Test-Path -LiteralPath $JavaBinary -PathType Leaf)) { throw 'JavaBinary must point to an existing java executable.' }
    $settings['BOOKING_LOAD_JAVA'] = (Resolve-Path -LiteralPath $JavaBinary).Path
}
foreach ($line in [IO.File]::ReadAllLines((Join-Path $projectRoot '.env'))) {
    if ($line -match '^(DB_USERNAME|DB_PASSWORD)=(.*)$') {
        $name = if ($Matches[1] -eq 'DB_USERNAME') { 'BOOKING_TEST_MYSQL_USER' } else { 'BOOKING_TEST_MYSQL_PASSWORD' }
        $settings[$name] = $Matches[2].Trim().Trim('"').Trim("'")
    }
}
Push-Location $projectRoot
try {
    foreach ($name in $settings.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process')
    }
    & ./gradlew.bat bookingRedisBenchmark --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark runner failed; inspect output before interpreting results.' }
    Write-Host "Results: $benchmarkOutput"
    Write-Host 'Check operation_success and validation.json; a completed runner does not mean every scenario passed.'
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Pop-Location
}
