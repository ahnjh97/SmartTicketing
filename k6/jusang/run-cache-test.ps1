[CmdletBinding()]
param(
    [ValidateSet("1-common","1-catalog","3-showtime","3-seat","5-distance","5-walk","5-transit")]
    [string]$Target = "1-common",

    [ValidateSet("smoke","load")]
    [string]$Profile = "smoke",

    [string]$BaseUrl = "http://localhost:8080",

    [int]$Vus = 10,
    [int]$Iterations = 100,

    [string]$MovieId = "14",
    [string]$TheaterId = "1",
    [string]$ShowtimeId = "115260",
    [string]$Date = "2026-10-09",

    [string]$Lat = "37.5665",
    [string]$Lon = "126.9780",
    [string]$Address = ""
)

$ErrorActionPreference = "Stop"

if ($Profile -eq "smoke") {
    $Vus = 1
    $Iterations = 1
}

$env:BASE_URL = $BaseUrl
$env:VUS = "$Vus"
$env:ITERATIONS = "$Iterations"
$env:MOVIE_ID = $MovieId
$env:THEATER_ID = $TheaterId
$env:SHOWTIME_ID = $ShowtimeId
$env:DATE = $Date
$env:LAT = $Lat
$env:LON = $Lon
$env:ADDRESS = $Address

$script = switch ($Target) {
    "1-common"  { "01-common-query.js" }
    "1-catalog" { "01-catalog-query.js" }
    "3-showtime" { "03-showtime-query.js" }
    "3-seat" { "03-seat-map-query.js" }
    "5-distance" { "05-nearby-distance.js" }
    "5-walk" { "05-nearby-walk.js" }
    "5-transit" { "05-nearby-transit.js" }
}

$path = Join-Path $PSScriptRoot $script

Write-Host ""
Write-Host "Target     : $Target"
Write-Host "Profile    : $Profile"
Write-Host "BASE_URL   : $BaseUrl"
Write-Host "VUs        : $Vus"
Write-Host "Iterations : $Iterations"
Write-Host ""

k6 run $path
if ($LASTEXITCODE -ne 0) {
    throw "k6 test failed: $Target"
}
