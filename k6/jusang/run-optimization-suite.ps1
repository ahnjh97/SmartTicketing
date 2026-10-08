param(
 [ValidateSet("smoke","load")][string]$Profile="load",
 [string]$BaseUrl="http://localhost:8080",
 [int]$Vus=10,[int]$Iterations=100,
 [string]$MovieId="14",[string]$TheaterId="1",[string]$ShowtimeId="115260",[string]$Date="2026-10-09",
 [string]$Lat="37.5665",[string]$Lon="126.9780",[string]$Address="",
 [switch]$ManageBackend,[switch]$OpenReport
)
$ErrorActionPreference="Stop"
Write-Host "=== 1 + 3 : Redis Cache ON/OFF ===" -ForegroundColor Green
$cacheArgs=@("-Profile",$Profile,"-BaseUrl",$BaseUrl,"-Vus","$Vus","-Iterations","$Iterations","-MovieId",$MovieId,"-TheaterId",$TheaterId,"-ShowtimeId",$ShowtimeId,"-Date",$Date)
if($ManageBackend){$cacheArgs += "-ManageBackend"}
if($OpenReport){$cacheArgs += "-OpenReport"}
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "run-cache-comparison.ps1") @cacheArgs
if($LASTEXITCODE -ne 0){throw "1/3 캐시 비교가 실패했습니다."}
Write-Host "=== 5 : Nearby baseline ===" -ForegroundColor Green
$nearArgs=@("-Profile",$Profile,"-BaseUrl",$BaseUrl,"-Vus","$Vus","-Iterations","$Iterations","-Lat",$Lat,"-Lon",$Lon,"-Address",$Address)
if($ManageBackend){$nearArgs += "-ManageBackend"}
if($OpenReport){$nearArgs += "-OpenReport"}
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "run-nearby-benchmark.ps1") @nearArgs
if($LASTEXITCODE -ne 0){throw "5번 nearby baseline이 실패했습니다."}
Write-Host "완료: 1/3 cache-test-report.html, 5 nearby-test-report.html" -ForegroundColor Green
