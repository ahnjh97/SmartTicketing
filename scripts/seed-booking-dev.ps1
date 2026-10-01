param(
    [Parameter(Mandatory = $true)]
    [long[]]$TheaterIds
)

$ErrorActionPreference = 'Stop'
if ($TheaterIds.Count -eq 0 -or @($TheaterIds | Where-Object { $_ -le 0 }).Count -gt 0) {
    throw 'Specify one or more existing positive theater IDs.'
}
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$envPath = Join-Path $projectRoot '.env'
$previousValues = @{}
$seedExitCode = 1
Push-Location -LiteralPath $projectRoot
try {
    # 단순 KEY=value 형식만 읽으며 내용을 실행하거나 출력하지 않는다.
    if (Test-Path -LiteralPath $envPath) {
        foreach ($line in [System.IO.File]::ReadAllLines($envPath)) {
            if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
                $key = $matches[1]
                $value = $matches[2].Trim()
                if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
                    $value = $value.Substring(1, $value.Length - 2)
                }
                if (-not $previousValues.ContainsKey($key)) {
                    $previousValues[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
                }
                [Environment]::SetEnvironmentVariable($key, $value, 'Process')
            }
        }
    }
    $ids = ($TheaterIds | Sort-Object -Unique) -join ','
    Write-Host "Starting development application with booking seed enabled for theater IDs: $ids"
    & .\gradlew.bat bootRun "--args=--spring.profiles.include=dev --booking.seed.enabled=true --booking.seed.theater-ids=$ids"
    $seedExitCode = $LASTEXITCODE
} finally {
    foreach ($key in $previousValues.Keys) {
        [Environment]::SetEnvironmentVariable($key, $previousValues[$key], 'Process')
    }
    Pop-Location
}
if ($seedExitCode -ne 0) { throw "Development application exited with code $seedExitCode" }
